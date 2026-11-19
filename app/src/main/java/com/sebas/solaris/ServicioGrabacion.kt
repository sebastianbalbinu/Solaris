package com.sebas.solaris

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Mantiene viva la grabación con la pantalla apagada o la app cerrada,
 * con una notificación fija y un botón para detener.
 */
class ServicioGrabacion : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Casa.iniciar(this)
        if (intent?.action == DETENER) {
            val id = Grabadora.idActual
            val inicio = Grabadora.inicioGrabacion
            if (Grabadora.grabando) {
                val dur = Grabadora.detenerGrabacion()
                if (id != null) {
                    if (dur > 600) Casa.dejarCintaPendiente(id, dur, inicio) else Casa.archivoCinta(id).delete()
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val gestor = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && gestor.getNotificationChannel(CANAL) == null) {
            gestor.createNotificationChannel(NotificationChannel(CANAL, "Grabación", NotificationManager.IMPORTANCE_LOW))
        }
        val abrir = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detener = PendingIntent.getService(
            this, 2, Intent(this, ServicioGrabacion::class.java).setAction(DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val aviso = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_rec)
            .setContentTitle("Solaris está grabando")
            .setContentText("La cinta sigue corriendo. Toca para volver a la estación.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .build()
        ServiceCompat.startForeground(
            this, 7, aviso,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val CANAL = "grabacion"
        private const val DETENER = "com.sebas.solaris.DETENER"

        fun iniciar(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, ServicioGrabacion::class.java)) } catch (e: Exception) {}
        }

        fun terminar(ctx: Context) {
            ctx.stopService(Intent(ctx, ServicioGrabacion::class.java))
        }
    }
}
