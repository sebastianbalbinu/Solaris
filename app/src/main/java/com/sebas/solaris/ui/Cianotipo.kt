package com.sebas.solaris.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.sebas.solaris.Clima
import com.sebas.solaris.Mar
import com.sebas.solaris.Paquete
import com.sebas.solaris.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * El mar del Drake, horneado: cada estado es un bucle de video y entre vecinos hay transiciones de verdad
 * (herramientas/hornear.py). El reproductor encadena bucle → transición → bucle sin cortes, siguiendo el
 * camino más corto por el mapa de vecinos (de espejo a temporal se pasa por calma y mar bravo, como en la realidad).
 * Qué estado toca lo deciden solo los datos reales del Drake. Encima del video: grano, viñeta y,
 * cuando allá hay tormenta eléctrica, relámpagos.
 */

// ---------------- los estados ----------------

/** Cómo se llama cada estado en pantalla. */
val NombresEstado = mapOf(
    "calma" to "CALMA SOLEMNE", "espejo" to "ESPEJO", "mar_de_fondo" to "MAR DE FONDO", "mar_bravo" to "MAR BRAVO",
    "temporal" to "TEMPORAL", "chubasco" to "CHUBASCO", "niebla" to "NIEBLA", "nevada" to "NEVADA",
    "hora_azul" to "HORA AZUL", "alba" to "ALBA", "ocaso" to "OCASO", "noche" to "NOCHE CERRADA",
    "luna_llena" to "LUNA LLENA", "aurora" to "AURORA AUSTRAL", "temporal_noche" to "TEMPORAL NOCTURNO",
)
private val Nocturnos = setOf("noche", "luna_llena", "aurora", "temporal_noche")

/**
 * El estado que dicen los datos reales del Drake. Cada estado horneado tiene su altura de ola
 * (espejo 0,7 m, calma 2,4 m, mar de fondo 6 m largo y liso, mar bravo 7 m picado, temporal 10 m):
 * se elige el más parecido a lo que de verdad hay, no el más tranquilo.
 */
fun estadoReal(m: Mar, ahora: Long = System.currentTimeMillis()): String {
    val h = m.horaNumero(ahora)
    val tormenta = m.viento >= 34 || m.ola >= 7.5
    val bravo = m.viento >= 22 || m.ola >= 4.5
    val gruesa = m.ola >= 3.0 || m.viento >= 16
    return when {
        tormenta && !m.esDia -> "temporal_noche"
        tormenta -> "temporal"
        m.nieva -> if (m.esDia) "nevada" else "noche"
        m.lluvia > 2.0 && m.esDia -> "chubasco"
        bravo && !m.esDia -> "temporal_noche"
        bravo -> "mar_bravo"
        m.visibilidad < 1.5 && m.esDia -> "niebla"
        // con mar gruesa el cielo de la hora no tapa el oleaje: se ve el mar como está
        gruesa && m.esDia -> "mar_de_fondo"
        abs(h - m.salida) < 0.75 -> "alba"
        h > m.puesta - 0.4 && h < m.puesta + 0.5 -> "ocaso"
        h >= m.puesta + 0.5 && h < m.puesta + 1.2 -> "hora_azul"
        !m.esDia && m.kp >= 5 && m.nubes < 60 -> "aurora"
        !m.esDia && m.luna(ahora) > 0.75 && m.nubes < 75 -> "luna_llena"
        !m.esDia -> "noche"
        m.viento < 6 && m.ola < 1.3 -> "espejo"
        else -> "calma"
    }
}

/**
 * La corriente: el mar es el del Drake y nada más. No lo mueve la escritura, ni la respiración, ni la voz.
 * Va hacia el estado real, pero un paso a la vez y quedándose un buen rato en cada uno, como el mar de verdad.
 */
object Corriente {
    /** Hacia dónde va el mar (el estado real del Drake). */
    var objetivo by mutableStateOf("calma")
        private set
    /** Lo que se está viendo (lo pone el reproductor cuando un bucle empieza). */
    var visible by mutableStateOf("calma")
    private var iniciada = false

    /** Nombre del mar que se ve. */
    val nombreVisible: String get() = NombresEstado[visible] ?: Clima.mar.nombre

    /** Al abrir: el mar ya está en el estado que toca (sin pasar antes por otro). */
    fun iniciar(m: Mar) {
        if (iniciada) return
        iniciada = true
        objetivo = estadoReal(m)
        visible = objetivo
    }

    fun avanzar(m: Mar) {
        iniciar(m)
        objetivo = estadoReal(m)
    }
}

/** Si se está respirando (la pantalla de respirar); el mar no cambia por eso. */
object Oleaje {
    var respirando by mutableStateOf(false)
}

/** Cuánto se queda el mar en un estado antes de dar el siguiente paso hacia el real. */
private const val PERMANENCIA_MS = 20 * 60 * 1000L

// ---------------- el reproductor ----------------

