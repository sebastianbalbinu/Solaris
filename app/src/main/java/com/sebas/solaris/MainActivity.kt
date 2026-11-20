package com.sebas.solaris

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.sebas.solaris.ui.Nav
import com.sebas.solaris.ui.Solaris
import com.sebas.solaris.ui.Tema

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nadie puede tomar capturas ni ver la estación en "apps recientes".
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // pantalla completa: el mar se dibuja también detrás de las barras del sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Casa.iniciar(this)
        Tema.iniciar(this)
        Clima.iniciar(this)
        Sonidos.iniciar(this)
        recibir(intent)
        setContent { Solaris(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibir(intent)
    }

    /** Lo que pide el widget: se cumple apenas se entra a la estación. */
    private fun recibir(intent: Intent?) {
        when (intent?.action) {
            Widget.ESCRIBIR -> Nav.pendiente = "escribir"
            Widget.GRABAR -> Nav.pendiente = "grabar"
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // La grabación sigue sola (con su notificación); la reproducción se detiene.
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
