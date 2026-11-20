package com.sebas.solaris.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sebas.solaris.Casa
import com.sebas.solaris.Exportador
import com.sebas.solaris.Grabadora
import com.sebas.solaris.Item
import com.sebas.solaris.ServicioGrabacion
import com.sebas.solaris.Sonidos
import com.sebas.solaris.Transcriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/*
 * Grabar sobre el mar: el casete sube desde abajo, la cinta gira y al terminar se elige qué fue:
 * una Voz (ideas, poemas: se transcribe) o un Rumor (el sonido de la vida: solo se guarda).
 * El audio nunca sale del celular.
 */

private val Rojo = Color(0xFFD65C48)

/** Lo que acaba de terminar de grabarse y espera nombre. */
private class Toma(val id: String, val dur: Long, val inicio: Long)

@Composable
fun Grabacion() {
    val ctx = LocalContext.current
    var aviso by remember { mutableStateOf<String?>(null) }
    var toma by remember { mutableStateOf<Toma?>(null) }

    fun empezar() {
        val id = Item.nuevoId()
        if (!Grabadora.grabar(id, Casa.archivoCinta(id))) { aviso = "No se pudo usar el micrófono."; Nav.grabando = false }
        else ServicioGrabacion.iniciar(ctx)
    }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) empezar() else { aviso = "Solaris necesita el micrófono para grabar."; Nav.grabando = false }
    }

    // al abrirse, empieza a grabar (pidiendo permisos si hace falta)
    LaunchedEffect(Nav.grabando) {
        if (!Nav.grabando || Grabadora.grabando || toma != null) return@LaunchedEffect
        Grabadora.detenerReproduccion()
        val faltan = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltan += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) faltan += Manifest.permission.POST_NOTIFICATIONS
        if (faltan.isEmpty()) empezar() else permisos.launch(faltan.toTypedArray())
    }

    // cintas que terminaron con la estación cerrada (desde la notificación): se nombran al volver
    LaunchedEffect(Unit) {
        if (!Grabadora.grabando && toma == null) Casa.cintasPendientes().firstOrNull()?.let { (id, dur, ini) -> toma = Toma(id, dur, ini) }
    }

    fun detener() {
        val id = Grabadora.idActual
        val inicio = Grabadora.inicioGrabacion
        val dur = Grabadora.detenerGrabacion()
        ServicioGrabacion.terminar(ctx)
        Nav.grabando = false
        if (id != null) {
            if (dur < 600) Casa.archivoCinta(id).delete() else toma = Toma(id, dur, inicio)
        }
    }

    val visible = Nav.grabando && toma == null
    if (visible) BackHandler { detener() }

    AnimatedVisibility(visible, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
        Box(Modifier.fillMaxSize().background(Color(0xB802070F)).clickable(remember { MutableInteractionSource() }, null) {})
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.82f, stiffness = 260f)) { it / 2 } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(320)) { it / 2 } + fadeOut(tween(250)),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            Casete(Modifier.fillMaxWidth())
            Spacer(Modifier.height(36.dp))
            Text(reloj(Grabadora.posicion), color = Azules.Crema, fontFamily = Mono, fontSize = 52.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Senal(if (Grabadora.pausado) "en pausa" else "grabando · sigue aunque apagues la pantalla", color = Azules.Bruma)
            Spacer(Modifier.height(26.dp))
            Nivel(Modifier.fillMaxWidth().height(70.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Redondo(tam = 56.dp, fondo = Color(0x33B9C6CC), alTocar = { Sonidos.tecla(); Grabadora.pausarGrabacion() }) {
                    if (Grabadora.pausado) Text("▶", color = Azules.Crema, fontSize = 18.sp)
                    else Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(2) { Box(Modifier.size(4.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(Azules.Crema)) }
                    }
                }
                // detener: un cuadrado dentro del anillo rojo
                Box(
                    Modifier.presionable { Sonidos.tecla(); detener() }.size(84.dp).clip(CircleShape).border(3.dp, Rojo, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Rojo)) }
                Spacer(Modifier.size(56.dp))
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    Elegir(toma) { toma = null }

    val msg = aviso
    if (msg != null) {
        LaunchedEffect(msg) { delay(3200); aviso = null }
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Text(
                msg, color = Azules.Crema, fontFamily = Sans, fontSize = 14.sp,
                modifier = Modifier.padding(top = 20.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE60B1620)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** Al soltar: ¿qué fue esto? */
@Composable
private fun Elegir(toma: Toma?, alCerrar: () -> Unit) {
    val numero = Casa.cintas().size + 1
    var nombre by remember(toma) { mutableStateOf("") }
    var rumor by remember(toma) { mutableStateOf(false) }
    fun guardar() {
        val t = toma ?: return
        val n = nombre.trim().ifBlank { (if (rumor) "rumor " else "voz ") + "%03d".format(numero) }
        Casa.nuevaCinta(t.id, n, t.dur, t.inicio, rumor)
        Casa.quitarPendiente(t.id)
        if (!rumor) Transcriptor.lanzar(t.id) {}
        Sonidos.acople()
        alCerrar()
    }
    Hoja(toma != null, { guardar() }) {
        Text("¿Qué fue esto?", color = Tema.p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Opcion("Voz", "ideas, poemas · se transcribe", !rumor, Modifier.weight(1f)) { rumor = false }
            Opcion("Rumor", "el sonido de la vida · solo se guarda", rumor, Modifier.weight(1f)) { rumor = true }
        }
        Campo(nombre, { nombre = it }, if (rumor) "Nombre (lluvia en el techo, la cena del domingo…)" else "Nombre (poema del bus, idea para el corto…)")
        Pildora("Entregar al mar", Modifier.fillMaxWidth()) { guardar() }
        toma?.let { Senal(duracion(it.dur) + " · wav 48 khz · se queda en este celular") }
    }
}

@Composable
private fun Opcion(titulo: String, sub: String, sel: Boolean, modifier: Modifier, alTocar: () -> Unit) {
    val p = Tema.p
    val forma = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(forma).background(if (sel) p.boton else p.superficie)
            .clickable { Sonidos.tic(); alTocar() }.padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(titulo, color = if (sel) p.botonTexto else p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 18.sp)
        Text(sub, color = if (sel) p.botonTexto.copy(alpha = 0.75f) else p.texto2, fontFamily = Sans, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

// ---------------- escuchar ----------------

@Composable
fun Escuchar() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = Nav.escuchando
    val c = id?.let { Casa.porId(it) }
    var nombre by remember(id) { mutableStateOf(c?.title ?: "") }
    var confirmar by remember(id) { mutableStateOf(false) }
    var aviso by remember(id) { mutableStateOf<String?>(null) }
    val carpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        Casa.pausarCandado = false
        if (uri != null) scope.launch { aviso = "Exportando…"; aviso = Exportador.exportar(ctx, uri) }
    }
    LaunchedEffect(Grabadora.reproduciendo) {
        while (isActive && Grabadora.reproduciendo) { Grabadora.actualizar(); delay(200) }
    }
    fun cerrar() { Grabadora.detenerReproduccion(); Nav.escuchando = null }
    val p = Tema.p
    Hoja(c != null, { cerrar() }) {
        if (c == null) return@Hoja
        val propia = Grabadora.idActual == c.id && (Grabadora.reproduciendo || Grabadora.pausado)
        Senal((if (c.esRumor) "Rumor" else "Voz") + " · " + formato("d MMM yyyy · HH:mm", c.created))
        Text(c.title.ifBlank { "cinta sin nombre" }, color = p.texto, fontFamily = Serif, fontSize = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // la cinta: avance y controles
        val avance = if (propia && c.dur > 0) (Grabadora.posicion.toFloat() / c.dur).coerceIn(0f, 1f) else 0f
        Onda(c.id, Modifier.fillMaxWidth().height(34.dp), p.texto2.copy(alpha = 0.35f), avance, p.acento)
        Row(Modifier.fillMaxWidth()) {
            Senal(duracion(if (propia) Grabadora.posicion else 0L), Modifier.weight(1f))
            Senal(duracion(c.dur))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Enlace("−10 s") { if (propia) Grabadora.adelantar(-10_000) }
            Spacer(Modifier.width(18.dp))
            Redondo(tam = 64.dp, fondo = p.boton, alTocar = {
                Sonidos.tecla()
                when {
                    Grabadora.reproduciendo && propia -> Grabadora.pausarReproduccion()
                    Grabadora.pausado && propia -> Grabadora.pausarReproduccion()
                    !Casa.archivoCinta(c.id).exists() -> aviso = "El audio de esta cinta quedó en el otro celular."
                    else -> Grabadora.reproducir(c.id, Casa.archivoCinta(c.id))
                }
            }) {
                if (Grabadora.reproduciendo && propia) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(2) { Box(Modifier.size(4.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(p.botonTexto)) }
                } else Text("▶", color = p.botonTexto, fontSize = 20.sp)
            }
            Spacer(Modifier.width(18.dp))
            Enlace("+10 s") { if (propia) Grabadora.adelantar(10_000) }
        }
        when {
            c.id in Transcriptor.enCurso -> Parrafo("Transcribiendo tu voz…")
            c.text.isNotBlank() -> Text(
                c.text, color = p.texto, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 26.sp,
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            )
        }
        aviso?.let { Parrafo(it) }
        Campo(nombre, { nombre = it }, "Nombre")
        if (nombre.isNotBlank() && nombre.trim() != c.title) Pildora("Guardar nombre", Modifier.fillMaxWidth()) { Casa.renombrarCinta(c.id, nombre) }
        val transcribir = when {
            c.esRumor && c.text.isBlank() -> "Transcribir de todos modos"
            c.text.isBlank() -> "Transcribir mi voz"
            else -> "Transcribir de nuevo"
        }
        Pildora(transcribir, Modifier.fillMaxWidth(), primaria = false) { Transcriptor.lanzar(c.id) { aviso = it } }
        Pildora("Exportar todas las cintas a una carpeta", Modifier.fillMaxWidth(), primaria = false) { Casa.pausarCandado = true; carpeta.launch(null) }
        if (!confirmar) Pildora("Borrar cinta", Modifier.fillMaxWidth(), primaria = false) { confirmar = true }
        else Pildora("Sí, borrarla para siempre", Modifier.fillMaxWidth(), primaria = false, tinta = Color(0xFFE0745F)) {
            Grabadora.detenerReproduccion(); Casa.borrarCinta(c.id); Nav.escuchando = null
        }
    }
}

// ---------------- el casete y el nivel ----------------

/** Un casete negro dibujado a mano: etiqueta crema, carretes que giran mientras graba. */
@Composable
private fun Casete(modifier: Modifier) {
    val giro = rememberInfiniteTransition(label = "carretes")
    val angulo by giro.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "giro")
    val ang = if (Grabadora.pausado) 0f else angulo
    val numero = Casa.cintas().size + 1
    Box(modifier.aspectRatio(1.62f)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawRoundRect(Color(0xFF101214), Offset.Zero, size, CornerRadius(w * 0.05f))
            drawRoundRect(Color(0xFF2A2E33), Offset(6f, 6f), Size(w - 12f, h - 12f), CornerRadius(w * 0.045f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            // etiqueta
            val lx = w * 0.08f; val ly = h * 0.09f; val lw = w * 0.84f; val lh = h * 0.6f
            drawRoundRect(Color(0xFFE9E0CC), Offset(lx, ly), Size(lw, lh), CornerRadius(w * 0.015f))
            drawRect(Azules.Cianotipo, Offset(lx, ly + lh * 0.21f), Size(lw, lh * 0.025f))
            // ventana de los carretes
            val vx = lx + lw * 0.2f; val vy = ly + lh * 0.55f; val vw = lw * 0.6f; val vh = lh * 0.36f
            drawRoundRect(Color(0xFF141618), Offset(vx, vy), Size(vw, vh), CornerRadius(vh / 2))
            drawRect(Color(0xFF46342A), Offset(vx + vw * 0.28f, vy + vh * 0.3f), Size(vw * 0.44f, vh * 0.4f))
            val r = vh * 0.36f
            for (cx in listOf(vx + vw * 0.2f, vx + vw * 0.8f)) {
                val c = Offset(cx, vy + vh / 2)
                drawCircle(Color(0xFFE9E0CC), r, c)
                drawCircle(Color(0xFF141618), r * 0.42f, c)
                for (k in 0 until 6) {
                    val a = Math.toRadians((ang + k * 60f).toDouble())
                    drawLine(
                        Color(0xFF141618),
                        Offset(c.x + (r * 0.42f * cos(a)).toFloat(), c.y + (r * 0.42f * sin(a)).toFloat()),
                        Offset(c.x + (r * 0.8f * cos(a)).toFloat(), c.y + (r * 0.8f * sin(a)).toFloat()),
                        r * 0.16f, StrokeCap.Round,
                    )
                }
            }
            // la boca de abajo y los tornillos
            val bw = w * 0.56f
            val boca = androidx.compose.ui.graphics.Path().apply {
                moveTo(w / 2 - bw / 2, h); lineTo(w / 2 - bw / 2 + w * 0.05f, h * 0.8f)
                lineTo(w / 2 + bw / 2 - w * 0.05f, h * 0.8f); lineTo(w / 2 + bw / 2, h); close()
            }
            drawPath(boca, Color(0xFF181A1C))
            for ((x, y) in listOf(0.035f to 0.06f, 0.965f to 0.06f, 0.035f to 0.94f, 0.965f to 0.94f)) {
                drawCircle(Color(0xFF34383C), w * 0.012f, Offset(w * x, h * y))
            }
        }
        // la letra de la etiqueta
        Column(Modifier.fillMaxSize().padding(start = 0.dp)) {
            Row(Modifier.fillMaxWidth().fillMaxHeight(0.19f).padding(horizontal = 0.dp), verticalAlignment = Alignment.Bottom) {
                Spacer(Modifier.fillMaxWidth(0.12f))
                Text("SOLARIS", color = Azules.Prusia, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 5.sp, modifier = Modifier.weight(1f))
                Text("C-60", color = Azules.Cianotipo, fontFamily = Mono, fontSize = 11.sp)
                Spacer(Modifier.fillMaxWidth(0.14f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Spacer(Modifier.fillMaxWidth(0.12f))
                Column {
                    Text("registro %03d".format(numero), color = Azules.Prusia, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 22.sp)
                    Text(
                        formato("d MMM · HH:mm", System.currentTimeMillis()).uppercase() + " · " + Corriente.nombreVisible,
                        color = Color(0xFF3C5A6E), fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** El nivel del micrófono como una onda que avanza. */
@Composable
private fun Nivel(modifier: Modifier) {
    val historia = remember { mutableStateListOf<Float>().apply { repeat(48) { add(0.04f) } } }
    LaunchedEffect(Unit) {
        while (isActive) {
            historia.removeAt(0)
            historia.add(if (Grabadora.pausado) 0.04f else Grabadora.nivel.coerceIn(0.04f, 1f))
            delay(70)
        }
    }
    Canvas(modifier) {
        val n = historia.size
        val paso = size.width / n
        historia.forEachIndexed { k, v ->
            val alto = size.height * v
            drawRoundRect(
                if (k > n - 6) Azules.Crema else Azules.Espuma.copy(alpha = 0.55f),
                Offset(k * paso, (size.height - alto) / 2), Size(paso * 0.5f, alto), CornerRadius(paso),
            )
        }
    }
}

private fun reloj(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
