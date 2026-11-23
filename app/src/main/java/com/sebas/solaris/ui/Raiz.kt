@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sebas.solaris.ui

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.animateFloat

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.haze
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.Huella
import com.sebas.solaris.MainActivity
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dónde está uno dentro de la estación. Sobrevive al cierre. */
object Nav {
    var enPortada by mutableStateOf(false)
    var borrador by mutableStateOf<Borrador?>(null)
    var ajustes by mutableStateOf(false)
    var cambiarPin by mutableStateOf(false)
    /** Grabando sobre el mar. */
    var grabando by mutableStateOf(false)
    /** Si se graba desde una nota: qué hacer con la cinta al entregarla (pegarla donde estaba el cursor). */
    var alGrabar: ((String) -> Unit)? = null
    /** La cinta abierta para escuchar. */
    var escuchando by mutableStateOf<String?>(null)
    /** Lo que pidió el widget ("escribir" o "grabar"). */
    var pendiente by mutableStateOf<String?>(null)
    /** Qué tan hondo ibas en la orilla. */
    val listaOrilla = LazyListState()

    fun abrirNota(id: String?) { borrador = Borrador(id, null) }
    fun reiniciar() { borrador = null; ajustes = false; cambiarPin = false; grabando = false; escuchando = null }
}

@Composable
fun Solaris(act: MainActivity) {
    val p = Tema.p
    val vista = LocalView.current
    val enCasa = Casa.conectada && Casa.lista
    val encima = Nav.borrador != null || Nav.ajustes || Nav.cambiarPin
    val pantallaOscura = when {
        !enCasa -> Tema.esNoche || Nav.enPortada
        Casa.abierta && encima -> Tema.esNoche
        else -> true
    }
    SideEffect {
        // capturas permitidas salvo que se pida proteger la pantalla
        if (Tema.protegerPantalla) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val c = WindowCompat.getInsetsController(act.window, vista)
        c.isAppearanceLightStatusBars = !pantallaOscura
        c.isAppearanceLightNavigationBars = !pantallaOscura
    }
    LaunchedEffect(Unit) {
        while (isActive) { delay(60_000); Tema.tic() }
    }
    LaunchedEffect(Unit) {
        while (isActive) { Clima.actualizar(); delay(15 * 60 * 1000L) }
    }

    Box(Modifier.fillMaxSize().background(if (enCasa) Color.Black else p.fondo)) {
        // un solo mar, debajo de todo: la entrada y la orilla son el mismo; sigue vivo mientras uno escribe (escribir lo mueve)
        if (enCasa) {
            val enSuperficie by remember { derivedStateOf { Nav.listaOrilla.firstVisibleItemIndex == 0 } }
            // escribiendo con el teclado abierto, el papel tapa el mar: el video descansa (queda su cuadro quieto)
            val teclado = WindowInsets.isImeVisible
            val escribiendo = Nav.borrador != null && teclado && Tema.papelLamina >= 0.8f
            val marVivo = !Casa.abierta || (Nav.borrador != null && !escribiendo) || (!encima && enSuperficie)
            DisposableEffect(Unit) { Vidrio.activo = true; onDispose { Vidrio.activo = false } }
            // El vidrio no mira el video en vivo (eso costaba un desenfoque por cuadro): mira una foto chiquita
            // del mar que se renueva cada segundo y medio. Va detrás del mar, así que no se ve sola.
            var fotoMar by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(marVivo) {
                while (isActive) {
                    MarVivo.cuadroChico()?.let { fotoMar = it }
                    delay(if (marVivo) 1500L else 6000L)
                }
            }
            // lo hondo, en escalones: el vidrio no se vuelve a desenfocar a cada cuadro del scroll
            val honduraVidrio by remember { derivedStateOf { (Vidrio.hondura * Vidrio.presencia * 10f).roundToInt() / 10f } }
            Box(Modifier.fillMaxSize().haze(Vidrio.estado)) {
                val f = fotoMar
                if (f != null) Image(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().background(Azules.Hondo))
                if (honduraVidrio > 0f) Box(Modifier.fillMaxSize().background(Azules.Abismo.copy(alpha = honduraVidrio * 0.84f)))
            }
            MarCianotipo(Modifier.fillMaxSize(), vivo = marVivo)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = Vidrio.hondura * Vidrio.presencia * 0.84f }.background(Azules.Abismo))
            // los velos de la llegada no desaparecen de golpe al abrir: se retiran despacio, como la marea
            val velo by animateFloatAsState(
                if (Casa.abierta) 0f else 1f,
                tween(if (Casa.abierta) 2600 else 500, easing = FastOutSlowInEasing), label = "velo",
            )
            if (velo > 0.002f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = velo }) {
                Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color(0xAA02070F), Color.Transparent))))
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC02070F)))))
            }
        }
        when {
            !Casa.conectada -> Conectar()
            !Casa.lista -> Bienvenida(act)
            else -> AnimatedContent(
                targetState = Casa.abierta,
                transitionSpec = { fadeIn(tween(1100, delayMillis = 150)) togetherWith fadeOut(tween(900)) },
                label = "llegada",
            ) { abierta ->
                // sin desenfoque de pantalla completa (era lo que más trababa al entrar): un leve acercamiento
                val escala by transition.animateFloat({ tween(900, easing = FastOutSlowInEasing) }, label = "acercar") {
                    if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 1.04f
                }
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = escala; scaleY = escala }) { if (abierta) Interior() else Entrada(act) }
            }
        }
        // escribiendo, el papel tapa el cielo, pero la luz de los relámpagos pasa a través de él
        if (enCasa && Nav.borrador != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val b = Relampago.brillo
                if (b > 0.01f) drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFFE6EEF6).copy(alpha = (b * 0.34f).coerceAtMost(0.34f)),
                        0.45f to Color(0xFFE6EEF6).copy(alpha = (b * 0.16f).coerceAtMost(0.16f)),
                        1f to Color(0xFFE6EEF6).copy(alpha = (b * 0.08f).coerceAtMost(0.08f)),
                    )
                )
            }
        }
    }
}

