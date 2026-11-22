package com.sebas.solaris

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * El vigía: cada media hora, aunque Solaris esté cerrada, mira cómo está el Drake y lo apunta.
 * Así al entrar el mar ya está como está allá, sin esperar a preguntar.
 */
class Vigia(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Clima.iniciar(applicationContext)
        Clima.actualizar(forzar = true)
        return Result.success()
    }

    companion object {
        fun programar(ctx: Context) {
            val pedido = PeriodicWorkRequestBuilder<Vigia>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("vigia-drake", ExistingPeriodicWorkPolicy.KEEP, pedido)
        }
    }
}
