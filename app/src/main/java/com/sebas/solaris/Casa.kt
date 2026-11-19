package com.sebas.solaris

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/** Lo que se está escribiendo. Vive en memoria para no perderse si el telón se cierra a mitad. */
class Borrador(val id: String?, cuarto: String?, val abrirFoto: Boolean = false) {
    var titulo by mutableStateOf("")
    var texto by mutableStateOf("")
    var cuarto by mutableStateOf(cuarto)
    var fotos by mutableStateOf(listOf<String>())
    val fotosNuevas = mutableListOf<String>()
    var creado: Long = System.currentTimeMillis()
    var cargado = id == null
    var fotoPedida = false

    fun vacio() = titulo.isBlank() && texto.isBlank() && fotos.isEmpty()
}

/**
 * La casa: guarda, cifra, abre y sincroniza. Es la única que toca la llave maestra.
 */
object Casa {
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val candadoSync = Mutex()
    private val cache = LruCache<String, Bitmap>(48)

    var conectada by mutableStateOf(false)
        private set
    var lista by mutableStateOf(false)
        private set
    var abierta by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<Item>>(emptyList())
        private set
    var estado by mutableStateOf("")
        private set
    var sincronizando by mutableStateOf(false)
        private set

    /** Mientras se elige una foto la app sale a segundo plano; no hay que cerrar el telón por eso. */
    @Volatile var pausarCandado = false

    @Volatile private var maestra: ByteArray? = null
    private var supa: Supa? = null

    private val dirItems get() = File(app.filesDir, "items").apply { mkdirs() }
    private val dirFotos get() = File(app.filesDir, "fotos").apply { mkdirs() }
    private fun archivoItem(id: String) = File(dirItems, "$id.bin")
    private fun archivoFoto(id: String) = File(dirFotos, "$id.bin")
    private val dirCintas get() = File(app.filesDir, "cintas").apply { mkdirs() }

    /** El audio de una cinta: vive solo en este celular. */
    fun archivoCinta(id: String) = File(dirCintas, "$id.wav")

    fun iniciar(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("casa", Context.MODE_PRIVATE)
        lista = prefs.getBoolean("lista", false)
        crearCliente()
    }

    // ---------------- conexión con Supabase ----------------

    private fun url() = BuildConfig.SUPABASE_URL.ifBlank { prefs.getString("supa_url", "") ?: "" }
    private fun llaveAnon() = BuildConfig.SUPABASE_ANON_KEY.ifBlank { prefs.getString("supa_key", "") ?: "" }

    fun conectar(url: String, llave: String) {
        var u = url.trim().trimEnd('/')
        if (!u.startsWith("http")) u = "https://$u"
        prefs.edit().putString("supa_url", u).putString("supa_key", llave.trim()).apply()
        crearCliente()
    }

    private fun crearCliente() {
        val u = url()
        val k = llaveAnon()
        conectada = u.isNotBlank() && k.isNotBlank()
        supa = if (conectada) Supa(u, k, cargarSesion(), ::guardarSesion) else null
    }

    private fun cargarSesion(): Sesion? = try {
        prefs.getString("sesion", null)?.let { Sesion.de(JSONObject(String(LlaveDelCelular.abrir(Crypto.deB64(it)), Charsets.UTF_8))) }
    } catch (e: Exception) {
        null
    }

    private fun guardarSesion(s: Sesion?) {
        if (s == null) prefs.edit().remove("sesion").apply()
        else prefs.edit().putString("sesion", Crypto.b64(LlaveDelCelular.sellar(s.json().toString().toByteArray(Charsets.UTF_8)))).apply()
    }

    fun email(): String = prefs.getString("email", "") ?: ""

    // ---------------- construir o recuperar la casa ----------------

