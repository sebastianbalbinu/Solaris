package com.sebas.solaris

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Clics mecánicos, vibración y el rumor del mar. */
object Sonidos {
    private var pool: SoundPool? = null
    private var tecla = 0
    private var suelta = 0
    private var tic = 0
    private var vibrador: Vibrator? = null
    private var mar: MediaPlayer? = null
    private lateinit var app: Context

    fun iniciar(ctx: Context) {
        if (pool != null) return
        app = ctx.applicationContext
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
        vibrador = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** Tecla de la grabadora: suena y se siente en la mano. */
    fun tecla() {
        pool?.play(tecla, 0.9f, 0.9f, 1, 0, 1f)
        vibrar(fuerte = true)
    }

    fun suelta() { pool?.play(suelta, 0.5f, 0.5f, 1, 0, 1f) }

    fun tic() {
        pool?.play(tic, 0.25f, 0.25f, 1, 0, 1f)
        vibrar(fuerte = false)
    }

    private fun vibrar(fuerte: Boolean) {
        val v = vibrador ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                v.vibrate(VibrationEffect.createPredefined(if (fuerte) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(if (fuerte) 22 else 8, if (fuerte) 200 else 60))
            }
        } catch (e: Exception) {}
    }

    /** El rumor del mar: un bucle suave cuyo volumen sigue a la tormenta. */
    fun mar(encendido: Boolean, intensidad: Float) {
        if (!encendido) {
            mar?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
            mar = null
            return
        }
        val m = mar ?: try {
            MediaPlayer.create(app, R.raw.mar)?.also { it.isLooping = true; it.start(); mar = it }
        } catch (e: Exception) { null } ?: return
        val vol = (0.18f + intensidad * 0.6f).coerceIn(0.05f, 0.85f)
        m.setVolume(vol, vol)
    }
}
