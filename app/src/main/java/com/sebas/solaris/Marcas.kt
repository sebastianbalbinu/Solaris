package com.sebas.solaris

/**
 * La diagramación de una nota vive dentro de su texto, en líneas de marca que no se ven:
 *   ⟦foto ID⟧          una foto en ese punto del texto
 *   ⟦fotos ID ID ID⟧   un collage de 2 o 3 fotos
 *   ⟦voz ID⟧           una voz pegada
 * Así lo que se guarda en la nube no cambia de forma. Las notas viejas (sin marcas) se ven como antes:
 * primero las fotos, luego las voces y luego el texto.
 */
data class Pieza(val tipo: String, val ids: List<String> = emptyList(), val texto: String = "") {
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
    private val linea = Regex("^⟦(foto|fotos|voz)((?: [^ ⟧]+)+)⟧$")

    fun esMarca(l: String): Boolean = linea.matches(l.trim())

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
                val ids = m.groupValues[2].trim().split(' ').filter { it.isNotBlank() }
                val tipo = m.groupValues[1]
                // solo cuenta lo que de verdad sigue pegado en la nota
                val vivos = (if (tipo == Pieza.VOZ) ids.filter { it in voces } else ids.filter { it in fotos }).filter { usadas.add(it) }
                when {
                    vivos.isEmpty() -> {}
                    tipo == Pieza.VOZ -> vivos.forEach { piezas += Pieza(Pieza.VOZ, listOf(it)) }
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
        val puestas = piezas.flatMap { it.ids }.toSet()
        val sueltas = fotos.filter { it !in puestas }.map { Pieza(Pieza.FOTO, listOf(it)) } +
            voces.filter { it !in puestas }.map { Pieza(Pieza.VOZ, listOf(it)) }
        return sueltas + piezas
    }

    /** Vuelve a coser las piezas en un solo texto con sus marcas. */
    fun unir(piezas: List<Pieza>): String {
        val sb = StringBuilder()
        fun linea(s: String) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(s) }
        piezas.forEach { p ->
            when (p.tipo) {
                Pieza.TEXTO -> if (p.texto.isNotEmpty()) linea(p.texto)
                Pieza.FOTO, Pieza.FOTOS -> if (p.ids.isNotEmpty()) linea("$ABRE${if (p.ids.size > 1) "fotos" else "foto"} ${p.ids.joinToString(" ")}$CIERRA")
                Pieza.VOZ -> p.ids.firstOrNull()?.let { linea("${ABRE}voz $it$CIERRA") }
            }
        }
        return sb.toString()
    }

    /** El texto sin las marcas, para vistas previas, búsquedas y exportar. */
    fun limpio(texto: String): String =
        if (texto.indexOf(ABRE) < 0) texto
        else texto.split('\n').filterNot { esMarca(it) }.joinToString("\n").replace(Regex("⟦[^⟧]*⟧"), "")
}
