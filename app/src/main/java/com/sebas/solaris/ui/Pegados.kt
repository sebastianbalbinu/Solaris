package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.Pegado
import com.sebas.solaris.Sonidos
import java.util.Locale
import kotlin.random.Random

/*
 * Lo que se pega encima de la hoja, como en un diario de papel. Cada cosa va anclada a un bloque y se mueve con él:
 * un dedo la arrastra, dos la giran y la agrandan; tocarla la elige (para quitarla o cambiarle el texto).
 */

/** Colores de la cinta de papel (washi), de las notas adhesivas y de la tinta de los sellos. */
val ColoresCinta = listOf(Color(0xCCE8C8A0), Color(0xCC9FC3DA), Color(0xCCD9A7A0), Color(0xCCB9C9A3), Color(0xCCEDE3CF))
val ColoresNota = listOf(Color(0xFFF3E19B), Color(0xFFF2C4C0), Color(0xFFC9DDEB))
val ColoresTinta = listOf(Color(0xFFB2412F), Color(0xFF1F4E6E), Color(0xFF2A2A2A))

/** Cuánto mide cada cosa pegada (ancho base en dp, a escala 1). */
private fun anchoBase(tipo: String): Float = when (tipo) {
    Pegado.POLAROID -> 150f
    Pegado.ESQUINAS -> 170f
    Pegado.CINTA -> 110f
    Pegado.CLIP -> 24f
    Pegado.NOTA -> 140f
    Pegado.BOLETO -> 180f
    Pegado.SELLO -> 112f
    Pegado.CASETE -> 120f
    else -> 100f
}

/** Las partes del dato: "color|texto" y similares. */
private fun partes(d: String) = d.split('|')

