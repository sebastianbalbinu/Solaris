package com.sebas.solaris.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
 * **negrita**, "- " lista (se ve como •) y "> " cita (se ve como │, en cursiva).
 */
class FormatoSencillo(private val tenue: Color, private val acento: Color, private val cita: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val visible = StringBuilder(t)
        val estilos = mutableListOf<Triple<SpanStyle, Int, Int>>()
        var inicio = 0
        while (inicio <= t.length) {
            val fin = t.indexOf('\n', inicio).let { if (it < 0) t.length else it }
            if (t.startsWith("- ", inicio)) {
                visible.setCharAt(inicio, '•')
                estilos += Triple(SpanStyle(color = acento), inicio, inicio + 1)
            } else if (t.startsWith("> ", inicio)) {
                visible.setCharAt(inicio, '│')
                estilos += Triple(SpanStyle(color = acento), inicio, inicio + 1)
                estilos += Triple(SpanStyle(fontStyle = FontStyle.Italic, color = cita), inicio + 2, fin)
            }
            inicio = fin + 1
        }
        Regex("\\*\\*([^*\\n]+)\\*\\*").findAll(t).forEach { m ->
            val a = m.range.first; val b = m.range.last + 1
            estilos += Triple(SpanStyle(color = tenue), a, a + 2)
            estilos += Triple(SpanStyle(fontWeight = FontWeight.SemiBold), a + 2, b - 2)
            estilos += Triple(SpanStyle(color = tenue), b - 2, b)
        }
        val salida = buildAnnotatedString {
            append(visible.toString())
            estilos.forEach { (e, a, b) -> if (a < b) addStyle(e, a, b) }
        }
        return TransformedText(salida, OffsetMapping.Identity)
    }
}

/** Para las vistas previas: el texto sin las marcas. */
fun sinMarcas(t: String): String =
    t.lines().joinToString(" ") { l ->
        when {
            l.startsWith("- ") -> "• " + l.drop(2)
            l.startsWith("> ") -> l.drop(2)
            else -> l
        }
    }.replace("**", "").trim()

fun conNegrita(v: TextFieldValue): TextFieldValue {
    val s = v.selection
    val t = v.text
    return if (s.collapsed) {
        TextFieldValue(t.substring(0, s.start) + "****" + t.substring(s.start), TextRange(s.start + 2))
    } else {
        val a = s.min; val b = s.max
        TextFieldValue(t.substring(0, a) + "**" + t.substring(a, b) + "**" + t.substring(b), TextRange(b + 4))
    }
}

/** Pone o quita un prefijo ("- " o "> ") al comienzo de la línea del cursor. */
fun alternarPrefijo(v: TextFieldValue, prefijo: String): TextFieldValue {
    val t = v.text
    val cursor = v.selection.start.coerceIn(0, t.length)
    val ini = t.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
    val inicioLinea = if (cursor > 0 && t.getOrNull(cursor - 1) == '\n') cursor else ini
    return if (t.startsWith(prefijo, inicioLinea)) {
        TextFieldValue(t.removeRange(inicioLinea, inicioLinea + prefijo.length), TextRange((cursor - prefijo.length).coerceAtLeast(inicioLinea)))
    } else {
        val otro = if (prefijo == "- ") "> " else "- "
        val base = if (t.startsWith(otro, inicioLinea)) t.removeRange(inicioLinea, inicioLinea + 2) else t
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
