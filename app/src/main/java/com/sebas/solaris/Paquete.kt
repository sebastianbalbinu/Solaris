package com.sebas.solaris

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * El mar del Drake (v3): oleaje con física real, horneado con herramientas/oceano.py y publicado en el
 * release "mar-v3". Cada estado es una altura de ola (1 a 10 m) con su luz (día, crepúsculo, noche):
 * un bucle de 30 s que cierra perfecto, y pasos de 60 s hacia sus vecinos.
 *
 * El paquete entero pesa varios GB, así que en el celular vive solo la vecindad: el mar que se ve,
 * los mares vecinos y los pasos para llegar a ellos (unos 150 MB). Lo demás baja cuando el Drake se acerca.
 */
object Paquete {
    private const val BASE = "https://github.com/sebastianbalbinu/Solaris/releases/download/mar-v3/"
    /** Hasta cuánto puede ocupar el mar en el celular. */
    private const val TOPE = 190L * 1024 * 1024
    private lateinit var dir: File
    private lateinit var app: Context
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajo: Job? = null
    private var indiceLeido = 0L

    /** Estado → archivo del bucle. */
    var estados by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** "a>b" → archivo del paso. */
    var transiciones by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Nombre del video → su curva de movimiento del barco. */
    private var movimientos: Map<String, String> = emptyMap()
    private var bytes: Map<String, Long> = emptyMap()
    /** Las alturas de ola que hay (en metros). */
    var escalera: List<Double> = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)
        private set
    /** Dónde queda el horizonte en el cuadro (0 arriba, 1 abajo). */
    var horizonte = 0.402f
        private set
    var entradaMs = 0L
        private set
    /** Cuántos archivos del mar llegaron (para que el reproductor se entere). */
    var llegados by mutableStateOf(0)
        private set
    /** 0..1 mientras se descarga; null cuando no está bajando. */
    var progreso by mutableStateOf<Float?>(null)
        private set
    /** Cuánto ocupa el mar en el celular, en bytes. */
    var ocupado by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        // los mares de antes (v1 realista viejo, v2 liminal) se borran: no se usan más
        listOf("mar/v1", "mar/v2").forEach { File(ctx.filesDir, it).takeIf { f -> f.exists() }?.deleteRecursively() }
        dir = File(ctx.filesDir, "mar/v3").apply { mkdirs() }
        File(dir, "indice.json").takeIf { it.exists() }?.let { try { leerIndice(JSONObject(it.readText())) } catch (e: Exception) {} }
        medir()
    }

    private fun leerIndice(o: JSONObject) {
        val e = o.getJSONObject("estados")
        estados = e.keys().asSequence().associateWith { e.getString(it) }
        val t = o.getJSONObject("transiciones")
        transiciones = t.keys().asSequence().associateWith { t.getString(it) }
        val m = o.optJSONObject("movimiento")
        movimientos = m?.keys()?.asSequence()?.associateWith { m.getString(it) } ?: emptyMap()
        val b = o.optJSONObject("bytes")
        bytes = b?.keys()?.asSequence()?.associateWith { b.getLong(it) } ?: emptyMap()
        o.optJSONArray("escalera")?.let { a -> escalera = (0 until a.length()).map { a.getDouble(it) } }
        horizonte = o.optDouble("horizonte", 0.402).toFloat()
        entradaMs = o.optLong("entrada_ms", 0L)
        indiceLeido = System.currentTimeMillis()
    }

    private fun archivo(nombre: String?): File? = nombre?.let { File(dir, it) }?.takeIf { it.exists() && it.length() > 0 }

    fun bucle(estado: String): File? = archivo(estados[estado])
    fun transicion(a: String, b: String): File? = archivo(transiciones["$a>$b"])
    /** La curva de cabeceo, balanceo y subida de un video ("dia_3m" o "dia_3m__dia_4m"). */
    fun movimiento(video: String): File? = archivo(movimientos[video])

    /** Los vecinos de un estado según el índice (estén o no bajados). */
    private fun vecinosDelIndice(a: String): List<String> =
        transiciones.keys.filter { it.startsWith("$a>") }.map { it.substringAfter('>') }

    /** Los vecinos a los que se puede pasar ya mismo (paso y bucle en el celular). */
    fun vecinos(a: String): List<String> = vecinosDelIndice(a).filter { transicion(a, it) != null && bucle(it) != null }

    /** El camino más corto de un estado a otro, por el mapa completo (lo que falte se baja). */
    fun camino(desde: String, hasta: String, soloBajado: Boolean = true): List<String>? {
        if (desde == hasta) return listOf(desde)
        val previo = HashMap<String, String>()
        val cola = ArrayDeque(listOf(desde))
        val visto = mutableSetOf(desde)
        while (cola.isNotEmpty()) {
            val a = cola.removeFirst()
            for (b in if (soloBajado) vecinos(a) else vecinosDelIndice(a)) {
                if (b in visto) continue
                visto += b; previo[b] = a
                if (b == hasta) {
                    val ruta = mutableListOf(b)
                    var x = b
                    while (x != desde) { x = previo[x]!!; ruta.add(0, x) }
                    return ruta
                }
                cola.addLast(b)
            }
        }
        return null
    }

    private fun video(a: String, b: String? = null) = if (b == null) a else "${a}__$b"

    /**
     * Lo que tiene que estar en el celular, en orden de urgencia: el mar que se ve, el siguiente paso
     * hacia el real, y la vecindad del que se ve.
     */
    private fun necesarios(visible: String, objetivo: String): List<String> {
        val l = mutableListOf<String>()
        fun conMov(f: String?, v: String) { if (f != null) { l += f; movimientos[v]?.let { l += it } } }
        conMov(estados[visible], visible)
        camino(visible, objetivo, soloBajado = false)?.zipWithNext()?.take(2)?.forEach { (a, b) ->
            conMov(transiciones["$a>$b"], video(a, b)); conMov(estados[b], b)
        }
        for (n in vecinosDelIndice(visible)) { conMov(transiciones["$visible>$n"], video(visible, n)); conMov(estados[n], n) }
        return l.distinct()
    }

    private fun conWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun medir() {
        ocupado = dir.listFiles()?.filter { it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L
    }

    /**
     * Baja lo que falta para el mar de ahora. El bucle que se ve baja con cualquier conexión (sin él no hay mar);
     * la vecindad, solo con Wi-Fi (o si [ahora]).
     */
    fun descargar(visible: String? = null, objetivo: String? = null, ahora: Boolean = false) {
        if (trabajo?.isActive == true) return
        trabajo = scope.launch {
            try {
                error = null
                // el índice se renueva cada tanto (mientras se terminan de hornear los videos, crece)
                if (estados.isEmpty() || System.currentTimeMillis() - indiceLeido > 6 * 3600_000L) {
                    val texto = get(BASE + "indice.json")
                    leerIndice(JSONObject(texto))
                    File(dir, "indice.json").writeText(texto)
                }
                val v = visible ?: objetivo ?: return@launch
                val o = objetivo ?: v
                val wifi = conWifi() || ahora
                val todos = necesarios(v, o)
                val urgentes = todos.take(if (wifi) todos.size else 2)
                val faltan = urgentes.filter { f -> val loc = File(dir, f); !loc.exists() || (bytes[f] != null && loc.length() != bytes[f]) }
                if (faltan.isNotEmpty()) {
                    val total = faltan.sumOf { bytes[it] ?: 20_000L }.coerceAtLeast(1L)
                    var hecho = 0L
                    progreso = 0f
                    for (f in faltan) {
                        val tmp = File(dir, "$f.parcial")
                        http.newCall(Request.Builder().url(BASE + f).build()).execute().use { r ->
                            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                            r.body!!.byteStream().use { i -> tmp.outputStream().use { o2 -> i.copyTo(o2) } }
                        }
                        tmp.renameTo(File(dir, f))
                        hecho += bytes[f] ?: 20_000L
                        progreso = hecho.toFloat() / total
                        llegados++
                    }
                }
                // lo que ya no está cerca se borra si el mar ocupa más de la cuenta
                medir()
                if (ocupado > TOPE) {
                    val guardar = todos.toSet() + "indice.json"
                    dir.listFiles()?.filter { it.name !in guardar && !it.name.endsWith(".parcial") }
                        ?.sortedBy { it.lastModified() }
                        ?.forEach { if (ocupado > TOPE * 8 / 10) { ocupado -= it.length(); it.delete() } }
                    medir()
                }
            } catch (e: Exception) {
                error = "No se pudo bajar el mar. Se intentará de nuevo."
            } finally {
                progreso = null
            }
        }
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
}