/** Una cosa pegada, dibujada a su tamaño ([ancho] ya con su escala). */
@Composable
fun PegadoVista(p: Pegado, b: Borrador, ancho: Dp) {
    when (p.tipo) {
        Pegado.POLAROID -> Column(
            Modifier.width(ancho).shadow(6.dp, RoundedCornerShape(2.dp)).background(Color(0xFFFAF8F2))
                .padding(start = ancho * 0.055f, end = ancho * 0.055f, top = ancho * 0.055f, bottom = ancho * 0.04f),
        ) {
            Foto(p.dato, Modifier.fillMaxWidth().aspectRatio(1f), lado = 600)
            Text(
                (b.pies[p.dato] ?: "").ifBlank { " " }, color = Color(0xFF2A2A2A), fontFamily = Mano, fontSize = (ancho.value * 0.11f).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = ancho * 0.03f),
            )
        }
        Pegado.ESQUINAS -> Box(Modifier.width(ancho).aspectRatio(4f / 3f)) {
            Foto(p.dato, Modifier.fillMaxSize().shadow(3.dp), lado = 700)
            // los cuatro esquineros de papel negro
            Canvas(Modifier.fillMaxSize()) {
                val e = size.width * 0.12f
                val c = Color(0xFF1A1A1A)
                fun esquina(a: Offset, b2: Offset, d: Offset) = drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b2.x, b2.y); lineTo(d.x, d.y); close() }, c)
                val w = size.width; val h = size.height; val o = e * 0.25f
                esquina(Offset(-o, -o), Offset(e, -o), Offset(-o, e))
                esquina(Offset(w + o, -o), Offset(w - e, -o), Offset(w + o, e))
                esquina(Offset(-o, h + o), Offset(e, h + o), Offset(-o, h - e))
                esquina(Offset(w + o, h + o), Offset(w - e, h + o), Offset(w + o, h - e))
            }
        }
        Pegado.CINTA -> Canvas(Modifier.size(ancho, ancho * 0.24f)) {
            val col = ColoresCinta[(p.dato.toIntOrNull() ?: 0).coerceIn(0, ColoresCinta.size - 1)]
            val w = size.width; val h = size.height
            // bordes rasgados a mano
            val r = Random(p.clave.hashCode())
            val camino = Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f)
                var y = 0f
                while (y < h) { y += h / 6; lineTo(w - r.nextFloat() * w * 0.04f, y.coerceAtMost(h)) }
                lineTo(0f, h)
                y = h
                while (y > 0) { y -= h / 6; lineTo(r.nextFloat() * w * 0.04f, y.coerceAtLeast(0f)) }
                close()
            }
            drawPath(camino, col)
            for (k in 1 until 8) drawLine(Color.White.copy(alpha = 0.18f), Offset(w * k / 8f, 0f), Offset(w * k / 8f - h * 0.6f, h), 2.dp.toPx())
        }
        Pegado.CLIP -> Canvas(Modifier.size(ancho, ancho * 2.8f)) {
            val w = size.width; val h = size.height
            val metal = Brush.linearGradient(listOf(Color(0xFFD9DDE2), Color(0xFF8C939C), Color(0xFFE7EAEE)), Offset.Zero, Offset(w, h))
            val g = w * 0.12f
            val clip = Path().apply {
                moveTo(w * 0.3f, h * 0.72f); lineTo(w * 0.3f, h * 0.18f)
                cubicTo(w * 0.3f, h * 0.02f, w * 0.85f, h * 0.02f, w * 0.85f, h * 0.18f)
                lineTo(w * 0.85f, h * 0.86f)
                cubicTo(w * 0.85f, h * 1.0f, w * 0.12f, h * 1.0f, w * 0.12f, h * 0.86f)
                lineTo(w * 0.12f, h * 0.28f)
            }
            drawPath(clip, metal, style = Stroke(g, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Pegado.NOTA -> {
            val pa = partes(p.dato)
            val col = ColoresNota[(pa.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, ColoresNota.size - 1)]
            Box(
                Modifier.size(ancho).shadow(5.dp).background(Brush.verticalGradient(listOf(col, col.copy(red = col.red * 0.96f, green = col.green * 0.95f))))
                    .padding(ancho * 0.08f),
            ) {
                Text(
                    pa.getOrNull(1)?.ifBlank { null } ?: "…", color = Color(0xFF2B2B2B), fontFamily = Mano,
                    fontSize = (ancho.value * 0.13f).sp, lineHeight = (ancho.value * 0.15f).sp, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Pegado.BOLETO -> Box(Modifier.size(ancho, ancho * 0.42f)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val forma = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(h * 0.06f)))
                }
                drawPath(forma, Color(0xFFF1E6CF))
                // la línea perforada y el marco impreso
                drawLine(Color(0xFF9C8A6A), Offset(w * 0.72f, h * 0.1f), Offset(w * 0.72f, h * 0.9f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawRect(Color(0xFF9C8A6A), Offset(w * 0.04f, h * 0.08f), Size(w * 0.64f, h * 0.84f), style = Stroke(1.dp.toPx()))
            }
            Column(Modifier.fillMaxWidth(0.7f).fillMaxHeight().padding(start = ancho * 0.06f, top = ancho * 0.03f), verticalArrangement = Arrangement.Center) {
                Text("PASE", color = Color(0xFF7A3C2C), fontFamily = Maquina, fontSize = (ancho.value * 0.055f).sp, letterSpacing = 2.sp)
                Text(
                    p.dato.ifBlank { "un lugar" }, color = Color(0xFF2B2B2B), fontFamily = Maquina, fontSize = (ancho.value * 0.075f).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (ancho.value * 0.085f).sp,
                )
            }
            Text(
                "Nº\n" + (p.clave.hashCode() and 0xFFF).toString().padStart(4, '0'), color = Color(0xFF7A3C2C), fontFamily = Maquina,
                fontSize = (ancho.value * 0.05f).sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.28f),
            )
        }
        Pegado.SELLO -> {
            val pa = partes(p.dato)
            val tinta = ColoresTinta[(pa.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, ColoresTinta.size - 1)].copy(alpha = 0.78f)
            Box(Modifier.size(ancho), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    drawCircle(tinta, r * 0.96f, style = Stroke(r * 0.06f))
                    drawCircle(tinta, r * 0.8f, style = Stroke(r * 0.025f))
                    // la tinta nunca cae pareja
                    val rnd = Random(p.clave.hashCode())
                    repeat(40) { drawCircle(Color.White.copy(alpha = 0.35f), r * 0.03f * rnd.nextFloat(), Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height)) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(ancho * 0.16f)) {
                    Text(pa.getOrNull(1) ?: "", color = tinta, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = (ancho.value * 0.15f).sp, textAlign = TextAlign.Center, maxLines = 1)
                    Text(pa.getOrNull(2) ?: "", color = tinta, fontFamily = Mono, fontSize = (ancho.value * 0.07f).sp, textAlign = TextAlign.Center, lineHeight = (ancho.value * 0.085f).sp, maxLines = 3)
                }
            }
        }
        Pegado.CASETE -> {
            val c = remember(p.dato, Casa.items) { Casa.porId(p.dato) }
            if (c != null) {
                val propia = com.sebas.solaris.Grabadora.idActual == c.id && com.sebas.solaris.Grabadora.reproduciendo
                val giro = carretes(propia)
                CaseteDibujo(Modifier.width(ancho), c.title, "", giro = giro, conLetra = false, rumor = c.esRumor)
            }
        }
        Pegado.TRAZO -> {
            val (cab, pts) = p.dato.split(';', limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val c = cab.split(',')
            val w = c.getOrNull(0)?.toFloatOrNull() ?: 100f
            val h = c.getOrNull(1)?.toFloatOrNull() ?: 100f
            val col = ColoresTinta[(c.getOrNull(2)?.toIntOrNull() ?: 0).coerceIn(0, ColoresTinta.size - 1)]
            val escala = ancho.value / w
            val puntos = remember(pts) {
                pts.split(' ').mapNotNull { q -> q.split(',').takeIf { it.size == 2 }?.let { Offset(it[0].toFloat(), it[1].toFloat()) } }
            }
            Canvas(Modifier.size(ancho, (h * escala).dp)) {
                if (puntos.size < 2) return@Canvas
                val camino = Path().apply {
                    moveTo(puntos[0].x * size.width, puntos[0].y * size.height)
                    for (i in 1 until puntos.size) {
                        val a = puntos[i - 1]; val q = puntos[i]
                        quadraticTo(a.x * size.width, a.y * size.height, (a.x + q.x) / 2 * size.width, (a.y + q.y) / 2 * size.height)
                    }
                    lineTo(puntos.last().x * size.width, puntos.last().y * size.height)
                }
                drawPath(camino, col.copy(alpha = 0.9f), style = Stroke(2.6.dp.toPx() * escala.coerceIn(0.6f, 2f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Ancho que tiene un trazo guardado (para escalarlo). */
private fun anchoTrazo(p: Pegado): Float = p.dato.substringBefore(',').toFloatOrNull() ?: 100f

/**
 * La capa de cosas pegadas encima de un bloque. Se pone con matchParentSize: las cosas se acomodan
 * relativas al bloque y pueden salirse de él.
 */
@Composable
fun CapaPegados(
    pegados: MutableList<Pegado>,
    b: Borrador,
    elegido: String?,
    editable: Boolean,
    alElegir: (String?) -> Unit,
    alCambiar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val densidad = LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val anchoPx = constraints.maxWidth.toFloat()
        pegados.forEach { p ->
            key(p.clave) {
                val base = if (p.tipo == Pegado.TRAZO) anchoTrazo(p) else anchoBase(p.tipo)
                val ancho = (base * p.escala).dp
                var tam by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                Box(
                    Modifier
                        .wrapContentSize(unbounded = true, align = Alignment.TopStart)
                        .offset {
                            androidx.compose.ui.unit.IntOffset((p.x * anchoPx - tam.width / 2f).toInt(), (p.y * densidad - tam.height / 2f).toInt())
                        }
                        .onSizeChanged { tam = it }
                        .then(
                            if (!editable) Modifier else Modifier
                                .pointerInput(p.clave) {
                                    detectTapGestures(onTap = { alElegir(if (elegido == p.clave) null else p.clave) })
                                }
                                .pointerInput(p.clave) {
                                    detectTransformGestures { _, pan, zoom, giro ->
                                        val i = pegados.indexOfFirst { it.clave == p.clave }
                                        if (i < 0) return@detectTransformGestures
                                        val q = pegados[i]
                                        pegados[i] = q.copy(
                                            x = q.x + pan.x / anchoPx.coerceAtLeast(1f),
                                            y = q.y + pan.y / densidad,
                                            escala = (q.escala * zoom).coerceIn(0.35f, 3.5f),
                                            giro = q.giro + giro,
                                        )
                                        if (elegido != p.clave) alElegir(p.clave)
                                    }
                                }
                                .pointerInput(p.clave) {
                                    // al soltar, se guarda
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val e = awaitPointerEvent()
                                        } while (e.changes.any { it.pressed })
                                        alCambiar()
                                    }
                                }
                        )
                        .graphicsLayer { rotationZ = p.giro }
                        .then(
                            if (elegido == p.clave) Modifier.drawBehind {
                                drawRoundRect(
                                    Color(0xFF4A90C2), Offset(-6.dp.toPx(), -6.dp.toPx()), Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                                    CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                                )
                            } else Modifier
                        ),
                ) { PegadoVista(p, b, ancho) }
            }
        }
    }
}

/** El ancho de los bloques de la hoja (todos miden lo mismo: el ancho del texto). */
object AnchoBloques {
    var actual = 1000f
}

/** Dónde va algo nuevo: en el bloque que está a la vista, cerca del centro de la pantalla. */
fun pegadoNuevo(tipo: String, dato: String, yDp: Float, escala: Float = 1f): Pegado =
    Pegado(tipo, 0.5f + (Random.nextFloat() - 0.5f) * 0.2f, yDp, (Random.nextFloat() - 0.5f) * 10f, escala, dato)

/** El sello de hoy: el día y el mes, con tinta roja. */
fun selloDeHoy(): String {
    val ahora = System.currentTimeMillis()
    return "0|" + formato("d", ahora) + "|" + formato("MMM yyyy", ahora).uppercase()
}

/** El sello del Drake: las coordenadas y cómo estaba el mar. */
fun selloDelDrake(): String {
    val m = Clima.mar
    return "1|58°S|" + "64°O\n" + String.format(Locale.US, "%.1f M", m.ola) + "\n" + Corriente.nombreVisible.take(14)
}

// ---------------- la hoja de pegar ----------------

/** Lo que se puede pegar encima de la hoja. */
@Composable
fun HojaPegar(visible: Boolean, alElegir: (tipo: String, dato: String) -> Unit, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
        Text("Pegar en la hoja", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Parrafo("Se pega en la parte de la hoja que estás viendo. Arrástralo con un dedo; con dos, gíralo y agrándalo.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpcionPegar("Polaroid", Modifier.weight(1f)) { alElegir(Pegado.POLAROID, "") }
            OpcionPegar("Foto con esquineros", Modifier.weight(1f)) { alElegir(Pegado.ESQUINAS, "") }
        }
        Text("Cinta de papel", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColoresCinta.forEachIndexed { i, c ->
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(4.dp)).background(c).clickable { alElegir(Pegado.CINTA, "$i") })
            }
        }
        Text("Nota adhesiva", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColoresNota.forEachIndexed { i, c ->
                Box(Modifier.weight(1f).height(44.dp).shadow(2.dp).background(c).clickable { alElegir(Pegado.NOTA, "$i|") })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpcionPegar("Boleto", Modifier.weight(1f)) { alElegir(Pegado.BOLETO, "") }
            OpcionPegar("Clip", Modifier.weight(1f)) { alElegir(Pegado.CLIP, "") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpcionPegar("Sello de hoy", Modifier.weight(1f)) { alElegir(Pegado.SELLO, selloDeHoy()) }
            OpcionPegar("Sello del Drake", Modifier.weight(1f)) { alElegir(Pegado.SELLO, selloDelDrake()) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OpcionPegar("Casete chiquito", Modifier.weight(1f)) { alElegir(Pegado.CASETE, "") }
            OpcionPegar("Dibujar a mano", Modifier.weight(1f)) { alElegir(Pegado.TRAZO, "") }
        }
    }
}

@Composable
private fun OpcionPegar(titulo: String, modifier: Modifier, alTocar: () -> Unit) {
    val p = Tema.p
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(p.superficie).clickable { Sonidos.tic(); alTocar() }.padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(titulo, color = p.texto, fontFamily = Sans, fontSize = 15.sp, textAlign = TextAlign.Center) }
}

/** Elegir una voz para pegarla como casete chiquito. */
@Composable
fun HojaElegirVoz(visible: Boolean, alElegir: (String) -> Unit, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
        Text("¿Qué casete pegar?", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        val cintas = remember(Casa.items) { Casa.cintas() }
        if (cintas.isEmpty()) Parrafo("Todavía no hay voces grabadas.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(cintas, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Sonidos.tic(); alElegir(c.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CaseteMini(c, Modifier.width(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { "Sin nombre" }, color = p.texto, fontFamily = Sans, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (c.esRumor) "Rumor" else "Voz") + " · " + duracion(c.dur) + " · " + hace(c.created), color = p.texto2, fontFamily = Sans, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---------------- dibujar a mano ----------------

/** Un trazo mientras se dibuja, en coordenadas de la pantalla. */
class TrazoVivo(val color: Int) { val puntos = mutableStateListOf<Offset>() }

/**
 * La capa para dibujar con el dedo encima de la hoja (flechas, subrayados, garabatos).
 * Mientras está abierta, la hoja no se desliza.
 */
@Composable
fun CapaDibujo(trazos: MutableList<TrazoVivo>, color: Int, modifier: Modifier) {
    val tinta = ColoresTinta
    Canvas(
        modifier.pointerInput(color) {
            awaitEachGesture {
                val d = awaitFirstDown()
                val t = TrazoVivo(color)
                t.puntos.add(d.position)
                trazos.add(t)
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    if (c.positionChange() != Offset.Zero) { t.puntos.add(c.position); c.consume() }
                }
            }
        },
    ) {
        for (t in trazos) {
            if (t.puntos.size < 2) continue
            val camino = Path().apply {
                moveTo(t.puntos[0].x, t.puntos[0].y)
                for (i in 1 until t.puntos.size) {
                    val a = t.puntos[i - 1]; val q = t.puntos[i]
                    quadraticTo(a.x, a.y, (a.x + q.x) / 2, (a.y + q.y) / 2)
                }
            }
            drawPath(camino, tinta[t.color.coerceIn(0, tinta.size - 1)].copy(alpha = 0.9f), style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * Convierte un trazo dibujado (en px de pantalla) en un pegado: lo encierra en su caja, guarda sus puntos
 * en fracciones de esa caja y su tamaño en dp. Devuelve el centro de la caja (en px de pantalla) y el dato.
 */
fun trazoAPegado(t: TrazoVivo, densidad: Float): Pair<Offset, String>? {
    if (t.puntos.size < 2) return null
    val minX = t.puntos.minOf { it.x }; val maxX = t.puntos.maxOf { it.x }
    val minY = t.puntos.minOf { it.y }; val maxY = t.puntos.maxOf { it.y }
    val w = (maxX - minX).coerceAtLeast(4f); val h = (maxY - minY).coerceAtLeast(4f)
    // menos puntos: uno cada ~3 dp
    val paso = 3f * densidad
    val reducidos = mutableListOf(t.puntos.first())
    for (q in t.puntos) if ((q - reducidos.last()).getDistance() >= paso) reducidos += q
    if (reducidos.last() != t.puntos.last()) reducidos += t.puntos.last()
    val pts = reducidos.joinToString(" ") { String.format(Locale.US, "%.3f,%.3f", (it.x - minX) / w, (it.y - minY) / h) }
    val dato = String.format(Locale.US, "%.1f,%.1f,%d;", w / densidad, h / densidad, t.color) + pts
    return Offset((minX + maxX) / 2, (minY + maxY) / 2) to dato
}

/** Un pedazo de cinta en diagonal: pegar cosas en la hoja. */
@Composable
fun IconoPegar(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.08f
        val cinta = Path().apply {
            moveTo(s * 0.18f, s * 0.42f); lineTo(s * 0.58f, s * 0.12f); lineTo(s * 0.82f, s * 0.44f); lineTo(s * 0.42f, s * 0.74f); close()
        }
        drawPath(cinta, color, style = Stroke(g, join = StrokeJoin.Round))
        drawLine(color, Offset(s * 0.3f, s * 0.86f), Offset(s * 0.86f, s * 0.86f), g, StrokeCap.Round)
    }
}

/** El color de fondo de cada papel (kraft y fotográfico cambian; los demás usan el del tema). */
fun colorDePapel(papel: Int, fondo: Color): Color = when (papel) {
    3 -> if (Tema.esNoche) Color(0xFF261E15) else Color(0xFFD9C3A0)
    5 -> if (Tema.esNoche) Color(0xFF101112) else Color(0xFFFCFCFA)
    else -> fondo
}

/** Los renglones, la cuadrícula o el margen rojo del papel, dibujados en la hoja (se deslizan con el texto). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarPapel(papel: Int, renglon: Float, margen: Float, tinta: Color) {
    if (papel == 0 || papel == 3 || papel == 5 || renglon <= 4f) return
    val linea = tinta.copy(alpha = if (Tema.esNoche) 0.16f else 0.2f)
    val g = 1f
    // los renglones empiezan donde empieza el texto (debajo del título)
    val inicio = 96.dp.toPx() + 110.dp.toPx()
    var y = inicio
    while (y < size.height) { drawLine(linea, Offset(0f, y), Offset(size.width, y), g); y += renglon }
    if (papel == 2) {
        var x = margen % renglon
        while (x < size.width) { drawLine(linea, Offset(x, 0f), Offset(x, size.height), g); x += renglon }
        var y2 = inicio - renglon
        while (y2 > 0) { drawLine(linea, Offset(0f, y2), Offset(size.width, y2), g); y2 -= renglon }
    }
    if (papel == 4) {
        // el margen rojo del cuaderno
        drawLine(Color(0xFFC85A4A).copy(alpha = 0.45f), Offset(margen * 0.62f, 0f), Offset(margen * 0.62f, size.height), 1.5.dp.toPx())
    }
}
