package com.sebas.solaris

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * Todo lo que vive en la casa es un Item: una página ("entrada") o una habitación ("cuarto").
 * Supabase nunca ve estos campos: el Item completo se cifra antes de salir.
 */
/** Inmutable: Compose puede saltarse las tarjetas que no cambiaron al deslizar. */
@androidx.compose.runtime.Immutable
data class Item(
    val id: String,
    val kind: String,
    val room: String?,
    val title: String,
    val text: String,
    val photos: List<String>,
    val color: Int,
    val created: Long,
    val updated: Long,
    val deleted: Boolean = false,
    val dur: Long = 0,
    /** Cómo va pegada cada foto en la lámina: "tamaño,giro" (tamaño 0 pequeña, 1 mediana, 2 grande; giro en grados). */
    val estilos: Map<String, String> = emptyMap(),
    /** Pie de cada foto (lo que se escribe debajo, en cursiva). */
    val pies: Map<String, String> = emptyMap(),
    /** Texto al lado de cada foto, cuando va diagramada como revista. */
    val lados: Map<String, String> = emptyMap(),
    /** Cómo va la lámina: "centro", "poema", "capitular" (separados por coma). */
    val forma: String = "",
    /** Voces pegadas en la nota (ids de cintas). */
    val voces: List<String> = emptyList(),
    /** Cómo estaba el Drake cuando se escribió. */
    val sello: String = "",
) {
    val esEntrada: Boolean get() = kind == "entrada"
    val esCuarto: Boolean get() = kind == "cuarto"
    val esCinta: Boolean get() = kind == "cinta"
    /** Un rumor es sonido de la vida que no se transcribe; una voz, ideas o poemas dichos en vivo. */
    val esRumor: Boolean get() = esCinta && room == "rumor"

    /** Páginas que suma al tomo: una por cada ~180 palabras (mínimo una) y una por cada foto. */
    val paginas: Int
        get() {
            if (!esEntrada || deleted) return 0
            val palabras = text.split(Regex("\\s+")).count { it.isNotBlank() } + title.split(Regex("\\s+")).count { it.isNotBlank() }
            return max(1, ceil(palabras / 180.0).toInt()) + photos.size
        }

    fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("room", room ?: JSONObject.NULL)
        .put("title", title)
        .put("text", text)
        .put("photos", JSONArray(photos))
        .put("color", color)
        .put("created", created)
        .put("updated", updated)
        .put("deleted", deleted)
        .put("dur", dur)
        .put("estilos", JSONObject(estilos))
        .put("pies", JSONObject(pies))
        .put("lados", JSONObject(lados))
        .put("forma", forma)
        .put("voces", JSONArray(voces))
        .put("sello", sello)

    companion object {
        fun nuevoId(): String = UUID.randomUUID().toString()

        fun de(o: JSONObject): Item {
            val fotos = o.optJSONArray("photos")
            return Item(
                id = o.getString("id"),
                kind = o.optString("kind", "entrada"),
                room = if (o.isNull("room")) null else o.optString("room"),
                title = o.optString("title"),
                text = o.optString("text"),
                photos = if (fotos == null) emptyList() else (0 until fotos.length()).map { fotos.getString(it) },
                color = o.optInt("color"),
                created = o.optLong("created"),
                updated = o.optLong("updated"),
                deleted = o.optBoolean("deleted"),
                dur = o.optLong("dur"),
                estilos = mapa(o, "estilos"),
                pies = mapa(o, "pies"),
                lados = mapa(o, "lados"),
                forma = o.optString("forma"),
                voces = o.optJSONArray("voces")?.let { v -> (0 until v.length()).map { v.getString(it) } } ?: emptyList(),
                sello = o.optString("sello"),
            )
        }

        private fun mapa(o: JSONObject, k: String): Map<String, String> =
            o.optJSONObject(k)?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap()

        fun cuarto(nombre: String, color: Int, cuando: Long = System.currentTimeMillis()) = Item(
            id = nuevoId(), kind = "cuarto", room = null, title = nombre, text = "",
            photos = emptyList(), color = color, created = cuando, updated = cuando,
        )
    }
}

