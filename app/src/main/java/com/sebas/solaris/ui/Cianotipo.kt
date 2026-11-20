package com.sebas.solaris.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.Image as ImagenCruda
import android.media.ImageReader
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.sebas.solaris.Clima
import com.sebas.solaris.Mar
import com.sebas.solaris.R
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/*
 * El mar del Drake como un cianotipo vivo.
 * La tarjeta gráfica lo pinta a un tercio de la resolución y se amplía con suavizado; el grano y la viñeta
 * van encima en alta resolución, así la textura sigue nítida. Android 13 en adelante; antes, una lámina quieta.
 */

private fun leerSombra(ctx: Context, id: Int): String =
    ctx.resources.openRawResource(id).bufferedReader().use { it.readText() }

@RequiresApi(33)
private fun RuntimeShader.u(nombre: String, v: Float) {
    try { setFloatUniform(nombre, v) } catch (e: IllegalArgumentException) {}
}

// ---------------- la corriente interior: el ánimo del mar ----------------

/** Los estados por los que deriva el mar. REAL deja mandar a la lectura del Drake. */
enum class Animo(val nombre: String) {
    REAL(""),
    CALMA("CALMA SOLEMNE"),
    TEMPORAL("TEMPORAL"),
    NIEBLA("NIEBLA"),
    ESPEJO("ESPEJO"),
    HORA_AZUL("HORA AZUL"),
    CHUBASCO("CHUBASCO"),
}

/** Hacia dónde quiere ir el mar en este momento. */
class Metas(
    val ola: Double, val periodo: Double, val viento: Double, val nubes: Double,
    val lluvia: Double, val vis: Double, val azul: Double, val rayos: Boolean,
)

/**
 * Sobre el mar real corre un ánimo que cambia solo, a veces despacio y a veces de golpe,
 * como cambia uno. Vive mientras la app está abierta y sigue igual entre pantallas.
 */
object Corriente {
    var actual by mutableStateOf(Animo.REAL)
        private set
    /** Qué tan rápido se acerca el mar al ánimo nuevo (por segundo). */
    var rapidez = 0.04
        private set
    private var restante = 0.0
    private var empezada = false
    /** Si este temporal trae rayos (se decide una vez, al empezar el ánimo). */
    private var conRayos = false

    /** Nombre del mar que se ve: el ánimo, o el estado real cuando manda la lectura. */
    val nombreVisible: String get() = actual.nombre.ifBlank { Clima.mar.nombre }

    private fun elegir(m: Mar) {
        val tormentoso = m.viento >= 30 || m.ola >= 5
        val pesos = linkedMapOf(
            Animo.REAL to 3.0,
            Animo.CALMA to if (tormentoso) 1.0 else 2.5,
            Animo.TEMPORAL to if (tormentoso) 3.0 else 1.0,
            Animo.NIEBLA to 1.6,
            Animo.ESPEJO to if (tormentoso) 0.5 else 1.4,
            Animo.HORA_AZUL to 1.2,
            Animo.CHUBASCO to if (tormentoso) 1.8 else 0.9,
        )
        pesos.remove(actual)
        var r = Random.nextDouble() * pesos.values.sum()
        var nuevo = Animo.REAL
        for ((a, w) in pesos) { r -= w; if (r <= 0) { nuevo = a; break } }
        actual = nuevo
        // casi siempre cambia como la marea; una de cada cuatro veces, de golpe
        rapidez = if (Random.nextDouble() < 0.25) 0.45 + Random.nextDouble() * 0.4 else 0.025 + Random.nextDouble() * 0.04
        restante = 45.0 + Random.nextDouble() * 200.0
        conRayos = Random.nextDouble() < 0.5
    }

    fun avanzar(m: Mar, dt: Double) {
        if (!empezada) {
            empezada = true
            elegir(m)
            rapidez = 1.0 // al abrir, el mar ya está en su ánimo
        }
        restante -= dt
        if (restante <= 0) elegir(m)
    }

