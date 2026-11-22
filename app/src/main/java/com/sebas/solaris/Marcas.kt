package com.sebas.solaris

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Algo pegado encima de la hoja, como en un diario de papel: una foto, cinta, un clip, una nota adhesiva,
 * un boleto, un sello, un casete chiquito o un trazo a mano. Va anclado a un bloque (se mueve con él).
 *  [x]: centro, en fracción del ancho del bloque (0..1, puede salirse un poco)
 *  [y]: centro, en dp desde el borde de arriba del bloque
 *  [giro]: grados; [escala]: 1 = tamaño normal
 *  [dato]: lo propio de cada tipo (id de foto o voz, color, texto, trazo…)
 */
data class Pegado(
    val tipo: String,
    val x: Float,
    val y: Float,
    val giro: Float = 0f,
    val escala: Float = 1f,
    val dato: String = "",
    val clave: String = java.util.UUID.randomUUID().toString(),
) {
    companion object {
        const val POLAROID = "polaroid"
        const val ESQUINAS = "esquinas"
        const val CINTA = "cinta"
        const val CLIP = "clip"
        const val NOTA = "nota"
        const val BOLETO = "boleto"
        const val SELLO = "sello"
        const val CASETE = "casete"
        const val TRAZO = "trazo"
    }
    /** El id de foto o voz que este pegado mantiene viva en la nota. */
    val foto: String? get() = dato.takeIf { tipo == POLAROID || tipo == ESQUINAS }
    val voz: String? get() = dato.takeIf { tipo == CASETE }
}

/**
 * La diagramación de una nota vive dentro de su texto, en líneas de marca que no se ven:
 *   ⟦foto ID⟧          una foto en ese punto del texto
 *   ⟦fotos ID ID ID⟧   un collage de 2 o 3 fotos
 *   ⟦voz ID =mini⟧     una voz pegada como casete (=grande, =mediano, =mini, =escondido)
 *   ⟦pega TIPO x y giro escala DATO⟧   algo pegado encima del bloque de arriba
 * Así lo que se guarda en la nube no cambia de forma. Las notas viejas (sin marcas) se ven como antes:
 * primero las fotos, luego las voces y luego el texto.
 */
data class Pieza(
    val tipo: String,
    val ids: List<String> = emptyList(),
    val texto: String = "",
    val opcion: String = "",
    val pegados: List<Pegado> = emptyList(),
) {
    companion object {
        const val TEXTO = "texto"
        const val FOTO = "foto"
        const val FOTOS = "fotos"
        const val VOZ = "voz"
    }
}

object Marcas {
    private const val ABRE = '⟦'
    private const val CIERRA = '⟧'
    private val linea = Regex("^⟦(foto|fotos|voz|pega)((?: [^ ⟧]+)+)⟧$")

    fun esMarca(l: String): Boolean = linea.matches(l.trim())

