package com.sebas.solaris.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import android.view.TextureView
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.sebas.solaris.Clima
import com.sebas.solaris.Grabadora
import com.sebas.solaris.Mar
import com.sebas.solaris.Paquete
import com.sebas.solaris.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * Qué estado toca lo deciden los datos reales del Drake; encima corre un ánimo que deriva hacia estados vecinos.
 * Encima del video, en alta: grano, viñeta, relámpagos, las visitas (témpano, albatros, barco), la respiración
 * y el temblor de la voz.
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
/** Donde el horizonte se ve limpio y pueden aparecer visitas. */
private val Despejados = setOf("calma", "espejo", "mar_de_fondo", "alba", "ocaso", "hora_azul", "noche", "luna_llena", "aurora")

/** El estado que dicen los datos reales del Drake, sin ánimo encima. */
fun estadoReal(m: Mar, ahora: Long = System.currentTimeMillis()): String {
    val h = m.horaNumero(ahora)
    val tormenta = m.viento >= 34 || m.ola >= 6
    val bravo = m.viento >= 22 || m.ola >= 4
    return when {
        tormenta && !m.esDia -> "temporal_noche"
        tormenta -> "temporal"
        m.nieva -> if (m.esDia) "nevada" else "noche"
        m.lluvia > 2.0 && m.esDia -> "chubasco"
        bravo && !m.esDia -> "temporal_noche"
        bravo -> "mar_bravo"
        abs(h - m.salida) < 0.75 -> "alba"
        h > m.puesta - 0.4 && h < m.puesta + 0.5 -> "ocaso"
        h >= m.puesta + 0.5 && h < m.puesta + 1.2 -> "hora_azul"
        !m.esDia && m.kp >= 5 && m.nubes < 60 -> "aurora"
        !m.esDia && m.luna(ahora) > 0.75 && m.nubes < 75 -> "luna_llena"
        !m.esDia -> "noche"
        m.visibilidad < 2.0 -> "niebla"
        m.periodo >= 14 && m.viento < 12 -> "mar_de_fondo"
        m.viento < 5 && m.ola < 1.2 -> "espejo"
        else -> "calma"
    }
}

/**
 * La corriente interior: sobre el estado real deriva un ánimo que se va, a ratos, a un estado vecino
 * (nunca a uno imposible: de noche no hay alba; con temporal real no hay espejo) y luego vuelve.
 */
object Corriente {
    /** Hacia dónde quiere ir el mar ahora. */
    var objetivo by mutableStateOf("calma")
        private set
    /** Lo que se está viendo (lo pone el reproductor cuando un bucle empieza). */
    var visible by mutableStateOf("calma")
    private var deriva: String? = null
    private var restante = 0.0
    private var iniciada = false

    /** Nombre del mar que se ve. */
    val nombreVisible: String get() = NombresEstado[visible] ?: Clima.mar.nombre

    /** Al abrir: el mar ya está en el estado que toca (sin pasar antes por otro). */
    fun iniciar(m: Mar) {
        if (iniciada) return
        iniciada = true
        objetivo = estadoReal(m)
        visible = objetivo
        restante = 60.0 + Random.nextDouble() * 120.0
    }

    fun avanzar(m: Mar, dt: Double) {
        iniciar(m)
        val real = estadoReal(m)
        restante -= dt
        if (restante <= 0) {
            restante = 45.0 + Random.nextDouble() * 200.0
            deriva = if (deriva != null || Random.nextDouble() < 0.4) null else {
                // un vecino del estado real que tenga sentido a esta hora
                val noche = !m.esDia
                Paquete.vecinos(real).filter { v ->
                    (v in Nocturnos) == noche && v !in setOf("alba", "ocaso", "hora_azul", "aurora", "luna_llena")
                }.randomOrNull()
            }
        }
        objetivo = deriva ?: real
    }
}

