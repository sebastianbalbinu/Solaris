package com.sebas.solaris.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Casa
import com.sebas.solaris.Grabadora
import com.sebas.solaris.Item
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Los casetes de Solaris: cada voz y cada rumor es una cinta de verdad, con su etiqueta escrita a mano,
 * la ventanita con los carretes (que giran solo cuando suena, y se van llenando de un lado al otro)
 * y la cinta café entre ellos.
 */

private val Carcasa = Color(0xFF1B1E24)
private val CarcasaFilo = Color(0xFF353A43)
private val Etiqueta = Color(0xFFEDE4CF)
private val Franja = Color(0xFF1F3A5E)
private val Tinta = Color(0xFF16263A)
private val CintaCafe = Color(0xFF4A3426)
private val RojoRec = Color(0xFFD65C48)

/**
 * El casete dibujado. [giro] son los grados de los carretes; [avance] (0..1) cuánta cinta pasó al carrete
 * de la derecha. Con [conLetra] escribe el título y el pie en la etiqueta.
 */
@Composable
fun CaseteDibujo(
    modifier: Modifier,
    titulo: String,
    pie: String,
    giro: Float = 0f,
    avance: Float = 0f,
    conLetra: Boolean = true,
    rumor: Boolean = false,
) {
    Box(modifier.aspectRatio(1.58f)) {
        Canvas(Modifier.fillMaxSize()) { dibujarCasete(giro, avance, rumor) }
        if (conLetra) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth
                Column(Modifier.padding(start = w * 0.11f, end = w * 0.11f, top = w * 0.075f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            titulo, color = Tinta, fontFamily = Mano, fontSize = (w.value * 0.082f).sp, lineHeight = (w.value * 0.085f).sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(if (rumor) "B" else "A", color = Franja, fontFamily = Mono, fontSize = (w.value * 0.045f).sp)
                    }
                    Text(
                        pie, color = Tinta.copy(alpha = 0.7f), fontFamily = Mono, fontSize = (w.value * 0.03f).sp,
                        letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun DrawScope.dibujarCasete(giro: Float, avance: Float, rumor: Boolean) {
    val w = size.width; val h = size.height
    // la carcasa, con su filo y la sombra de abajo
    drawRoundRect(Color(0x33000000), Offset(w * 0.01f, h * 0.025f), size, CornerRadius(w * 0.045f))
    drawRoundRect(Carcasa, Offset.Zero, size, CornerRadius(w * 0.045f))
    drawRoundRect(CarcasaFilo, Offset(w * 0.012f, w * 0.012f), Size(w - w * 0.024f, h - w * 0.024f), CornerRadius(w * 0.038f), style = Stroke(w * 0.006f))
    // la etiqueta
    val lx = w * 0.075f; val ly = h * 0.08f; val lw = w * 0.85f; val lh = h * 0.62f
    drawRoundRect(Etiqueta, Offset(lx, ly), Size(lw, lh), CornerRadius(w * 0.012f))
    // renglones para escribir y la franja de color
    for (k in 0 until 2) drawLine(Tinta.copy(alpha = 0.12f), Offset(lx + lw * 0.04f, ly + lh * (0.2f + k * 0.13f)), Offset(lx + lw * 0.96f, ly + lh * (0.2f + k * 0.13f)), w * 0.003f)
    drawRect(if (rumor) Color(0xFF7A4A2E) else Franja, Offset(lx, ly + lh * 0.42f), Size(lw, lh * 0.075f))
    // la ventana
    val vx = lx + lw * 0.2f; val vy = ly + lh * 0.55f; val vw = lw * 0.6f; val vh = lh * 0.36f
    drawRoundRect(Color(0xFF0F1115), Offset(vx, vy), Size(vw, vh), CornerRadius(vh / 2))
    val rMax = vh * 0.46f
    val rHub = vh * 0.2f
    // la cinta enrollada: pasa de la izquierda a la derecha
    val a = avance.coerceIn(0f, 1f)
    val rIzq = sqrt(rHub * rHub + (rMax * rMax - rHub * rHub) * (1f - a))
    val rDer = sqrt(rHub * rHub + (rMax * rMax - rHub * rHub) * a)
    val ci = Offset(vx + vw * 0.22f, vy + vh / 2)
    val cd = Offset(vx + vw * 0.78f, vy + vh / 2)
    drawRect(CintaCafe, Offset(ci.x, vy + vh * 0.78f), Size(cd.x - ci.x, vh * 0.06f))
    drawCircle(CintaCafe, rIzq, ci)
    drawCircle(CintaCafe, rDer, cd)
    // los ejes blancos con dientes, girando
    for (c in listOf(ci, cd)) {
        drawCircle(Etiqueta, rHub, c)
        drawCircle(Color(0xFF0F1115), rHub * 0.45f, c)
        for (k in 0 until 6) {
            val ang = Math.toRadians((giro + k * 60f).toDouble())
            drawLine(
                Color(0xFF0F1115),
                Offset(c.x + (rHub * 0.42f * cos(ang)).toFloat(), c.y + (rHub * 0.42f * sin(ang)).toFloat()),
                Offset(c.x + (rHub * 0.78f * cos(ang)).toFloat(), c.y + (rHub * 0.78f * sin(ang)).toFloat()),
                rHub * 0.16f, StrokeCap.Round,
            )
        }
    }
    // el brillo del plástico de la ventana
    drawRoundRect(Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color.Transparent), vy, vy + vh * 0.6f), Offset(vx, vy), Size(vw, vh * 0.5f), CornerRadius(vh / 2))
    // la boca de abajo, los agujeros y los tornillos
    val bw = w * 0.56f
    val boca = Path().apply {
        moveTo(w / 2 - bw / 2, h); lineTo(w / 2 - bw / 2 + w * 0.05f, h * 0.8f)
        lineTo(w / 2 + bw / 2 - w * 0.05f, h * 0.8f); lineTo(w / 2 + bw / 2, h); close()
    }
    drawPath(boca, Color(0xFF14161A))
    for (x in listOf(0.38f, 0.62f)) drawCircle(Color(0xFF0A0B0D), w * 0.018f, Offset(w * x, h * 0.9f))
    for ((x, y) in listOf(0.035f to 0.06f, 0.965f to 0.06f, 0.035f to 0.94f, 0.965f to 0.94f, 0.5f to 0.94f)) {
        drawCircle(Color(0xFF41464F), w * 0.011f, Offset(w * x, h * y))
    }
}

/** Gira mientras [vivo]: devuelve el ángulo de los carretes. */
@Composable
fun carretes(vivo: Boolean, vueltaMs: Int = 2400): Float {
    var ang by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(vivo) {
        var antes = 0L
        while (isActive && vivo) {
            withFrameNanos { t ->
                if (antes != 0L) ang = (ang + (t - antes) / 1_000_000f / vueltaMs * 360f) % 360f
                antes = t
            }
        }
    }
    return ang
}

/** Un casete chiquito y quieto, para la orilla. */
@Composable
fun CaseteMini(c: Item, modifier: Modifier) {
    CaseteDibujo(modifier, c.title, "", conLetra = false, rumor = c.esRumor)
}

/** Los tamaños de un casete pegado en la nota. */
object TamCasete {
    const val GRANDE = ""
    const val MEDIANO = "mediano"
    const val MINI = "mini"
    const val ESCONDIDO = "escondido"
    val todos = listOf(GRANDE to "Grande", MEDIANO to "Mediano", MINI to "Mini", ESCONDIDO to "Escondido")
}

/**
 * Una voz pegada en la nota, como casete que se escucha ahí mismo: tocarlo lo hace sonar (y girar).
 * [tam] es grande, mediano, mini o escondido (asoma bajo el papel hasta que se saca).
 * [alOpciones] (en el editor) abre su tamaño y lo demás.
 */
@Composable
fun CasetePegado(c: Item, tam: String, alOpciones: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = Tema.p
    val propia = Grabadora.idActual == c.id
    val suena = propia && Grabadora.reproduciendo
    LaunchedEffect(suena) { while (isActive && suena) { Grabadora.actualizar(); delay(120) } }
    val giro = carretes(suena)
    val avance = if (propia && c.dur > 0) (Grabadora.posicion.toFloat() / c.dur).coerceIn(0f, 1f) else 0f
    fun tocar() {
        Sonidos.tecla()
        when {
            suena -> Grabadora.pausarReproduccion()
            propia && Grabadora.pausado -> Grabadora.reproducir(c.id, Casa.archivoCinta(c.id))
            !Casa.archivoCinta(c.id).exists() -> android.widget.Toast.makeText(ctx, "El audio de esta cinta quedó en el otro celular.", android.widget.Toast.LENGTH_LONG).show()
            else -> Grabadora.reproducir(c.id, Casa.archivoCinta(c.id))
        }
    }
    var afuera by remember(c.id, tam) { mutableStateOf(tam != TamCasete.ESCONDIDO) }
    val ancho = when (tam) { TamCasete.MEDIANO -> 0.66f; TamCasete.MINI -> 0.4f; TamCasete.ESCONDIDO -> 0.66f; else -> 0.92f }
    val pie = (if (c.esRumor) "RUMOR · " else "VOZ · ") + duracion(c.dur) + " · " + formato("d MMM", c.created).uppercase()
    Column(
        modifier.fillMaxWidth().padding(vertical = 10.dp).animateContentSize(spring(dampingRatio = 0.85f, stiffness = 300f)),
        horizontalAlignment = if (tam == TamCasete.MINI) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (!afuera) {
            // escondida: solo asoma el lomo del casete bajo el papel
            Row(
                Modifier.clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                    .clickable { Sonidos.tic(); afuera = true }
                    .background(Brush.verticalGradient(listOf(p.texto2.copy(alpha = 0.14f), Carcasa.copy(alpha = 0.92f))))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconoCasete(Etiqueta, 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("una voz escondida · sácala", color = Etiqueta.copy(alpha = 0.85f), fontFamily = Mano, fontSize = 17.sp)
            }
            return@Column
        }
        Box(
            Modifier.fillMaxWidth(ancho)
                .graphicsLayer { rotationZ = (c.id.hashCode() % 5) * 0.35f }
                .clickable(remember { MutableInteractionSource() }, null) { tocar() },
        ) {
            CaseteDibujo(
                Modifier.fillMaxWidth(), c.title.ifBlank { if (c.esRumor) "rumor" else "voz" }, pie,
                giro = giro, avance = avance, conLetra = tam != TamCasete.MINI, rumor = c.esRumor,
            )
        }
        if (tam != TamCasete.MINI) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(ancho), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(p.boton).clickable { tocar() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (suena) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(2) { Box(Modifier.size(3.dp, 12.dp).background(p.botonTexto)) }
                    } else Text("▶", color = p.botonTexto, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Senal(duracion(if (propia) Grabadora.posicion else 0L) + " / " + duracion(c.dur), color = p.texto2, modifier = Modifier.weight(1f))
                if (tam == TamCasete.ESCONDIDO) Enlace("guardar", color = p.texto2) { afuera = false }
                if (alOpciones != null) Box(Modifier.size(34.dp).clip(CircleShape).clickable { Sonidos.tic(); alOpciones() }, contentAlignment = Alignment.Center) {
                    IconoPuntos(p.texto2)
                }
            }
        } else if (alOpciones != null) {
            Enlace("opciones", color = p.texto2, modifier = Modifier.padding(top = 2.dp)) { alOpciones() }
        }
    }
}

// ---------------- el deck de grabar ----------------

private val Panel = Color(0xFF1A2540)
private val PanelClaro = Color(0xFF233154)
private val Aluminio = Color(0xFFC9CED6)
private val Serigrafia = Color(0xFFC9D2E3)

/** Un tornillo del panel. */
private fun DrawScope.tornillo(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFFD9DDE3), Color(0xFF6B717B)), c, r), r, c)
    drawLine(Color(0xFF3A3F47), Offset(c.x - r * 0.6f, c.y), Offset(c.x + r * 0.6f, c.y), r * 0.25f)
}

