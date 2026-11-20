package com.sebas.solaris.ui

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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
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
        // un solo mar, debajo de todo: la entrada y la orilla son el mismo; se queda quieto mientras uno escribe
        if (enCasa) {
            val enSuperficie = Nav.listaOrilla.firstVisibleItemIndex == 0
            MarCianotipo(Modifier.fillMaxSize(), vivo = !(Casa.abierta && (encima || !enSuperficie)))
        }
        when {
            !Casa.conectada -> Conectar()
            !Casa.lista -> Bienvenida(act)
            else -> AnimatedContent(
                targetState = Casa.abierta,
                transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(700)) },
                label = "llegada",
            ) { abierta -> if (abierta) Interior() else Entrada(act) }
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
        if (conHuella && !pinVisible) Huella.pedir(act) { Casa.abrirConCelular() }
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
        Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color(0xAA02070F), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC02070F)))))

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
                            Modifier.presionable { Sonidos.tic(); Huella.pedir(act) { Casa.abrirConCelular() } }
                                .graphicsLayer { alpha = alfa }.size(72.dp).clip(CircleShape).background(Color(0x33B9C6CC)),
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
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = orilla }) { if (orilla > 0.01f) Orilla() }
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
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 300f)) { it / 4 } + fadeIn(tween(320)),
        exit = slideOutVertically(tween(300)) { it / 4 } + fadeOut(tween(260)),
    ) { contenido() }
}
