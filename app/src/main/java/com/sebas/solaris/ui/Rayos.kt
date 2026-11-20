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
import androidx.media3.exoplayer.ExoPlayer
import com.sebas.solaris.Paquete
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
 * Su frecuencia la marcan el mar y el ritmo con que se escribe.
 */

// ---------------- el ritmo de la escritura ----------------

/**
 * Cuánto se está escribiendo: solo se cuentan teclas y pausas, nunca qué se escribe.
 * Lento y con pausas largas → el mar se aquieta; sostenido → mar bravo; rápido y sin parar → temporal y rayos.
 */
object Ritmo {
    private val teclas = ArrayDeque<Long>()
    private var ultima = 0L
    /** Pausas largas entre ráfagas, en promedio (segundos). */
    private var pausaMedia = 3f
    /** 0 = calma absoluta, 1 = tormenta. */
    var intensidad by mutableStateOf(0f)
        private set

    fun tecla(n: Int) {
        val ahora = System.currentTimeMillis()
        val pausa = (ahora - ultima) / 1000f
        if (ultima > 0 && pausa > 1.5f && pausa < 120f) pausaMedia += (pausa - pausaMedia) * 0.3f
        ultima = ahora
        // pegar un texto largo cuenta como una sola tecla
        repeat(n.coerceIn(1, 3)) { teclas.addLast(ahora) }
    }

    /** Se llama cuatro veces por segundo. */
    fun actualizar() {
        val ahora = System.currentTimeMillis()
        while (teclas.isNotEmpty() && ahora - teclas.first() > 20_000) teclas.removeFirst()
        val porMinuto = teclas.size * 3f
        var meta = when {
            porMinuto < 50 -> 0f
            porMinuto < 160 -> (porMinuto - 50) / 110f * 0.55f
            else -> 0.55f + min(1f, (porMinuto - 160) / 120f) * 0.45f
        }
        val quieto = (ahora - ultima) / 1000f
        if (quieto > 4f) meta *= exp(-(quieto - 4f) / 8f)
        intensidad += (meta - intensidad) * 0.12f
    }

    /** ¿Se está escribiendo de verdad (una lámina abierta y teclas en el último minuto y medio)? */
    val activo: Boolean get() = Nav.borrador != null && System.currentTimeMillis() - ultima < 90_000

    /** El estado que pide la escritura. */
    fun estado(noche: Boolean): String = when {
        noche -> if (intensidad > 0.55f) "temporal_noche" else "noche"
        intensidad > 0.55f -> "temporal"
        intensidad > 0.22f -> "mar_bravo"
        pausaMedia > 6f -> "espejo"
        else -> "calma"
    }
}

// ---------------- un rayo ----------------

private class Trazo(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val brillo: Float)

/** Un rayo: su forma (en coordenadas 0..1 del cuadro) y su destello cuadro a cuadro. */
private class Rayo(semilla: Long, lejano: Boolean) {
    val trazos = mutableListOf<Trazo>()
    val destello: FloatArray
    val nacio = System.nanoTime()
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

/** Cada cuánto cae un rayo (por segundo) en cada estado, sin escribir. */
private val RayosPorSegundo = mapOf("temporal" to 0.07f, "temporal_noche" to 0.08f, "chubasco" to 0.04f, "mar_bravo" to 0f)

/**
 * Dibuja los rayos sobre el video. [jugador] da en qué cuadro va el bucle, para leer la silueta del agua.
 */
@Composable
fun Tormenta(jugador: ExoPlayer, vivo: Boolean) {
    val ctx = LocalContext.current
    val rayos = remember { mutableStateListOf<Rayo>() }
    var cuadro by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    // el ritmo se lee cuatro veces por segundo; los rayos nacen según el mar y la escritura
    LaunchedEffect(vivo) {
        while (isActive && vivo) {
            Ritmo.actualizar()
            val v = Corriente.visible
            val base = RayosPorSegundo[v]
            if (base != null) {
                val porEscribir = if (Ritmo.activo) Ritmo.intensidad * (if (v == "mar_bravo") 0.12f else 0.4f) else 0f
                val tasa = base + porEscribir
                if (rayos.size < 2 && Random.nextFloat() < tasa * 0.25f) {
                    val lejano = v == "mar_bravo" || Random.nextFloat() < 0.3f
                    rayos += Rayo(Random.nextLong(), lejano)
                    if (!lejano || Random.nextFloat() < 0.5f) scope.launch { delay(1200L + Random.nextLong(2300)); trueno(ctx, lejano) }
                }
            }
            delay(250)
        }
    }
    // mientras haya rayos, se redibuja a cada cuadro de la pantalla; si no, nada
    LaunchedEffect(rayos.size) {
        while (isActive && rayos.isNotEmpty()) {
            withFrameNanos { t ->
                cuadro = t
                rayos.removeAll { it.termino(t) }
            }
        }
    }
    if (rayos.isEmpty()) return

    Canvas(Modifier.fillMaxSize()) {
        val ahora = cuadro
        val w = size.width; val h = size.height
        // dónde cae el cuadro del video en la pantalla (el video se recorta al centro)
        val vs = jugador.videoSize
        val vw = if (vs.width > 0) vs.width.toFloat() else 450f
        val vh = if (vs.height > 0) vs.height.toFloat() else 1000f
        val esc = max(w / vw, h / vh)
        fun aPantalla(fx: Float, fy: Float) = Offset(w / 2 + (fx * vw - vw / 2) * esc, h / 2 + (fy * vh - vh / 2) * esc)

        // la silueta del agua en este cuadro: el cielo es lo que está encima
        val cielo = Path()
        val id = jugador.currentMediaItem?.mediaId ?: ""
        val mascara = if (id.startsWith("bucle:")) Paquete.mascara(id.substringAfter(':')) else null
        val cols = Paquete.columnasMascara
        if (mascara != null && mascara.size >= cols) {
            val cuadros = mascara.size / cols
            val f = ((jugador.currentPosition * 30 / 1000) % cuadros).toInt()
            cielo.moveTo(aPantalla(0f, 0f).x, 0f)
            for (c in 0 until cols) {
                val fy = (mascara[f * cols + c].toInt() and 0xFFFF) / 65535f
                val p0 = aPantalla(c.toFloat() / cols, fy); val p1 = aPantalla((c + 1f) / cols, fy)
                cielo.lineTo(p0.x, p0.y); cielo.lineTo(p1.x, p1.y)
            }
            cielo.lineTo(aPantalla(1f, 0f).x, 0f); cielo.close()
        } else {
            val y = aPantalla(0f, 0.365f).y
            cielo.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, y))
        }

