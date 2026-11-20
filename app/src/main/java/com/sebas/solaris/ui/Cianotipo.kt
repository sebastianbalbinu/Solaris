package com.sebas.solaris.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.sebas.solaris.Clima
import com.sebas.solaris.Mar
import com.sebas.solaris.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/*
 * El mar del Drake como un cianotipo vivo, horneado: cada ánimo es un video en bucle sin costura
 * (renderizado de antemano con herramientas/sombra_mar.agsl). El celular solo lo reproduce con su
 * decodificador de video, así que va fluido y casi no gasta. Entre ánimos, un fundido.
 * Encima, en alta resolución: el grano del papel, la viñeta, la noche y los relámpagos.
 */

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

/**
 * Sobre el mar real corre un ánimo que cambia solo, a veces despacio y a veces de golpe,
 * como cambia uno. Vive mientras la app está abierta y sigue igual entre pantallas.
 */
object Corriente {
    var actual by mutableStateOf(Animo.REAL)
        private set
    /** Cuánto dura el fundido hacia el ánimo nuevo. */
    var fundidoMs = 6000
        private set
    /** Si este temporal trae relámpagos (se decide una vez, al empezar el ánimo). */
    var conRayos = false
        private set
    private var restante = 0.0
    private var empezada = false

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
        fundidoMs = if (Random.nextDouble() < 0.25) 1200 + Random.nextInt(800) else 6000 + Random.nextInt(6000)
        restante = 45.0 + Random.nextDouble() * 200.0
        conRayos = Random.nextDouble() < 0.6
    }

    fun avanzar(m: Mar, dt: Double) {
        if (!empezada) {
            empezada = true
            elegir(m)
        }
        restante -= dt
        if (restante <= 0) elegir(m)
    }

    /** El clip que corresponde a lo que se ve ahora. */
    fun clip(m: Mar): Int {
        val a = if (actual != Animo.REAL) actual else when {
            m.lluvia > 2.0 -> Animo.CHUBASCO
            m.viento >= 30 || m.ola >= 5 -> Animo.TEMPORAL
            m.visibilidad < 2.0 -> Animo.NIEBLA
            else -> Animo.CALMA
        }
        return when (a) {
            Animo.TEMPORAL -> R.raw.mar_temporal
            Animo.CHUBASCO -> R.raw.mar_chubasco
            Animo.NIEBLA -> R.raw.mar_niebla
            Animo.HORA_AZUL -> R.raw.mar_hora_azul
            Animo.ESPEJO -> if (m.esDia) R.raw.mar_espejo else R.raw.mar_noche
            else -> if (m.esDia) R.raw.mar_calma else R.raw.mar_noche
        }
    }

    /** ¿Caen relámpagos en lo que se ve? */
    fun relampaguea(m: Mar): Boolean =
        (actual == Animo.TEMPORAL && conRayos) || (actual == Animo.REAL && m.rayos)
}

// ---------------- el mar ----------------

/** Una capa de video. Se apilan durante un fundido; al terminar, queda una sola. */
private class Capa(val n: Int, val clip: Int, val alfa: Animatable<Float, *>)

private var contadorCapas = 0

/**
 * El mar vivo. Con [vivo] en falso se queda quieto en el cuadro actual (por ejemplo, mientras se escribe encima)
 * y el decodificador descansa.
 */
@Composable
fun MarCianotipo(modifier: Modifier, vivo: Boolean = true) {
    val ctx = LocalContext.current
    val grano = remember { ShaderBrush(ImageShader(teselaDeGrano(), TileMode.Repeated, TileMode.Repeated)) }
    var granoDesfase by remember { mutableStateOf(Offset.Zero) }
    val capas = remember { mutableStateListOf<Capa>() }
    var destello by remember { mutableStateOf(0f) }

    // la corriente avanza una vez por segundo
    LaunchedEffect(Unit) {
        while (isActive) { Corriente.avanzar(Clima.mar, 1.0); delay(1000) }
    }
    val mar = Clima.mar
    val clip = Corriente.clip(mar)

    // cada vez que cambia el clip, el nuevo aparece encima con un fundido y el viejo se va
    LaunchedEffect(clip) {
        val primera = capas.isEmpty()
        val nueva = Capa(contadorCapas++, clip, Animatable(if (primera) 1f else 0f))
        capas.add(nueva)
        if (!primera) nueva.alfa.animateTo(1f, tween(Corriente.fundidoMs.coerceAtLeast(300), easing = LinearEasing))
        capas.removeAll { it !== nueva }
    }

    // el grano salta de lugar 12 veces por segundo; los relámpagos, de vez en cuando
    LaunchedEffect(vivo) {
        while (isActive && vivo) {
            granoDesfase = Offset(Random.nextFloat() * 256f, Random.nextFloat() * 256f)
            destello *= exp(-0.083f * 7f)
            if (destello < 0.01f) destello = 0f
            if (Corriente.relampaguea(Clima.mar) && Random.nextFloat() < 0.083f * 0.09f) destello = 0.8f + Random.nextFloat() * 0.2f
            delay(83)
        }
    }

    // la noche hunde cualquier ánimo; la hora azul y la noche ya vienen oscuras de fábrica
    val noche = if (!mar.esDia && clip != R.raw.mar_noche && clip != R.raw.mar_hora_azul) 0.38f else 0f

    Box(modifier) {
        // mientras llega el primer cuadro
        Image(painterResource(R.drawable.mar_quieto), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        for (c in capas) {
            key(c.n) {
                CapaVideo(ctx, c.clip, vivo, Modifier.fillMaxSize().graphicsLayer { alpha = c.alfa.value })
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (noche > 0f) drawRect(Color.Black.copy(alpha = noche))
            if (destello > 0f) drawRect(Color(0xFFDCE6EA).copy(alpha = destello * 0.22f))
            translate(-granoDesfase.x, -granoDesfase.y) {
                drawRect(grano, topLeft = Offset.Zero, size = Size(size.width + 256f, size.height + 256f))
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

/** Un clip del mar en bucle, recortado al centro para llenar la pantalla. Sin sonido. */
@Composable
private fun CapaVideo(ctx: android.content.Context, clip: Int, vivo: Boolean, modifier: Modifier) {
    val jugador = remember(clip) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${ctx.packageName}/$clip")))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(jugador) { onDispose { jugador.release() } }
    LaunchedEffect(jugador, vivo) { jugador.playWhenReady = vivo }

    AndroidView(
        factory = { c ->
            TextureView(c).also { tv ->
                fun recortar() {
                    val vs = jugador.videoSize
                    val w = tv.width.toFloat(); val h = tv.height.toFloat()
                    if (vs.width == 0 || vs.height == 0 || w == 0f || h == 0f) return
                    val escala = max(w / vs.width, h / vs.height)
                    val m = Matrix()
                    m.setScale(vs.width * escala / w, vs.height * escala / h, w / 2f, h / 2f)
                    tv.setTransform(m)
                }
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recortar() }
                jugador.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) = recortar()
                })
                jugador.setVideoTextureView(tv)
            }
        },
        modifier = modifier,
    )
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