    /** Crea la cuenta y la bóveda. Devuelve el código de recuperación para anotarlo en papel. */
    suspend fun crearCasa(email: String, clave: String, frase: String): String = withContext(Dispatchers.IO) {
        val s = supa ?: throw Problema("Primero conecta Supabase.")
        try {
            s.registrar(email.trim(), clave)
        } catch (e: Problema) {
            // La cuenta ya existía (por ejemplo, un intento anterior a medias): si aún no tiene bóveda, se sigue.
            if (e.message?.contains("ya tiene una casa") != true) throw e
            s.entrar(email.trim(), clave)
            if (s.leerBoveda() != null) throw e
        }
        val m = Crypto.azar(32)
        val sal = Crypto.azar(16)
        val envuelta = Crypto.sellar(Crypto.derivar(Crypto.limpiarFrase(frase), sal, Crypto.ITERACIONES), m)
        val codigo = Crypto.codigoRecuperacion()
        val rsal = Crypto.azar(16)
        val renvuelta = Crypto.sellar(Crypto.derivar(Crypto.limpiarCodigo(codigo), rsal, Crypto.ITERACIONES), m)
        val boveda = JSONObject()
            .put("user_id", s.uid())
            .put("salt", Crypto.b64(sal))
            .put("iter", Crypto.ITERACIONES)
            .put("wrapped", Crypto.b64(envuelta))
            .put("rsalt", Crypto.b64(rsal))
            .put("rwrapped", Crypto.b64(renvuelta))
        s.guardarBoveda(boveda)
        instalar(email.trim(), m, boveda)
        codigo
    }

    /** Entra a una casa que ya existe (celular nuevo). Con el código se elige además una frase nueva. */
    suspend fun entrarCasa(email: String, clave: String, secreto: String, esCodigo: Boolean, fraseNueva: String?) =
        withContext(Dispatchers.IO) {
            val s = supa ?: throw Problema("Primero conecta Supabase.")
            s.entrar(email.trim(), clave)
            val v = s.leerBoveda() ?: throw Problema("Esta cuenta todavía no tiene casa. Usa “Crear mi casa”.")
            val iter = v.optInt("iter", Crypto.ITERACIONES)
            val m = try {
                if (esCodigo) Crypto.abrir(
                    Crypto.derivar(Crypto.limpiarCodigo(secreto), Crypto.deB64(v.getString("rsalt")), iter),
                    Crypto.deB64(v.getString("rwrapped")),
                ) else Crypto.abrir(
                    Crypto.derivar(Crypto.limpiarFrase(secreto), Crypto.deB64(v.getString("salt")), iter),
                    Crypto.deB64(v.getString("wrapped")),
                )
            } catch (e: GeneralSecurityException) {
                throw Problema(if (esCodigo) "Ese código no abre esta casa." else "Esa frase llave no abre esta casa.")
            }
            if (esCodigo && fraseNueva != null) {
                val sal = Crypto.azar(16)
                v.put("salt", Crypto.b64(sal))
                v.put("iter", Crypto.ITERACIONES)
                v.put("wrapped", Crypto.b64(Crypto.sellar(Crypto.derivar(Crypto.limpiarFrase(fraseNueva), sal, Crypto.ITERACIONES), m)))
                s.guardarBoveda(v)
            }
            instalar(email.trim(), m, v)
        }

    private fun instalar(email: String, m: ByteArray, boveda: JSONObject) {
        dirItems.listFiles()?.forEach { it.delete() }
        dirFotos.listFiles()?.forEach { it.delete() }
        prefs.edit()
            .putString("email", email)
            .putString("maestra", Crypto.b64(LlaveDelCelular.sellar(m)))
            .putString("boveda", boveda.toString())
            .putLong("cursor", 0)
            .putStringSet("sucios", emptySet())
            .putStringSet("fotos_pendientes", emptySet())
            .putStringSet("fotos_borrar", emptySet())
            .apply()
        maestra = m
        items = emptyList()
    }

    // ---------------- PIN, huella y telón ----------------

    private const val ITER_PIN = 40_000
    const val INTENTOS_PIN = 8