/** Encadena bucles y transiciones en una sola lista, sin cortes. */
private class Mareas(private val ctx: android.content.Context) {
    val jugador: ExoPlayer = ExoPlayer.Builder(ctx).build().apply {
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
    }
    var actual: String? = null
        private set
    private var enCamino = false
    /** Cuándo empezó el estado que se ve (para quedarse en él un buen rato). */
    private var llegada = 0L

    init {
        jugador.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, razon: Int) {
                val id = item?.mediaId ?: return
                if (id.startsWith("entrada:")) {
                    // ya pasó la transición: lo que se ve es el estado nuevo
                    actual = id.substringAfter(':')
                    Corriente.visible = actual!!
                }
                if (id.startsWith("bucle:")) {
                    actual = id.substringAfter(':')
                    Corriente.visible = actual!!
                    jugador.repeatMode = Player.REPEAT_MODE_ONE
                    val i = jugador.currentMediaItemIndex
                    if (i > 0) jugador.removeMediaItems(0, i)
                    enCamino = false
                    llegada = System.currentTimeMillis()
                }
            }
        })
    }

    private fun item(id: String, f: File, desdeMs: Long = 0L): MediaItem {
        val b = MediaItem.Builder().setMediaId(id).setUri(Uri.fromFile(f))
        if (desdeMs > 0) b.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(desdeMs).build())
        return b.build()
    }

    /** Lo que suena sin paquete: el bucle de calma que viene con la app. */
    private fun respaldo() = MediaItem.Builder().setMediaId("respaldo")
        .setUri(Uri.parse("android.resource://${ctx.packageName}/${R.raw.mar_calma}")).build()

    fun empezar(estado: String) {
        val f = Paquete.bucle(estado)
        if (f != null) {
            jugador.setMediaItem(item("bucle:$estado", f))
            actual = estado
            Corriente.visible = estado
            llegada = System.currentTimeMillis()
        } else {
            jugador.setMediaItem(respaldo())
            actual = null
        }
        jugador.repeatMode = Player.REPEAT_MODE_ONE
        jugador.prepare()
    }

    /** Si el mar tiene que ir a otro estado, encola el siguiente paso: termina el bucle, transición, el nuevo. */
    fun seguir() {
        if (enCamino) return
        val a = actual
        val destino = Corriente.objetivo
        if (a == null) {
            // llegó el paquete mientras sonaba el respaldo
            Paquete.bucle(destino)?.let { empezar(destino) }
            return
        }
        if (a == destino) return
        // el mar no cambia de un momento a otro: se queda en cada estado antes de moverse
        if (System.currentTimeMillis() - llegada < PERMANENCIA_MS) return
        val ruta = Paquete.camino(a, destino) ?: return
        val b = ruta.getOrNull(1) ?: return
        val tr = Paquete.transicion(a, b) ?: return
        val bb = Paquete.bucle(b) ?: return
        jugador.addMediaItem(item("trans:$a>$b", tr))
        jugador.addMediaItem(item("entrada:$b", bb, Paquete.entradaMs))
        jugador.addMediaItem(item("bucle:$b", bb))
        // el bucle actual termina su vuelta y la lista sigue sola
        jugador.repeatMode = Player.REPEAT_MODE_OFF
        enCamino = true
    }

    fun soltar() = jugador.release()
}

// ---------------- el mar ----------------

/**
 * El mar vivo. Con [vivo] en falso se queda quieto en el cuadro actual (mientras se escribe encima)
 * y el decodificador descansa.
 */
