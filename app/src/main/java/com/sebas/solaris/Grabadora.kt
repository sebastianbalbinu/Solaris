package com.sebas.solaris

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Graba en WAV (PCM 16 bits, 48 kHz, mono) y reproduce.
 * Los archivos quedan solo en este celular.
 */
object Grabadora {
    const val FRECUENCIA = 48_000

    var grabando by mutableStateOf(false)
        private set
    var reproduciendo by mutableStateOf(false)
        private set
    var pausado by mutableStateOf(false)
        private set
    /** Milisegundos de la cinta en curso (grabando o reproduciendo). */
    var posicion by mutableLongStateOf(0L)
        private set
    /** Nivel de la voz, de 0 a 1, para la luz REC. */
    var nivel by mutableStateOf(0f)
        private set

    var idActual: String? = null
        private set
    var inicioGrabacion: Long = 0L
        private set

    @Volatile private var seguir = false
    private var hilo: Thread? = null
    private var jugador: MediaPlayer? = null

    @SuppressLint("MissingPermission")
    fun grabar(id: String, archivo: File): Boolean {
        if (grabando) return false
        detenerReproduccion()
        val minimo = AudioRecord.getMinBufferSize(FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimo <= 0) return false
        val tam = minimo * 4
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, FRECUENCIA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tam)
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        idActual = id
        inicioGrabacion = System.currentTimeMillis()
        posicion = 0
        seguir = true
        grabando = true
        pausado = false
        hilo = thread(name = "grabadora") {
            val salida = RandomAccessFile(archivo, "rw")
            try {
                salida.setLength(0)
                salida.write(cabecera(0))
                val buf = ShortArray(tam / 2)
                val bytes = ByteArray(buf.size * 2)
                var total = 0L
                rec.startRecording()
                while (seguir) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (pausado) continue
                    var pico = 0
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                        val a = if (v < 0) -v else v
                        if (a > pico) pico = a
                    }
                    salida.write(bytes, 0, n * 2)
                    total += n * 2
                    posicion = total * 1000 / (FRECUENCIA * 2)
                    nivel = (pico / 32768f).coerceIn(0f, 1f)
                }
                rec.stop()
                salida.seek(0)
                salida.write(cabecera(total))
            } catch (e: Exception) {
                // si algo falla, lo grabado hasta ahí se conserva
            } finally {
                try { salida.close() } catch (e: Exception) {}
                rec.release()
            }
        }
        return true
    }

    fun pausarGrabacion() { if (grabando) pausado = !pausado }

    /** Detiene la grabación y devuelve su duración en milisegundos. */
    fun detenerGrabacion(): Long {
        if (!grabando) return 0
        seguir = false
        hilo?.join(2000)
        hilo = null
        grabando = false
        pausado = false
        nivel = 0f
        return posicion
    }

    fun reproducir(id: String, archivo: File, alTerminar: () -> Unit = {}): Boolean {
        if (grabando || !archivo.exists()) return false
        if (idActual == id && jugador != null && pausado) {
            jugador?.start(); pausado = false; reproduciendo = true; return true
        }
        detenerReproduccion()
        return try {
            val j = MediaPlayer()
            j.setDataSource(archivo.absolutePath)
            j.setOnCompletionListener { detenerReproduccion(); alTerminar() }
            j.prepare()
            j.start()
            jugador = j
            idActual = id
            reproduciendo = true
            pausado = false
            true
        } catch (e: Exception) {
            false
        }
    }

    fun pausarReproduccion() {
        val j = jugador ?: return
        if (j.isPlaying) { j.pause(); pausado = true; reproduciendo = false }
    }

    fun detenerReproduccion() {
        jugador?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        jugador = null
        reproduciendo = false
        pausado = false
    }

    /** Se llama seguido desde la pantalla para mover el contador mientras suena. */
    fun actualizar() {
        val j = jugador ?: return
        try { posicion = j.currentPosition.toLong() } catch (e: Exception) {}
    }

    fun adelantar(ms: Int) {
        val j = jugador ?: return
        try { j.seekTo((j.currentPosition + ms).coerceIn(0, j.duration)) } catch (e: Exception) {}
    }

    private fun cabecera(datos: Long): ByteArray {
        val canales = 1
        val bits = 16
        val tasa = FRECUENCIA * canales * bits / 8
        val b = ByteArray(44)
        fun s(o: Int, t: String) { t.forEachIndexed { i, c -> b[o + i] = c.code.toByte() } }
        fun i32(o: Int, v: Long) { for (k in 0 until 4) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        fun i16(o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte() }
        s(0, "RIFF"); i32(4, 36 + datos); s(8, "WAVE"); s(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, canales); i32(24, FRECUENCIA.toLong()); i32(28, tasa.toLong())
        i16(32, canales * bits / 8); i16(34, bits); s(36, "data"); i32(40, datos)
        return b
    }
}