        for (r in rayos) {
            val i = r.intensidad(ahora)
            if (i <= 0f) continue
            val arriba = aPantalla(r.arriba.x, r.arriba.y)
            val abajo = aPantalla(r.abajo.x, r.abajo.y)
            // todo el mar se aclara un instante
            drawRect(Color(0xFFDCE6EA).copy(alpha = 0.08f * i))
            clipPath(cielo) {
                // las nubes se encienden por dentro, más cerca del rayo
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFFE4ECF2).copy(alpha = 0.55f * i), Color(0xFFB9C6CC).copy(alpha = 0.2f * i), Color.Transparent),
                        center = arriba, radius = w * 0.9f,
                    )
                )
                // el rayo: resplandor ancho, halo y núcleo
                drawIntoCanvas { c ->
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    for ((ancho, difuso, alfa) in listOf(Triple(16f, 12f, 0.22f), Triple(6f, 4f, 0.5f), Triple(1.8f, 0f, 1f))) {
                        p.maskFilter = if (difuso > 0f && Build.VERSION.SDK_INT >= 28) BlurMaskFilter(difuso * density * r.escala, BlurMaskFilter.Blur.NORMAL) else null
                        for (t in r.trazos) {
                            p.strokeWidth = ancho * density * r.escala * (0.5f + 0.5f * t.brillo)
                            p.color = android.graphics.Color.argb((255 * min(1f, t.brillo * alfa * i)).toInt(), 238, 244, 255)
                            val a = aPantalla(t.x0, t.y0); val b = aPantalla(t.x1, t.y1)
                            c.nativeCanvas.drawLine(a.x, a.y, b.x, b.y, p)
                        }
                    }
                }
            }
            // el reflejo: el agua lo devuelve roto, en tajos horizontales bajo el rayo
            clipPath(cielo, clipOp = ClipOp.Difference) {
                val g = Random(r.nacio xor (ahora / 33_333_333L))
                val bajo = h - abajo.y
                for (k in 0 until 26) {
                    val prof = g.nextFloat()
                    val y = abajo.y + prof * prof * bajo * 0.45f
                    val ancho = w * (0.01f + 0.05f * prof) * (0.5f + g.nextFloat())
                    val x = abajo.x + (g.nextFloat() - 0.5f) * w * (0.02f + 0.1f * prof)
                    drawRect(Color(0xFFE6EEF6).copy(alpha = 0.5f * i * (1f - prof) * g.nextFloat()), Offset(x - ancho / 2, y), Size(ancho, 1.5f * density + prof * 3f * density))
                }
            }
        }
    }
}

/** El trueno no suena (el mar es silencioso): se siente, largo y tenue, un par de segundos después. */
private fun trueno(ctx: Context, lejano: Boolean) {
    val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    if (!v.hasVibrator()) return
    val f = if (lejano) 0.5f else 1f
    val tiempos = longArrayOf(0, 70, 50, 140, 80, 260)
    val fuerza = intArrayOf(0, (45 * f).toInt(), (18 * f).toInt(), (70 * f).toInt(), (25 * f).toInt(), (38 * f).toInt())
    try {
        if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createWaveform(tiempos, fuerza, -1))
    } catch (e: Exception) {}
}
