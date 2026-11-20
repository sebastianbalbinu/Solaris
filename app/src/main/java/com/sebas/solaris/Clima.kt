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
    /** Hora local (en el Drake) a la que sale y se pone el sol hoy. */
    val salida: Double = 5.0,
    val puesta: Double = 21.0,
    /** Índice geomagnético Kp (NOAA): desde 5, la aurora austral se puede ver a esta latitud. */
    val kp: Double = 0.0,
) {
    /** Cae nieve o aguanieve allá. */
    val nieva: Boolean get() = codigo in 71..77 || codigo == 85 || codigo == 86

    /** Cuánto de la luna está iluminado ahora mismo (0 nueva, 1 llena). */
    fun luna(ahora: Long = System.currentTimeMillis()): Double {
        val dias = (ahora / 86_400_000.0 + 2440587.5) - 2451550.1
        val edad = ((dias % 29.530588853) + 29.530588853) % 29.530588853
        return (1 - kotlin.math.cos(2 * Math.PI * edad / 29.530588853)) / 2
    }

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
        .put("salida", salida).put("puesta", puesta).put("kp", kp)

    companion object {
        /** Lo que se muestra antes de la primera lectura real. */
        val Inicial = Mar(3.4, 11.0, 24.0, 85.0, 0.4, 12.0, false, 61, -3 * 3600, 0, false)

        fun de(o: JSONObject) = Mar(
            o.optDouble("ola", 3.0), o.optDouble("periodo", 11.0), o.optDouble("viento", 20.0), o.optDouble("nubes", 80.0),
            o.optDouble("lluvia", 0.0), o.optDouble("vis", 10.0), o.optBoolean("dia"), o.optInt("codigo"),
            o.optInt("desfase", -3 * 3600), o.optLong("cuando"), true,
            o.optDouble("salida", 5.0), o.optDouble("puesta", 21.0), o.optDouble("kp", 0.0),
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
                    "&daily=sunrise,sunset&forecast_days=1&wind_speed_unit=kn&timezone=auto"
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
            fun horaDe(iso: String?): Double? = iso?.substringAfter('T', "")?.split(":")?.let {
                val h = it.getOrNull(0)?.toDoubleOrNull() ?: return@let null
                h + (it.getOrNull(1)?.toDoubleOrNull() ?: 0.0) / 60.0
            }
            val diario = tiempo.optJSONObject("daily")
            val salida = horaDe(diario?.optJSONArray("sunrise")?.optString(0)) ?: mar.salida
            val puesta = horaDe(diario?.optJSONArray("sunset")?.optString(0)) ?: mar.puesta
            // la actividad geomagnética del planeta (la misma para todo el hemisferio)
            val kp = try {
                val arr = org.json.JSONArray(get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"))
                val ultimo = arr.get(arr.length() - 1)
                when (ultimo) {
                    is org.json.JSONArray -> ultimo.optString(1).toDoubleOrNull()
                    is JSONObject -> ultimo.optDouble("Kp", ultimo.optDouble("kp_index", Double.NaN)).takeIf { !it.isNaN() }
                    else -> null
                } ?: mar.kp
            } catch (e: Exception) { mar.kp }
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
                salida = salida,
                puesta = puesta,
                kp = kp,
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
