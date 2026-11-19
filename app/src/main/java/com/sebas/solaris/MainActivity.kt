package com.sebas.solaris

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sebas.solaris.ui.Solaris
import com.sebas.solaris.ui.Tema

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nadie puede tomar capturas ni ver la estación en "apps recientes".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        Casa.iniciar(this)
        Tema.iniciar(this)
        Clima.iniciar(this)
        Sonidos.iniciar(this)
        setContent { Solaris(this) }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Si se sale grabando, la cinta se guarda tal como va.
        if (Grabadora.grabando) {
            val id = Grabadora.idActual
            val inicio = Grabadora.inicioGrabacion
            val dur = Grabadora.detenerGrabacion()
            if (id != null && dur > 500) Casa.nuevaCinta(id, "registro sin nombre", dur, inicio)
        }
        Grabadora.detenerReproduccion()
        // Apenas sales de la app, la estación se cierra.
        if (!Casa.pausarCandado) Casa.cerrar()
    }
}

object Huella {
    private const val TIPO = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun disponible(act: FragmentActivity): Boolean =
        BiometricManager.from(act).canAuthenticate(TIPO) == BiometricManager.BIOMETRIC_SUCCESS

    fun pedir(act: FragmentActivity, alAbrir: () -> Unit) {
        if (!disponible(act)) return
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                alAbrir()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Solaris")
                .setSubtitle("Entrar a la estación")
                .setNegativeButtonText("Usar PIN")
                .setAllowedAuthenticators(TIPO)
                .build()
        )
    }
}
