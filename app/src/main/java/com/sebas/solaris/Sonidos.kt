package com.sebas.solaris

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Clics mecánicos, vibración y el rumor del mar. */
object Sonidos {
    /** Solo suena con la app a la vista: al salir, la pantalla de entrada se escribía sola y "crujía" de fondo. */
    @Volatile var aLaVista = true

    private var pool: SoundPool? = null
    private var tecla = 0
    private var suelta = 0
    private var tic = 0
    private var vibrador: Vibrator? = null
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("sonidos", Context.MODE_PRIVATE) }
    /** Los clics de los botones. */
    var conSonido: Boolean
        get() = prefs.getBoolean("sonido", true)
        set(v) { prefs.edit().putBoolean("sonido", v).apply() }
    /** Que los botones (y los rayos) se sientan en la mano. */
    var conVibracion: Boolean
        get() = prefs.getBoolean("vibracion", true)
        set(v) { prefs.edit().putBoolean("vibracion", v).apply() }

    fun iniciar(ctx: Context) {
        if (pool != null) return
        app = ctx.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // con el volumen de medios: el de "sonidos del sistema" suele estar en cero y no se oía nada
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        if (conSonido && aLaVista) pool?.play(tecla, 1f, 1f, 1, 0, 1f)
        vibrar(fuerte = true)
    }

    fun suelta() { if (conSonido && aLaVista) pool?.play(suelta, 0.7f, 0.7f, 1, 0, 1f) }

    fun tic() {
        if (conSonido && aLaVista) pool?.play(tic, 0.6f, 0.6f, 1, 0, 1f)
        vibrar(fuerte = false)
    }

    /** Un tic muy suave, sin vibración: el arranque escribiéndose. */
    fun teclear() { if (conSonido && aLaVista) pool?.play(tic, 0.25f, 0.25f, 0, 0, 1.25f) }

    /**
     * Un rayo se siente siempre: un chasquido seco al caer y, segundos después, el trueno que rueda.
     * Funciona aunque el celular no permita graduar la fuerza de la vibración.
     */
    fun rayo(lejano: Boolean) {
        val v = vibrador ?: return
        if (!conVibracion || !aLaVista || !v.hasVibrator()) return
        try {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(if (lejano) 18 else 30, if (lejano) 120 else 255))
            else v.vibrate(VibrationEffect.createOneShot(if (lejano) 15 else 25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    fun trueno(lejano: Boolean) {
        val v = vibrador ?: return
        if (!conVibracion || !aLaVista || !v.hasVibrator()) return
        val f = if (lejano) 0.55f else 1f
        try {
            if (v.hasAmplitudeControl()) {
                val tiempos = longArrayOf(0, 90, 60, 160, 90, 320, 120, 180)
                val fuerza = intArrayOf(0, (90 * f).toInt(), (30 * f).toInt(), (150 * f).toInt(), (45 * f).toInt(), (110 * f).toInt(), (20 * f).toInt(), (60 * f).toInt())
                v.vibrate(VibrationEffect.createWaveform(tiempos, fuerza, -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(if (lejano) longArrayOf(0, 60, 90, 120) else longArrayOf(0, 90, 70, 180, 90, 120), -1))
            }
        } catch (e: Exception) {}
    }

    /** Un pulso suave, para respirar (al cambiar de inhalar a exhalar). */
    fun pulso() {
        val v = vibrador ?: return
        if (!conVibracion || !aLaVista) return
        try {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(40, 70))
            else v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    /** El acople con la estación: una vibración firme. */
    fun acople() { vibrar(fuerte = true) }

    /**
     * Pulsos explícitos (duración y fuerza) en vez de los efectos predefinidos del sistema:
     * muchos celulares (Xiaomi, entre ellos) ignoran esos efectos y los botones no se sentían.
     */
    private fun vibrar(fuerte: Boolean) {
        val v = vibrador ?: return
        if (!conVibracion || !aLaVista) return
        try {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(if (fuerte) 26 else 12, if (fuerte) 220 else 110))
            else v.vibrate(VibrationEffect.createOneShot(if (fuerte) 22 else 10, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

}