@Composable
fun MarCianotipo(modifier: Modifier, vivo: Boolean = true) {
    val ctx = LocalContext.current
    val grano = remember { ShaderBrush(ImageShader(teselaDeGrano(), TileMode.Repeated, TileMode.Repeated)) }
    var granoDesfase by remember { mutableStateOf(Offset.Zero) }

    val mareas = remember { Mareas(ctx) }
    var listo by remember { mutableStateOf(false) }
    var primerCuadro by remember { mutableStateOf(false) }
    val aparece by animateFloatAsState(if (primerCuadro) 1f else 0f, tween(700), label = "aparece")
    // mientras el video arranca se ve su primer cuadro exacto (el mismo con que empieza el bucle): no hay salto
    val ultimo = remember { File(ctx.filesDir, "mar/ultimo.jpg") }
    var quieto by remember { mutableStateOf(leerUltimo(ultimo)) }
    var lienzo by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(Unit) {
        mareas.jugador.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { primerCuadro = true }
        })
        onDispose { mareas.soltar() }
    }
    // al irse, se guarda el cuadro que se estaba viendo (solo sirve si todavía no hay paquete)
    val ciclo = LocalLifecycleOwner.current
    DisposableEffect(ciclo) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) lienzo?.let { tv ->
                try {
                    if (primerCuadro && tv.width > 0) tv.getBitmap(tv.width / 3, tv.height / 3)?.let { b ->
                        ultimo.parentFile?.mkdirs()
                        ultimo.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    }
                } catch (e: Exception) {}
            }
        }
        ciclo.lifecycle.addObserver(o)
        onDispose { ciclo.lifecycle.removeObserver(o) }
    }
    // El mar arranca como está el Drake: el vigía lo dejó apuntado hace menos de media hora.
    // Solo si esa lectura es vieja se espera un instante a la de ahora.
    LaunchedEffect(Unit) {
        if (!Clima.fresca) {
            val pedido = CoroutineScope(Dispatchers.IO).async { Clima.actualizar() }
            withTimeoutOrNull(1800) { pedido.await() }
        }
        Corriente.iniciar(Clima.mar)
        val estado = Corriente.objetivo
        withContext(Dispatchers.IO) { primerCuadroDe(estado) }?.let { quieto = it }
        mareas.empezar(estado)
        listo = true
    }
    LaunchedEffect(vivo, listo) { mareas.jugador.playWhenReady = vivo && listo }

    // una vez por minuto se mira el Drake; el mar va hacia él sin prisa
    LaunchedEffect(listo) {
        while (isActive && listo) {
            Corriente.avanzar(Clima.mar)
            mareas.seguir()
            delay(60_000)
        }
    }
    // cuando llega un archivo nuevo del paquete, el mar puede usarlo
    LaunchedEffect(Paquete.estados, listo) { if (listo) mareas.seguir() }

    // el grano de la película salta 12 veces por segundo
    LaunchedEffect(vivo) {
        while (isActive && vivo) {
            granoDesfase = Offset(Random.nextFloat() * 256f, Random.nextFloat() * 256f)
            delay(83)
        }
    }

    val noche = if (!Clima.mar.esDia && Corriente.visible !in Nocturnos) 0.4f else 0f

    Box(modifier) {
        androidx.compose.animation.Crossfade(quieto, animationSpec = tween(600), label = "quieto") { q ->
            if (q != null) Image(q, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Image(painterResource(R.drawable.mar_quieto), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        AndroidView(
            factory = { c -> TextureView(c).also { tv -> lienzo = tv; MarVivo.lienzo = tv; conectar(tv, mareas.jugador) } },
            // el video entra fundiéndose sobre su propio primer cuadro
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = aparece },
        )
        Tormenta(vivo)
        Canvas(Modifier.fillMaxSize()) {
            if (noche > 0f) drawRect(Color.Black.copy(alpha = noche))
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

/** El primer cuadro del bucle de un estado: lo que el video va a mostrar al arrancar. */
private fun primerCuadroDe(estado: String): ImageBitmap? {
    val f = Paquete.bucle(estado) ?: return null
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(f.path)
        r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
    } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) {} }
}

private fun leerUltimo(f: File): ImageBitmap? =
    try { if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null } catch (e: Exception) { null }

/** Une el reproductor a la superficie y recorta el video al centro para llenar la pantalla. */
private fun conectar(tv: TextureView, jugador: ExoPlayer) {
    fun recortar() {
        val vs = jugador.videoSize
        val w = tv.width.toFloat(); val h = tv.height.toFloat()
        if (vs.width == 0 || vs.height == 0 || w == 0f || h == 0f) return
        val escala = max(w / vs.width, h / vs.height)
        tv.setTransform(Matrix().apply { setScale(vs.width * escala / w, vs.height * escala / h, w / 2f, h / 2f) })
    }
    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> recortar() }
    jugador.addListener(object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = recortar()
    })
    jugador.setVideoTextureView(tv)
    // el mar va a 60 cuadros: en pantallas de 90 o 120 Hz se le pide ese ritmo exacto para que no tiemble
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        jugador.setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
    }
}

/** El mar que se está viendo, para poder fotografiarlo (al compartir una lámina). */
object MarVivo {
    var lienzo: TextureView? = null
    fun cuadro(): Bitmap? = try { lienzo?.takeIf { it.width > 0 }?.bitmap } catch (e: Exception) { null }
    /** Una foto muy chica del mar, para el vidrio (que de todas formas la desenfoca). */
    fun cuadroChico(): ImageBitmap? = try {
        lienzo?.takeIf { it.width > 0 && it.isAvailable }?.let { it.getBitmap(it.width / 6, it.height / 6) }?.asImageBitmap()
    } catch (e: Exception) { null }
}

/** El grano del papel: una tesela de ruido que se repite y salta de lugar unas 12 veces por segundo. */
private fun teselaDeGrano(): ImageBitmap {
    val n = 256
    val px = IntArray(n * n)
    val r = Random(7)
    for (i in px.indices) {
        val g = (r.nextFloat() + r.nextFloat() - 1f) // -1..1, más denso en el centro
        val a = (kotlin.math.abs(g) * 20f).toInt().coerceIn(0, 255)
        px[i] = if (g > 0) (a shl 24) or 0xEDE3CF else (a shl 24) or 0x02070F
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}