    fun metas(m: Mar): Metas {
        val real = Metas(m.ola, m.periodo, m.viento, m.nubes / 100.0, m.lluvia, m.visibilidad, 0.0, m.rayos)
        val a = when (actual) {
            Animo.REAL -> return real
            Animo.CALMA -> Metas(2.2, 13.0, 9.0, 0.6, 0.0, 15.0, 0.0, false)
            Animo.TEMPORAL -> Metas(max(m.ola, 6.5), 11.0, 42.0, 0.95, 1.5, 4.0, 0.0, conRayos || m.rayos)
            Animo.NIEBLA -> Metas(1.8, 12.0, 12.0, 0.95, 0.0, 0.35, 0.0, false)
            Animo.ESPEJO -> Metas(0.7, 14.0, 2.0, 0.35, 0.0, 25.0, 0.0, false)
            Animo.HORA_AZUL -> Metas(2.5, 12.0, 15.0, 0.5, 0.0, 18.0, 1.0, false)
            Animo.CHUBASCO -> Metas(m.ola + 1.0, 10.0, 30.0, 1.0, 5.0, 3.0, 0.0, false)
        }
        // el ánimo manda, pero el mar real se sigue notando un poco
        fun mezcla(x: Double, y: Double) = x * 0.8 + y * 0.2
        return Metas(
            mezcla(a.ola, real.ola), mezcla(a.periodo, real.periodo), mezcla(a.viento, real.viento),
            mezcla(a.nubes, real.nubes), mezcla(a.lluvia, real.lluvia), mezcla(a.vis, real.vis), a.azul, a.rayos,
        )
    }
}

/** El estado del mar que se pinta: se acerca a las metas sin saltos (salvo cuando el ánimo cambia de golpe). */
private class EstadoMar {
    var ola = 3.0; var periodo = 11.0; var viento = 20.0; var nubes = 0.8
    var lluvia = 0.3; var vis = 10.0; var luz = 0.0; var alba = 0.0; var rayos = 0.0
    var azul = 0.0; var flash = 0.0

