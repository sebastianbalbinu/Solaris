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
    /** Fondo de las hojas que suben desde abajo. */
    val hoja: Color,
    /** Fondo del segmento elegido en los selectores. */
    val seleccion: Color,
)

/** Los azules de Solaris, del abismo a la espuma, y el crema como único cálido. */
object Azules {
    val Abismo = Color(0xFF02070F)
    val Hondo = Color(0xFF061A2B)
    val Noche = Color(0xFF0B2A40)
    val Prusia = Color(0xFF123B57)
    val Cianotipo = Color(0xFF1F4E6E)
    val Niebla = Color(0xFF5F7F96)
    val Bruma = Color(0xFF7E9BB0)
    val Hielo = Color(0xFF9FC3DA)
    val Espuma = Color(0xFFB9C6CC)
    val Crema = Color(0xFFEDE3CF)
}

/** Un papel de día: crema con tinta azul, nunca negro puro. */
private fun papelDeDia(fondo: Long, superficie: Long, linea: Long, hoja: Long) = Paleta(
    fondo = Color(fondo),
    superficie = Color(superficie),
    texto = Color(0xFF12304A),
    texto2 = Color(0xFF5B6E80),
    linea = Color(linea),
    acento = Color(0xFF1F4E6E),
    boton = Color(0xFF12304A),
    botonTexto = Color(fondo),
    coloresLetra = listOf(Color(0xFF12304A), Color(0xFF1F4E6E), Color(0xFF2B2F35), Color(0xFF5B4636)),
    hoja = Color(hoja),
    seleccion = Color(fondo),
)

/** Claro: para leer bajo el sol. */
val DiaClaro = papelDeDia(0xFFF3EADB, 0xFFE9DFCD, 0xFFDDD2BE, 0xFFF8F1E5)
/** Tibio: papel de libro viejo, el de todos los días. */
val DiaTibio = papelDeDia(0xFFEDE3CF, 0xFFE1D6C0, 0xFFD5C9B1, 0xFFF5EDDF)
/** Penumbra: crema oscuro para leer al atardecer sin pasar al azul. */
val DiaPenumbra = papelDeDia(0xFFD3C8B2, 0xFFC7BBA4, 0xFFBDB09A, 0xFFE0D6C3)

val Papeles = listOf(DiaClaro, DiaTibio, DiaPenumbra)
val NombresPapel = listOf("Claro", "Tibio", "Penumbra")

/** Abismo: negro absoluto, para que la pantalla OLED apague esos píxeles; la letra es espuma. */
val Abismo = Paleta(
    fondo = Color(0xFF000000),
    superficie = Color(0xFF0B1620),
    texto = Color(0xFFD5DEE2),
    texto2 = Color(0xFF6F8798),
    linea = Color(0xFF14222D),
    acento = Azules.Hielo,
    boton = Color(0xFFD5DEE2),
    botonTexto = Color(0xFF000000),
    coloresLetra = listOf(Color(0xFFC9D4D9), Color(0xFFE8DCC4), Azules.Hielo, Azules.Bruma),
    hoja = Color(0xFF0B1620),
    seleccion = Color(0xFF1C2C38),
)

/** Cianotipo: azul de Prusia apagado con letra crema. Cansa menos que el blanco sobre negro. */
val Cianotipo = Paleta(
    fondo = Color(0xFF0E2A3F),
    superficie = Color(0xFF163650),
    texto = Color(0xFFE8DCC4),
    texto2 = Color(0xFF7E9BB0),
    linea = Color(0xFF1C3D57),
    acento = Azules.Hielo,
    boton = Color(0xFFE8DCC4),
    botonTexto = Color(0xFF0E2A3F),
    coloresLetra = listOf(Color(0xFFE8DCC4), Color(0xFFC9D4D9), Azules.Hielo, Color(0xFFD9C7A3)),
    hoja = Color(0xFF123650),
    seleccion = Color(0xFF1F4866),
)

val Oscuros = listOf(Abismo, Cianotipo)
val NombresOscuro = listOf("Abismo", "Cianotipo")

val NombresColorDia = listOf("Tinta azul", "Prusia", "Grafito", "Sepia")
val NombresColorNoche = listOf("Espuma", "Crema", "Hielo", "Bruma")
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
    /** Tono del papel de día: 0 claro, 1 tibio, 2 penumbra. */
    var papel by mutableIntStateOf(1)
        private set
    /** Modo oscuro: 0 abismo, 1 cianotipo. */
    var oscuro by mutableIntStateOf(0)
        private set

    fun iniciar(ctx: Context) {
        prefs = ctx.getSharedPreferences("tema", Context.MODE_PRIVATE)
        modo = prefs.getString("modo", "auto") ?: "auto"
        tamano = prefs.getInt("tamano", 1)
        colorDia = prefs.getInt("color_dia", 0)
        colorNoche = prefs.getInt("color_noche", 0)
        papel = prefs.getInt("papel", 1)
        oscuro = prefs.getInt("oscuro", 0)
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

    val p: Paleta get() = if (esNoche) Oscuros[oscuro.coerceIn(0, Oscuros.size - 1)] else Papeles[papel.coerceIn(0, Papeles.size - 1)]

    fun fijarOscuro(i: Int) {
        oscuro = i.coerceIn(0, Oscuros.size - 1)
        prefs.edit().putInt("oscuro", oscuro).apply()
    }

    fun fijarPapel(i: Int) {
        papel = i.coerceIn(0, Papeles.size - 1)
        prefs.edit().putInt("papel", papel).apply()
    }

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
            in 12..16 -> "Mediodía en" to "alta mar."
            in 17..19 -> "Entramos en" to "la sombra."
            in 20..23 -> "Turno de" to "medianoche."
            else -> "Hora de las" to "cosas extrañas."
        }
    }
}

/** La escala tipográfica de la estación: pocos tamaños, siempre los mismos. */
object Tipo {
    const val PORTADA = 46
    const val TITULO = 34
    const val ENCABEZADO = 23
    const val CUERPO = 17
    const val SECUNDARIO = 15
    const val NOTA = 13
    const val SENAL = 10
}
