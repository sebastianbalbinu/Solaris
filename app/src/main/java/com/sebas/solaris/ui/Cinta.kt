package com.sebas.solaris.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.Grabadora
import com.sebas.solaris.Item
import com.sebas.solaris.R
import com.sebas.solaris.Exportador
import com.sebas.solaris.ServicioGrabacion
import com.sebas.solaris.Sonidos
import com.sebas.solaris.Transcriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ANCHO = 370f
private const val ALTO = 748f

/** La pantalla convertida en grabadora. Medidas en unidades del dibujo (370 × 748). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Cinta() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cintas = Casa.cintas()
    // 0 = cinta nueva; 1..n = cintas guardadas, de la más nueva a la más vieja
    var posicion by remember { mutableIntStateOf(0) }
    var nombrar by remember { mutableStateOf<Triple<String, Long, Long>?>(null) }
    var opciones by remember { mutableStateOf<Item?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Nav.cintaElegida) {
        val id = Nav.cintaElegida ?: return@LaunchedEffect
        val i = cintas.indexOfFirst { it.id == id }
        if (i >= 0) posicion = i + 1
        Nav.cintaElegida = null
    }
    LaunchedEffect(Grabadora.reproduciendo) {
        while (isActive && Grabadora.reproduciendo) { Grabadora.actualizar(); delay(200) }
    }
    if (posicion > cintas.size) posicion = cintas.size
    val actual: Item? = if (posicion == 0) null else cintas.getOrNull(posicion - 1)
    val numeroNueva = cintas.size + 1

    // cintas que terminaron con la estación cerrada (desde la notificación): se nombran al volver
    LaunchedEffect(Unit) {
        if (!Grabadora.grabando && nombrar == null) Casa.cintasPendientes().firstOrNull()?.let { nombrar = it }
    }

    fun empezarGrabacion() {
        val id = Item.nuevoId()
        if (!Grabadora.grabar(id, Casa.archivoCinta(id))) aviso = "No se pudo usar el micrófono."
        else ServicioGrabacion.iniciar(ctx)
    }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) empezarGrabacion() else aviso = "Solaris necesita el micrófono para grabar."
    }
    val carpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        Casa.pausarCandado = false
        if (uri != null) scope.launch { aviso = "Exportando…"; aviso = Exportador.exportar(ctx, uri) }
    }

    fun transcribir(id: String) {
        Transcriptor.lanzar(id) { aviso = it }
    }

    fun rec() {
        if (Grabadora.grabando) return
        Grabadora.detenerReproduccion()
        posicion = 0
        val faltan = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltan += Manifest.permission.RECORD_AUDIO
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) faltan += Manifest.permission.POST_NOTIFICATIONS
        if (faltan.isEmpty()) empezarGrabacion() else permisos.launch(faltan.toTypedArray())
    }
    fun stop() {
        if (Grabadora.grabando) {
            val id = Grabadora.idActual
            val inicio = Grabadora.inicioGrabacion
            val dur = Grabadora.detenerGrabacion()
            ServicioGrabacion.terminar(ctx)
            if (id != null) {
                if (dur < 600) Casa.archivoCinta(id).delete()
                else nombrar = Triple(id, dur, inicio)
            }
        } else Grabadora.detenerReproduccion()
    }
    fun play() {
        val c = actual ?: return
        if (Grabadora.grabando) return
        if (!Casa.archivoCinta(c.id).exists()) { aviso = "El audio de esta cinta quedó en el otro celular."; return }
        Grabadora.reproducir(c.id, Casa.archivoCinta(c.id))
    }
    fun pausa() {
        when {
            Grabadora.grabando -> Grabadora.pausarGrabacion()
            Grabadora.reproduciendo -> Grabadora.pausarReproduccion()
            Grabadora.pausado && actual != null -> play()
        }
    }
    fun rew() {
        if (Grabadora.grabando) return
        if (Grabadora.reproduciendo) Grabadora.adelantar(-10_000)
        else if (posicion < cintas.size) { Grabadora.detenerReproduccion(); posicion++ }
    }
    fun ff() {
        if (Grabadora.grabando) return
        if (Grabadora.reproduciendo) Grabadora.adelantar(10_000)
        else if (posicion > 0) { Grabadora.detenerReproduccion(); posicion-- }
    }

    val girando = Grabadora.grabando && !Grabadora.pausado || Grabadora.reproduciendo
    val transicion = rememberInfiniteTransition(label = "carretes")
    val giro by transicion.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "giro")
    val parpadeo by transicion.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "rec")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(top = 8.dp, bottom = 84.dp), contentAlignment = Alignment.TopCenter) {
            val ancho: Dp = minOf(maxWidth - 16.dp, maxHeight * (ANCHO / ALTO))
            val u = ancho.value / ANCHO
            fun d(v: Float): Dp = (v * u).dp

            Box(Modifier.size(ancho, d(ALTO))) {
                Image(painterResource(R.drawable.grabadora), "Grabadora de casete", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

                // carretes: la cinta pasa del izquierdo al derecho
                val avance = when {
                    Grabadora.grabando -> (Grabadora.posicion / (30 * 60_000f)).coerceIn(0f, 0.95f)
                    actual != null && Grabadora.idActual == actual.id && actual.dur > 0 && (Grabadora.reproduciendo || Grabadora.pausado) ->
                        (Grabadora.posicion.toFloat() / actual.dur).coerceIn(0f, 1f)
                    else -> 0f
                }
                Carrete(d(138f), d(463f), d(17f), d(17f + 9f * (1 - avance)), if (girando) giro else 0f)
                Carrete(d(232f), d(463f), d(17f), d(17f + 9f * avance), if (girando) giro * 1.3f else 0f)

                // la etiqueta del casete
                val tinta = Color(0xFF2C2418)
                val tinta2 = Color(0xFF5A4A30)
                val titulo = actual?.title?.ifBlank { "cinta sin nombre" } ?: "registro %03d".format(numeroNueva)
                Text(titulo, color = tinta, fontFamily = Serif, fontSize = (19 * u).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(d(66f), d(383f)).width(d(196f)))
                Text("LADO A", color = tinta2, fontFamily = Mono, fontSize = (8 * u).sp, modifier = Modifier.offset(d(276f), d(389f)))
                val cuando = actual?.created ?: System.currentTimeMillis()
                Text(
                    formato("dd.MM.yyyy · HH:mm", cuando) + " · DRAKE: " + Clima.mar.nombre,
                    color = tinta2, fontFamily = Mono, fontSize = (7.5f * u).sp, maxLines = 1,
                    modifier = Modifier.offset(d(66f), d(407f)).width(d(250f)),
                )
                val ms = when {
                    Grabadora.grabando -> Grabadora.posicion
                    actual != null && Grabadora.idActual == actual.id && (Grabadora.reproduciendo || Grabadora.pausado) -> Grabadora.posicion
                    actual != null -> actual.dur
                    else -> 0L
                }
                Text(reloj(ms), color = tinta, fontFamily = Mono, fontSize = (15 * u).sp, letterSpacing = (2 * u).sp, modifier = Modifier.offset(d(66f), d(502f)))
                Text("WAV · 48 kHz · EN ESTE CELULAR", color = tinta2, fontFamily = Mono, fontSize = (7 * u).sp, modifier = Modifier.offset(d(176f), d(507f)))
                val pie = when {
                    Grabadora.grabando && Grabadora.pausado -> "en pausa…"
                    Grabadora.grabando -> "grabando… (sigue aunque apagues la pantalla)"
                    actual != null && actual.id in Transcriptor.enCurso -> "transcribiendo tu voz…"
                    actual == null -> "REC para grabar · ◀◀ ▶▶ para cambiar de cinta"
                    actual.text.isNotBlank() -> "“" + actual.text.take(60) + "…”"
                    else -> "${posicion} de ${cintas.size} · mantén presionado para renombrar"
                }
                Text(pie, color = Color(0xFF4A3C28), fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = (13 * u).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(d(66f), d(525f)).width(d(250f)))

                // tocar o mantener la etiqueta
                Box(
                    Modifier.offset(d(44f), d(372f)).size(d(282f), d(178f))
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { if (actual != null && !Grabadora.grabando) { Sonidos.tecla(); if (Grabadora.reproduciendo) Grabadora.pausarReproduccion() else play() } },
                            onLongClick = { if (actual != null && !Grabadora.grabando) opciones = actual },
                        )
                )

                // luz de REC
                if (Grabadora.grabando) {
                    val a = if (Grabadora.pausado) parpadeo else (0.6f + Grabadora.nivel * 0.4f)
                    Canvas(Modifier.offset(d(146f), d(583f)).size(d(28f))) {
                        drawCircle(Color(0xFFE0745F).copy(alpha = 0.25f * a), size.minDimension / 2)
                        drawCircle(Color(0xFFE0745F).copy(alpha = a), size.minDimension / 7)
                    }
                }

                // teclas
                Tecla(d(22f), d(652f), d(52f), d(72f), Grabadora.grabando) { rec() }
                Tecla(d(78f), d(652f), d(52f), d(72f), Grabadora.reproduciendo) { play() }
                Tecla(d(134f), d(648f), d(52f), d(76f), false) { rew() }
                Tecla(d(190f), d(648f), d(52f), d(76f), false) { ff() }
                Tecla(d(246f), d(648f), d(52f), d(76f), false) { stop() }
                Tecla(d(302f), d(648f), d(46f), d(76f), Grabadora.pausado) { pausa() }
            }
        }

        val msg = aviso
        if (msg != null) {
            LaunchedEffect(msg) { delay(3200); aviso = null }
            Text(
                msg, color = Color(0xFFECE6DA), fontFamily = Sans, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xE6202023)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }

        NombrarCinta(nombrar, numeroNueva) { id ->
            nombrar = null
            if (id != null) { Casa.quitarPendiente(id); transcribir(id) }
        }
        OpcionesCinta(
            opciones,
            alTranscribir = { id -> transcribir(id) },
            alExportar = { Casa.pausarCandado = true; carpeta.launch(null) },
        ) { opciones = null }
    }
}

private fun reloj(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

@Composable
private fun Carrete(x: Dp, y: Dp, r: Dp, rollo: Dp, angulo: Float) {
    Canvas(Modifier.offset(x - rollo, y - rollo).size(rollo * 2)) {
        drawCircle(Color(0xFF3B2C1E), size.minDimension / 2)
    }
    Canvas(Modifier.offset(x - r, y - r).size(r * 2).rotate(angulo)) {
        val c = Offset(size.width / 2, size.height / 2)
        val rad = size.minDimension / 2
        drawCircle(Color(0xFFE6DAC4), rad, c)
        drawCircle(Color(0xFF2B2118), rad * 0.35f, c)
        val g = rad * 0.17f
        drawLine(Color(0xFF2B2118), Offset(c.x, c.y - rad * 0.82f), Offset(c.x, c.y - rad * 0.5f), g, StrokeCap.Round)
        drawLine(Color(0xFF2B2118), Offset(c.x, c.y + rad * 0.5f), Offset(c.x, c.y + rad * 0.82f), g, StrokeCap.Round)
        drawLine(Color(0xFF2B2118), Offset(c.x - rad * 0.82f, c.y), Offset(c.x - rad * 0.5f, c.y), g, StrokeCap.Round)
        drawLine(Color(0xFF2B2118), Offset(c.x + rad * 0.5f, c.y), Offset(c.x + rad * 0.82f, c.y), g, StrokeCap.Round)
    }
}

/** Una tecla de la grabadora: suena al tocarla y se ve hundida mientras está activa. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tecla(x: Dp, y: Dp, w: Dp, h: Dp, hundida: Boolean, alTocar: () -> Unit) {
    var presionada by remember { mutableStateOf(false) }
    Box(
        Modifier.offset(x, y).size(w, h).clip(RoundedCornerShape(4.dp))
            .background(if (hundida || presionada) Color(0x66000000) else Color.Transparent)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { Sonidos.tecla(); alTocar() },
            )
    ) {
        if (hundida) Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55000000)))
    }
}

@Composable
private fun NombrarCinta(dato: Triple<String, Long, Long>?, numero: Int, alCerrar: (String?) -> Unit) {
    var nombre by remember(dato) { mutableStateOf("registro %03d".format(numero)) }
    val guardar = {
        val d = dato
        if (d != null) Casa.nuevaCinta(d.first, nombre.trim().ifBlank { "registro %03d".format(numero) }, d.second, d.third)
        alCerrar(d?.first)
    }
    Hoja(dato != null, { guardar() }) {
        Text("¿Cómo se llama esta cinta?", color = Tema.p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Parrafo("Por ejemplo: lluvia agosto, calles centro, memorias extrañas.")
        Campo(nombre, { nombre = it }, "Nombre")
        Pildora("Guardar cinta", Modifier.fillMaxWidth()) { guardar() }
    }
}

@Composable
private fun OpcionesCinta(c: Item?, alTranscribir: (String) -> Unit, alExportar: () -> Unit, alCerrar: () -> Unit) {
    var nombre by remember(c) { mutableStateOf(c?.title ?: "") }
    var confirmar by remember(c) { mutableStateOf(false) }
    Hoja(c != null, alCerrar) {
        Text("Esta cinta", color = Tema.p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Campo(nombre, { nombre = it }, "Nombre")
        Pildora("Guardar nombre", Modifier.fillMaxWidth(), habilitada = nombre.isNotBlank()) { c?.let { Casa.renombrarCinta(it.id, nombre) }; alCerrar() }
        Pildora(if (c?.text.isNullOrBlank()) "Transcribir mi voz" else "Transcribir de nuevo", Modifier.fillMaxWidth(), primaria = false) {
            c?.let { alTranscribir(it.id) }; alCerrar()
        }
        Pildora("Exportar todas las cintas a una carpeta", Modifier.fillMaxWidth(), primaria = false) { alCerrar(); alExportar() }
        if (!confirmar) Pildora("Borrar cinta", Modifier.fillMaxWidth(), primaria = false) { confirmar = true }
        else Pildora("Sí, borrarla para siempre", Modifier.fillMaxWidth(), primaria = false) {
            c?.let { Grabadora.detenerReproduccion(); Casa.borrarCinta(it.id) }; alCerrar()
        }
    }
}