/** Lo que el usuario le hace al mar: respirar con él y hablarle. */
object Oleaje {
    var respirando by mutableStateOf(false)
}

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
                    seguir()
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
    var destello by remember { mutableStateOf(0f) }
    var reloj by remember { mutableStateOf(0f) }
    var temblor by remember { mutableStateOf(0f) }
    var visita by remember { mutableStateOf<Visita?>(null) }

    val mareas = remember {
        Corriente.iniciar(Clima.mar)
        Mareas(ctx).also { it.empezar(Corriente.objetivo) }
    }
    DisposableEffect(Unit) { onDispose { mareas.soltar() } }
    LaunchedEffect(vivo) { mareas.jugador.playWhenReady = vivo }

    // la corriente avanza una vez por segundo; si el objetivo cambia, el mar emprende el camino
    LaunchedEffect(Unit) {
        while (isActive) {
            Corriente.avanzar(Clima.mar, 1.0)
            mareas.seguir()
            delay(1000)
        }
    }
    // cuando llega un archivo nuevo del paquete, el mar puede usarlo
    LaunchedEffect(Paquete.estados) { mareas.seguir() }

    // respirar: el mar se aquieta y va más lento
    val velocidad = if (Oleaje.respirando) 0.45f else 1f
    LaunchedEffect(velocidad) {
        val desde = mareas.jugador.playbackParameters.speed
        for (i in 1..20) {
            mareas.jugador.playbackParameters = PlaybackParameters(desde + (velocidad - desde) * i / 20f)
            delay(60)
        }
    }

    // el grano salta 12 veces por segundo; relámpagos, temblor de la voz y visitas
    LaunchedEffect(vivo) {
        var vuelta = 0
        while (isActive && vivo) {
            granoDesfase = Offset(Random.nextFloat() * 256f, Random.nextFloat() * 256f)
            reloj += 0.083f
            destello *= exp(-0.083f * 7f)
            if (destello < 0.01f) destello = 0f
            val v = Corriente.visible
            val rayos = (v == "temporal" || v == "temporal_noche") && (Clima.mar.rayos || Random.nextFloat() < 0.4f)
            if (rayos && Random.nextFloat() < 0.083f * 0.05f) destello = 0.8f + Random.nextFloat() * 0.2f
            val nivel = if (Nav.grabando && Grabadora.grabando && !Grabadora.pausado) Grabadora.nivel.coerceIn(0f, 1f) else 0f
            temblor += (nivel - temblor) * 0.35f
            if (vuelta++ % 360 == 0) visita = visita?.takeIf { !it.terminada(reloj) } ?: nuevaVisita(reloj)
            delay(83)
        }
    }

    val noche = if (!Clima.mar.esDia && Corriente.visible !in Nocturnos) 0.4f else 0f
    val efecto = remember { if (Build.VERSION.SDK_INT >= 33) try { RuntimeShader(AGUA_QUE_TIEMBLA) } catch (e: Exception) { null } else null }

    Box(modifier) {
        Image(painterResource(R.drawable.mar_quieto), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AndroidView(
            factory = { c -> TextureView(c).also { tv -> conectar(tv, mareas.jugador) } },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // la voz hace temblar el agua
                if (Build.VERSION.SDK_INT >= 33 && efecto != null && temblor > 0.02f) {
                    efecto.setFloatUniform("res", size.width, size.height)
                    efecto.setFloatUniform("t", reloj)
                    efecto.setFloatUniform("amp", temblor)
                    renderEffect = RenderEffect.createRuntimeShaderEffect(efecto, "agua").asComposeRenderEffect()
                } else {
                    renderEffect = null
                    // sin Android 13: un vaivén suave en vez de ondas
                    translationY = sin(reloj * 9f) * temblor * 3f
                }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            if (Corriente.visible in Despejados) visita?.let { dibujarVisita(it, reloj, Clima.mar.esDia) }
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
}

/** El agua tiembla con la voz: ondas que bajan desde el horizonte, más fuertes cerca. */
private const val AGUA_QUE_TIEMBLA = """
uniform shader agua;
uniform float2 res;
uniform float t;
uniform float amp;
half4 main(float2 p) {
    float y = p.y / res.y;
    float bajo = smoothstep(0.34, 0.6, y);
    float ondas = sin(p.y * 0.07 - t * 9.0) * 0.6 + sin(p.x * 0.025 + p.y * 0.04 + t * 6.0) * 0.4;
    float2 q = p + float2(ondas * 7.0, ondas * 2.5) * amp * bajo;
    return agua.eval(q);
}
"""

// ---------------- las visitas ----------------

/** Algo que pasa por el horizonte: raro, breve y propio del lugar. */
private class Visita(val tipo: Int, val desde: Float, val dura: Float, val x0: Float, val sentido: Float, val alto: Float) {
    fun terminada(t: Float) = t > desde + dura
    fun avance(t: Float) = ((t - desde) / dura).coerceIn(0f, 1f)
}

private const val TEMPANO = 0
private const val ALBATROS = 1
private const val BARCO = 2

/** Cada medio minuto se tira la suerte. Témpanos más seguidos en el invierno austral; albatros de día; barcos de noche. */
private fun nuevaVisita(t: Float): Visita? {
    val m = Clima.mar
    val mes = Calendar.getInstance().get(Calendar.MONTH) + 1
    val invierno = if (mes in 6..11) 2f else 1f
    val r = Random.nextFloat()
    return when {
        r < 0.025f * invierno -> Visita(TEMPANO, t, 360f + Random.nextFloat() * 240f, 0.15f + Random.nextFloat() * 0.5f, if (Random.nextBoolean()) 1f else -1f, 0.6f + Random.nextFloat() * 0.6f)
        m.esDia && r < 0.025f * invierno + 0.05f -> Visita(ALBATROS, t, 22f + Random.nextFloat() * 10f, 0f, if (Random.nextBoolean()) 1f else -1f, 0.18f + Random.nextFloat() * 0.1f)
        !m.esDia && r < 0.025f * invierno + 0.04f -> Visita(BARCO, t, 300f + Random.nextFloat() * 200f, 0.2f + Random.nextFloat() * 0.5f, if (Random.nextBoolean()) 1f else -1f, 1f)
        else -> null
    }
}

private fun DrawScope.dibujarVisita(v: Visita, t: Float, dia: Boolean) {
    val w = size.width; val h = size.height
    val hz = h * 0.36f
    val a = v.avance(t)
    // aparece y se va despacio
    val alfa = (minOf(a, 1f - a) * 8f).coerceIn(0f, 1f)
    when (v.tipo) {
        TEMPANO -> {
            val ancho = w * 0.07f * v.alto
            val x = w * (v.x0 + v.sentido * a * 0.15f)
            val alto = ancho * 0.22f
            val cuerpo = Path().apply {
                moveTo(x, hz); lineTo(x + ancho * 0.06f, hz - alto * 0.8f); lineTo(x + ancho * 0.25f, hz - alto)
                lineTo(x + ancho * 0.48f, hz - alto); lineTo(x + ancho * 0.56f, hz - alto * 1.25f); lineTo(x + ancho * 0.82f, hz - alto * 1.15f)
                lineTo(x + ancho * 0.92f, hz - alto * 0.5f); lineTo(x + ancho, hz); close()
            }
            val sombra = Path().apply {
                moveTo(x + ancho * 0.56f, hz - alto * 1.25f); lineTo(x + ancho * 0.82f, hz - alto * 1.15f)
                lineTo(x + ancho * 0.92f, hz - alto * 0.5f); lineTo(x + ancho, hz); lineTo(x + ancho * 0.7f, hz); close()
            }
            val claro = if (dia) Color(0xFFD5DEE2) else Color(0xFF8FA6B5)
            drawPath(cuerpo, claro.copy(alpha = 0.92f * alfa))
            drawPath(sombra, Color(0xFF7E9BB0).copy(alpha = 0.7f * alfa))
            // su reflejo, roto por el agua
            for (k in 0 until 4) {
                val yy = hz + 2f + k * alto * 0.3f
                drawRect(claro.copy(alpha = 0.18f * alfa * (1f - k / 4f)), Offset(x + ancho * 0.1f, yy), Size(ancho * 0.8f, alto * 0.12f))
            }
        }
        ALBATROS -> {
            val x = if (v.sentido > 0) -w * 0.1f + a * w * 1.2f else w * 1.1f - a * w * 1.2f
            val y = h * v.alto + sin(a * 6.28f * 1.5f) * h * 0.015f
            val env = w * 0.06f
            // planea casi sin aletear: alas largas y finas, apenas arqueadas
            val aleteo = sin(t * 2.2f) * 0.15f
            val ala = Path().apply {
                moveTo(x - env, y + env * (0.05f + aleteo * 0.3f))
                quadraticBezierTo(x - env * 0.5f, y - env * (0.12f + aleteo), x, y)
                quadraticBezierTo(x + env * 0.5f, y - env * (0.12f + aleteo), x + env, y + env * (0.05f + aleteo * 0.3f))
            }
            drawPath(ala, Color(0xFF0B2A40).copy(alpha = 0.85f * alfa), style = Stroke(width = env * 0.07f))
            drawCircle(Color(0xFF0B2A40).copy(alpha = 0.85f * alfa), env * 0.05f, Offset(x, y + env * 0.01f))
        }
        BARCO -> {
            val x = w * (v.x0 + v.sentido * a * 0.12f)
            val luces = listOf(0f to -3f, 9f to -2f, 17f to -4f)
            for ((dx, dy) in luces) {
                val c = Offset(x + dx * density, hz + dy * density)
                drawCircle(Brush.radialGradient(listOf(Color(0x66EDE3CF), Color.Transparent), c, 9f * density), 9f * density, c, alpha = alfa)
                drawCircle(Color(0xFFEDE3CF).copy(alpha = alfa), 1.6f * density, c)
                // la estela de luz sobre el agua
                drawRect(Color(0x26EDE3CF).copy(alpha = 0.15f * alfa), Offset(c.x - 0.8f * density, hz + 3f * density), Size(1.6f * density, 34f * density))
            }
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