    private fun cod(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun decod(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
    private fun num(f: Float) = String.format(Locale.US, "%.3f", f).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun leerPegado(t: List<String>): Pegado? {
        if (t.size < 5) return null
        val dato = t.getOrNull(5)?.let { if (it == "-") "" else decod(it) } ?: ""
        return Pegado(
            tipo = t[0],
            x = t[1].toFloatOrNull() ?: return null,
            y = t[2].toFloatOrNull() ?: return null,
            giro = t[3].toFloatOrNull() ?: 0f,
            escala = t[4].toFloatOrNull() ?: 1f,
            dato = dato,
        )
    }

    private fun escribirPegado(p: Pegado) =
        "${ABRE}pega ${p.tipo} ${num(p.x)} ${num(p.y)} ${num(p.giro)} ${num(p.escala)} ${cod(p.dato).ifEmpty { "-" }}$CIERRA"

    /** Parte el texto en piezas, en el orden en que van en la lámina. */
    fun partir(texto: String, fotos: List<String>, voces: List<String>): List<Pieza> {
        val piezas = mutableListOf<Pieza>()
        val usadas = mutableSetOf<String>()
        val parrafo = StringBuilder()
        var hayParrafo = false
        fun cerrarTexto() {
            if (hayParrafo) piezas += Pieza(Pieza.TEXTO, texto = parrafo.toString())
            parrafo.clear(); hayParrafo = false
        }
        texto.split('\n').forEach { l ->
            val m = linea.matchEntire(l.trim())
            if (m != null) {
                cerrarTexto()
                val tokens = m.groupValues[2].trim().split(' ').filter { it.isNotBlank() }
                val tipo = m.groupValues[1]
                if (tipo == "pega") {
                    val p = leerPegado(tokens) ?: return@forEach
                    // una foto o voz pegada tiene que seguir en la nota
                    if (p.foto != null && p.foto !in fotos) return@forEach
                    if (p.voz != null && p.voz !in voces) return@forEach
                    p.foto?.let { usadas += it }; p.voz?.let { usadas += it }
                    // va encima de la pieza de arriba (si no hay, de un párrafo vacío)
                    if (piezas.isEmpty()) piezas += Pieza(Pieza.TEXTO)
                    val ultima = piezas.removeAt(piezas.size - 1)
                    piezas += ultima.copy(pegados = ultima.pegados + p)
                    return@forEach
                }
                val ids = tokens.filter { !it.startsWith("=") }
                val opcion = tokens.firstOrNull { it.startsWith("=") }?.removePrefix("=") ?: ""
                // solo cuenta lo que de verdad sigue pegado en la nota
                val vivos = (if (tipo == Pieza.VOZ) ids.filter { it in voces } else ids.filter { it in fotos }).filter { usadas.add(it) }
                when {
                    vivos.isEmpty() -> {}
                    tipo == Pieza.VOZ -> vivos.forEach { piezas += Pieza(Pieza.VOZ, listOf(it), opcion = opcion) }
                    vivos.size == 1 -> piezas += Pieza(Pieza.FOTO, vivos)
                    else -> piezas += Pieza(Pieza.FOTOS, vivos)
                }
            } else {
                if (hayParrafo) parrafo.append('\n')
                parrafo.append(l); hayParrafo = true
            }
        }
        cerrarTexto()
        // lo que no tiene marca (notas de antes): fotos y voces al comienzo, como siempre se vieron
        val sueltas = fotos.filter { it !in usadas }.map { Pieza(Pieza.FOTO, listOf(it)) } +
            voces.filter { it !in usadas }.map { Pieza(Pieza.VOZ, listOf(it)) }
        return sueltas + piezas
    }

    /** Vuelve a coser las piezas en un solo texto con sus marcas. */
    fun unir(piezas: List<Pieza>): String {
        val sb = StringBuilder()
        var primera = true
        fun linea(s: String) { if (!primera) sb.append('\n'); sb.append(s); primera = false }
        piezas.forEach { p ->
            val op = if (p.opcion.isNotBlank()) " =${p.opcion}" else ""
            when (p.tipo) {
                Pieza.TEXTO -> if (p.texto.isNotEmpty() || p.pegados.isNotEmpty()) linea(p.texto)
                Pieza.FOTO, Pieza.FOTOS -> if (p.ids.isNotEmpty()) linea("$ABRE${if (p.ids.size > 1) "fotos" else "foto"} ${p.ids.joinToString(" ")}$CIERRA")
                Pieza.VOZ -> p.ids.firstOrNull()?.let { linea("${ABRE}voz $it$op$CIERRA") }
            }
            p.pegados.forEach { linea(escribirPegado(it)) }
        }
        return sb.toString()
    }

    /** Todas las fotos y voces que una nota usa, sea en el texto o pegadas encima. */
    fun fotosDe(piezas: List<Pieza>): List<String> =
        piezas.flatMap { p -> (if (p.tipo == Pieza.FOTO || p.tipo == Pieza.FOTOS) p.ids else emptyList()) + p.pegados.mapNotNull { it.foto } }.distinct()

    fun vocesDe(piezas: List<Pieza>): List<String> =
        piezas.flatMap { p -> (if (p.tipo == Pieza.VOZ) p.ids else emptyList()) + p.pegados.mapNotNull { it.voz } }.distinct()

    /** El texto sin las marcas, para vistas previas, búsquedas y exportar. */
    fun limpio(texto: String): String =
        if (texto.indexOf(ABRE) < 0) texto
        else texto.split('\n').filterNot { esMarca(it) }.joinToString("\n").replace(Regex("⟦[^⟧]*⟧"), "")
}
