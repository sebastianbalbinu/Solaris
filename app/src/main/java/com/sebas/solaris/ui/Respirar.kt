package com.sebas.solaris.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/*
 * Respirar con el mar. Un patrón marca el ritmo (inhala, retén, exhala, pausa) con un anillo fino;
 * la luz del centro la mueve uno con el dedo: hacia arriba se toma aire, hacia abajo se suelta.
 * Si no se toca, la luz sigue sola al anillo. Los patrones propios se guardan.
 */

/** Un ritmo de respiración, en segundos. */
data class Patron(val nombre: String, val inhala: Int, val retiene: Int, val exhala: Int, val pausa: Int, val propio: Boolean = false) {
    val cifra: String get() = listOf(inhala, retiene, exhala, pausa).filter { it > 0 }.joinToString("·")
    val ciclo: Int get() = inhala + retiene + exhala + pausa

    /** En qué momento del ciclo va: (paso 0..3, fracción del paso, segundos que quedan). */
    fun en(t: Float): Triple<Int, Float, Int> {
        var s = t % ciclo.coerceAtLeast(1)
        listOf(inhala, retiene, exhala, pausa).forEachIndexed { i, d ->
            if (d > 0 && s < d) return Triple(i, s / d, kotlin.math.ceil(d - s).toInt())
            s -= d
        }
        return Triple(0, 0f, inhala)
    }

    fun json(): JSONObject = JSONObject().put("n", nombre).put("i", inhala).put("r", retiene).put("e", exhala).put("p", pausa)
}

object Respiraciones {
    val base = listOf(
        Patron("Calma", 4, 0, 6, 0),
        Patron("Caja", 4, 4, 4, 4),
        Patron("Dormir", 4, 7, 8, 0),
        Patron("Coherente", 5, 0, 5, 0),
    )
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("respirar", Context.MODE_PRIVATE) }
    var propios by mutableStateOf<List<Patron>>(emptyList())
        private set
    var elegido by mutableStateOf(base[0])

    val todos: List<Patron> get() = base + propios

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        propios = try {
            val a = JSONArray(prefs.getString("propios", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Patron(it.getString("n"), it.getInt("i"), it.getInt("r"), it.getInt("e"), it.getInt("p"), propio = true)
            }
        } catch (e: Exception) { emptyList() }
        val nombre = prefs.getString("elegido", null)
        elegido = todos.firstOrNull { it.nombre == nombre } ?: base[0]
    }

    fun elegir(p: Patron) { elegido = p; prefs.edit().putString("elegido", p.nombre).apply() }

    fun guardar(p: Patron, antes: String? = null) {
        val nuevo = p.copy(propio = true)
        propios = propios.filter { it.nombre != (antes ?: p.nombre) && it.nombre != p.nombre } + nuevo
        escribir(); elegir(nuevo)
    }

    fun borrar(p: Patron) {
        propios = propios.filter { it.nombre != p.nombre }
        escribir()
        if (elegido.nombre == p.nombre) elegir(base[0])
    }

    private fun escribir() = prefs.edit().putString("propios", JSONArray(propios.map { it.json() }).toString()).apply()
}

private val NombresPaso = listOf("inhala", "retén", "exhala", "pausa")

