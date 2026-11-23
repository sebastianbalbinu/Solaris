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
        // pantalla completa: el mar se dibuja también detrás de las barras del sistema
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Casa.iniciar(this)
        Tema.iniciar(this)
        Clima.iniciar(this)
        Vigia.programar(this)
        Sonidos.iniciar(this)
        Boyas.iniciar(this)
        Paquete.iniciar(this)
        com.sebas.solaris.ui.Balanceo.iniciar(this)
        Paquete.descargar()
        Marea.iniciar(this)
        com.sebas.solaris.ui.Respiraciones.iniciar(this)
        Ambiente.iniciar(this)
        // la estación se cierra cuando se apaga la pantalla (no cada vez que se cambia de app)
        registerReceiver(alApagar, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        recibir(intent)
        setContent { Solaris(this) }
    }

    override fun onStart() {
        super.onStart()
        Sonidos.aLaVista = true
        Ambiente.aLaVista = true
    }

    private val alApagar = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (!Casa.pausarCandado) Casa.cerrar()
            com.sebas.solaris.ui.Nav.soloMar = false
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(alApagar) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // al volver: si se dio el permiso de instalar, sigue la actualización; si no, mira si hay marea nueva
        Marea.alVolver(this)
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
        Sonidos.aLaVista = false
        Ambiente.aLaVista = false
        // La grabación sigue sola (con su notificación); la reproducción se detiene.
        Grabadora.detenerReproduccion()
        com.sebas.solaris.ui.Oleaje.respirando = false
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
