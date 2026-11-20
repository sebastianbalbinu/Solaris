package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.EstiloFoto
import com.sebas.solaris.Item
import com.sebas.solaris.Sonidos

/*
 * Lo que hace que una nota se sienta diario: fotos pegadas con su pie, fotos diagramadas con texto al lado
 * (como en una revista), voces pegadas y el sello de cómo estaba el mar al escribir.
 */

/** El papel de las fotos pegadas: un crema claro, como el borde de una copia. */
val BordeFoto = Color(0xFFF7F2E8)
/** La cinta que las sostiene. */
val CintaFoto = Color(0x8CE9DFC9)
/** La tinta del pie de foto, escrita sobre el borde de papel. */
val TintaPie = Color(0xFF3A3328)

/** Cómo estaba el Drake en este momento, para sellar una nota nueva. */
fun selloDelMar(): String {
    val m = Clima.mar
    return Corriente.nombreVisible.lowercase() + " · olas " + uno(m.ola) + " m · viento " + m.viento.toInt() + " kn"
}

/** Una foto en su papel, con la cinta arriba y, si tiene, su pie escrito a mano en el borde. */
@Composable
fun PapelFoto(id: String, giro: Float, pie: String, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(Casa.fotoEnCache(id, 900), id) {
        if (value == null) value = Casa.foto(id, 900)
    }
    Box(modifier.padding(horizontal = 6.dp, vertical = 12.dp).graphicsLayer { rotationZ = giro }) {
        Column(
            Modifier.graphicsLayer { shadowElevation = 6.dp.toPx() }
                .background(BordeFoto)
                .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = if (pie.isBlank()) 9.dp else 10.dp),
        ) {
            val b = bmp
            val proporcion = if (b != null && b.height > 0) b.width.toFloat() / b.height else 0.8f
            Box(Modifier.fillMaxWidth().aspectRatio(proporcion.coerceIn(0.5f, 2f)).background(Color(0xFFE6DED0))) {
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (pie.isNotBlank()) {
                Text(
                    pie.trim(), color = TintaPie, fontFamily = Serif, fontStyle = FontStyle.Italic,
                    fontSize = 14.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-8).dp)
                .graphicsLayer { rotationZ = -3f }
                .size(width = 58.dp, height = 18.dp)
                .background(CintaFoto),
        )
    }
}

/**
 * Una foto pegada a la lámina. Sola: con su tamaño, recostada a un lado como en un cuaderno.
 * Al lado: ocupa media columna y su texto la rodea; lo que no cabe junto a ella sigue debajo, a todo lo ancho.
 */
@Composable
fun FotoPegada(id: String, estilo: EstiloFoto, indice: Int, pie: String, lado: String, estiloTexto: TextStyle, alTocar: () -> Unit) {
    val toque = Modifier.clickable(remember { MutableInteractionSource() }, null) { alTocar() }
    if (estilo.alLado) {
        FotoDiagramada(id, estilo, pie, lado, estiloTexto, toque)
        return
    }
    val alineacion = when {
        estilo.tam == 2 -> Alignment.Center
        indice % 2 == 0 -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alineacion) {
        PapelFoto(id, estilo.giro, pie, Modifier.fillMaxWidth(estilo.ancho).then(toque))
    }
}

@Composable
private fun FotoDiagramada(id: String, estilo: EstiloFoto, pie: String, lado: String, estiloTexto: TextStyle, modifier: Modifier) {
    val medidor = rememberTextMeasurer()
    val texto = lado.trim()
    SubcomposeLayout(modifier.fillMaxWidth()) { c ->
        val ancho = c.maxWidth
        val anchoFoto = (ancho * 0.48f).toInt()
        val hueco = 12.dp.roundToPx()
        val foto = subcompose("foto") { PapelFoto(id, estilo.giro * 0.5f, pie, Modifier.fillMaxWidth()) }
            .first().measure(Constraints.fixedWidth(anchoFoto))
        val anchoLado = (ancho - anchoFoto - hueco).coerceAtLeast(1)
        // cuánto del texto cabe junto a la foto: las líneas que terminan antes que ella
        var corte = texto.length
        if (texto.isNotEmpty()) {
            val l = medidor.measure(AnnotatedString(texto), estiloTexto, constraints = Constraints(maxWidth = anchoLado))
            val limite = foto.height - 8.dp.roundToPx()
            var ultima = -1
            for (i in 0 until l.lineCount) if (l.getLineBottom(i) <= limite) ultima = i
            corte = when {
                ultima == l.lineCount - 1 -> texto.length
                ultima < 0 -> 0
                else -> l.getLineEnd(ultima)
            }
        }
        val junto = texto.substring(0, corte).trimEnd()
        val resto = texto.substring(corte).trimStart()
        val pJunto = subcompose("junto") { Text(junto, style = estiloTexto) }.first().measure(Constraints(maxWidth = anchoLado))
        val pResto = if (resto.isEmpty()) null else subcompose("resto") { Text(resto, style = estiloTexto) }.first().measure(Constraints(maxWidth = ancho))
        val arriba = maxOf(foto.height, pJunto.height + 12.dp.roundToPx())
        val alto = arriba + (pResto?.height ?: 0)
        val derecha = estilo.pos == EstiloFoto.DERECHA
        layout(ancho, alto) {
            foto.place(if (derecha) ancho - anchoFoto else 0, 0)
            pJunto.place(if (derecha) 0 else anchoFoto + hueco, 12.dp.roundToPx())
            pResto?.place(0, arriba)
        }
    }
}