// ---------------- la llegada a la estación ----------------

@Composable
fun Entrada(act: MainActivity) {
    var paso by remember { mutableIntStateOf(0) }
    var llegada by remember { mutableStateOf(0.0) }
    var pinVisible by remember { mutableStateOf(Casa.pinBloqueado()) }
    val conHuella = remember { Casa.huellaActiva() && Huella.disponible(act) }
    var objetivo by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { objetivo = 1f }
    val aparicion by animateFloatAsState(objetivo, tween(1600), label = "aparicion")
    LaunchedEffect(Unit) {
        val inicio = System.currentTimeMillis()
        var acoplado = false
        while (isActive) {
            llegada = ((System.currentTimeMillis() - inicio) / 3500.0).coerceIn(0.0, 1.0)
            if (!acoplado && llegada >= 1.0) { acoplado = true; Sonidos.acople() }
            if (acoplado) break
            delay(50)
        }
    }
    LaunchedEffect(Unit) {
        listOf(700L, 800L, 800L, 800L, 900L).forEach { delay(it); paso++ }
        if (conHuella && !pinVisible) Huella.pedir(act) { Casa.abrirAlFondo() }
    }
    val e = (1 - Math.pow(1 - llegada, 3.0)).toFloat()

    Box(Modifier.fillMaxSize()) {
        // el mar sube desde el abismo: la oscuridad se retira de abajo hacia arriba
        val velo = 1f - minOf(e, aparicion)
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = (velo * 3f).coerceAtMost(1f)),
                (velo * 0.9f).coerceIn(0.001f, 0.98f) to Color.Black.copy(alpha = (velo * 3f).coerceAtMost(1f)),
                (velo * 0.9f + 0.25f).coerceIn(0.002f, 0.999f) to Color.Black.copy(alpha = velo * 0.6f),
                1f to Color.Transparent,
            )
        ))
        Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Senal("Estación Solaris · alta mar", color = Azules.Espuma)
            Senal(Clima.LUGAR, color = Azules.Bruma)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val gris = Azules.Bruma
                val hielo = Azules.Hielo
                if (paso >= 1) LineaArranque("Señal de la estación", "OK", gris, hielo)
                if (paso >= 2) LineaArranque("Viento", "${Clima.mar.viento.toInt()} nudos", gris, hielo)
                if (paso >= 3) LineaArranque("El mar", Corriente.nombreVisible.lowercase(), gris, hielo)
                if (paso >= 4) LineaArranque("Bitácora cifrada", "LISTA", gris, hielo)
            }
            Spacer(Modifier.height(30.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.heightIn(min = 190.dp)) {
                val visible = paso >= 5
                val alfa by animateFloatAsState(if (visible) 1f else 0f, tween(1300), label = "titulo")
                val tracking by animateFloatAsState(if (visible) 9f else 22f, tween(1800, easing = FastOutSlowInEasing), label = "tracking")
                val linea by animateFloatAsState(if (visible) 1f else 0f, tween(2000, delayMillis = 350, easing = FastOutSlowInEasing), label = "linea")
                Text("SOLARIS", color = Azules.Crema, fontFamily = Sans, fontSize = 40.sp, letterSpacing = (tracking * 1.25f).sp,
                    modifier = Modifier.graphicsLayer { alpha = alfa })
                Horizonte(linea, Azules.Crema.copy(alpha = 0.8f), Modifier.width(250.dp))
                Spacer(Modifier.height(8.dp))
                val (a, b) = Tema.saludo()
                Text("$a $b", color = Azules.Espuma, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp,
                    modifier = Modifier.graphicsLayer { alpha = alfa })
                Spacer(Modifier.height(28.dp))
                if (visible) {
                    if (conHuella) {
                        Box(
                            Modifier.presionable { Sonidos.tic(); Huella.pedir(act) { Casa.abrirAlFondo() } }
                                .graphicsLayer { alpha = alfa }.size(72.dp).vidrio(CircleShape, Azules.Noche, 0.3f),
                            contentAlignment = Alignment.Center,
                        ) { IconoHuella(Azules.Crema) }
                        Spacer(Modifier.height(10.dp))
                        Enlace("Usar PIN", color = Azules.Espuma) { pinVisible = true }
                    } else {
                        Pildora("Entrar", Modifier.width(200.dp).graphicsLayer { alpha = alfa }) { pinVisible = true }
                    }
                }
            }
        }

        Candado(pinVisible) { pinVisible = false }
    }
}