/** El panel azul del deck, con sus tornillos. */
@Composable
fun PanelDeck(modifier: Modifier, contenido: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(PanelClaro, Panel)))
            .drawBehind {
                val r = 5.dp.toPx()
                val m = 11.dp.toPx()
                listOf(Offset(m, m), Offset(size.width - m, m), Offset(m, size.height - m), Offset(size.width - m, size.height - m)).forEach { tornillo(it, r) }
            }
            .padding(horizontal = 22.dp, vertical = 18.dp),
        content = contenido,
    )
}

/** Una aguja de vúmetro: de −20 a +3 dB, con su zona roja. [nivel] 0..1. */
@Composable
fun Vumetro(modifier: Modifier, nivel: Float, rotulo: String) {
    Box(modifier.aspectRatio(1.55f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF111318)).padding(5.dp)) {
        Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)).background(Color(0xFFE9DFC6))) {
            val w = size.width; val h = size.height
            val c = Offset(w / 2, h * 1.08f)
            val r = h * 0.92f
            val desde = -50f; val hasta = 50f
            // la escala
            for (k in 0..10) {
                val a = Math.toRadians((desde + (hasta - desde) * k / 10f - 90f).toDouble())
                val rojo = k >= 8
                val largo = if (k % 2 == 0) 0.13f else 0.08f
                drawLine(
                    if (rojo) Color(0xFFC8442E) else Color(0xFF2A2A2A),
                    Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()),
                    Offset(c.x + (r * (1 - largo) * cos(a)).toFloat(), c.y + (r * (1 - largo) * sin(a)).toFloat()),
                    w * 0.008f,
                )
            }
            drawArc(Color(0xFFC8442E), -90f + 30f, 20f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w * 0.012f))
            // la aguja
            val ang = Math.toRadians((desde + (hasta - desde) * nivel.coerceIn(0f, 1f) - 90f).toDouble())
            drawLine(Color(0xFF1B1B1B), c, Offset(c.x + (r * 1.02f * cos(ang)).toFloat(), c.y + (r * 1.02f * sin(ang)).toFloat()), w * 0.012f, StrokeCap.Round)
            // la luz cálida de atrás
            drawRect(Brush.radialGradient(listOf(Color(0x33FFB347), Color.Transparent), Offset(w / 2, h), w * 0.7f))
        }
        Text(rotulo, color = Color(0xFF2A2A2A), fontFamily = Mono, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center).padding(top = 14.dp))
    }
}

