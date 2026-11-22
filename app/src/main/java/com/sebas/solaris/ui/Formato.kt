package com.sebas.solaris.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Formato sencillo dentro del texto, sin cambiar lo que se guarda:
 * **negrita**, ~~tachado~~, "- " lista (se ve como •), "> " cita (│, en cursiva),
 * "» " cita destacada (grande, en serif, centrada) y "~~~" sola en su línea (una ola que separa).
 * Con [capitular], la primera letra del texto se agranda como en los libros.
 */
class FormatoSencillo(
    private val tenue: Color,
    private val acento: Color,
    private val cita: Color,
    private val tam: Float = 17f,
    private val capitular: Boolean = false,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val visible = StringBuilder(t)
        val estilos = mutableListOf<Triple<SpanStyle, Int, Int>>()
        val parrafos = mutableListOf<Triple<ParagraphStyle, Int, Int>>()
        var inicio = 0
        var primera = true
        while (inicio <= t.length) {
            val fin = t.indexOf('\n', inicio).let { if (it < 0) t.length else it }
            val finParrafo = if (fin < t.length) fin + 1 else fin
            val linea = t.substring(inicio, fin)
            when {
                linea.startsWith("- ") -> {
                    visible.setCharAt(inicio, '•')
                    estilos += Triple(SpanStyle(color = acento), inicio, inicio + 1)
                }
                linea.startsWith("> ") -> {
                    visible.setCharAt(inicio, '│')
                    estilos += Triple(SpanStyle(color = acento), inicio, inicio + 1)
                    estilos += Triple(SpanStyle(fontStyle = FontStyle.Italic, color = cita), inicio + 2, fin)
                }
                linea.startsWith("» ") -> {
                    estilos += Triple(SpanStyle(color = tenue.copy(alpha = 0.35f), fontSize = (tam * 0.8f).sp), inicio, inicio + 2)
                    estilos += Triple(SpanStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = (tam * 1.45f).sp, color = acento), inicio + 2, fin)
                    parrafos += Triple(ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (tam * 1.95f).sp), inicio, finParrafo)
                }
                linea.trim() == "~~~" -> {
                    for (k in inicio until fin) if (visible[k] == '~') visible.setCharAt(k, '∿')
                    estilos += Triple(SpanStyle(color = acento.copy(alpha = 0.7f), letterSpacing = 0.5.em, fontSize = (tam * 1.2f).sp), inicio, fin)
                    parrafos += Triple(ParagraphStyle(textAlign = TextAlign.Center, lineHeight = (tam * 2.6f).sp), inicio, finParrafo)
                }
                capitular && primera && linea.isNotBlank() -> {
                    val k = linea.indexOfFirst { it.isLetterOrDigit() }
                    if (k >= 0) estilos += Triple(SpanStyle(fontFamily = Serif, fontSize = (tam * 2.7f).sp, color = acento), inicio + k, inicio + k + 1)
                }
            }
            if (linea.isNotBlank()) primera = false
            inicio = fin + 1
        }
        Regex("\\*\\*([^*\\n]+)\\*\\*").findAll(t).forEach { m ->
            val a = m.range.first; val b = m.range.last + 1
            estilos += Triple(SpanStyle(color = tenue), a, a + 2)
            estilos += Triple(SpanStyle(fontWeight = FontWeight.SemiBold), a + 2, b - 2)
            estilos += Triple(SpanStyle(color = tenue), b - 2, b)
        }
        Regex("(?<!~)~~([^~\\n]+)~~(?!~)").findAll(t).forEach { m ->
            val a = m.range.first; val b = m.range.last + 1
            estilos += Triple(SpanStyle(color = tenue), a, a + 2)
            estilos += Triple(SpanStyle(textDecoration = TextDecoration.LineThrough, color = cita), a + 2, b - 2)
            estilos += Triple(SpanStyle(color = tenue), b - 2, b)
        }
        val salida = buildAnnotatedString {
            append(visible.toString())
            estilos.forEach { (e, a, b) -> if (a < b && b <= length) addStyle(e, a, b) }
            parrafos.forEach { (e, a, b) -> if (a < b && b <= length) addStyle(e, a, b) }
        }
        return TransformedText(salida, OffsetMapping.Identity)
    }
}