/** Cómo va la lámina entera: alineación, modo poema y letra capitular. */
data class Forma(
    val centro: Boolean = false,
    val poema: Boolean = false,
    val capitular: Boolean = false,
    /** Aire entre renglones: 0 junto, 1 normal, 2 amplio. */
    val aire: Int = 1,
    /** Márgenes de la página: 0 estrechos, 1 normales, 2 anchos. */
    val margen: Int = 1,
    /** El cuerpo en serif, como un libro, aunque no sea poema. */
    val serif: Boolean = false,
    /** El papel: 0 liso, 1 rayado, 2 cuadriculado, 3 kraft, 4 cuaderno (con margen rojo), 5 fotográfico. */
    val papel: Int = 0,
    /** La letra: 0 la de siempre (sans o serif), 1 máquina de escribir, 2 a mano. */
    val letra: Int = 0,
) {
    fun texto() = listOfNotNull(
        "centro".takeIf { centro }, "poema".takeIf { poema }, "capitular".takeIf { capitular },
        "aire$aire".takeIf { aire != 1 }, "margen$margen".takeIf { margen != 1 }, "serif".takeIf { serif },
        "papel$papel".takeIf { papel != 0 }, "letra$letra".takeIf { letra != 0 },
    ).joinToString(",")
    /** El poema siempre va centrado. */
    val centrado: Boolean get() = centro || poema
    /** Cuánto se multiplica el interlineado. */
    val factorAire: Float get() = when (aire) { 0 -> 0.86f; 2 -> 1.22f; else -> 1f }
    /** Margen a cada lado, en dp. */
    val margenDp: Int get() = when (margen) { 0 -> 16; 2 -> 40; else -> 24 } + (if (papel == 4) 14 else 0)
    companion object {
        private fun entero(f: Set<String>, prefijo: String, def: Int, max: Int) =
            f.firstOrNull { it.startsWith(prefijo) }?.removePrefix(prefijo)?.toIntOrNull()?.coerceIn(0, max) ?: def
        fun de(s: String?): Forma {
            val f = s?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
            return Forma(
                "centro" in f, "poema" in f, "capitular" in f,
                aire = entero(f, "aire", 1, 2),
                margen = entero(f, "margen", 1, 2),
                serif = "serif" in f,
                papel = entero(f, "papel", 0, 5),
                letra = entero(f, "letra", 0, 2),
            )
        }
        /** Plantillas para empezar una página. */
        val Plantillas = listOf(
            "Revista" to Forma(capitular = true, margen = 2, serif = true),
            "Cuaderno" to Forma(papel = 4, letra = 2, aire = 2),
            "Álbum" to Forma(papel = 3, letra = 1, margen = 0),
            "Carta" to Forma(papel = 1, serif = true, aire = 2, margen = 2),
            "Bitácora" to Forma(papel = 2, letra = 1),
        )
    }
}

/** Cómo va pegada una foto: su tamaño y un giro leve, como si se hubiera pegado a mano. */
data class EstiloFoto(val tam: Int, val giro: Float, val pos: Int = SOLA) {
    fun texto() = "$tam," + String.format(java.util.Locale.US, "%.1f", giro) + ",$pos"
    /** Va al lado del texto, como en una revista. */
    val alLado: Boolean get() = pos != SOLA
    val ancho: Float get() = when (tam) { 0 -> 0.48f; 2, 3 -> 1f; else -> 0.7f }
    /** A sangre: de borde a borde de la pantalla, sin papel ni cinta, como una foto de revista. */
    val aSangre: Boolean get() = tam == 3 && !alLado

    companion object {
        fun de(s: String?): EstiloFoto {
            val p = s?.split(",") ?: return EstiloFoto(1, 0f)
            return EstiloFoto(
                p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 3) ?: 1,
                p.getOrNull(1)?.toFloatOrNull()?.coerceIn(-10f, 10f) ?: 0f,
                p.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 2) ?: SOLA,
            )
        }
        const val SOLA = 0
        const val IZQUIERDA = 1
        const val DERECHA = 2

        /** Una foto recién pegada: mediana y un poco chueca. */
        fun nueva() = EstiloFoto(1, (kotlin.random.Random.nextFloat() * 5f - 2.5f))
    }
}
