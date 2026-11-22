package com.sebas.solaris.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import com.sebas.solaris.Clima
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * Los rayos: cada uno se genera en el momento (punto medio desplazado, con ramas), así que nunca hay dos iguales.
 * Un relámpago es un golpe, un apagón, una o dos réplicas y un desvanecerse. El cielo se enciende desde adentro,
 * el agua lo devuelve roto, y las crestas que se levantan lo tapan (con la silueta del agua horneada cuadro a cuadro).
 * Solo caen cuando en el Drake hay tormenta eléctrica de verdad. Cada rayo se pinta una sola vez en una imagen
 * (con su reflejo roto en el agua) y después solo se enciende y se apaga: así no tiembla ni pesa.
 */

// ---------------- un rayo ----------------

private class Trazo(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val brillo: Float)

/** Un rayo: su forma (en coordenadas 0..1 del cuadro) y su destello cuadro a cuadro. */
private class Rayo(semilla: Long, val lejano: Boolean) {
    /** El rayo ya pintado, a media resolución (lo pone la tormenta al nacer). */
    var imagen: androidx.compose.ui.graphics.ImageBitmap? = null
    val semillaReflejo = semilla
    val trazos = mutableListOf<Trazo>()
    val destello: FloatArray
    var nacio = System.nanoTime()
    val arriba: Offset
    val abajo: Offset
    val escala = if (lejano) 0.55f else 1f

    init {
        val r = Random(semilla)
        val x0 = 0.15f + r.nextFloat() * 0.7f
        val y0 = if (lejano) 0.2f + r.nextFloat() * 0.06f else 0.04f + r.nextFloat() * 0.08f
        val x1 = x0 + (r.nextFloat() - 0.5f) * 0.35f * escala
        arriba = Offset(x0, y0); abajo = Offset(x1, 0.37f)
        partir(arriba, abajo, 0.26f * escala, 9, 1f, r)
        // golpe, apagón, réplica, apagón, a veces otra réplica, y un resplandor que se va
        val sec = mutableListOf(1f, 0.35f, 0f, 0.85f, 0.25f, 0f, 0f)
        if (r.nextFloat() < 0.6f) sec += listOf(0.6f, 0.15f)
        for (k in 0 until 8) sec += 0.08f * Math.pow(0.6, k.toDouble()).toFloat()
        destello = sec.map { it * (if (lejano) 0.5f else 1f) }.toFloatArray()
    }

    private fun partir(a: Offset, b: Offset, desv: Float, nivel: Int, brillo: Float, r: Random) {
        if (nivel == 0) { trazos += Trazo(a.x, a.y, b.x, b.y, brillo); return }
        val dx = b.x - a.x; val dy = b.y - a.y
        val l = hypot(dx, dy) + 1e-6f
        val off = (r.nextFloat() - 0.5f) * desv
        // el desvío es en ancho de pantalla: el cuadro es ~0.45 de ancho por alto
        val m = Offset((a.x + b.x) / 2 - dy / l * off, (a.y + b.y) / 2 + dx / l * off * 0.45f)
        partir(a, m, desv / 2, nivel - 1, brillo, r)
        partir(m, b, desv / 2, nivel - 1, brillo, r)
        if (brillo > 0.25f && nivel > 2 && r.nextFloat() < 0.42f) {
            val ang = atan2(dy, dx) + (r.nextFloat() - 0.5f) * 1.2f
            val largo = l * (0.4f + r.nextFloat() * 0.5f)
            val c = Offset(m.x + cos(ang) * largo * 0.6f, m.y + kotlin.math.abs(sin(ang)) * largo * 0.9f)
            partir(m, c, desv / 2.5f, nivel - 2, brillo * 0.45f, r)
        }
    }

    /** Cuánto brilla ahora (0 cuando ya terminó). */
    fun intensidad(ahora: Long): Float {
        val k = ((ahora - nacio) / 33_333_333L).toInt()
        return if (k in destello.indices) destello[k] else 0f
    }

    fun termino(ahora: Long) = ((ahora - nacio) / 33_333_333L).toInt() >= destello.size
}

// ---------------- la tormenta ----------------

/** Los estados donde puede haber tormenta eléctrica a la vista. */
private val ConTormenta = setOf("temporal", "temporal_noche", "chubasco", "mar_bravo")

/** Dónde cae el cuadro del video (450×1000, recortado al centro) en una pantalla de w×h. */
private class Encuadre(val w: Float, val h: Float) {
    private val vw = 450f; private val vh = 1000f
    private val esc = max(w / vw, h / vh)
    fun aPantalla(fx: Float, fy: Float) = Offset(w / 2 + (fx * vw - vw / 2) * esc, h / 2 + (fy * vh - vh / 2) * esc)
    val horizonte: Float get() = aPantalla(0f, 0.37f).y
}

