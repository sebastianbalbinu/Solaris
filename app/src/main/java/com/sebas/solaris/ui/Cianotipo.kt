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

/** La altura de ola como aparece en el nombre de un estado: 1.5 → "1.5", 3.0 → "3". */
fun etiquetaOla(h: Double): String = if (h % 1.0 == 0.0) h.toInt().toString() else h.toString()

/** La luz que hay en el Drake: día, crepúsculo (alba u ocaso) o noche. */
fun luzReal(m: Mar, ahora: Long = System.currentTimeMillis()): String {
    val h = m.horaNumero(ahora)
    return when {
        abs(h - m.salida) < 0.6 -> "crep"
        h > m.puesta - 0.5 && h < m.puesta + 0.7 -> "crep"
        !m.esDia -> "noche"
        else -> "dia"
    }
}

/**
 * El estado que dicen los datos reales del Drake: su luz y la altura de ola más cercana de la escalera
 * (1, 1,5, 2, 3, 4, 5, 6, 8 y 10 m). Nombres como "dia_3m" o "noche_1.5m".
 */
fun estadoReal(m: Mar, ahora: Long = System.currentTimeMillis()): String {
    val ola = Paquete.escalera.minByOrNull { abs(it - m.ola) } ?: 3.0
    return luzReal(m, ahora) + "_" + etiquetaOla(ola) + "m"
}

/**
 * La corriente: el mar es el del Drake y nada más. No lo mueve la escritura, ni la respiración, ni la voz.
 * Va hacia el estado real, pero un paso a la vez y quedándose un buen rato en cada uno, como el mar de verdad.
 */
object Corriente {
    /** Hacia dónde va el mar (el estado real del Drake). */
    var objetivo by mutableStateOf("dia_3m")
        private set
    /** Lo que se está viendo (lo pone el reproductor cuando un bucle empieza). */
    var visible by mutableStateOf("dia_3m")
    private var iniciada = false

    /** Nombre del mar que se ve (el del Drake). */
    val nombreVisible: String get() = Clima.mar.nombre

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
        if (Paquete.entradaMs > 0) jugador.addMediaItem(item("entrada:$b", bb, Paquete.entradaMs))
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
            // que en el celular esté el mar de ahora y su vecindad
            Paquete.descargar(Corriente.visible, Corriente.objetivo)
            delay(60_000)
        }
    }
    // cuando llega un archivo nuevo del paquete, el mar puede usarlo
    LaunchedEffect(Paquete.llegados, listo) { if (listo) mareas.seguir() }

    // el barco: cabecea y se balancea con las olas que tiene debajo (o la cámara quieta, si se prefiere)
    LaunchedEffect(vivo, Balanceo.quieta, lienzo) {
        val tv = lienzo ?: return@LaunchedEffect
        if (!vivo) return@LaunchedEffect
        if (Balanceo.quieta) { MarVivo.mover(tv, mareas.jugador, 0f, 0f, 1f); return@LaunchedEffect }
        while (isActive) {
            withFrameNanos {
                val id = mareas.jugador.currentMediaItem?.mediaId ?: ""
                val video = when {
                    id.startsWith("bucle:") -> id.substringAfter(':')
                    id.startsWith("trans:") -> id.substringAfter(':').replace(">", "__")
                    else -> null
                }
                val (cab, bal) = video?.let { Balanceo.en(it, mareas.jugador.currentPosition) } ?: (0f to 0f)
                MarVivo.mover(tv, mareas.jugador, cab, bal, Balanceo.ZOOM)
            }
        }
    }

    // el grano de la película salta 12 veces por segundo
    LaunchedEffect(vivo) {
        while (isActive && vivo) {
            granoDesfase = Offset(Random.nextFloat() * 256f, Random.nextFloat() * 256f)
            delay(83)
        }
    }

    // si todavía se ve un mar de día y allá ya es de noche (mientras el mar llega), se oscurece
    val noche = if (!Clima.mar.esDia && !Corriente.visible.startsWith("noche") && !Corriente.visible.startsWith("crep")) 0.4f else 0f

    Box(modifier) {
        // el cuadro quieto, con el mismo acercamiento que tendrá el video (así no salta al empezar)
        val acerca = if (Balanceo.quieta) 1f else Balanceo.ZOOM
        androidx.compose.animation.Crossfade(quieto, animationSpec = tween(600), label = "quieto") { q ->
            if (q != null) Image(q, null, Modifier.fillMaxSize().graphicsLayer { scaleX = acerca; scaleY = acerca }, contentScale = ContentScale.Crop)
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
    tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> MarVivo.recolocar(tv, jugador) }
    jugador.addListener(object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) = MarVivo.recolocar(tv, jugador)
    })
    jugador.setVideoTextureView(tv)
    // el mar va a 60 cuadros: en pantallas de 90 o 120 Hz se le pide ese ritmo exacto para que no tiemble
    if (android.os.Build.VERSION.SDK_INT >= 30) {
        jugador.setVideoChangeFrameRateStrategy(androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
    }
}