    fun avanzar(m: Mar, dt: Double) {
        Corriente.avanzar(m, dt)
        val meta = Corriente.metas(m)
        val hora = m.horaNumero()
        val albaMeta = when {
            hora in 4.5..7.5 -> 1.0 - kotlin.math.abs(hora - 6.0) / 1.5
            hora in 17.5..20.5 -> 1.0 - kotlin.math.abs(hora - 19.0) / 1.5
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val luzMeta = if (Corriente.actual == Animo.HORA_AZUL) 0.45 else if (m.esDia) 1.0 else 0.0
        val k = (dt * Corriente.rapidez).coerceAtMost(1.0)
        // las ráfagas: el viento nunca está quieto del todo
        val rafaga = (Random.nextDouble() - 0.5) * dt * 6.0
        ola += (meta.ola - ola) * k; periodo += (meta.periodo - periodo) * k
        viento += (meta.viento - viento) * k + rafaga
        nubes += (meta.nubes - nubes) * k; lluvia += (meta.lluvia - lluvia) * k; vis += (meta.vis - vis) * k
        azul += (meta.azul - azul) * k
        luz += (luzMeta - luz) * (dt * 0.25).coerceAtMost(1.0); alba += (albaMeta - alba) * (dt * 0.25).coerceAtMost(1.0)
        rayos += ((if (meta.rayos) 1.0 else 0.0) - rayos) * k
        flash *= exp(-dt * 7.0)
        if (rayos > 0.5 && Random.nextDouble() < dt * 0.09) flash = 0.8 + Random.nextDouble() * 0.2
    }

    @RequiresApi(33)
    fun aplicar(s: RuntimeShader, t: Float, w: Float, h: Float) {
        s.setFloatUniform("res", w, h)
        s.u("t", t)
        s.u("ola", ola.toFloat())
        s.u("periodo", periodo.coerceAtLeast(4.0).toFloat())
        s.u("pic", (viento / 45.0).coerceIn(0.0, 1.0).toFloat())
        s.u("nubes", nubes.coerceIn(0.0, 1.0).toFloat())
        s.u("vis", vis.coerceAtLeast(0.2).toFloat())
        s.u("luz", luz.toFloat())
        s.u("alba", alba.toFloat())
        s.u("lluvia", lluvia.coerceIn(0.0, 6.0).toFloat())
        s.u("flash", flash.toFloat())
        s.u("azul", azul.coerceIn(0.0, 1.0).toFloat())
    }
}

// ---------------- el motor: pinta el mar en su propio hilo ----------------

/** Un solo reloj y un solo estado para todo el mar: así no se reinicia al pasar de una pantalla a otra. */
private val origen = android.os.SystemClock.uptimeMillis()
private val estadoMar = EstadoMar()

/** El último cuadro pintado, para que una pantalla nueva nunca empiece en blanco. */
private var ultimoCuadro: ImageBitmap? = null

/**
 * Pinta el sombreador en una superficie pequeña de la tarjeta gráfica, en un hilo aparte:
 * la interfaz nunca espera al mar. Cada cuadro listo se entrega por [alCuadro].
 */
@RequiresApi(33)
private class MotorMar(val w: Int, val h: Int, private val sombra: RuntimeShader, private val alCuadro: (ImageBitmap) -> Unit) {
    private val hilo = android.os.HandlerThread("mar", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val manejo = android.os.Handler(hilo.looper)
    private val lector = ImageReader.newInstance(
        w, h, PixelFormat.RGBA_8888, 5,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    private val nodo = RenderNode("mar").apply { setPosition(0, 0, w, h) }
    private val pintor = HardwareRenderer().apply {
        setSurface(lector.surface)
        setContentRoot(nodo)
        isOpaque = true
    }
    private val pintura = android.graphics.Paint()
    // las dos imágenes más recientes siguen abiertas mientras la pantalla las muestra
    private val abiertas = ArrayDeque<ImagenCruda>()
    @Volatile var periodoMs = 33L
    private var corriendo = false
    private var anterior = 0L
    private var proximo = 0L

    init {
        lector.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            val buf = img.hardwareBuffer
            if (buf == null) { img.close(); return@setOnImageAvailableListener }
            val bmp = Bitmap.wrapHardwareBuffer(buf, ColorSpace.get(ColorSpace.Named.SRGB))
            buf.close()
            abiertas.addLast(img)
            while (abiertas.size > 2) abiertas.removeFirst().close()
            if (bmp != null) alCuadro(bmp.asImageBitmap())
        }, manejo)
    }

    private val paso = object : Runnable {
        override fun run() {
            if (!corriendo) return
            val ahora = android.os.SystemClock.uptimeMillis()
            val dt = ((ahora - anterior) / 1000.0).coerceIn(0.0, 0.1)
            anterior = ahora
            estadoMar.avanzar(Clima.mar, dt)
            estadoMar.aplicar(sombra, (ahora - origen) / 1000f + 20f, w.toFloat(), h.toFloat())
            val c = nodo.beginRecording()
            pintura.shader = sombra
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), pintura)
            nodo.endRecording()
            pintor.createRenderRequest().syncAndDraw()
            // ritmo exacto: el próximo cuadro se agenda contra el reloj, no contra cuánto tardó este
            proximo += periodoMs
            if (proximo <= ahora) proximo = ahora + periodoMs
            manejo.postAtTime(this, proximo)
        }
    }

    fun arrancar() = manejo.post {
        if (!corriendo) {
            corriendo = true
            anterior = android.os.SystemClock.uptimeMillis()
            proximo = anterior
            manejo.post(paso)
        }
    }

    /** Se queda quieto: la última imagen sigue en pantalla y la tarjeta gráfica descansa. */
    fun parar() = manejo.post { corriendo = false; manejo.removeCallbacks(paso) }

    fun soltar() {
        manejo.post {
            corriendo = false
            manejo.removeCallbacksAndMessages(null)
            pintor.destroy()
            lector.setOnImageAvailableListener(null, null)
            lector.close()
            hilo.quitSafely()
        }
    }
}

