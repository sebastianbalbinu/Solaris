package com.sebas.solaris

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sebas.solaris.ui.Corriente
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.exp

/**
 * El sonido del mar, en capas que siguen al Drake real igual que el video:
 * mar en calma, mar bravo y temporal según la altura de las olas, la lluvia cuando llueve allá,
 * y un trueno con cada rayo. Todo cambia despacio (como los pasos de un minuto del mar).
 * Grabaciones de Freesound: borralbi (CC0), bruno.auzet (CC0), George_Papargyris (CC BY 4.0),
 * jrosin (CC BY 4.0) y BlueDelta (CC0).
 */
object Ambiente {
    private const val BASE = "https://github.com/sebastianbalbinu/Solaris/releases/download/sonido-v1/"
    private val CAPAS = listOf("mar_calma", "mar_bravo", "mar_temporal", "lluvia")
    private val GOLPES = listOf("trueno_lejano", "trueno_cercano")

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jugadores = HashMap<String, ExoPlayer>()
    private val actual = HashMap<String, Float>()
    private var general = 0f
    private var iniciado = false

    /** Si suena el mar (se puede apagar en Ajustes). */
    var activo by mutableStateOf(true)
        private set
    /** Si los sonidos ya están en el celular. */
    var listo by mutableStateOf(false)
        private set
    /** La app está a la vista. */
    var aLaVista = false

    fun fijarActivo(v: Boolean) {
        activo = v
        prefs.edit().putBoolean("sonido_mar", v).apply()
    }

    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("solaris_ui", Context.MODE_PRIVATE)
        activo = prefs.getBoolean("sonido_mar", true)
        dir = File(app.filesDir, "sonido/v1").apply { mkdirs() }
        scope.launch {
            withContext(Dispatchers.IO) { bajar() }
            listo = (CAPAS + GOLPES).all { archivo(it) != null }
            if (listo) crear()
            mezclar()
        }
    }

    private fun archivo(nombre: String): File? = File(dir, "$nombre.ogg").takeIf { it.exists() && it.length() > 0 }

    /** Baja lo que falte (unos 6 MB en total, una sola vez). */
    private suspend fun bajar() {
        val faltan = (CAPAS + GOLPES).filter { archivo(it) == null }
        if (faltan.isEmpty()) return
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        // primero el mar en calma y el bravo: con eso ya suena
        for (n in faltan.sortedBy { if (it.startsWith("mar")) 0 else 1 }) {
            var intento = 0
            while (intento < 3) {
                try {
                    http.newCall(Request.Builder().url(BASE + "$n.ogg").build()).execute().use { r ->
                        if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                        val tmp = File(dir, "$n.ogg.part")
                        r.body!!.byteStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp.renameTo(File(dir, "$n.ogg"))
                    }
                    break
                } catch (e: Exception) {
                    intento++
                    delay(4000L * intento)
                }
            }
        }
    }

    private fun crear() {
        val atributos = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
        for (n in CAPAS) {
            val f = archivo(n) ?: continue
            val j = ExoPlayer.Builder(app).build()
            // no le quita el sonido a otras apps (si pones música, el mar queda debajo)
            j.setAudioAttributes(atributos, false)
            j.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
            j.repeatMode = Player.REPEAT_MODE_ONE
            j.volume = 0f
            j.prepare()
            jugadores[n] = j
            actual[n] = 0f
        }
    }

    private fun suave(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Lo que debería sonar ahora mismo, según el mar que se ve y el clima de allá. */
    private fun objetivos(): Map<String, Float> {
        val h = Regex("_([0-9.]+)m").find(Corriente.visible)?.groupValues?.get(1)?.toDoubleOrNull() ?: Clima.mar.ola
        val temporal = suave(5.5, 8.5, h)
        val calma = 1 - suave(2.0, 3.5, h)
        val bravo = (1 - calma - temporal).coerceAtLeast(0.0)
        val m = Clima.mar
        val lluvia = maxOf(suave(0.2, 3.0, m.lluvia), if (m.rayos) 0.7 else 0.0) * 0.85
        return mapOf(
            "mar_calma" to calma.toFloat(),
            "mar_bravo" to bravo.toFloat(),
            "mar_temporal" to temporal.toFloat(),
            "lluvia" to lluvia.toFloat(),
        )
    }

    /** Callado mientras se graba o se escucha una cinta (el micrófono no debe oír el mar del parlante). */
    private fun callado(): Boolean =
        !activo || !aLaVista || !Casa.conectada || Grabadora.grabando || Grabadora.reproduciendo

    private suspend fun mezclar() {
        var antes = System.nanoTime()
        while (scope.isActive) {
            delay(100)
            val ahora = System.nanoTime()
            val dt = ((ahora - antes) / 1e9).toFloat().coerceIn(0f, 1f)
            antes = ahora
            if (jugadores.isEmpty()) continue
            // el volumen general entra en unos 3 s y se va en 1 s
            val metaGeneral = if (callado()) 0f else 0.85f
            val tg = if (metaGeneral > general) 1.2f else 0.35f
            general += (metaGeneral - general) * (1 - exp(-dt / tg))
            // cada capa cambia sin prisa (unos 40 s para llegar), como el mar
            val metas = objetivos()
            for ((n, j) in jugadores) {
                val a = actual[n] ?: 0f
                val b = a + ((metas[n] ?: 0f) - a) * (1 - exp(-dt / 14f))
                actual[n] = b
                val v = (b * general).coerceIn(0f, 1f)
                j.volume = v
                if (v > 0.002f && !j.isPlaying) j.play()
                if (v <= 0.002f && j.isPlaying) j.pause()
            }
        }
    }

    /** Un trueno, lejano o cercano, cuando cae un rayo. */
    fun trueno(lejano: Boolean) {
        if (!iniciado || callado()) return
        val f = archivo(if (lejano) "trueno_lejano" else "trueno_cercano") ?: return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
            mp.setDataSource(f.path)
            val v = (if (lejano) 0.55f else 0.95f) * general.coerceAtLeast(0.3f)
            mp.setVolume(v, v)
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {}
    }
}