@Composable
private fun LineaArranque(que: String, estado: String, gris: Color, hielo: Color) {
    val total = que.length + estado.length
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (n < total) { delay(22); n++; if (n % 3 == 0) Sonidos.teclear() }
    }
    Row(Modifier.fillMaxWidth()) {
        Text(que.take(n).uppercase(), color = gris, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        Text(estado.take((n - que.length).coerceAtLeast(0)).uppercase(), color = hielo, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp)
    }
}

/** El PIN (o la frase llave) en una hoja oscura sobre el mar. */
@Composable
private fun Candado(visible: Boolean, alCerrar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var modoFrase by remember { mutableStateOf(Casa.pinBloqueado()) }
    var frase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    val claro = Color(0xFFECE6DA)

    Hoja(visible, { if (!Casa.pinBloqueado()) alCerrar() }) {
        if (!modoFrase) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Tu PIN", color = Tema.p.texto, fontFamily = Sans, fontSize = 17.sp)
                Puntos(pin.length, Casa.largoPin(), Tema.p.texto)
                Text(error ?: " ", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 13.sp)
                Teclado(
                    alDigito = { d ->
                        if (!trabajando && pin.length < Casa.largoPin()) {
                            pin += d; error = null
                            if (pin.length == Casa.largoPin()) {
                                trabajando = true
                                val intento = pin
                                scope.launch {
                                    if (!Casa.probarPin(intento)) {
                                        pin = ""
                                        if (Casa.pinBloqueado()) { modoFrase = true; error = "Demasiados intentos. Entra con tu frase llave." }
                                        else error = "Ese no es tu PIN. Quedan ${Casa.INTENTOS_PIN - Casa.fallosPin()} intentos."
                                    }
                                    trabajando = false
                                }
                            }
                        }
                    },
                    alBorrar = { pin = pin.dropLast(1) },
                    colorTexto = Tema.p.texto,
                    fondoTecla = Tema.p.superficie,
                )
                Enlace("Olvidé mi PIN") { modoFrase = true; error = null }
            }
        } else {
            Text("Frase llave", color = Tema.p.texto, fontFamily = Sans, fontSize = 17.sp)
            Campo(frase, { frase = it; error = null }, "Escribe tu frase llave", secreto = true, tipo = KeyboardType.Password)
            if (error != null) Text(error ?: "", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 13.sp)
            Pildora(if (trabajando) "Abriendo…" else "Entrar", Modifier.fillMaxWidth(), habilitada = frase.isNotBlank() && !trabajando) {
                trabajando = true
                scope.launch {
                    if (!Casa.abrirConFrase(frase)) error = "Esa frase no abre esta estación." else frase = ""
                    trabajando = false
                }
            }
            if (!Casa.pinBloqueado()) Enlace("Volver al PIN") { modoFrase = false; error = null }
        }
    }
}

