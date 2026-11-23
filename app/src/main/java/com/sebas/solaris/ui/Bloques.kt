package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.EstiloFoto
import com.sebas.solaris.Marcas
import com.sebas.solaris.Pieza
import com.sebas.solaris.Sonidos

/*
 * La nota como una pila de bloques: párrafos, fotos, collages y voces, en el orden que uno quiera.
 * Se escriben como siempre; en "diagramar" se arrastran para cambiarlos de lugar.
 */

/** Un bloque de la lámina, con su estado vivo mientras se edita. */
@Stable
class Bloque(tipo: String, ids: List<String> = emptyList(), texto: String = "") {
    val clave: String = java.util.UUID.randomUUID().toString()
    var tipo by mutableStateOf(tipo)
    var ids by mutableStateOf(ids)
    var valor by mutableStateOf(TextFieldValue(texto))
    val esTexto: Boolean get() = tipo == Pieza.TEXTO
    val esFoto: Boolean get() = tipo == Pieza.FOTO || tipo == Pieza.FOTOS
    fun pieza() = Pieza(tipo, ids, valor.text)
}

fun bloquesDe(b: Borrador): List<Bloque> =
    ordenar(Marcas.partir(b.texto, b.fotos, b.voces).map { Bloque(it.tipo, it.ids, it.texto) })

/**
 * Deja la pila en orden: junta párrafos seguidos, quita fotos vacías, y deja siempre un párrafo
 * (aunque sea vacío) después de cada foto o voz, para poder escribir ahí.
 */
fun ordenar(l: List<Bloque>, compacta: Boolean = false): List<Bloque> {
    val limpia = mutableListOf<Bloque>()
    for (x in l) {
        if (x.esFoto) {
            if (x.ids.isEmpty()) continue
            x.tipo = if (x.ids.size > 1) Pieza.FOTOS else Pieza.FOTO
        }
        val antes = limpia.lastOrNull()
        if (x.esTexto && antes != null && antes.esTexto) {
            val a = antes.valor.text; val b = x.valor.text
            antes.valor = TextFieldValue(
                when { a.isEmpty() -> b; b.isEmpty() -> a; else -> a + "\n" + b },
            )
            continue
        }
        limpia += x
    }
    val salida = mutableListOf<Bloque>()
    limpia.forEachIndexed { i, x ->
        // un párrafo vacío solo sobrevive justo después de una foto o una voz, o si es lo único
        if (x.esTexto && x.valor.text.isEmpty()) {
            val trasAlgo = i > 0 && !limpia[i - 1].esTexto
            if (compacta || (!trasAlgo && limpia.size > 1)) return@forEachIndexed
        }
        salida += x
        if (!compacta && !x.esTexto && limpia.getOrNull(i + 1)?.esTexto != true) salida += Bloque(Pieza.TEXTO)
    }
    if (salida.isEmpty() || (!compacta && salida.none { it.esTexto })) salida += Bloque(Pieza.TEXTO)
    return salida
}

/** Pasa la pila al borrador: el texto con sus marcas, y las fotos y voces en su orden. */
fun volcar(b: Borrador, l: List<Bloque>) {
    b.texto = Marcas.unir(l.map { it.pieza() })
    val fotos = l.filter { it.esFoto }.flatMap { it.ids }
    if (fotos != b.fotos) b.fotos = fotos
    val voces = l.filter { it.tipo == Pieza.VOZ }.flatMap { it.ids }
    if (voces != b.voces) b.voces = voces
}

fun SnapshotStateList<Bloque>.reemplazar(nuevos: List<Bloque>) {
    clear(); addAll(nuevos)
}

// ---------------- las fotos nuevas ----------------