/** El grano del papel: una tesela de ruido que se repite y salta de lugar unas 12 veces por segundo. */
private fun teselaDeGrano(): ImageBitmap {
    val n = 256
    val px = IntArray(n * n)
    val r = Random(7)
    for (i in px.indices) {
        val g = (r.nextFloat() + r.nextFloat() - 1f) // -1..1, más denso en el centro
        val a = (kotlin.math.abs(g) * 30f).toInt().coerceIn(0, 255)
        px[i] = if (g > 0) (a shl 24) or 0xEDE3CF else (a shl 24) or 0x02070F
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** ¿Se está calentando el celular? */
private fun caliente(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
}

// ---------------- el mar ----------------

/**
 * El mar vivo. Con [vivo] en falso se queda quieto en el último cuadro (por ejemplo, mientras se escribe encima)
 * y la tarjeta gráfica descansa.
 */
@Composable
fun MarCianotipo(modifier: Modifier, vivo: Boolean = true) {
    if (Build.VERSION.SDK_INT >= 33) MarVivo(modifier, vivo)
    else Image(painterResource(R.drawable.mar_quieto), null, modifier, contentScale = ContentScale.Crop)
}

@RequiresApi(33)
@Composable
private fun MarVivo(modifier: Modifier, vivo: Boolean) {
    val ctx = LocalContext.current
    val sombra = remember { try { RuntimeShader(leerSombra(ctx, R.raw.sombra_mar)) } catch (e: Exception) { null } }
    if (sombra == null) {
        Image(painterResource(R.drawable.mar_quieto), null, modifier, contentScale = ContentScale.Crop)
        return
    }
    val grano = remember { ShaderBrush(ImageShader(teselaDeGrano(), TileMode.Repeated, TileMode.Repeated)) }
    var tam by remember { mutableStateOf(IntSize.Zero) }
    var cuadro by remember { mutableStateOf(ultimoCuadro) }
    var granoDesfase by remember { mutableStateOf(Offset.Zero) }

    val motor = remember(tam) {
        if (tam.width == 0 || tam.height == 0) null
        else MotorMar(max(1, tam.width / 3), max(1, tam.height / 3), sombra) { c -> cuadro = c; ultimoCuadro = c }
    }
    DisposableEffect(motor) { onDispose { motor?.soltar() } }
    LaunchedEffect(motor, vivo) {
        if (motor == null) return@LaunchedEffect
        if (!vivo) { motor.parar(); return@LaunchedEffect }
        motor.arrancar()
        var vueltas = 0
        while (isActive) {
            // el grano salta de lugar 12 veces por segundo; cada cinco segundos se mira la temperatura
            granoDesfase = Offset(Random.nextFloat() * 256f, Random.nextFloat() * 256f)
            if (vueltas++ % 60 == 0) motor.periodoMs = if (caliente(ctx)) 50L else 33L
            kotlinx.coroutines.delay(83)
        }
    }

    Box(modifier.onSizeChanged { tam = it }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = cuadro
            if (c != null) {
                drawImage(
                    c, IntOffset.Zero, IntSize(c.width, c.height), IntOffset.Zero,
                    IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low,
                )
            } else {
                drawRect(Color(0xFF061A2B))
            }
            translate(-granoDesfase.x, -granoDesfase.y) {
                drawRect(grano, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width + 256f, size.height + 256f))
            }
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0x99000000),
                    center = Offset(size.width / 2f, size.height / 2f), radius = max(size.width, size.height) * 0.62f,
                )
            )
        }
    }
}