    suspend fun fijarPin(pin: String) = withContext(Dispatchers.Default) {
        val sal = Crypto.azar(16)
        val hash = Crypto.derivar(pin, sal, ITER_PIN)
        prefs.edit()
            .putString("pin_sal", Crypto.b64(sal))
            .putString("pin_hash", Crypto.b64(hash))
            .putInt("pin_largo", pin.length)
            .putInt("pin_fallos", 0)
            .apply()
    }

    fun largoPin(): Int = prefs.getInt("pin_largo", 4)
    fun fallosPin(): Int = prefs.getInt("pin_fallos", 0)
    fun pinBloqueado(): Boolean = fallosPin() >= INTENTOS_PIN
    fun huellaActiva(): Boolean = prefs.getBoolean("huella", false)
    fun usarHuella(si: Boolean) = prefs.edit().putBoolean("huella", si).apply()

    fun terminarInstalacion(huella: Boolean) {
        prefs.edit().putBoolean("lista", true).putBoolean("huella", huella).apply()
        lista = true
        abrirConCelular()
    }

    /** true si el PIN es correcto (y abre la casa). */
    suspend fun probarPin(pin: String): Boolean {
        val ok = withContext(Dispatchers.Default) {
            val sal = prefs.getString("pin_sal", null) ?: return@withContext false
            val hash = prefs.getString("pin_hash", null) ?: return@withContext false
            Crypto.iguales(Crypto.derivar(pin, Crypto.deB64(sal), ITER_PIN), Crypto.deB64(hash))
        }
        if (!ok) {
            prefs.edit().putInt("pin_fallos", fallosPin() + 1).apply()
            return false
        }
        prefs.edit().putInt("pin_fallos", 0).apply()
        return abrirConCelular()
    }