/** La foto a sangre: de borde a borde, sin papel, saliéndose de los márgenes. */
@Composable
fun FotoASangre(id: String, pie: String, sangria: Dp, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(Casa.fotoEnCache(id, 1400), id) {
        if (value == null) value = Casa.foto(id, 1400)
    }
    Column(
        modifier.fillMaxWidth().layout { m, c ->
            val extra = sangria.roundToPx()
            val p = m.measure(c.copy(minWidth = c.maxWidth + extra * 2, maxWidth = c.maxWidth + extra * 2))
            layout(c.maxWidth, p.height) { p.place(-extra, 0) }
        },
    ) {
        val b = bmp
        val proporcion = if (b != null && b.height > 0) b.width.toFloat() / b.height else 1.4f
        Box(Modifier.fillMaxWidth().aspectRatio(proporcion.coerceIn(0.6f, 2.4f)).background(Tema.p.superficie)) {
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (pie.isNotBlank()) {
            Text(
                pie.trim(), color = Tema.p.texto2, fontFamily = Serif, fontStyle = FontStyle.Italic,
                fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = sangria, end = sangria),
            )
        }
    }
}

/** Un collage: dos o tres fotos en una misma tira de papel. */
@Composable
fun Collage(ids: List<String>, giro: Float, pie: String, modifier: Modifier = Modifier, alTocar: (String) -> Unit) {
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).graphicsLayer { rotationZ = giro }) {
        Column(
            Modifier.graphicsLayer { shadowElevation = 6.dp.toPx() }.background(BordeFoto).padding(7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().aspectRatio(if (ids.size >= 3) 2.1f else 1.55f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ids.forEach { id ->
                    Foto(
                        id,
                        Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { alTocar(id) },
                        lado = 700,
                    )
                }
            }
            if (pie.isNotBlank()) {
                Text(
                    pie.trim(), color = TintaPie, fontFamily = Serif, fontStyle = FontStyle.Italic,
                    fontSize = 14.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-8).dp).graphicsLayer { rotationZ = 2f }
                .size(width = 66.dp, height = 18.dp).background(CintaFoto),
        )
    }
}

// ---------------- diagramar ----------------

/**
 * La pila vista desde arriba: cada bloque es una tira que se arrastra por su asa.
 * Las fotos seguidas se pueden juntar en un collage, y un collage se puede separar.
 */