/** Una voz pegada en la nota: una cinta pequeña que se escucha ahí mismo. */
@Composable
fun CintaPegada(c: Item, alQuitar: (() -> Unit)?, alTocar: () -> Unit) {
    val p = Tema.p
    val forma = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(forma).background(p.superficie.copy(alpha = 0.85f)).border(1.dp, p.linea, forma)
            .clickable { Sonidos.tic(); alTocar() }.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconoCasete(p.acento, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title.ifBlank { if (c.esRumor) "Rumor" else "Voz" }, color = p.texto, fontFamily = Serif, fontStyle = FontStyle.Italic,
                fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (c.esRumor) "rumor · " else "voz · ") + duracion(c.dur) + " · " + formato("d MMM", c.created),
                color = p.texto2, fontFamily = Sans, fontSize = 12.sp,
            )
        }
        if (alQuitar != null) {
            Box(Modifier.size(36.dp).clip(CircleShape).clickable { Sonidos.tic(); alQuitar() }, contentAlignment = Alignment.Center) {
                Text("×", color = p.texto2, fontFamily = Sans, fontSize = 20.sp)
            }
        }
    }
}

/** Elegir qué voces van pegadas en la nota. */
@Composable
fun HojaVoces(visible: Boolean, elegidas: List<String>, alAlternar: (String) -> Unit, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
        Text("Pegar una voz", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        val cintas = remember(Casa.items) { Casa.cintas() }
        if (cintas.isEmpty()) {
            Parrafo("Todavía no hay voces grabadas. Mantén el botón del centro de la orilla, o toca «grabar».")
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(cintas, key = { it.id }) { c ->
                    val sel = c.id in elegidas
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Sonidos.tic(); alAlternar(c.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (sel) p.acento else Color.Transparent)
                                .border(1.5.dp, if (sel) p.acento else p.texto2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Text("✓", color = p.fondo, fontSize = 12.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { "Sin nombre" }, color = p.texto, fontFamily = Sans, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (c.esRumor) "Rumor" else "Voz") + " · " + duracion(c.dur) + " · " + hace(c.created),
                                color = p.texto2, fontFamily = Sans, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
        Pildora("Listo", Modifier.fillMaxWidth()) { alCerrar() }
    }
}

/** Lo que se puede poner en la lámina además de texto. */
@Composable
fun HojaInsertar(visible: Boolean, alElegir: (String) -> Unit, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
        Text("Poner en la lámina", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        OpcionInsertar("Cita destacada", "Una frase tuya, grande, en el centro de la página.") { alElegir("cita") }
        OpcionInsertar("Ola que separa", "Una línea de ola para cambiar de tema o de momento.") { alElegir("ola") }
        OpcionInsertar("Pegar una voz", "Una de tus voces o rumores, para escucharla aquí.") { alElegir("voz") }
        OpcionInsertar("Foto", "Pegada como en un cuaderno, sola o al lado del texto.") { alElegir("foto") }
    }
}

@Composable
private fun OpcionInsertar(titulo: String, detalle: String, alTocar: () -> Unit) {
    val p = Tema.p
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.superficie)
            .clickable { Sonidos.tic(); alTocar() }.padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(titulo, color = p.texto, fontFamily = Sans, fontSize = 16.sp)
        Text(detalle, color = p.texto2, fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Interruptor sencillo, al estilo de iOS. */
@Composable
fun Interruptor(titulo: String, detalle: String?, encendido: Boolean, alCambiar: (Boolean) -> Unit) {
    val p = Tema.p
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { Sonidos.tic(); alCambiar(!encendido) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titulo, color = p.texto, fontFamily = Sans, fontSize = 16.sp)
            if (detalle != null) Text(detalle, color = p.texto2, fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        val fondo = if (encendido) p.acento else p.linea
        Box(Modifier.size(width = 48.dp, height = 28.dp).clip(CircleShape).background(fondo).padding(3.dp)) {
            Box(Modifier.align(if (encendido) Alignment.CenterEnd else Alignment.CenterStart).size(22.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
fun IconoAlinear(color: Color, centro: Boolean, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.09f
        listOf(0.84f, 0.56f, 0.84f, 0.42f).forEachIndexed { i, largo ->
            val y = s * (0.2f + i * 0.2f)
            val w = s * largo
            val x0 = if (centro) (s - w) / 2 else s * 0.08f
            drawLine(color, Offset(x0, y), Offset(x0 + w, y), g, StrokeCap.Round)
        }
    }
}

@Composable
fun IconoCasete(color: Color, tam: Dp = 22.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.07f
        drawRoundRect(color, Offset(s * 0.04f, s * 0.2f), androidx.compose.ui.geometry.Size(s * 0.92f, s * 0.6f), androidx.compose.ui.geometry.CornerRadius(s * 0.1f), style = Stroke(g))
        drawCircle(color, s * 0.1f, Offset(s * 0.33f, s * 0.47f), style = Stroke(g))
        drawCircle(color, s * 0.1f, Offset(s * 0.67f, s * 0.47f), style = Stroke(g))
        drawLine(color, Offset(s * 0.43f, s * 0.47f), Offset(s * 0.57f, s * 0.47f), g)
    }
}

@Composable
fun IconoTachado(color: Color) {
    Text(
        "S", color = color, fontFamily = Sans, fontSize = 17.sp,
        style = TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
    )
}
