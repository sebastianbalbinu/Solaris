package com.sebas.solaris

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Widget de la pantalla de inicio: escribir o grabar con un toque. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, gestor: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_solaris)
            v.setOnClickPendingIntent(R.id.widget_escribir, abrir(ctx, ESCRIBIR, 11))
            v.setOnClickPendingIntent(R.id.widget_grabar, abrir(ctx, GRABAR, 12))
            gestor.updateAppWidget(id, v)
        }
    }

    private fun abrir(ctx: Context, accion: String, codigo: Int): PendingIntent =
        PendingIntent.getActivity(
            ctx, codigo,
            Intent(ctx, MainActivity::class.java).setAction(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ESCRIBIR = "com.sebas.solaris.ESCRIBIR"
        const val GRABAR = "com.sebas.solaris.GRABAR"
    }
}