@Composable
fun Diagramar(b: Borrador, bloques: SnapshotStateList<Bloque>, alAbrirFoto: (String) -> Unit, alCambiar: () -> Unit) {
    val p = Tema.p
    val altos = remember { mutableStateMapOf<String, Int>() }
    var arrastrado by remember { mutableStateOf<String?>(null) }
    var desfase by remember { mutableFloatStateOf(0f) }
    val hueco = 10.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(hueco)) {
        Senal("Arrastra por el asa para cambiar el orden", color = p.texto2)
        Spacer(Modifier.height(4.dp))
        bloques.forEachIndexed { i, bl ->
            key(bl.clave) {
                val activo = arrastrado == bl.clave
                val forma = RoundedCornerShape(18.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { altos[bl.clave] = it.height }
                        .zIndex(if (activo) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (activo) desfase else 0f
                            val e = if (activo) 1.03f else 1f
                            scaleX = e; scaleY = e
                        }
                        .shadow(if (activo) 14.dp else 0.dp, forma)
                        .clip(forma).background(p.superficie)
                        .clickable(enabled = bl.esFoto) { bl.ids.firstOrNull()?.let(alAbrirFoto) }
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { Tira(b, bl) }
                    val siguiente = bloques.getOrNull(i + 1)
                    if (bl.tipo == Pieza.FOTO && siguiente != null && siguiente.esFoto && siguiente.ids.size < 3) {
                        Enlace("Juntar", color = p.acento) {
                            siguiente.ids = bl.ids + siguiente.ids
                            bl.ids = emptyList()
                            bloques.reemplazar(ordenar(bloques.toList(), compacta = true))
                            alCambiar()
                        }
                    }
                    if (bl.tipo == Pieza.FOTOS) {
                        Enlace("Separar", color = p.acento) {
                            val i0 = bloques.indexOf(bl)
                            val sueltas = bl.ids.map { Bloque(Pieza.FOTO, listOf(it)) }
                            bloques.removeAt(i0)
                            bloques.addAll(i0, sueltas)
                            alCambiar()
                        }
                    }
                    // el asa
                    Box(
                        Modifier.size(48.dp).pointerInput(bl.clave) {
                            detectDragGestures(
                                onDragStart = { arrastrado = bl.clave; desfase = 0f; Sonidos.tic() },
                                onDragEnd = { arrastrado = null; desfase = 0f; alCambiar() },
                                onDragCancel = { arrastrado = null; desfase = 0f; alCambiar() },
                            ) { cambio, d ->
                                cambio.consume()
                                desfase += d.y
                                val k = bloques.indexOfFirst { it.clave == bl.clave }
                                if (k < 0) return@detectDragGestures
                                val paso = hueco.toPx()
                                if (desfase > 0 && k < bloques.size - 1) {
                                    val h = (altos[bloques[k + 1].clave] ?: 0) + paso
                                    if (desfase > h / 2) {
                                        bloques.add(k + 1, bloques.removeAt(k)); desfase -= h; Sonidos.tic()
                                    }
                                } else if (desfase < 0 && k > 0) {
                                    val h = (altos[bloques[k - 1].clave] ?: 0) + paso
                                    if (-desfase > h / 2) {
                                        bloques.add(k - 1, bloques.removeAt(k)); desfase += h; Sonidos.tic()
                                    }
                                }
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { IconoAsa(p.texto2) }
                }
            }
        }
    }
}

/** Cómo se ve un bloque en la pila de diagramar. */
@Composable
private fun Tira(b: Borrador, bl: Bloque) {
    val p = Tema.p
    when (bl.tipo) {
        Pieza.TEXTO -> Column {
            Senal("Texto", color = p.texto2)
            Spacer(Modifier.height(4.dp))
            Text(
                sinMarcas(bl.valor.text).ifBlank { "…" }, color = p.texto, fontFamily = Sans, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        Pieza.FOTO, Pieza.FOTOS -> Row(verticalAlignment = Alignment.CenterVertically) {
            bl.ids.forEach { id ->
                Foto(id, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), lado = 200)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(6.dp))
            val est = EstiloFoto.de(bl.ids.firstOrNull()?.let { b.estilos[it] })
            val que = when {
                bl.ids.size > 1 -> "Collage"
                est.aSangre -> "A sangre"
                est.pos == EstiloFoto.IZQUIERDA -> "Texto a la derecha"
                est.pos == EstiloFoto.DERECHA -> "Texto a la izquierda"
                else -> listOf("Pequeña", "Mediana", "Grande", "A sangre")[est.tam.coerceIn(0, 3)]
            }
            Column {
                Senal(if (bl.ids.size > 1) "Fotos" else "Foto", color = p.texto2)
                Text(que, color = p.texto, fontFamily = Sans, fontSize = 14.sp)
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            IconoCasete(p.acento, 24.dp)
            Spacer(Modifier.width(10.dp))
            val c = remember(bl.ids, Casa.items) { bl.ids.firstOrNull()?.let { Casa.porId(it) } }
            Text(
                c?.title?.ifBlank { "Voz" } ?: "Voz", color = p.texto, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun IconoAsa(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.08f
        listOf(0.32f, 0.5f, 0.68f).forEach { y ->
            drawLine(color, Offset(s * 0.18f, s * y), Offset(s * 0.82f, s * y), g, StrokeCap.Round)
        }
    }
}

/** Cuatro rectángulos: diagramar la lámina. */
@Composable
fun IconoDiagramar(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.08f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(g)
        val r = androidx.compose.ui.geometry.CornerRadius(s * 0.06f)
        drawRoundRect(color, Offset(s * 0.12f, s * 0.12f), androidx.compose.ui.geometry.Size(s * 0.76f, s * 0.3f), r, style = st)
        drawRoundRect(color, Offset(s * 0.12f, s * 0.54f), androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.34f), r, style = st)
        drawLine(color, Offset(s * 0.56f, s * 0.6f), Offset(s * 0.88f, s * 0.6f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.56f, s * 0.72f), Offset(s * 0.88f, s * 0.72f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.56f, s * 0.84f), Offset(s * 0.78f, s * 0.84f), g, StrokeCap.Round)
    }
}