@Composable
fun Respiracion() {
    val visible = Oleaje.respirando
    val presencia by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 1400 else 900), label = "presencia")
    if (presencia < 0.001f && !visible) return
    if (visible) BackHandler { Oleaje.respirando = false }

    val patron = Respiraciones.elegido
    var guia by remember { mutableFloatStateOf(0f) }   // 0 vacío, 1 lleno: lo que marca el patrón
    var luz by remember { mutableFloatStateOf(0f) }    // la luz del centro: el dedo, o la guía si no se toca
    var paso by remember { mutableIntStateOf(0) }
    var quedan by remember { mutableIntStateOf(patron.inhala) }
    var tocando by remember { mutableStateOf(false) }
    var dedoSube by remember { mutableStateOf<Boolean?>(null) }
    var pista by remember { mutableStateOf(true) }
    var editando by remember { mutableStateOf<Patron?>(null) }

    LaunchedEffect(visible, patron) {
        if (!visible) return@LaunchedEffect
        guia = 0f; paso = 0; quedan = patron.inhala
        delay(700)
        val inicio = System.nanoTime()
        var antes = -1
        while (isActive && Oleaje.respirando) {
            val t = (System.nanoTime() - inicio) / 1e9f
            if (t > 15 * 60) { Oleaje.respirando = false; break }
            val (i, f, q) = patron.en(t)
            val suave = f * f * (3 - 2 * f)
            guia = when (i) { 0 -> suave; 1 -> 1f; 2 -> 1f - suave; else -> 0f }
            if (!tocando) luz += (guia - luz) * 0.12f
            paso = i; quedan = q
            if (i != antes) { if (antes >= 0) Sonidos.pulso(); antes = i }
            if (t > 9f) pista = false
            delay(16)
        }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = presencia }
            .pointerInput(Unit) {
                // arrastrar hacia arriba llena la luz (inhalar), hacia abajo la vacía (exhalar); un toque suelto termina
                val recorrido = 260.dp.toPx()
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = true)
                    var y = d.position.y
                    var arrastro = false
                    var ultimaDir: Boolean? = null
                    var ajeno = false
                    tocando = true
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == d.id } ?: break
                        // un botón (los ritmos, la hoja) se quedó con el toque: no es para terminar
                        if (!c.pressed) { ajeno = c.isConsumed; break }
                        val dy = c.position.y - y
                        if (!arrastro && abs(c.position.y - d.position.y) > viewConfiguration.touchSlop) arrastro = true
                        if (arrastro) {
                            luz = (luz - dy / recorrido).coerceIn(0f, 1f)
                            if (abs(dy) > 0.5f) {
                                val sube = dy < 0
                                if (ultimaDir != null && sube != ultimaDir) Sonidos.pulso()
                                ultimaDir = sube; dedoSube = sube
                            }
                            y = c.position.y
                            c.consume()
                        }
                    }
                    tocando = false
                    dedoSube = null
                    if (!arrastro && !ajeno && editando == null) Oleaje.respirando = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x6602070F)))
        Canvas(Modifier.size(310.dp)) {
            val c = center
            val radio = size.minDimension / 2
            fun r(v: Float) = radio * (0.36f + 0.56f * v)
            // la luz: la mueve uno
            drawCircle(
                Brush.radialGradient(
                    0f to Azules.Crema.copy(alpha = 0.16f + 0.16f * luz),
                    0.7f to Azules.Crema.copy(alpha = 0.06f + 0.06f * luz),
                    1f to Color.Transparent,
                    center = c, radius = r(luz) * 1.3f,
                ),
                r(luz) * 1.3f, c,
            )
            drawCircle(Azules.Crema.copy(alpha = 0.75f), r(luz), c, style = Stroke(1.6f * density))
            // el anillo guía: marca el ritmo
            drawCircle(Azules.Hielo.copy(alpha = 0.55f), r(guia), c, style = Stroke(0.9f * density))
            // el piso y el techo del aire
            drawCircle(Azules.Crema.copy(alpha = 0.1f), r(0f), c, style = Stroke(0.6f * density))
            drawCircle(Azules.Crema.copy(alpha = 0.1f), r(1f), c, style = Stroke(0.6f * density))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(targetState = paso, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "paso") { i ->
                Text(NombresPaso[i], color = Azules.Crema, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 34.sp)
            }
            Text(quedan.toString(), color = Azules.Crema.copy(alpha = 0.55f), fontFamily = Mono, fontSize = 13.sp)
        }

        // arriba: la pista, mientras uno aprende
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val alfa by animateFloatAsState(if (pista || tocando) 1f else 0f, tween(900), label = "pista")
            Text(
                when (dedoSube) { true -> "↑  tomando aire"; false -> "↓  soltando"; null -> "desliza hacia arriba al inhalar y hacia abajo al exhalar" },
                color = Azules.Espuma, fontFamily = Sans, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = alfa },
            )
        }

        // abajo: los ritmos
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Respiraciones.todos.forEach { pt ->
                    val sel = pt.nombre == patron.nombre
                    val forma = RoundedCornerShape(50)
                    Column(
                        Modifier.clip(forma).background(if (sel) Azules.Crema else Color(0x220B1620))
                            .border(1.dp, if (sel) Azules.Crema else Color(0x44B9C6CC), forma)
                            .clickable {
                                Sonidos.tic()
                                if (sel && pt.propio) editando = pt else Respiraciones.elegir(pt)
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(pt.nombre, color = if (sel) Azules.Abismo else Azules.Crema, fontFamily = Sans, fontSize = 13.sp, maxLines = 1)
                        Text(pt.cifra, color = if (sel) Azules.Prusia else Azules.Bruma, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
                val forma = RoundedCornerShape(50)
                Box(
                    Modifier.clip(forma).border(1.dp, Color(0x44B9C6CC), forma)
                        .clickable { Sonidos.tic(); editando = Patron("", 4, 2, 6, 0, propio = true) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) { Text("+ propio", color = Azules.Crema, fontFamily = Sans, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Text("toca para volver", color = Azules.Espuma.copy(alpha = 0.55f), fontFamily = Sans, fontSize = 13.sp)
        }

        HojaPatron(editando) { editando = null }
    }
}

/** Crear o cambiar un ritmo propio. */
@Composable
private fun HojaPatron(patron: Patron?, alCerrar: () -> Unit) {
    val p = Tema.p
    var nombre by remember(patron) { mutableStateOf(patron?.nombre ?: "") }
    var valores by remember(patron) { mutableStateOf(listOf(patron?.inhala ?: 4, patron?.retiene ?: 0, patron?.exhala ?: 6, patron?.pausa ?: 0)) }
    Hoja(patron != null, alCerrar) {
        val pt = patron ?: return@Hoja
        Text(if (pt.nombre.isBlank()) "Un ritmo propio" else pt.nombre, color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Campo(nombre, { nombre = it.take(18) }, "Nombre")
        listOf("Inhala", "Retén", "Exhala", "Pausa").forEachIndexed { i, que ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(que, color = p.texto, fontFamily = Sans, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Redondo(tam = 38.dp, alTocar = { valores = valores.toMutableList().also { it[i] = (it[i] - 1).coerceAtLeast(if (i == 0 || i == 2) 1 else 0) } }) {
                    Text("−", color = p.texto, fontFamily = Sans, fontSize = 18.sp)
                }
                Text("${valores[i]} s", color = p.texto, fontFamily = Mono, fontSize = 15.sp, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Redondo(tam = 38.dp, alTocar = { valores = valores.toMutableList().also { it[i] = (it[i] + 1).coerceAtMost(16) } }) {
                    Text("+", color = p.texto, fontFamily = Sans, fontSize = 18.sp)
                }
            }
        }
        val nombreFinal = nombre.trim().ifBlank { valores.filter { it > 0 }.joinToString("·") }
        val choca = Respiraciones.base.any { it.nombre.equals(nombreFinal, ignoreCase = true) }
        if (choca) Parrafo("Ese nombre ya lo tiene un ritmo de la estación.")
        Pildora("Guardar", Modifier.fillMaxWidth(), habilitada = !choca) {
            Respiraciones.guardar(Patron(nombreFinal, valores[0], valores[1], valores[2], valores[3], propio = true), pt.nombre.ifBlank { null })
            alCerrar()
        }
        if (pt.nombre.isNotBlank()) Pildora("Borrar este ritmo", Modifier.fillMaxWidth(), primaria = false) {
            Respiraciones.borrar(pt); alCerrar()
        }
    }
}