    /** Abre con la llave guardada en el celular (después del PIN o la huella). */
    fun abrirConCelular(): Boolean {
        return try {
            val guardada = prefs.getString("maestra", null) ?: return false
            maestra = LlaveDelCelular.abrir(Crypto.deB64(guardada))
            cargarTodo()
            abierta = true
            sincronizar()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Abre con la frase llave, sin internet. Sirve si se olvidó el PIN. */
    suspend fun abrirConFrase(frase: String): Boolean {
        val m = withContext(Dispatchers.Default) {
            try {
                val v = JSONObject(prefs.getString("boveda", null) ?: return@withContext null)
                Crypto.abrir(
                    Crypto.derivar(Crypto.limpiarFrase(frase), Crypto.deB64(v.getString("salt")), v.optInt("iter", Crypto.ITERACIONES)),
                    Crypto.deB64(v.getString("wrapped")),
                )
            } catch (e: Exception) {
                null
            }
        } ?: return false
        prefs.edit().putString("maestra", Crypto.b64(LlaveDelCelular.sellar(m))).putInt("pin_fallos", 0).apply()
        maestra = m
        cargarTodo()
        abierta = true
        sincronizar()
        return true
    }

    fun cerrar() {
        if (!abierta) return
        maestra?.fill(0)
        maestra = null
        abierta = false
        items = emptyList()
        cache.evictAll()
    }

    /** Borra todo de este celular. Lo que ya subió a la nube vuelve al entrar de nuevo. */
    fun salirDeEsteCelular() {
        cerrar()
        dirItems.listFiles()?.forEach { it.delete() }
        dirFotos.listFiles()?.forEach { it.delete() }
        dirCintas.listFiles()?.forEach { it.delete() }
        val url = prefs.getString("supa_url", null)
        val key = prefs.getString("supa_key", null)
        prefs.edit().clear().putString("supa_url", url).putString("supa_key", key).apply()
        lista = false
        estado = ""
        crearCliente()
    }

    fun pendientes(): Int = conjunto("sucios").size

    // ---------------- páginas y habitaciones ----------------

    private fun cargarTodo() {
        val m = maestra ?: return
        items = dirItems.listFiles()?.mapNotNull { f ->
            try {
                Item.de(JSONObject(String(Crypto.abrir(m, f.readBytes()), Charsets.UTF_8)))
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    fun porId(id: String): Item? = items.firstOrNull { it.id == id }

    @Synchronized
    private fun ponerEnLista(item: Item) {
        items = items.filter { it.id != item.id } + item
    }

    private fun guardarInterno(item: Item) {
        val m = maestra ?: return
        archivoItem(item.id).writeBytes(Crypto.sellar(m, item.json().toString().toByteArray(Charsets.UTF_8)))
        ponerEnLista(item)
        agregar("sucios", item.id)
    }

    fun guardar(item: Item) {
        scope.launch {
            withContext(Dispatchers.IO) { guardarInterno(item) }
            sincronizar()
        }
    }

    fun guardarBorrador(b: Borrador) {
        // fotos agregadas y quitadas en esta misma visita: no se guardan
        olvidarFotos(b.fotosNuevas.filter { it !in b.fotos })
        b.fotosNuevas.retainAll(b.fotos)
        val previo = b.id?.let { porId(it) }
        if (previo != null && previo.title == b.titulo.trim() && previo.text == b.texto &&
            previo.room == b.cuarto && previo.photos == b.fotos
        ) return
        val ahora = System.currentTimeMillis()
        previo?.photos?.filter { it !in b.fotos }?.let { olvidarFotos(it) }
        guardar(
            Item(
                id = b.id ?: Item.nuevoId(),
                kind = "entrada",
                room = b.cuarto,
                title = b.titulo.trim(),
                text = b.texto,
                photos = b.fotos,
                color = 0,
                created = previo?.created ?: b.creado,
                updated = ahora,
            )
        )
    }

    /** Quema la página: si era nueva, desaparece sin dejar rastro; si existía, se borra también de la nube. */
    fun quemar(b: Borrador) {
        val previo = b.id?.let { porId(it) }
        olvidarFotos(b.fotos + (previo?.photos ?: emptyList()))
        if (previo != null) {
            guardar(previo.copy(title = "", text = "", photos = emptyList(), updated = System.currentTimeMillis(), deleted = true))
        }
    }

    /** Si se sale sin escribir nada, las fotos recién agregadas no se quedan huérfanas. */
    fun descartarNuevas(b: Borrador) {
        olvidarFotos(b.fotosNuevas.filter { id -> b.id == null || porId(b.id)?.photos?.contains(id) != true })
    }

    private fun olvidarFotos(ids: List<String>) {
        if (ids.isEmpty()) return
        ids.forEach {
            archivoFoto(it).delete()
            cache.evictAll()
            quitar("fotos_pendientes", it)
            agregar("fotos_borrar", it)
        }
    }

    fun nuevoCuarto(nombre: String, color: Int) = guardar(Item.cuarto(nombre.trim(), color))

    fun renombrarCuarto(c: Item, nombre: String, color: Int) =
        guardar(c.copy(title = nombre.trim(), color = color, updated = System.currentTimeMillis()))

    /** Pregunta de la entrada según la hora, y la habitación que le corresponde. */
    fun pregunta(): Pair<String, String> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..10 -> "¿Qué soñaste?" to "Sueños"
            in 11..17 -> "¿Qué viste hoy?" to "Memorias extrañas"
            else -> "¿Qué cargas hoy?" to "Desahogos"
        }
    }


    // ---------------- notas, cintas y búsqueda ----------------

    fun notas(): List<Item> = items.filter { it.esEntrada && !it.deleted }.sortedByDescending { it.created }
    fun cintas(): List<Item> = items.filter { it.esCinta && !it.deleted }.sortedByDescending { it.created }

    fun borrarNota(id: String) {
        val previo = porId(id) ?: return
        olvidarFotos(previo.photos)
        guardar(previo.copy(title = "", text = "", photos = emptyList(), updated = System.currentTimeMillis(), deleted = true))
    }

    /** Registra una cinta recién grabada. El audio queda en el celular; a la nube sube solo su ficha y su texto. */
    fun nuevaCinta(id: String, nombre: String, duracion: Long, creada: Long) {
        // se guarda en el acto: puede llamarse justo antes de que el telón se cierre
        guardarInterno(Item(id = id, kind = "cinta", room = null, title = nombre, text = "", photos = emptyList(),
            color = 0, created = creada, updated = System.currentTimeMillis(), dur = duracion))
        sincronizar()
    }

    fun renombrarCinta(id: String, nombre: String) {
        val c = porId(id) ?: return
        guardar(c.copy(title = nombre.trim(), updated = System.currentTimeMillis()))
    }

    fun borrarCinta(id: String) {
        val c = porId(id) ?: return
        archivoCinta(id).delete()
        guardar(c.copy(title = "", text = "", updated = System.currentTimeMillis(), deleted = true))
    }

    /** Lo que el océano devuelve para una consulta de la terminal. */
    fun buscar(consulta: String): List<Item> {
        val q = consulta.trim().lowercase()
        val todo = items.filter { (it.esEntrada || it.esCinta) && !it.deleted }.sortedByDescending { it.created }
        if (q.isEmpty()) return emptyList()
        val hoy = Calendar.getInstance()
        fun mismoDia(t: Long, c: Calendar): Boolean {
            val x = Calendar.getInstance().apply { timeInMillis = t }
            return x.get(Calendar.YEAR) == c.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        }
        return when (q) {
            "hoy" -> todo.filter { mismoDia(it.created, hoy) }
            "ayer" -> { val a = (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }; todo.filter { mismoDia(it.created, a) } }
            "notas" -> todo.filter { it.esEntrada }
            "cintas" -> todo.filter { it.esCinta }
            "todo" -> todo
            else -> todo.filter { (it.title + " " + it.text).lowercase().contains(q) }
        }
    }

    fun temas(): List<Pair<String, Int>> {
        val r = Regex("#[\\p{L}0-9_]+")
        return notas().flatMap { n -> r.findAll(n.text + " " + n.title).map { it.value.lowercase() }.toSet() }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    }

    // ---------------- fotos ----------------

    /** Reduce la foto, la endereza, la cifra y la guarda. Devuelve su id. */
    suspend fun agregarFoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        val m = maestra ?: return@withContext null
        try {
            val cr = app.contentResolver
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
            var muestra = 1
            while (max(limites.outWidth, limites.outHeight) / (muestra * 2) >= 1600) muestra *= 2
            val original = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
            } ?: return@withContext null
            val giro = try {
                cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            } catch (e: Exception) {
                0
            }
            val escala = min(1f, 1600f / max(original.width, original.height))
            val matriz = Matrix().apply {
                postScale(escala, escala)
                if (giro != 0) postRotate(giro.toFloat())
            }
            val ajustada = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matriz, true)
            val salida = ByteArrayOutputStream()
            ajustada.compress(Bitmap.CompressFormat.JPEG, 82, salida)
            val id = Item.nuevoId()
            archivoFoto(id).writeBytes(Crypto.sellar(m, salida.toByteArray()))
            agregar("fotos_pendientes", id)
            id
        } catch (e: Exception) {
            null
        }
    }

