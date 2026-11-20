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
            )
        }

        fun cuarto(nombre: String, color: Int, cuando: Long = System.currentTimeMillis()) = Item(
            id = nuevoId(), kind = "cuarto", room = null, title = nombre, text = "",
            photos = emptyList(), color = color, created = cuando, updated = cuando,
        )
    }
}
