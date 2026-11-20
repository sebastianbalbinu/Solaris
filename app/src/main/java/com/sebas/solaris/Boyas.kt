package com.sebas.solaris

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Una boya: un recordatorio tranquilo ("¿ya me tomé la pastilla?"). Flota en la orilla desde su hora
 * hasta que uno la toca; si se quiere, llega un aviso silencioso. Vive solo en este celular.
 */
data class Boya(val id: String, val nombre: String, val minuto: Int?, val aviso: Boolean) {
    val hora: String get() = minuto?.let { "%02d:%02d".format(it / 60, it % 60) } ?: ""
}

object Boyas {
    private const val CANAL = "boyas"
    private var app: Context? = null
    private lateinit var prefs: SharedPreferences

    var lista by mutableStateOf<List<Boya>>(emptyList())
        private set
    var hechasHoy by mutableStateOf<Set<String>>(emptySet())
        private set

    fun iniciar(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        prefs = ctx.applicationContext.getSharedPreferences("boyas", Context.MODE_PRIVATE)
        cargar()
    }

    private fun hoy(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun cargar() {
        val arr = JSONArray(prefs.getString("lista", "[]"))
        lista = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Boya(o.getString("id"), o.getString("nombre"), if (o.isNull("minuto")) null else o.getInt("minuto"), o.optBoolean("aviso"))
        }
        refrescarHechas()
    }

    /** Las marcas de "hecho" valen solo por el día. */
    fun refrescarHechas() {
        val h = hoy()
        hechasHoy = lista.filter { prefs.getString("hecha_" + it.id, "") == h }.map { it.id }.toSet()
    }

    private fun escribir() {
        val arr = JSONArray()
        lista.forEach { b ->
            arr.put(JSONObject().put("id", b.id).put("nombre", b.nombre).put("minuto", b.minuto ?: JSONObject.NULL).put("aviso", b.aviso))
        }
        prefs.edit().putString("lista", arr.toString()).apply()
    }

    fun nueva(nombre: String, minuto: Int?, aviso: Boolean) = guardar(Boya(UUID.randomUUID().toString(), nombre.trim(), minuto, aviso))

    fun guardar(b: Boya) {
        lista = lista.filter { it.id != b.id } + b
        lista = lista.sortedBy { it.minuto ?: -1 }
        escribir()
        programar(b)
    }

    fun borrar(id: String) {
        cancelar(id)
        lista = lista.filter { it.id != id }
        prefs.edit().remove("hecha_$id").apply()
        escribir()
    }

    /** Uno la tocó: se hunde hasta mañana. */
    fun marcar(id: String) {
        prefs.edit().putString("hecha_$id", hoy()).apply()
        refrescarHechas()
        val ctx = app ?: return
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode())
        lista.firstOrNull { it.id == id }?.let { programar(it) }
    }

    /** Las que flotan ahora: ya llegó su hora y todavía no se tocaron. */
    fun visibles(ahora: Calendar = Calendar.getInstance()): List<Boya> {
        val min = ahora.get(Calendar.HOUR_OF_DAY) * 60 + ahora.get(Calendar.MINUTE)
        return lista.filter { it.id !in hechasHoy && (it.minuto == null || min >= it.minuto) }
    }

    // ---------------- los avisos silenciosos ----------------

    private fun intento(ctx: Context, id: String, accion: String): PendingIntent {
        val i = Intent(ctx, AvisoBoya::class.java).setAction(accion).putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, (accion + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun programarTodas() = lista.forEach { programar(it) }

    private fun programar(b: Boya) {
        val ctx = app ?: return
        val alarma = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intento(ctx, b.id, AvisoBoya.SONAR)
        alarma.cancel(pi)
        val m = b.minuto ?: return
        if (!b.aviso) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // si ya pasó su hora hoy, o ya se tocó, el próximo aviso es mañana
        if (c.timeInMillis <= System.currentTimeMillis() || b.id in hechasHoy) c.add(Calendar.DAY_OF_YEAR, 1)
        alarma.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pi)
    }

    private fun cancelar(id: String) {
        val ctx = app ?: return
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(intento(ctx, id, AvisoBoya.SONAR))
    }

    fun avisar(id: String) {
        val ctx = app ?: return
        refrescarHechas()
        val b = lista.firstOrNull { it.id == id } ?: return
        if (b.id in hechasHoy) { programar(b); return }
        val gestor = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && gestor.getNotificationChannel(CANAL) == null) {
            gestor.createNotificationChannel(NotificationChannel(CANAL, "Boyas", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Recordatorios tranquilos, sin sonido"
                setSound(null, null)
                enableVibration(false)
            })
        }
        val abrir = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // en la pantalla bloqueada no se lee qué es: solo "una boya"
        val publica = NotificationCompat.Builder(ctx, CANAL).setSmallIcon(R.drawable.ic_boya)
            .setContentTitle("Solaris").setContentText("Una boya flota en tu orilla").build()
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_boya)
            .setContentTitle(b.nombre)
            .setContentText("Una boya flota en tu orilla")
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publica)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .addAction(0, "Hecho", intento(ctx, b.id, AvisoBoya.HECHO))
            .build()
        try { gestor.notify(b.id.hashCode(), n) } catch (e: SecurityException) {}
        // mañana otra vez
        programar(b)
    }
}

/** Recibe la hora de una boya, el "Hecho" de la notificación y el reinicio del celular. */
class AvisoBoya : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boyas.iniciar(ctx)
        when (intent.action) {
            SONAR -> intent.getStringExtra("id")?.let { Boyas.avisar(it) }
            HECHO -> intent.getStringExtra("id")?.let { Boyas.marcar(it) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Boyas.programarTodas()
        }
    }

    companion object {
        const val SONAR = "com.sebas.solaris.BOYA_SONAR"
        const val HECHO = "com.sebas.solaris.BOYA_HECHO"
    }
}