/** Para las vistas previas: el texto sin las marcas. */
fun sinMarcas(t: String): String =
    t.lines().joinToString(" ") { l ->
        when {
            l.startsWith("- ") -> "• " + l.drop(2)
            l.startsWith("> ") || l.startsWith("» ") -> l.drop(2)
            l.trim() == "~~~" -> "·"
            else -> l
        }
    }.replace("**", "").replace("~~", "").replace(Regex("⟦[^⟧]*⟧"), "").trim()

fun conNegrita(v: TextFieldValue): TextFieldValue = conMarca(v, "**")
fun conTachado(v: TextFieldValue): TextFieldValue = conMarca(v, "~~")

/** Envuelve lo elegido con una marca (o la deja lista para escribir dentro). */
fun conMarca(v: TextFieldValue, m: String): TextFieldValue {
    val s = v.selection
    val t = v.text
    return if (s.collapsed) {
        TextFieldValue(t.substring(0, s.start) + m + m + t.substring(s.start), TextRange(s.start + m.length))
    } else {
        val a = s.min; val b = s.max
        TextFieldValue(t.substring(0, a) + m + t.substring(a, b) + m + t.substring(b), TextRange(b + m.length * 2))
    }
}

/** Pone un bloque en su propia línea, donde está el cursor (una cita destacada, una ola). */
fun insertarLinea(v: TextFieldValue, contenido: String, cursorDentro: Int = contenido.length): TextFieldValue {
    val t = v.text
    val c = v.selection.start.coerceIn(0, t.length)
    val antes = if (c > 0 && t[c - 1] != '\n') "\n" else ""
    val despues = if (c < t.length && t[c] != '\n') "\n" else if (c == t.length) "\n" else ""
    val nuevo = t.substring(0, c) + antes + contenido + despues + t.substring(c)
    return TextFieldValue(nuevo, TextRange(c + antes.length + cursorDentro))
}

/** Pone o quita un prefijo ("- ", "> " o "» ") al comienzo de la línea del cursor. */
fun alternarPrefijo(v: TextFieldValue, prefijo: String): TextFieldValue {
    val t = v.text
    val cursor = v.selection.start.coerceIn(0, t.length)
    val ini = t.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
    val inicioLinea = if (cursor > 0 && t.getOrNull(cursor - 1) == '\n') cursor else ini
    return if (t.startsWith(prefijo, inicioLinea)) {
        TextFieldValue(t.removeRange(inicioLinea, inicioLinea + prefijo.length), TextRange((cursor - prefijo.length).coerceAtLeast(inicioLinea)))
    } else {
        val otro = listOf("- ", "> ", "» ").firstOrNull { it != prefijo && t.startsWith(it, inicioLinea) }
        val base = if (otro != null) t.removeRange(inicioLinea, inicioLinea + 2) else t
        val corr = if (base.length != t.length) -2 else 0
        TextFieldValue(base.substring(0, inicioLinea) + prefijo + base.substring(inicioLinea), TextRange(cursor + corr + prefijo.length))
    }
}

/** Al presionar enter en una lista, la siguiente línea sigue siendo de lista. */
fun continuarLista(viejo: TextFieldValue, nuevo: TextFieldValue): TextFieldValue {
    val t = nuevo.text
    if (t.length != viejo.text.length + 1) return nuevo
    val c = nuevo.selection.start
    if (c <= 0 || t.getOrNull(c - 1) != '\n') return nuevo
    val antes = t.lastIndexOf('\n', c - 2).let { if (it < 0) 0 else it + 1 }
    val linea = t.substring(antes, c - 1)
    if (!linea.startsWith("- ")) return nuevo
    if (linea == "- ") {
        // línea vacía de lista: se sale de la lista
        val sin = t.removeRange(antes, c)
        return TextFieldValue(sin, TextRange(antes))
    }
    return TextFieldValue(t.substring(0, c) + "- " + t.substring(c), TextRange(c + 2))
}
