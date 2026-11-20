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
 * El mar del Drake viene en un paquete aparte: bucles de cada estado y transiciones entre vecinos,
 * horneados con herramientas/hornear.py y publicados en la rama "mar" del repo. Se baja una vez
 * (por defecto solo con Wi-Fi) y queda en el celular; si sale un paquete nuevo, se baja solo lo que cambió.
 */
object Paquete {
    private const val BASE = "https://raw.githubusercontent.com/sebastianbalbinu/Solaris/mar/v2/"
    private lateinit var dir: File
    private lateinit var app: Context
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trabajo: Job? = null

    /** Estado → archivo del bucle. */
    var estados by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** "a>b" → archivo de la transición. */
    var transiciones by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /** Estado → silueta del agua cuadro a cuadro (para que los rayos queden detrás de las olas). */
    var mascaras by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var columnasMascara = 90
        private set
    private val cacheMascaras = HashMap<String, ShortArray>()

    /** Los estados que son de noche. */
    var nocturnos by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Dónde empieza el bucle de destino después de una transición. */
    var entradaMs = 6000L
        private set
    /** 0..1 mientras se descarga; null cuando no está bajando. */
    var progreso by mutableStateOf<Float?>(null)
        private set
    /** Cuánto pesa el paquete entero, en bytes. */
    var peso by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        // el mar liminal (v2) reemplaza al realista: lo viejo se borra para no ocupar espacio
        File(ctx.filesDir, "mar/v1").takeIf { it.exists() }?.deleteRecursively()
        dir = File(ctx.filesDir, "mar/v2").apply { mkdirs() }
        File(dir, "indice.json").takeIf { it.exists() }?.let { try { leerIndice(JSONObject(it.readText())) } catch (e: Exception) {} }
    }

    private fun leerIndice(o: JSONObject) {
        val e = o.getJSONObject("estados")
        estados = e.keys().asSequence().associateWith { e.getString(it) }
        val t = o.getJSONObject("transiciones")
        transiciones = t.keys().asSequence().associateWith { t.getString(it) }
        val n = o.optJSONArray("noche")
        nocturnos = if (n == null) emptySet() else (0 until n.length()).map { n.getString(it) }.toSet()
        entradaMs = o.optLong("entrada_ms", 6000L)
        val m = o.optJSONObject("mascaras")
        mascaras = m?.keys()?.asSequence()?.associateWith { m.getString(it) } ?: emptyMap()
        columnasMascara = o.optInt("mascara_columnas", 90)
        val b = o.optJSONObject("bytes")
        peso = b?.keys()?.asSequence()?.sumOf { b.getLong(it) } ?: 0L
    }

    private fun archivo(nombre: String?): File? = nombre?.let { File(dir, it) }?.takeIf { it.exists() && it.length() > 0 }

    fun bucle(estado: String): File? = archivo(estados[estado])
    fun transicion(a: String, b: String): File? = archivo(transiciones["$a>$b"])

    /** La silueta del agua de un bucle: cuadros × columnas, cada valor es la fila (0..65535) donde empieza el agua. */
    fun mascara(estado: String): ShortArray? {
        cacheMascaras[estado]?.let { return it }
        val f = archivo(mascaras[estado]) ?: return null
        val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val arr = ShortArray(b.remaining()).also { b.get(it) }
        cacheMascaras[estado] = arr
        return arr
    }

    private fun todos(): List<String> = (estados.values + transiciones.values + mascaras.values).distinct()

    /** ¿Está todo en el celular? */
    val completo: Boolean
        get() = estados.isNotEmpty() && todos().all { File(dir, it).exists() }

    /** Los vecinos de un estado (a los que se puede pasar con una transición que ya está bajada). */
    fun vecinos(a: String): List<String> = transiciones.keys.filter { it.startsWith("$a>") }.map { it.substringAfter('>') }
        .filter { transicion(a, it) != null && bucle(it) != null }

    /** El camino más corto de un estado a otro, pasando por vecinos. */
    fun camino(desde: String, hasta: String): List<String>? {
        if (desde == hasta) return listOf(desde)
        val previo = HashMap<String, String>()
        val cola = ArrayDeque(listOf(desde))
        val visto = mutableSetOf(desde)
        while (cola.isNotEmpty()) {
            val a = cola.removeFirst()
            for (b in vecinos(a)) {
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

    private fun conWifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Baja lo que falte. Sin [ahora], solo si hay Wi-Fi. */
    fun descargar(ahora: Boolean = false) {
        if (trabajo?.isActive == true) return
        if (!ahora && !conWifi()) return
        trabajo = scope.launch {
            try {
                error = null
                val texto = get(BASE + "indice.json")
                val o = JSONObject(texto)
                leerIndice(o)
                val bytes = o.optJSONObject("bytes")
                val faltan = todos().filter { f ->
                    val local = File(dir, f)
                    !local.exists() || (bytes != null && local.length() != bytes.optLong(f, local.length()))
                }
                val total = faltan.sumOf { bytes?.optLong(it, 1L) ?: 1L }.coerceAtLeast(1L)
                var hecho = 0L
                progreso = 0f
                // primero los bucles (con ellos ya se ve el mar), luego las transiciones
                for (f in faltan.sortedBy { if (it.contains("__")) 1 else 0 }) {
                    cacheMascaras.clear()
                    val tmp = File(dir, "$f.parcial")
                    http.newCall(Request.Builder().url(BASE + f).build()).execute().use { r ->
                        if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                        r.body!!.byteStream().use { i -> tmp.outputStream().use { o2 -> i.copyTo(o2) } }
                    }
                    tmp.renameTo(File(dir, f))
                    hecho += bytes?.optLong(f, 1L) ?: 1L
                    progreso = hecho.toFloat() / total
                    // con cada archivo nuevo el mar puede usarlo enseguida
                    estados = estados.toMap()
                }
                File(dir, "indice.json").writeText(texto)
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
