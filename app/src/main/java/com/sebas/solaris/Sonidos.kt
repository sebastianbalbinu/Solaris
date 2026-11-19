package com.sebas.solaris

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Clics mecánicos: las teclas de la grabadora y los toques de la interfaz. */
object Sonidos {
    private var pool: SoundPool? = null
    private var tecla = 0
    private var suelta = 0
    private var tic = 0

    fun iniciar(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        tecla = p.load(ctx, R.raw.tecla, 1)
        suelta = p.load(ctx, R.raw.suelta, 1)
        tic = p.load(ctx, R.raw.tic, 1)
        pool = p
    }

    fun tecla() { pool?.play(tecla, 0.9f, 0.9f, 1, 0, 1f) }
    fun suelta() { pool?.play(suelta, 0.5f, 0.5f, 1, 0, 1f) }
    fun tic() { pool?.play(tic, 0.25f, 0.25f, 1, 0, 1f) }
}
