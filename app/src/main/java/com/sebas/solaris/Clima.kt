package com.sebas.solaris

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Cómo está el mar real bajo la estación. */
data class Mar(
    val ola: Double,
    val periodo: Double,
    val viento: Double,
    val nubes: Double,
    val lluvia: Double,
    val visibilidad: Double,
    val esDia: Boolean,
    val codigo: Int,
    val desfase: Int,
    val cuando: Long,
    val real: Boolean,
) {
    val rayos: Boolean get() = codigo >= 95 || (viento >= 40 && lluvia >= 2)

    val nombre: String
        get() = when {
            rayos -> "TEMPORAL CON RAYOS"
            viento >= 34 || ola >= 6 -> "TEMPORAL"
            visibilidad < 2 -> "NIEBLA"
            lluvia >= 0.5 -> "LLUVIA EN ALTA MAR"
            ola < 2.5 && viento < 16 -> "CALMA SOLEMNE"
            ola < 4.5 -> "MAREJADA"
            else -> "MAR GRUESA"
        }

    /** Hora local en el Paso de Drake. */
    fun horaLocal(ahora: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.format(Date(ahora + desfase * 1000L))
    }

    fun horaNumero(ahora: Long = System.currentTimeMillis()): Double {
        val segundos = ((ahora / 1000 + desfase) % 86400 + 86400) % 86400
        return segundos / 3600.0
    }

    fun json(): JSONObject = JSONObject().put("ola", ola).put("periodo", periodo).put("viento", viento).put("nubes", nubes)
        .put("lluvia", lluvia).put("vis", visibilidad).put("dia", esDia).put("codigo", codigo).put("desfase", desfase).put("cuando", cuando)

    companion object {
        /** Lo que se muestra antes de la primera lectura real. */
        val Inicial = Mar(3.4, 11.0, 24.0, 85.0, 0.4, 12.0, false, 61, -3 * 3600, 0, false)

        fun de(o: JSONObject) = Mar(
            o.optDouble("ola", 3.0), o.optDouble("periodo", 11.0), o.optDouble("viento", 20.0), o.optDouble("nubes", 80.0),
            o.optDouble("lluvia", 0.0), o.optDouble("vis", 10.0), o.optBoolean("dia"), o.optInt("codigo"),
            o.optInt("desfase", -3 * 3600), o.optLong("cuando"), true,
        )
    }
}

/**
 * Lee cada hora el mar del Paso de Drake desde Open-Meteo (gratis, sin llave).
 * Si no hay internet, usa la última lectura guardada.
 */
object Clima {
    const val LAT = -58.2
    const val LON = -64.67
    const val LUGAR = "PASO DE DRAKE · 58°12′S 64°40′O"

    private lateinit var prefs: SharedPreferences
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    var mar by mutableStateOf(Mar.Inicial)
        private set

    fun iniciar(ctx: Context) {
        prefs = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)
        prefs.getString("mar", null)?.let { try { mar = Mar.de(JSONObject(it)) } catch (e: Exception) {} }
    }

    suspend fun actualizar(forzar: Boolean = false) = withContext(Dispatchers.IO) {
        if (!forzar && mar.real && System.currentTimeMillis() - mar.cuando < 50 * 60 * 1000) return@withContext
        try {
            val tiempo = JSONObject(get(
                "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&current=wind_speed_10m,cloud_cover,precipitation,visibility,is_day,weather_code" +
                    "&wind_speed_unit=kn&timezone=auto"
            ))
            val ahora = tiempo.getJSONObject("current")
            val desfase = tiempo.optInt("utc_offset_seconds", -3 * 3600)
            val marino = JSONObject(get(
                "https://marine-api.open-meteo.com/v1/marine?latitude=$LAT&longitude=$LON" +
                    "&hourly=wave_height,wave_period&forecast_days=2&timezone=GMT"
            ))
            val horas = marino.getJSONObject("hourly")
            val tiempos = horas.getJSONArray("time")
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            val clave = f.format(Date())
            var i = 0
            for (k in 0 until tiempos.length()) if (tiempos.getString(k) == clave) { i = k; break }
            val olas = horas.getJSONArray("wave_height")
            val periodos = horas.getJSONArray("wave_period")
            val ola = olas.optDouble(i, Double.NaN).let { if (it.isNaN()) mar.ola else it }
            val periodo = periodos.optDouble(i, Double.NaN).let { if (it.isNaN()) mar.periodo else it }
            val nuevo = Mar(
                ola = ola,
                periodo = periodo.coerceIn(4.0, 20.0),
                viento = ahora.optDouble("wind_speed_10m", mar.viento),
                nubes = ahora.optDouble("cloud_cover", mar.nubes),
                lluvia = ahora.optDouble("precipitation", 0.0),
                visibilidad = ahora.optDouble("visibility", 10000.0) / 1000.0,
                esDia = ahora.optInt("is_day", 0) == 1,
                codigo = ahora.optInt("weather_code", 0),
                desfase = desfase,
                cuando = System.currentTimeMillis(),
                real = true,
            )
            mar = nuevo
            prefs.edit().putString("mar", nuevo.json().toString()).apply()
        } catch (e: Exception) {
            // sin conexión: el océano sigue con la última lectura
        }
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
}