/** Una tecla del deck: sube y baja como las de verdad; [abajo] la deja hundida. */
@Composable
fun TeclaDeck(rotulo: String, abajo: Boolean, modifier: Modifier = Modifier, habilitada: Boolean = true, alTocar: () -> Unit, icono: @Composable () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(rotulo, color = Serigrafia, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        val forma = RoundedCornerShape(5.dp)
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(forma).background(Color(0xFF0D1220)).padding(top = if (abajo) 5.dp else 0.dp, bottom = if (abajo) 0.dp else 5.dp)
                .clip(forma)
                .background(Brush.verticalGradient(if (abajo) listOf(Color(0xFFAEB3BB), Color(0xFF9097A1)) else listOf(Color(0xFFE6E9ED), Color(0xFFB8BDC5))))
                .border(1.dp, Color(0x55FFFFFF), forma)
                .clickable(enabled = habilitada) { alTocar() },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.graphicsLayer { alpha = if (habilitada) 1f else 0.4f }) { icono() } }
    }
}

/** El contador del deck: cifras claras en una ventanita oscura. */
@Composable
fun ContadorDeck(texto: String, rec: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0E14)).border(1.dp, Color(0xFF2E3A55), RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (rec) RojoRec else Color(0xFF3A2020)))
        Spacer(Modifier.width(10.dp))
        Text(texto, color = Color(0xFFFFB347), fontFamily = Mono, fontSize = 30.sp, letterSpacing = 3.sp)
    }
}

val RojoGrabar: Color get() = RojoRec
val TintaDeck: Color get() = Serigrafia
val AluminioDeck: Color get() = Aluminio