// ---------------- adentro ----------------

@Composable
fun Interior() {
    LaunchedEffect(Nav.pendiente) {
        when (Nav.pendiente) {
            "escribir" -> { Nav.ajustes = false; Nav.abrirNota(null) }
            "grabar" -> { Nav.ajustes = false; Nav.borrador = null; Nav.grabando = true }
        }
        Nav.pendiente = null
    }
    // lo último que estuvo abierto, para que pueda salir con su animación
    var ultimoBorrador by remember { mutableStateOf<Borrador?>(null) }
    Nav.borrador?.let { ultimoBorrador = it }

    // mientras algo está encima (escribir, ajustes), la orilla se retira: solo queda el mar
    val encima = Nav.borrador != null || Nav.ajustes || Nav.cambiarPin
    val orilla by animateFloatAsState(if (encima) 0f else 1f, tween(if (encima) 220 else 500), label = "orilla")
    LaunchedEffect(Unit) { snapshotFlow { orilla }.collect { Vidrio.presencia = it } }
    // las letras de las notas se cargan de antemano: abrir la primera nota no espera a leerlas
    val letras = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    LaunchedEffect(Unit) { listOf(Serif, Sans, Mono, Maquina, Mano).forEach { runCatching { letras.preload(it) } } }
    Box(Modifier.fillMaxSize()) {
        // la orilla se queda armada debajo (volver de una nota no la reconstruye); solo se oculta
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = orilla }) { Orilla() }
        // y mientras está oculta no recibe toques
        if (encima) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }
        )
        Encima(Nav.borrador != null) { ultimoBorrador?.let { Editor(it) } }
        Encima(Nav.ajustes) { Ajustes() }
        Encima(Nav.cambiarPin) { Box(Modifier.fillMaxSize().background(Tema.p.fondo).systemBarsPadding()) { PinNuevo { Nav.cambiarPin = false } } }
        Grabacion()
        Escuchar()
    }
}

/** Lo que sube sobre el mar: una lámina que llega con resorte y se hunde al cerrarse. */
@Composable
private fun Encima(visible: Boolean, contenido: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 300f)) { it / 5 } + fadeIn(tween(320)) +
            scaleIn(spring(dampingRatio = 0.9f, stiffness = 300f), initialScale = 0.97f),
        exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 420f)) { it / 5 } + fadeOut(tween(240)),
    ) { Box(Modifier.fillMaxSize()) { contenido() } }
}
