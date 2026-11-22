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
        if (!Grabadora.grabar(id, Casa.archivoCinta(id))) aviso = "No se pudo usar el micrófono."
        else ServicioGrabacion.iniciar(ctx)
    }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) empezar() else aviso = "Solaris necesita el micrófono para grabar."
    }

    // REC: empieza a grabar cuando uno quiere (pidiendo permisos si hace falta), nunca solo al abrir
    fun rec() {
        if (Grabadora.grabando || toma != null) return
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
    fun cerrar() { Nav.grabando = false; Nav.alGrabar = null }
    if (visible) BackHandler { if (Grabadora.grabando) detener() else cerrar() }

    AnimatedVisibility(visible, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
        Box(Modifier.fillMaxSize().background(Color(0xA602070F)).clickable(remember { MutableInteractionSource() }, null) { if (!Grabadora.grabando) cerrar() })
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 260f)) { it / 2 } + fadeIn(tween(250)),
        exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 380f)) { it / 2 } + fadeOut(tween(250)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Deck(alRec = { Sonidos.tecla(); rec() }, alStop = { Sonidos.tecla(); if (Grabadora.grabando) detener() else cerrar() })
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
        Nav.alGrabar?.invoke(t.id)
        Nav.alGrabar = null
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
        val giro = carretes(propia && Grabadora.reproduciendo)
        CaseteDibujo(
            Modifier.fillMaxWidth(0.78f).align(Alignment.CenterHorizontally), c.title.ifBlank { if (c.esRumor) "rumor" else "voz" },
            (if (c.esRumor) "RUMOR · " else "VOZ · ") + duracion(c.dur) + " · " + formato("d MMM", c.created).uppercase(),
            giro = giro, avance = avance, rumor = c.esRumor,
        )
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

/**
 * El deck: panel azul con tornillos, la bahía con el casete adentro, el contador, dos vúmetros con aguja
 * y las teclas de verdad (REC, PAUSA, STOP). No graba hasta que uno toca REC.
 */
@Composable
private fun Deck(alRec: () -> Unit, alStop: () -> Unit) {
    val graba = Grabadora.grabando
    val pausa = Grabadora.pausado
    val giro = carretes(graba && !pausa, 2000)
    // la aguja tiene inercia: sube rápido y cae despacio, como las de verdad
    var aguja by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(graba, pausa) {
        while (isActive) {
            val meta = if (graba && !pausa) (Grabadora.nivel * 1.25f).coerceIn(0f, 1f) else 0f
            aguja += (meta - aguja) * (if (meta > aguja) 0.45f else 0.12f)
            delay(30)
        }
    }
    val numero = Casa.cintas().size + 1
    PanelDeck(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SOLARIS", color = TintaDeck, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 4.sp)
                Text("CASSETTE DECK · TC-58", color = TintaDeck.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (Nav.alGrabar != null) "SE PEGA EN LA NOTA" else "AL MAR", color = TintaDeck.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.5.sp)
                Text("AUTO STOP", color = TintaDeck.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        // la bahía: marco de aluminio y el casete adentro
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFD5DAE1), Color(0xFFA9AFB8))))
                .padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF15181E)).padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            CaseteDibujo(
                Modifier.fillMaxWidth(), "registro %03d".format(numero),
                formato("d MMM · HH:mm", System.currentTimeMillis()).uppercase() + " · " + Corriente.nombreVisible,
                giro = giro, avance = (Grabadora.posicion / 3_600_000f).coerceIn(0f, 0.95f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Vumetro(Modifier.weight(1f), aguja, "L")
            Vumetro(Modifier.weight(1f), (aguja * 0.94f), "R")
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ContadorDeck(reloj(if (graba) Grabadora.posicion else 0L), graba && !pausa)
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    !graba -> "toca REC cuando quieras"
                    pausa -> "en pausa"
                    else -> "grabando · sigue con la pantalla apagada"
                },
                color = TintaDeck.copy(alpha = 0.75f), fontFamily = Sans, fontSize = 13.sp, lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeclaDeck("RECORD", graba, Modifier.weight(1f), habilitada = !graba, alTocar = alRec) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(RojoGrabar))
            }
            TeclaDeck("PAUSE", pausa, Modifier.weight(1f), habilitada = graba, alTocar = { Sonidos.tecla(); Grabadora.pausarGrabacion() }) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(2) { Box(Modifier.size(4.dp, 15.dp).background(Color(0xFF2A2E35))) } }
            }
            TeclaDeck("STOP", false, Modifier.weight(1f), alTocar = alStop) {
                Box(Modifier.size(14.dp).background(Color(0xFF2A2E35)))
            }
        }
        Spacer(Modifier.height(6.dp))
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