    fun fotoEnCache(id: String, lado: Int): Bitmap? = cache.get("$id@$lado")

    suspend fun foto(id: String, lado: Int): Bitmap? = withContext(Dispatchers.IO) {
        val m = maestra ?: return@withContext null
        val f = archivoFoto(id)
        if (!f.exists()) {
            val s = supa ?: return@withContext null
            try {
                f.writeBytes(s.bajarFoto("${s.uid()}/$id.bin"))
            } catch (e: Exception) {
                return@withContext null
            }
        }
        try {
            val bytes = Crypto.abrir(m, f.readBytes())
            val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
            var muestra = 1
            while (max(limites.outWidth, limites.outHeight) / (muestra * 2) >= lado) muestra *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra })
            if (bmp != null) cache.put("$id@$lado", bmp)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- sincronización ----------------

    fun sincronizar() {
        scope.launch { sincronizarAhora() }
    }

    private suspend fun sincronizarAhora() {
        candadoSync.withLock { sincronizarDentro() }
    }

    private suspend fun sincronizarDentro() {
        val s = supa ?: return
        val m = maestra ?: return
        sincronizando = true
        estado = "Guardando…"
        try {
            withContext(Dispatchers.IO) {
                val uid = s.uid()
                // 1) subir lo que cambió en este celular
                val sucios = conjunto("sucios")
                val pendientes = conjunto("fotos_pendientes")
                val filas = mutableListOf<JSONObject>()
                val enviados = mutableMapOf<String, Long>()
                for (id in sucios) {
                    val f = archivoItem(id)
                    val item = porId(id)
                    if (!f.exists() || item == null) {
                        quitar("sucios", id); continue
                    }
                    for (foto in item.photos) {
                        if (foto in pendientes) {
                            val ff = archivoFoto(foto)
                            if (ff.exists()) s.subirFoto("$uid/$foto.bin", ff.readBytes())
                            quitar("fotos_pendientes", foto)
                        }
                    }
                    filas += JSONObject()
                        .put("id", id)
                        .put("user_id", uid)
                        .put("updated_at", item.updated)
                        .put("deleted", item.deleted)
                        .put("data", Crypto.b64(f.readBytes()))
                    enviados[id] = item.updated
                }
                filas.chunked(40).forEach { grupo -> s.subirItems(JSONArray(grupo)) }
                enviados.forEach { (id, cuando) -> if (porId(id)?.updated == cuando) quitar("sucios", id) }

                // 2) borrar de la nube las fotos quitadas o quemadas
                for (foto in conjunto("fotos_borrar")) {
                    s.borrarFoto("$uid/$foto.bin")
                    quitar("fotos_borrar", foto)
                }

                // 3) traer lo que llegó desde otros celulares
                var cursor = prefs.getLong("cursor", 0)
                while (true) {
                    val llegadas = s.traerItems(cursor, 500)
                    for (i in 0 until llegadas.length()) {
                        val r = llegadas.getJSONObject(i)
                        val id = r.getString("id")
                        val local = porId(id)
                        if (local == null || r.getLong("updated_at") > local.updated) {
                            val caja = Crypto.deB64(r.getString("data"))
                            val item = try {
                                Item.de(JSONObject(String(Crypto.abrir(m, caja), Charsets.UTF_8)))
                            } catch (e: Exception) {
                                null
                            }
                            if (item != null) {
                                archivoItem(id).writeBytes(caja)
                                if (abierta) ponerEnLista(item)
                            }
                        }
                        cursor = max(cursor, r.getLong("rev"))
                    }
                    prefs.edit().putLong("cursor", cursor).apply()
                    if (llegadas.length() < 500) break
                }
            }
            estado = if (pendientes() == 0) "Todo a salvo en la nube" else "Quedan ${pendientes()} por guardar"
        } catch (e: Problema) {
            estado = e.message ?: "Algo falló al guardar"
        } catch (e: IOException) {
            estado = "Sin conexión · se guardará después"
        } catch (e: Exception) {
            estado = "Algo falló al guardar: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            sincronizando = false
            if (!abierta) items = emptyList()
        }
    }

    // ---------------- conjuntos en preferencias ----------------

    @Synchronized
    private fun conjunto(nombre: String): Set<String> = HashSet(prefs.getStringSet(nombre, emptySet()) ?: emptySet())

    @Synchronized
    private fun agregar(nombre: String, valor: String) {
        prefs.edit().putStringSet(nombre, conjunto(nombre) + valor).apply()
    }

    @Synchronized
    private fun quitar(nombre: String, valor: String) {
        prefs.edit().putStringSet(nombre, conjunto(nombre) - valor).apply()
    }
}
