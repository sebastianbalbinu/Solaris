package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
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
    var pestana by mutableStateOf("cuaderno")
    var borrador by mutableStateOf<Borrador?>(null)
    var ajustes by mutableStateOf(false)
    var cambiarPin by mutableStateOf(false)
    var cintaElegida by mutableStateOf<String?>(null)
    /** Lo que pidió el widget ("escribir" o "grabar"). */
    var pendiente by mutableStateOf<String?>(null)
    /** Dónde ibas en la lista del cuaderno. */
    val listaCuaderno = LazyListState()

    fun abrirNota(id: String?) { borrador = Borrador(id, null) }
    fun reiniciar() { pestana = "cuaderno"; borrador = null; ajustes = false; cambiarPin = false; cintaElegida = null }
}

@Composable
fun Solaris(act: MainActivity) {
    val p = Tema.p
    val vista = LocalView.current
    val oscuro = Tema.esNoche || (Casa.abierta && (Nav.pestana != "cuaderno" || Nav.ajustes)) || !Casa.abierta
    SideEffect {
        val fondo = if (oscuro) Color.Black else p.fondo
        act.window.statusBarColor = fondo.toArgb()
        act.window.navigationBarColor = fondo.toArgb()
        val c = WindowCompat.getInsetsController(act.window, vista)
        c.isAppearanceLightStatusBars = !oscuro
        c.isAppearanceLightNavigationBars = !oscuro
    }
    LaunchedEffect(Unit) {
        while (isActive) { delay(60_000); Tema.tic() }
    }
    LaunchedEffect(Unit) { Clima.actualizar() }

    Box(Modifier.fillMaxSize().background(if (Casa.abierta) p.fondo else Color.Black)) {
        when {
            !Casa.conectada -> Conectar()
            !Casa.lista -> Bienvenida(act)
            Casa.abierta -> Interior()
            else -> Entrada(act)
        }
    }
}

// ---------------- la llegada a la estación ----------------

@Composable
fun Entrada(act: MainActivity) {
    val planeta = remember { PlanetaPixel() }
    val bmp = remember { Bitmap.createBitmap(planeta.ancho, planeta.alto, Bitmap.Config.ARGB_8888) }
    var cuadro by remember { mutableIntStateOf(0) }
    var paso by remember { mutableIntStateOf(0) }
    var altitud by remember { mutableIntStateOf(520) }
    var pinVisible by remember { mutableStateOf(Casa.pinBloqueado()) }
    val conHuella = remember { Casa.huellaActiva() && Huella.disponible(act) }

    LaunchedEffect(Unit) {
        val inicio = System.currentTimeMillis()
        while (isActive) {
            val seg = (System.currentTimeMillis() - inicio) / 1000.0
            val llegada = (seg / 3.5).coerceIn(0.0, 1.0)
            withContext(Dispatchers.Default) { planeta.cuadro(0.07, llegada) }
            bmp.setPixels(planeta.px, 0, planeta.ancho, 0, 0, planeta.ancho, planeta.alto)
            cuadro++
            val e = 1 - Math.pow(1 - llegada, 3.0)
            altitud = (520 - e * 108).toInt()
            delay(70)
        }
    }
    LaunchedEffect(Unit) {
        listOf(600L, 600L, 600L, 600L, 700L).forEach { delay(it); paso++ }
        if (conHuella && !pinVisible) Huella.pedir(act) { Casa.abrirConCelular() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LienzoPixel(bmp, cuadro, Modifier.fillMaxSize())
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))

        Column(Modifier.padding(horizontal = 26.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Senal("Estación Solaris · $altitud km", color = Color(0xFFD6EEF2))
            Senal("Bajo nosotros: paso de Drake", color = Color(0xB3D6EEF2))
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val gris = Color(0xFFA3AEB4)
                val hielo = Color(0xFFA8E6EE)
                if (paso >= 1) LineaArranque("Acople con la estación", "OK", gris, hielo)
                if (paso >= 2) LineaArranque("Presión de cabina", "OK", gris, hielo)
                if (paso >= 3) LineaArranque("El mar abajo", Clima.mar.nombre.lowercase(), gris, hielo)
                if (paso >= 4) LineaArranque("Bitácora cifrada", "LISTA", gris, hielo)
            }
            Spacer(Modifier.height(30.dp))
            AnimatedVisibility(paso >= 5, enter = fadeIn(tween(900))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SOLARIS", color = Color(0xFFECE6DA), fontFamily = Serif, fontSize = 48.sp, letterSpacing = 9.sp)
                    val (a, b) = Tema.saludo()
                    Text("$a $b", color = Color(0xFFCFC8BC), fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 20.sp)
                    Spacer(Modifier.height(30.dp))
                    if (conHuella) {
                        Box(
                            Modifier.size(72.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                                .clickable { Sonidos.tic(); Huella.pedir(act) { Casa.abrirConCelular() } },
                            contentAlignment = Alignment.Center,
                        ) { IconoHuella(Color(0xFFECE6DA)) }
                        Spacer(Modifier.height(10.dp))
                        Enlace("Usar PIN", color = Color(0xFFB9C3C8)) { pinVisible = true }
                    } else {
                        Pildora("Entrar", Modifier.width(200.dp)) { pinVisible = true }
                    }
                }
            }
        }

        Candado(pinVisible) { pinVisible = false }
    }
}

@Composable
private fun LineaArranque(que: String, estado: String, gris: Color, hielo: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(que.uppercase(), color = gris, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        Text(estado.uppercase(), color = hielo, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.2.sp)
    }
}

/** El PIN (o la frase llave) en una hoja oscura sobre el planeta. */
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
            "grabar" -> { Nav.ajustes = false; Nav.borrador = null; Nav.pestana = "cinta" }
        }
        Nav.pendiente = null
    }
    Box(Modifier.fillMaxSize()) {
        when {
            Nav.cambiarPin -> Box(Modifier.fillMaxSize().background(Tema.p.fondo)) { PinNuevo { Nav.cambiarPin = false } }
            Nav.ajustes -> Ajustes()
            Nav.borrador != null -> Editor(Nav.borrador!!)
            else -> {
                when (Nav.pestana) {
                    "cinta" -> Cinta()
                    "oceano" -> Oceano()
                    else -> Cuaderno()
                }
                BarraPestanas(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** La barra de abajo: una píldora flotante con los tres lugares. */
@Composable
fun BarraPestanas(modifier: Modifier = Modifier) {
    val oscura = Tema.esNoche || Nav.pestana != "cuaderno"
    val fondo = if (oscura) Color(0xE6161618) else Color(0xEBE9DDC8)
    val activo = if (oscura) Color(0xFFECE6DA) else Color(0xFF1B1A18)
    val inactivo = if (oscura) Color(0xFF8E8E93) else Color(0xFF8A7F6E)
    Row(
        modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(28.dp)).background(fondo).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf("cuaderno" to "Cuaderno", "cinta" to "Cinta", "oceano" to "Océano").forEach { (clave, nombre) ->
            val sel = Nav.pestana == clave
            Box(
                Modifier.clip(RoundedCornerShape(22.dp))
                    .background(if (sel) (if (oscura) Color(0xFF2A2A2E) else Color(0xFFF8F0E3)) else Color.Transparent)
                    .clickable { if (!sel) { Sonidos.tic(); Nav.pestana = clave } }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(nombre, color = if (sel) activo else inactivo, fontFamily = Sans, fontSize = 14.sp)
            }
        }
    }
}
