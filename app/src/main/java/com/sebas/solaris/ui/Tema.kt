package com.sebas.solaris.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.sebas.solaris.R
import java.util.Calendar

val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)
val Sans = FontFamily(
    Font(R.font.sans_regular, FontWeight.Normal),
    Font(R.font.sans_medium, FontWeight.Medium),
    Font(R.font.sans_semibold, FontWeight.SemiBold),
)
val Mono = FontFamily(
    Font(R.font.plex_mono, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

/** Colores de un modo. */
data class Paleta(
    val fondo: Color,
    val superficie: Color,
    val texto: Color,
    val texto2: Color,
    val linea: Color,
    val acento: Color,
    val boton: Color,
    val botonTexto: Color,
    val coloresLetra: List<Color>,
)

val Dia = Paleta(
    fondo = Color(0xFFF3E9D8),
    superficie = Color(0xFFE9DDC8),
    texto = Color(0xFF1B1A18),
    texto2 = Color(0xFF6F6555),
    linea = Color(0xFFE0D2BB),
    acento = Color(0xFF2F5D5A),
    boton = Color(0xFF1B1A18),
    botonTexto = Color(0xFFF3E9D8),
    coloresLetra = listOf(Color(0xFF1B1A18), Color(0xFF5B4636), Color(0xFF2F4A5C), Color(0xFF2F5D5A)),
)

/** Noche: negro absoluto, para que la pantalla OLED apague esos píxeles. */
val Noche = Paleta(
    fondo = Color(0xFF000000),
    superficie = Color(0xFF151517),
    texto = Color(0xFFECE6DA),
    texto2 = Color(0xFF8E8E93),
    linea = Color(0xFF1F1F22),
    acento = Color(0xFFA8E6EE),
    boton = Color(0xFFECE6DA),
    botonTexto = Color(0xFF000000),
    coloresLetra = listOf(Color(0xFFECE6DA), Color(0xFFB9B9BE), Color(0xFFA8E6EE), Color(0xFFE8C9A0)),
)

val NombresColorDia = listOf("Tinta", "Sepia", "Azul tinta", "Océano")
val NombresColorNoche = listOf("Hueso", "Niebla", "Hielo", "Ámbar")
val Tamanos = listOf(15, 17, 19, 22, 25)

/** Modo día/noche y preferencias de lectura. Se guardan en el celular. */
object Tema {
    private lateinit var prefs: SharedPreferences

    /** "auto", "dia" o "noche". */
    var modo by mutableStateOf("auto")
        private set
    var reloj by mutableIntStateOf(0)
        private set
    var tamano by mutableIntStateOf(1)
        private set
    var colorDia by mutableIntStateOf(0)
        private set
    var colorNoche by mutableIntStateOf(0)
        private set

    fun iniciar(ctx: Context) {
        prefs = ctx.getSharedPreferences("tema", Context.MODE_PRIVATE)
        modo = prefs.getString("modo", "auto") ?: "auto"
        tamano = prefs.getInt("tamano", 1)
        colorDia = prefs.getInt("color_dia", 0)
        colorNoche = prefs.getInt("color_noche", 0)
    }

    /** Se llama cada minuto para que el modo automático cambie solo al atardecer. */
    fun tic() { reloj++ }

    private fun esHoraDeNoche(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 18 || h < 6
    }

    val esNoche: Boolean
        get() {
            reloj
            return when (modo) {
                "dia" -> false
                "noche" -> true
                else -> esHoraDeNoche()
            }
        }

    val p: Paleta get() = if (esNoche) Noche else Dia

    fun alternar() = fijarModo(if (esNoche) "dia" else "noche")

    fun fijarModo(m: String) {
        modo = m
        prefs.edit().putString("modo", m).apply()
    }

    fun fijarTamano(i: Int) {
        tamano = i.coerceIn(0, Tamanos.size - 1)
        prefs.edit().putInt("tamano", tamano).apply()
    }

    fun fijarColor(i: Int) {
        if (esNoche) {
            colorNoche = i; prefs.edit().putInt("color_noche", i).apply()
        } else {
            colorDia = i; prefs.edit().putInt("color_dia", i).apply()
        }
    }

    val colorLetra: Color get() = p.coloresLetra[(if (esNoche) colorNoche else colorDia).coerceIn(0, 3)]
    val indiceColor: Int get() = if (esNoche) colorNoche else colorDia
    val tamanoLetra: Int get() = Tamanos[tamano.coerceIn(0, Tamanos.size - 1)]

    /** El saludo de la estación según la hora. */
    fun saludo(): Pair<String, String> {
        reloj
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..7 -> "Amanece sobre" to "el océano."
            in 8..11 -> "La estación" to "está en calma."
            in 12..16 -> "Mediodía en" to "la órbita."
            in 17..19 -> "Entramos en" to "la sombra."
            in 20..23 -> "Turno de" to "medianoche."
            else -> "Hora de las" to "cosas extrañas."
        }
    }
}
