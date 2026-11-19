package com.sebas.logia

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sebas.logia.ui.Logia

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nadie puede tomar capturas ni ver la casa en "apps recientes".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        Casa.iniciar(this)
        setContent { Logia(this) }
    }

    override fun onStop() {
        super.onStop()
        // Apenas sales de la app, el telón se cierra.
        if (!Casa.pausarCandado && !isChangingConfigurations) Casa.cerrar()
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
                .setTitle("Logia")
                .setSubtitle("Abre el telón")
                .setNegativeButtonText("Usar PIN")
                .setAllowedAuthenticators(TIPO)
                .build()
        )
    }
}
