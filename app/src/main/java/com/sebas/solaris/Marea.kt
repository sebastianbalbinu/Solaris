package com.sebas.solaris

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * La marea nueva: Solaris se actualiza sola. Mira en GitHub si hay una versión más reciente,
 * la baja (con Wi-Fi, sin preguntar) y abre el instalador de Android; solo queda tocar "Actualizar".
 * La casa (notas, voces, bóveda) no se toca: la app nueva se instala encima, con la misma firma.
 */
object Marea {
    private const val ULTIMA = "https://api.github.com/repos/sebastianbalbinu/Solaris/releases/latest"
    private const val APK = "https://github.com/sebastianbalbinu/Solaris/releases/download/v0.2.%d/Solaris.apk"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private var trabajo: Job? = null

    /** El número de la versión nueva, si la hay. */
    var nueva by mutableStateOf<Int?>(null)
        private set
    /** 0..1 mientras se baja; null si no. */
    var progreso by mutableStateOf<Float?>(null)
        private set
    /** Ya está bajada: tocar abre el instalador. */
    var lista by mutableStateOf(false)
        private set
    var mensaje by mutableStateOf<String?>(null)
        private set
    /** Se pidió permiso para instalar: al volver a la app, se instala. */
    private var esperandoPermiso = false

    val actual: Int get() = BuildConfig.VERSION_CODE

    private fun dir() = File(app.cacheDir, "marea").also { it.mkdirs() }
    private fun archivo(n: Int) = File(dir(), "Solaris-$n.apk")

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        // lo que quedó de actualizaciones ya instaladas se borra
        dir().listFiles()?.forEach { f ->
            val n = f.name.removePrefix("Solaris-").removeSuffix(".apk").toIntOrNull()
            if (n == null || n <= actual) f.delete()
        }
    }

    /** Una vez al día (o cuando se pide) se mira si hay marea nueva. */
    fun revisar(forzar: Boolean = false) {
        if (trabajo?.isActive == true) return
        val prefs = app.getSharedPreferences("marea", Context.MODE_PRIVATE)
        val ahora = System.currentTimeMillis()
        if (!forzar && ahora - prefs.getLong("revisada", 0L) < 20 * 60 * 60 * 1000L) return
        trabajo = scope.launch {
            try {
                mensaje = if (forzar) "Mirando el horizonte…" else null
                val texto = http.newCall(Request.Builder().url(ULTIMA).header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    r.body?.string() ?: ""
                }
                prefs.edit().putLong("revisada", ahora).apply()
                val n = JSONObject(texto).optString("tag_name").removePrefix("v0.2.").toIntOrNull()
                if (n != null && n > actual) {
                    nueva = n
                    mensaje = null
                    lista = archivo(n).exists()
                    // con Wi-Fi se baja sola, así tocar ya es instalar
                    if (!lista && wifi()) bajar(n)
                } else {
                    nueva = null
                    mensaje = if (forzar) "Tienes la última marea (0.2.$actual)." else null
                }
            } catch (e: Exception) {
                mensaje = if (forzar) "No se pudo mirar el horizonte. Revisa la conexión." else null
            }
        }
    }

    private fun bajar(n: Int) {
        val f = archivo(n)
        val tmp = File(dir(), "Solaris-$n.parcial")
        progreso = 0f
        try {
            http.newCall(Request.Builder().url(APK.format(n)).build()).execute().use { r ->
                if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                val cuerpo = r.body!!
                val total = cuerpo.contentLength().coerceAtLeast(1L)
                var hecho = 0L
                cuerpo.byteStream().use { i ->
                    tmp.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val k = i.read(buf)
                            if (k < 0) break
                            o.write(buf, 0, k)
                            hecho += k
                            progreso = (hecho.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            tmp.renameTo(f)
            lista = true
        } catch (e: Exception) {
            tmp.delete()
            mensaje = "No se pudo bajar la marea nueva. Toca para intentarlo otra vez."
        } finally {
            progreso = null
        }
    }

    /** Lo que hace tocar el aviso: bajar si hace falta y abrir el instalador. */
    fun tocar(ctx: Context) {
        val n = nueva ?: return
        if (progreso != null) return
        if (lista && archivo(n).exists()) { instalar(ctx, n); return }
        mensaje = null
        trabajo = scope.launch {
            bajar(n)
            if (lista) launchMain { instalar(ctx, n) }
        }
    }

    private fun launchMain(f: () -> Unit) = CoroutineScope(Dispatchers.Main).launch { f() }

    private fun instalar(ctx: Context, n: Int) {
        // la primera vez Android pide permitir instalar apps desde Solaris
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            esperandoPermiso = true
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        esperandoPermiso = false
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.archivos", archivo(n))
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Al volver de los ajustes de Android con el permiso dado, se sigue con la instalación. */
    fun alVolver(ctx: Context) {
        if (esperandoPermiso && Build.VERSION.SDK_INT >= 26 && ctx.packageManager.canRequestPackageInstalls()) {
            val n = nueva ?: return
            if (archivo(n).exists()) instalar(ctx, n)
        } else revisar()
    }

    private fun wifi(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
