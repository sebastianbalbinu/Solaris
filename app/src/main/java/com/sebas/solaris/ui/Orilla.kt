package com.sebas.solaris.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.Item
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/*
 * La orilla: el mar es el inicio. Arriba, la superficie (la telemetría, el saludo, los ecos que emergen);
 * al deslizar hacia abajo uno se hunde: el agua se oscurece y aparecen los registros, de lo más nuevo
 * (cerca de la superficie) a lo más viejo (en lo hondo). Un solo botón para entregarle algo al mar.
 */

private val Claro = Color(0xFFDCE6EA)

@Composable
fun Orilla() {
    val lista = Nav.listaOrilla
    // grabar es sobre el mar abierto: si uno estaba hondo entre los recuerdos, sube a la superficie
    LaunchedEffect(Nav.grabando) { if (Nav.grabando && lista.firstVisibleItemIndex + lista.firstVisibleItemScrollOffset > 0) lista.animateScrollToItem(0) }
    var consulta by remember { mutableStateOf("") }
    val todos = remember(Casa.items) { Casa.registros() }
    val registros = remember(consulta, Casa.items) {
        if (consulta.isBlank()) todos else Casa.buscar(consulta.trim().removePrefix(">").trim())
    }
    // qué tan hondo va uno: 0 en la superficie, 1 cuando la superficie ya quedó arriba
    val hondura by remember {
        derivedStateOf {
            if (lista.firstVisibleItemIndex > 0) 1f
            else {
                val alto = lista.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1
                (lista.firstVisibleItemScrollOffset.toFloat() / alto).coerceIn(0f, 1f)
            }
        }
    }
    val hondo by remember { derivedStateOf { hondura > 0.6f } }
    // la profundidad del registro que está arriba: un metro por día
    val metros by remember(registros) {
        derivedStateOf {
            val i = lista.firstVisibleItemIndex - 2
            val r = registros.getOrNull(i.coerceAtLeast(0))
            if (lista.firstVisibleItemIndex < 2 || r == null) 0 else diasDesde(r.created)
        }
    }

    // mientras se respira, todo lo demás se retira
    val retirada by androidx.compose.animation.core.animateFloatAsState(if (Oleaje.respirando) 0f else 1f, androidx.compose.animation.core.tween(1200), label = "retirada")
    Box(Modifier.fillMaxSize()) {
        // el agua se oscurece al bajar (la oscuridad es parte del mar, así el vidrio también la ve)
        LaunchedEffect(Unit) { snapshotFlow { hondura }.collect { Vidrio.hondura = it } }
        DisposableEffect(Unit) { onDispose { Vidrio.hondura = 0f } }

        LazyColumn(Modifier.fillMaxSize().graphicsLayer { alpha = retirada }, state = lista, contentPadding = PaddingValues(bottom = 150.dp)) {
            item(key = "superficie") { Superficie(Modifier.fillParentMaxHeight()) }
            item(key = "sonar") { Sonar(consulta, { consulta = it }, registros.size) }
            if (registros.isEmpty()) {
                item(key = "vacio") {
                    Text(
                        if (consulta.isBlank()) "El mar todavía no guarda nada. Entrégale lo primero que no quieras perder."
                        else "El sonar no encontró nada con eso.",
                        color = Azules.Bruma, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 19.sp, lineHeight = 26.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    )
                }
            }
            items(registros, key = { it.id }) { r ->
                Tarjeta(r) {
                    Sonidos.tic()
                    if (r.esCinta) Nav.escuchando = r.id else Nav.abrirNota(r.id)
                }
            }
        }

        // niebla en los bordes: los registros se disuelven arriba y abajo en vez de cortarse
        val niebla = ((hondura - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (niebla > 0.01f) {
            Box(
                Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).graphicsLayer { alpha = niebla * retirada }
                    .nieblaDeBorde(arriba = true)
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().height(150.dp)
                    .graphicsLayer { alpha = niebla * retirada }.nieblaDeBorde(arriba = false)
            )
        }

        // la profundidad, arriba, mientras uno está hondo
        if (hondo) {
            Row(
                Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp).graphicsLayer { alpha = ((hondura - 0.6f) / 0.4f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Senal("Profundidad  −$metros m", color = Azules.Espuma)
            }
        }

        BotonUnico(Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = retirada })
        Respiracion()
    }
}

// ---------------- la superficie ----------------

@Composable
private fun Superficie(modifier: Modifier) {
    val mar = Clima.mar
    val (a, b) = Tema.saludo()
    val vista = androidx.compose.ui.platform.LocalView.current
    Box(modifier.fillMaxWidth().pointerInput(Unit) {
        // dejar el dedo un momento sobre el mar (sin moverlo) empieza a respirar con él
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var solto = false
            // si se suelta o se desliza antes, no es respirar: es tocar o recordar
            withTimeoutOrNull(500) { waitForUpOrCancellation(); solto = true }
            if (!solto && !Oleaje.respirando) {
                Oleaje.respirando = true
                vista.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }) {
        // velos para que la letra se lea sobre el cielo y sobre el agua
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(Color(0x9902070F), Color.Transparent))))

        Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp).padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Senal(Clima.LUGAR, color = Azules.Espuma)
                    Senal(
                        Corriente.nombreVisible + " · olas " + uno(mar.ola) + " m · " + mar.periodo.toInt() + " s",
                        color = Azules.Bruma,
                    )
                }
                Redondo(fondo = Azules.Noche, vidrio = true, alTocar = { Nav.ajustes = true }) { IconoPuntos(Azules.Crema) }
            }
            Spacer(Modifier.height(44.dp))
            Text(a, color = Azules.Crema, fontFamily = Serif, fontSize = 46.sp, lineHeight = 48.sp)
            Text(b, color = Azules.Crema, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 46.sp, lineHeight = 48.sp)
            Spacer(Modifier.height(18.dp))
            FlotanBoyas()
            AvisoMarea()
        }

        // un eco emerge cerca del horizonte, se queda un rato y se hunde
        Eco(Modifier.align(Alignment.Center).padding(top = 40.dp))

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 132.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "mantén el dedo sobre el mar para respirar · desliza para recordar", color = Azules.Espuma, fontFamily = Sans, fontSize = 13.sp,
                style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC02070F), Offset(0f, 1f), 10f)),
            )
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.size(18.dp, 10.dp)) {
                val g = 1.5.dp.toPx()
                drawLine(Azules.Bruma, Offset(0f, 0f), Offset(size.width / 2, size.height), g)
                drawLine(Azules.Bruma, Offset(size.width, 0f), Offset(size.width / 2, size.height), g)
            }
        }
    }
}

