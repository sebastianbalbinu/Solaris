package com.sebas.solaris.ui

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.sebas.solaris.Clima
import com.sebas.solaris.Mar
import com.sebas.solaris.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/*
 * Las escenas de Solaris como cianotipos vivos: el mar del Drake y la Tierra desde la estación.
 * Las dibuja la tarjeta gráfica (Android 13 en adelante); en celulares más viejos queda una lámina quieta.
 */

private fun leerSombra(ctx: Context, id: Int): String =
    ctx.resources.openRawResource(id).bufferedReader().use { it.readText() }

@RequiresApi(33)
private fun RuntimeShader.u(nombre: String, v: Float) {
    try { setFloatUniform(nombre, v) } catch (e: IllegalArgumentException) {}
}

/** El estado del mar se acerca despacio a la lectura real, para que los cambios no salten. */
private class EstadoMar {
    var ola = 3.0; var periodo = 11.0; var viento = 20.0; var nubes = 0.8
    var lluvia = 0.3; var vis = 10.0; var luz = 0.0; var alba = 0.0; var rayos = 0.0
    var flash = 0.0
    private var primera = true

    fun avanzar(m: Mar, dt: Double) {
        val hora = m.horaNumero()
        val albaMeta = when {
            hora in 4.5..7.5 -> 1.0 - abs(hora - 6.0) / 1.5
            hora in 17.5..20.5 -> 1.0 - abs(hora - 19.0) / 1.5
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val k = if (primera) 1.0 else (dt * 0.25).coerceAtMost(1.0)
        primera = false
        ola += (m.ola - ola) * k; periodo += (m.periodo - periodo) * k; viento += (m.viento - viento) * k
        nubes += (m.nubes / 100.0 - nubes) * k; lluvia += (m.lluvia - lluvia) * k; vis += (m.visibilidad - vis) * k
        luz += ((if (m.esDia) 1.0 else 0.0) - luz) * k; alba += (albaMeta - alba) * k
        rayos += ((if (m.rayos) 1.0 else 0.0) - rayos) * k
        flash *= exp(-dt * 7.0)
        if (rayos > 0.5 && Random.nextDouble() < dt * 0.09) flash = 0.8 + Random.nextDouble() * 0.2
    }
}

/** Corre un reloj a unos 30 cuadros por segundo mientras la pantalla está visible. */
@Composable
private fun relojDeEscena(alAvanzar: (Double) -> Unit = {}): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var antes = 0L
        while (isActive) {
            withFrameNanos { ahora ->
                if (antes != 0L) {
                    val dt = ((ahora - antes) / 1e9).coerceIn(0.0, 0.1)
                    t.floatValue += dt.toFloat()
                    alAvanzar(dt)
                }
                antes = ahora
            }
            delay(26)
        }
    }
    return t
}

// ---------------- el mar ----------------

@Composable
fun MarCianotipo(modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= 33) MarVivo(modifier)
    else Image(painterResource(R.drawable.mar_quieto), null, modifier, contentScale = ContentScale.Crop)
}

@RequiresApi(33)
@Composable
private fun MarVivo(modifier: Modifier) {
    val ctx = LocalContext.current
    val sombra = remember { try { RuntimeShader(leerSombra(ctx, R.raw.sombra_mar)) } catch (e: Exception) { null } }
    if (sombra == null) {
        Image(painterResource(R.drawable.mar_quieto), null, modifier, contentScale = ContentScale.Crop)
        return
    }
    val estado = remember { EstadoMar() }
    val t by relojDeEscena { dt -> estado.avanzar(Clima.mar, dt) }
    val pincel = remember { ShaderBrush(sombra) }
    Canvas(modifier) {
        val tt = t
        sombra.setFloatUniform("res", size.width, size.height)
        sombra.u("t", tt)
        sombra.u("ola", estado.ola.toFloat())
        sombra.u("periodo", estado.periodo.coerceAtLeast(4.0).toFloat())
        sombra.u("pic", (estado.viento / 45.0).coerceIn(0.0, 1.0).toFloat())
        sombra.u("nubes", estado.nubes.coerceIn(0.0, 1.0).toFloat())
        sombra.u("vis", estado.vis.coerceAtLeast(0.2).toFloat())
        sombra.u("luz", estado.luz.toFloat())
        sombra.u("alba", estado.alba.toFloat())
        sombra.u("lluvia", estado.lluvia.coerceIn(0.0, 6.0).toFloat())
        sombra.u("flash", estado.flash.toFloat())
        sombra.u("semilla", (tt * 12f).toInt().toFloat() % 97f)
        drawRect(pincel)
    }
}

// ---------------- la Tierra desde la estación ----------------

@Composable
fun PlanetaCianotipo(modifier: Modifier, llegada: () -> Float) {
    if (Build.VERSION.SDK_INT >= 33) PlanetaVivo(modifier, llegada)
    else Image(painterResource(R.drawable.planeta_quieto), null, modifier, contentScale = ContentScale.Crop)
}

@RequiresApi(33)
@Composable
private fun PlanetaVivo(modifier: Modifier, llegada: () -> Float) {
    val ctx = LocalContext.current
    val sombra = remember { try { RuntimeShader(leerSombra(ctx, R.raw.sombra_planeta)) } catch (e: Exception) { null } }
    if (sombra == null) {
        Image(painterResource(R.drawable.planeta_quieto), null, modifier, contentScale = ContentScale.Crop)
        return
    }
    val t by relojDeEscena()
    val pincel = remember { ShaderBrush(sombra) }
    Canvas(modifier) {
        val tt = t
        sombra.setFloatUniform("res", size.width, size.height)
        sombra.u("t", tt)
        sombra.u("llegada", llegada())
        sombra.u("semilla", (tt * 12f).toInt().toFloat() % 97f)
        drawRect(pincel)
    }
}