/** Pinta un rayo una sola vez: el trazo en el cielo y su reflejo roto en el agua. */
private fun pintar(r: Rayo, w: Int, h: Int, densidad: Float): android.graphics.Bitmap {
    val esc = 0.5f
    val bmp = android.graphics.Bitmap.createBitmap((w * esc).toInt().coerceAtLeast(1), (h * esc).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.scale(esc, esc)
    val e = Encuadre(w.toFloat(), h.toFloat())
    val hz = e.horizonte
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    c.save()
    c.clipRect(0f, 0f, w.toFloat(), hz)
    for ((ancho, difuso, alfa) in listOf(Triple(9f, 7f, 0.16f), Triple(3.6f, 1.6f, 0.55f), Triple(1.7f, 0f, 1f), Triple(0.7f, 0f, 1f))) {
        p.maskFilter = if (difuso > 0f) BlurMaskFilter(difuso * densidad * r.escala, BlurMaskFilter.Blur.NORMAL) else null
        for (t in r.trazos) {
            p.strokeWidth = ancho * densidad * r.escala * (0.5f + 0.5f * t.brillo)
            val (rr, gg, bb) = if (ancho < 1f) Triple(255, 255, 255) else Triple(228, 238, 255)
            p.color = android.graphics.Color.argb((255 * min(1f, t.brillo * alfa)).toInt(), rr, gg, bb)
            val a = e.aPantalla(t.x0, t.y0); val b = e.aPantalla(t.x1, t.y1)
            c.drawLine(a.x, a.y, b.x, b.y, p)
        }
    }
    c.restore()
    // el reflejo: el agua lo devuelve roto, en tajos horizontales bajo el rayo (siempre los mismos)
    val abajo = e.aPantalla(r.abajo.x, r.abajo.y)
    val g = Random(r.semillaReflejo)
    val bajo = h - abajo.y
    val pr = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    for (k in 0 until 26) {
        val prof = g.nextFloat()
        val y = abajo.y + prof * prof * bajo * 0.45f
        val ancho = w * (0.01f + 0.05f * prof) * (0.5f + g.nextFloat())
        val x = abajo.x + (g.nextFloat() - 0.5f) * w * (0.02f + 0.1f * prof)
        pr.color = android.graphics.Color.argb((255 * 0.45f * (1f - prof) * g.nextFloat()).toInt(), 230, 238, 246)
        c.drawRect(x - ancho / 2, y, x + ancho / 2, y + 1.5f * densidad + prof * 3f * densidad, pr)
    }
    return bmp
}

/**
 * Dibuja los rayos sobre el video, solo si en el Drake hay tormenta eléctrica ahora mismo.
 */
@Composable
fun Tormenta(vivo: Boolean) {
    val rayos = remember { mutableStateListOf<Rayo>() }
    var cuadro by remember { mutableLongStateOf(0L) }
    var tam by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    val scope = rememberCoroutineScope()

    LaunchedEffect(vivo) {
        while (isActive && vivo) {
            val hay = Clima.mar.rayos && Corriente.visible in ConTormenta
            if (hay && tam.width > 0 && rayos.size < 2 && Random.nextFloat() < 0.06f) {
                val lejano = Corriente.visible == "mar_bravo" || Random.nextFloat() < 0.35f
                val r = Rayo(Random.nextLong(), lejano)
                // se pinta aparte, y recién entonces cae
                val img = withContext(Dispatchers.Default) { pintar(r, tam.width, tam.height, densidad) }
                r.imagen = img.asImageBitmap()
                r.nacio = System.nanoTime()
                rayos += r
                com.sebas.solaris.Sonidos.rayo(lejano)
                scope.launch { delay((if (lejano) 2200L else 900L) + Random.nextLong(1600)); com.sebas.solaris.Sonidos.trueno(lejano) }
            }
            delay(1000)
        }
    }
    // mientras haya rayos, se redibuja a cada cuadro de la pantalla; si no, nada
    LaunchedEffect(rayos.size) {
        while (isActive && rayos.isNotEmpty()) {
            withFrameNanos { t ->
                cuadro = t
                rayos.removeAll { it.termino(t) }
                Relampago.brillo = rayos.maxOfOrNull { it.intensidad(t) * it.escala } ?: 0f
            }
        }
        Relampago.brillo = 0f
    }

    Canvas(Modifier.fillMaxSize().onSizeChanged { tam = it }) {
        if (rayos.isEmpty()) return@Canvas
        val ahora = cuadro
        val w = size.width; val h = size.height
        val e = Encuadre(w, h)
        val hz = e.horizonte
        for (r in rayos) {
            val i = r.intensidad(ahora)
            if (i <= 0f) continue
            // todo el mar se aclara un instante
            drawRect(Color(0xFFDCE6EA).copy(alpha = 0.08f * i))
            // las nubes se encienden por dentro, más cerca del rayo
            clipRect(0f, 0f, w, hz) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFFE4ECF2).copy(alpha = 0.55f * i), Color(0xFFB9C6CC).copy(alpha = 0.2f * i), Color.Transparent),
                        center = e.aPantalla(r.arriba.x, r.arriba.y), radius = w * 0.9f,
                    )
                )
            }
            r.imagen?.let { drawImage(it, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()), alpha = i.coerceIn(0f, 1f)) }
        }
    }
}

/** La luz del relámpago en este instante (0..1), para lo que tapa el cielo. */
object Relampago {
    var brillo by mutableFloatStateOf(0f)
}
