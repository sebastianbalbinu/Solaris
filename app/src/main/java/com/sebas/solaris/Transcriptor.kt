package com.sebas.solaris

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Convierte la voz de una cinta en texto.
 * El WAV se reduce a 16 kHz (lo que usa Whisper) y se envía por partes de 5 minutos.
 */
object Transcriptor {
    var enCurso by mutableStateOf(setOf<String>())
        private set

    private const val SEG_POR_PARTE = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Transcribe en segundo plano, aunque se cambie de pantalla. */
    fun lanzar(id: String, alError: (String) -> Unit) {
        if (id in enCurso) return
        scope.launch { transcribir(id)?.let(alError) }
    }

    /** Devuelve null si salió bien, o el mensaje del problema. */
    suspend fun transcribir(id: String): String? = withContext(Dispatchers.IO) {
        val archivo = Casa.archivoCinta(id)
        if (!archivo.exists()) return@withContext "El audio de esta cinta no está en este celular."
        enCurso = enCurso + id
        try {
            val textos = mutableListOf<String>()
            RandomAccessFile(archivo, "r").use { f ->
                val total = f.length() - 44
                if (total <= 0) return@withContext "La cinta está vacía."
                val bytesParte = SEG_POR_PARTE.toLong() * Grabadora.FRECUENCIA * 2
                var leido = 0L
                f.seek(44)
                while (leido < total) {
                    val n = minOf(bytesParte, total - leido).toInt()
                    val buf = ByteArray(n)
                    f.readFully(buf)
                    leido += n
                    val texto = Casa.transcribirAudio(a16k(buf))
                    if (texto.isNotBlank()) textos += texto
                }
            }
            withContext(Dispatchers.Main) { Casa.ponerTranscripcion(id, textos.joinToString(" ")) }
            null
        } catch (e: Problema) {
            e.message ?: "La transcripción falló."
        } catch (e: java.io.IOException) {
            "Sin conexión: la transcripción queda para después."
        } catch (e: Exception) {
            "La transcripción falló: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            enCurso = enCurso - id
        }
    }

    /** De 48 kHz a 16 kHz (promedio de cada 3 muestras), con su cabecera WAV. */
    private fun a16k(pcm48: ByteArray): ByteArray {
        val muestras = pcm48.size / 2
        val salida = muestras / 3
        val out = ByteArrayOutputStream(44 + salida * 2)
        out.write(cabecera(salida * 2L, 16_000))
        var i = 0
        val b = ByteArray(2)
        while (i + 2 < muestras) {
            var suma = 0
            for (k in 0 until 3) {
                val lo = pcm48[(i + k) * 2].toInt() and 0xFF
                val hi = pcm48[(i + k) * 2 + 1].toInt()
                suma += (hi shl 8) or lo
            }
            val v = (suma / 3).coerceIn(-32768, 32767)
            b[0] = (v and 0xFF).toByte(); b[1] = ((v shr 8) and 0xFF).toByte()
            out.write(b)
            i += 3
        }
        return out.toByteArray()
    }

    private fun cabecera(datos: Long, frecuencia: Int): ByteArray {
        val h = ByteArray(44)
        fun s(o: Int, t: String) { t.forEachIndexed { i, c -> h[o + i] = c.code.toByte() } }
        fun i32(o: Int, v: Long) { for (k in 0 until 4) h[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
        fun i16(o: Int, v: Int) { h[o] = (v and 0xFF).toByte(); h[o + 1] = ((v shr 8) and 0xFF).toByte() }
        s(0, "RIFF"); i32(4, 36 + datos); s(8, "WAVE"); s(12, "fmt ")
        i32(16, 16); i16(20, 1); i16(22, 1); i32(24, frecuencia.toLong()); i32(28, frecuencia * 2L)
        i16(32, 2); i16(34, 16); s(36, "data"); i32(40, datos)
        return h
    }
}

/** Copia las cintas (WAV) a una carpeta que elijas: tu respaldo, fuera de la nube. */
object Exportador {
    suspend fun exportar(ctx: Context, carpeta: Uri): String = withContext(Dispatchers.IO) {
        val destino = DocumentFile.fromTreeUri(ctx, carpeta) ?: return@withContext "No se pudo abrir esa carpeta."
        val f = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)
        var n = 0
        for (c in Casa.cintas()) {
            val origen = Casa.archivoCinta(c.id)
            if (!origen.exists()) continue
            val nombre = (f.format(Date(c.created)) + " " + c.title.ifBlank { "cinta" })
                .replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
            if (destino.findFile("$nombre.wav") != null) continue
            val nuevo = destino.createFile("audio/wav", nombre) ?: continue
            ctx.contentResolver.openOutputStream(nuevo.uri)?.use { out -> origen.inputStream().use { it.copyTo(out) } }
            n++
        }
        if (n == 0) "No había cintas nuevas para exportar." else if (n == 1) "1 cinta exportada." else "$n cintas exportadas."
    }
}
