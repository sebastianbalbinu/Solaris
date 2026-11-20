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
                estilos = o.optJSONObject("estilos")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap(),
            )
        }

        fun cuarto(nombre: String, color: Int, cuando: Long = System.currentTimeMillis()) = Item(
            id = nuevoId(), kind = "cuarto", room = null, title = nombre, text = "",
            photos = emptyList(), color = color, created = cuando, updated = cuando,
        )
    }
}

/** Cómo va pegada una foto: su tamaño y un giro leve, como si se hubiera pegado a mano. */
data class EstiloFoto(val tam: Int, val giro: Float) {
    fun texto() = "$tam," + String.format(java.util.Locale.US, "%.1f", giro)
    val ancho: Float get() = when (tam) { 0 -> 0.48f; 2 -> 1f; else -> 0.7f }

    companion object {
        fun de(s: String?): EstiloFoto {
            val p = s?.split(",") ?: return EstiloFoto(1, 0f)
            return EstiloFoto(p.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 2) ?: 1, p.getOrNull(1)?.toFloatOrNull()?.coerceIn(-10f, 10f) ?: 0f)
        }

        /** Una foto recién pegada: mediana y un poco chueca. */
        fun nueva() = EstiloFoto(1, (kotlin.random.Random.nextFloat() * 5f - 2.5f))
    }
}