@Composable
private fun Eco(modifier: Modifier) {
    val ecos = remember(Casa.items.size) { Casa.ecos() }
    if (ecos.isEmpty()) return
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(ecos) {
        while (isActive) { delay(14_000); i = (i + 1) % ecos.size }
    }
    AnimatedContent(
        targetState = ecos[i % ecos.size],
        transitionSpec = { fadeIn(tween(2600, delayMillis = 900)) togetherWith fadeOut(tween(1800)) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 36.dp),
        label = "eco",
    ) { e ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Senal(hace(e.created), color = Azules.Espuma.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
            Text(
                "“" + frase(e) + "”",
                color = Azules.Crema.copy(alpha = 0.82f), fontFamily = Serif, fontStyle = FontStyle.Italic,
                fontSize = 22.sp, lineHeight = 28.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------- el botón único ----------------

/** Tocar el círculo: escribir. Mantenerlo (o tocar "grabar"): grabar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotonUnico(modifier: Modifier) {
    val forma = RoundedCornerShape(50)
    Row(
        modifier.navigationBarsPadding().padding(bottom = 20.dp)
            .vidrio(forma, Azules.Hondo, 0.32f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "escribir", color = Azules.Espuma, fontFamily = Sans, fontSize = 15.sp,
            modifier = Modifier.clip(forma).combinedClickable(onClick = { Sonidos.tic(); Nav.abrirNota(null) }).padding(horizontal = 18.dp, vertical = 14.dp),
        )
        val fuente = remember { MutableInteractionSource() }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Azules.Crema)
                .combinedClickable(
                    interactionSource = fuente, indication = null,
                    onClick = { Sonidos.tic(); Nav.abrirNota(null) },
                    onLongClick = { Sonidos.tecla(); Nav.grabando = true },
                ),
            contentAlignment = Alignment.Center,
        ) { IconoMas(Azules.Prusia, 22.dp) }
        Text(
            "grabar", color = Azules.Espuma, fontFamily = Sans, fontSize = 15.sp,
            modifier = Modifier.clip(forma).combinedClickable(onClick = { Sonidos.tecla(); Nav.grabando = true }).padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

// ---------------- el sonar y los registros ----------------

@Composable
private fun Sonar(consulta: String, alCambiar: (String) -> Unit, cuantos: Int) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp).padding(top = 34.dp, bottom = 10.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Recordar", color = Azules.Crema, fontFamily = Serif, fontSize = 38.sp, modifier = Modifier.weight(1f))
            Senal(if (cuantos == 1) "1 registro" else "$cuantos registros", color = Azules.Bruma, modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(14.dp))
        val forma = RoundedCornerShape(18.dp)
        Row(
            Modifier.fillMaxWidth().vidrio(forma, Azules.Abismo, 0.5f, filo = 0.7f)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(">", color = Azules.Bruma, fontFamily = Mono, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = consulta,
                onValueChange = alCambiar,
                singleLine = true,
                textStyle = TextStyle(color = Azules.Crema, fontFamily = Mono, fontSize = 16.sp),
                cursorBrush = SolidColor(Azules.Crema),
                modifier = Modifier.weight(1f),
                decorationBox = { campo ->
                    Box {
                        if (consulta.isEmpty()) Text("hoy, ayer, #tema, voces, rumores…", color = Azules.Niebla, fontFamily = Mono, fontSize = 16.sp)
                        campo()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Senal("sonar", color = Azules.Niebla)
        }
    }
}

@Composable
private fun Tarjeta(r: Item, alTocar: () -> Unit) {
    val forma = RoundedCornerShape(22.dp)
    val tipo = when {
        r.esRumor -> "Rumor"
        r.esCinta -> "Voz"
        else -> "Escrito"
    }
    val titulo = when {
        r.esCinta -> r.title.ifBlank { "cinta sin nombre" }
        else -> r.title.ifBlank { sinMarcas(com.sebas.solaris.Marcas.limpio(r.text).lineSequence().firstOrNull { it.isNotBlank() } ?: "Fotos") }
    }
    val sub = when {
        r.esRumor -> duracion(r.dur) + " · solo sonido"
        r.esCinta -> if (r.text.isNotBlank()) "“" + r.text.replace('\n', ' ').take(90) + "”" else duracion(r.dur) + " · sin transcribir"
        r.title.isBlank() -> sinMarcas(com.sebas.solaris.Marcas.limpio(r.text).lineSequence().filter { it.isNotBlank() }.drop(1).joinToString(" "))
        else -> sinMarcas(com.sebas.solaris.Marcas.limpio(r.text).replace('\n', ' '))
    }
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .presionable { alTocar() }
            .vidrio(forma, Azules.Hondo, 0.42f, filo = 0.8f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Senal(tipo + " · " + hace(r.created) + " · −" + diasDesde(r.created) + " m", color = Azules.Bruma)
            Text(titulo, color = Azules.Crema, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = Azules.Espuma, fontFamily = Sans, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.esCinta) Onda(r.id, Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(22.dp))
        }
        r.photos.firstOrNull()?.let { f ->
            Spacer(Modifier.width(14.dp))
            Foto(f, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), lado = 200)
        }
    }
}

/** Una forma de onda quieta, propia de cada cinta (sale de su id). */
@Composable
fun Onda(semilla: String, modifier: Modifier, color: Color = Azules.Bruma, progreso: Float = 0f, acento: Color = color) {
    val h = semilla.hashCode()
    Canvas(modifier) {
        val n = 34
        val paso = size.width / n
        for (k in 0 until n) {
            val v = 0.25f + 0.75f * abs(sin(k * 0.55f + h * 0.001f) * 0.6f + sin(k * 1.7f + h * 0.0007f) * 0.4f)
            val alto = size.height * v
            val c = if (k < progreso * n) acento else color
            drawRoundRect(c, Offset(k * paso, (size.height - alto) / 2), Size(paso * 0.45f, alto), CornerRadius(paso))
        }
    }
}

// ---------------- palabras ----------------

fun uno(v: Double) = String.format(Locale.US, "%.1f", v).replace('.', ',')

fun duracion(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

fun diasDesde(t: Long): Int = ((System.currentTimeMillis() - t) / 86_400_000L).toInt().coerceAtLeast(0)

fun hace(t: Long): String {
    val dias = diasDesde(t)
    return when {
        dias == 0 -> "hoy " + formato("HH:mm", t)
        dias == 1 -> "ayer"
        dias < 14 -> "hace $dias días"
        dias < 60 -> "hace ${dias / 7} semanas"
        dias < 360 -> "hace ${dias / 30} meses"
        dias < 730 -> "hace un año"
        else -> "hace ${dias / 365} años"
    }
}

private fun frase(e: Item): String {
    val t = if (e.esCinta) e.title + (if (e.text.isNotBlank()) ": " + e.text else "") else e.title.ifBlank { e.text }
    return sinMarcas(com.sebas.solaris.Marcas.limpio(t).replace('\n', ' ')).take(110).trim()
}