/**
 * Cómo se mueve el barco: las curvas de cabeceo y balanceo de cada video (horneadas con la física de las olas
 * que hay bajo un casco de ~70 m), leídas al ritmo del video. Con [quieta], la cámara no se mueve.
 */
object Balanceo {
    /** Un poco de acercamiento para que al inclinarse no se vean los bordes. */
    const val ZOOM = 1.07f
    private const val MAX_BAL = 1.6f
    private const val MAX_CAB = 1.8f
    private lateinit var prefs: android.content.SharedPreferences
    var quieta by mutableStateOf(false)
        private set
    private val curvas = HashMap<String, FloatArray?>()

    fun iniciar(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("mar", android.content.Context.MODE_PRIVATE)
        quieta = prefs.getBoolean("camara_quieta", false)
    }

    fun fijarQuieta(q: Boolean) { quieta = q; prefs.edit().putBoolean("camara_quieta", q).apply() }

    private fun curva(video: String): FloatArray? = curvas.getOrPut(video) {
        val f = Paquete.movimiento(video) ?: return null
        try {
            val a = org.json.JSONObject(f.readText()).getJSONArray("mov")
            FloatArray(a.length() * 2) { i -> a.getJSONArray(i / 2).getDouble(i % 2).toFloat() }
        } catch (e: Exception) { null }
    }

    /** Cabeceo y balanceo (grados) en este momento del video. */
    fun en(video: String, posMs: Long): Pair<Float, Float>? {
        val c = curva(video) ?: return null
        val n = c.size / 2
        if (n < 2) return null
        val x = (posMs / 1000f * 30f).coerceAtLeast(0f)
        val i = x.toInt().coerceAtMost(n - 1)
        val j = (i + 1).coerceAtMost(n - 1)
        val f = x - i
        val cab = c[i * 2] + (c[j * 2] - c[i * 2]) * f
        val bal = c[i * 2 + 1] + (c[j * 2 + 1] - c[i * 2 + 1]) * f
        return cab.coerceIn(-MAX_CAB, MAX_CAB) to bal.coerceIn(-MAX_BAL, MAX_BAL)
    }
}

/** El mar que se está viendo, para poder fotografiarlo (al compartir una lámina). */
object MarVivo {
    var lienzo: TextureView? = null
    private var cab = 0f
    private var bal = 0f
    private var zoom = 1f
    private val matriz = Matrix()

    /** Recorta el video al centro para llenar la pantalla, con la inclinación del barco encima. */
    fun recolocar(tv: TextureView, jugador: ExoPlayer) = mover(tv, jugador, cab, bal, zoom)

    fun mover(tv: TextureView, jugador: ExoPlayer, cabeceo: Float, balanceo: Float, acerca: Float) {
        cab = cabeceo; bal = balanceo; zoom = acerca
        val vs = jugador.videoSize
        val w = tv.width.toFloat(); val h = tv.height.toFloat()
        if (vs.width == 0 || vs.height == 0 || w == 0f || h == 0f) return
        val escala = max(w / vs.width, h / vs.height)
        matriz.reset()
        matriz.setScale(vs.width * escala / w * acerca, vs.height * escala / h * acerca, w / 2f, h / 2f)
        // de proa arriba, el horizonte baja; el campo vertical del cuadro es de 78°
        matriz.postTranslate(0f, cabeceo * h * acerca / 78f)
        matriz.postRotate(-balanceo, w / 2f, h / 2f)
        tv.setTransform(matriz)
    }
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
