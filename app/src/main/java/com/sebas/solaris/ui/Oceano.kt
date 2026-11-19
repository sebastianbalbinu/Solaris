package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Casa
import com.sebas.solaris.Clima
import com.sebas.solaris.Item
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

private val Claro = Color(0xFFE4F1F3)
private val Tenue = Color(0xFF9FBAC2)
private val Calido = Color(0xFFF2C2A6)

@Composable
fun Oceano() {
    var consulta by remember { mutableStateOf("") }
    var mostrada by remember { mutableStateOf("") }
    var verTemas by remember { mutableStateOf(false) }
    val mar = Clima.mar

    LaunchedEffect(Unit) {
        while (isActive) { Clima.actualizar(); delay(15 * 60 * 1000L) }
    }
    val intensidad = ((mar.ola / 8.0 + mar.viento / 50.0) / 2.0).coerceIn(0.0, 1.0).toFloat()
    LaunchedEffect(Tema.sonidoMar, intensidad) { Sonidos.mar(Tema.sonidoMar, intensidad) }
    DisposableEffect(Unit) { onDispose { Sonidos.mar(false, 0f) } }
    val recuerdo = remember { Casa.recuerdoDeHoy() }

    val resultados: List<Item> = remember(mostrada, Casa.items) { if (mostrada.isBlank()) emptyList() else Casa.buscar(mostrada) }
    val temas = remember(verTemas, Casa.items) { if (verTemas) Casa.temas() else emptyList() }

    fun consultar(q: String) {
        Sonidos.tic()
        verTemas = false
        mostrada = q.trim()
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05080C))) {
        PixelVivo(
            Modifier.fillMaxSize(),
            intervaloMs = 85,
            crear = { w, h -> OceanoPixel(w, h) },
            paso = { o, _ -> o.cuadro(Clima.mar, 0.085) },
        )
        Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color(0x99050810), Color.Transparent))))

        Column(Modifier.statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Senal("Solaris · alta mar", Modifier.weight(1f), color = Claro)
                Senal("${mar.horaLocal()} local", color = Claro)
            }
            Senal(Clima.LUGAR, color = Tenue)
            Text(mar.nombre, color = Claro, fontFamily = Mono, fontSize = 14.sp, letterSpacing = 2.5.sp, modifier = Modifier.padding(top = 10.dp))
            Senal("Ola ${uno(mar.ola)} m · ${mar.periodo.toInt()} s · viento ${mar.viento.toInt()} nudos", color = Tenue)
            Senal("Nubes ${mar.nubes.toInt()}% · visibilidad ${uno(mar.visibilidad)} km", color = Tenue)
            Senal(if (mar.real) "Lectura real · " + formato("HH:mm", mar.cuando) else "Esperando la primera lectura del mar…", color = Tenue)
            Row(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x26E4F1F3))
                    .clickable { Sonidos.tic(); Tema.alternarSonidoMar() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconoParlante(Claro, Tema.sonidoMar, 16.dp)
                Spacer(Modifier.width(8.dp))
                Senal(if (Tema.sonidoMar) "Rumor del mar" else "Mar en silencio", color = Claro)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
            Box(Modifier.fillMaxWidth().height(70.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF0040608)))))
            Column(
                Modifier.fillMaxWidth().background(Color(0xF0040608)).navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (verTemas) {
                    Linea("solaris> #temas")
                    if (temas.isEmpty()) Linea("todavía no hay #temas en tus notas.", Tenue)
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(temas) { (tema, n) ->
                            Text("$tema · $n", color = Claro, fontFamily = Mono, fontSize = 13.sp,
                                modifier = Modifier.clickable { consulta = tema; consultar(tema) }.padding(vertical = 2.dp))
                        }
                    }
                } else if (mostrada.isNotBlank()) {
                    Linea("solaris> $mostrada")
                    Linea(
                        when (resultados.size) { 0 -> "el océano no devuelve nada."; 1 -> "el océano devuelve 1 recuerdo"; else -> "el océano devuelve ${resultados.size} recuerdos" },
                        Tenue,
                    )
                    LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(resultados, key = { it.id }) { r -> Resultado(r, mostrada) }
                    }
                } else if (recuerdo != null) {
                    Linea("el océano te devuelve algo, " + hace(recuerdo.created) + ":", Tenue)
                    Resultado(recuerdo, "")
                } else {
                    Linea("pregúntale al océano por una palabra, un día o un #tema.", Tenue)
                }

                Row(
                    Modifier.fillMaxWidth().border(1.dp, Color(0x33E4F1F3), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("solaris>", color = Tenue, fontFamily = Mono, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = consulta,
                        onValueChange = { consulta = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Claro, fontFamily = Mono, fontSize = 14.sp),
                        cursorBrush = SolidColor(Claro),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { consultar(consulta) }),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("hoy", "ayer", "cintas", "#temas").forEach { c ->
                        Text(
                            c, color = Claro, fontFamily = Mono, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x1FE4F1F3))
                                .clickable { if (c == "#temas") { Sonidos.tic(); verTemas = true; mostrada = "" } else { consulta = c; consultar(c) } }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun uno(v: Double) = String.format(Locale.US, "%.1f", v).replace('.', ',')

@Composable
private fun Linea(texto: String, color: Color = Claro) {
    Text(texto, color = color, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun Resultado(r: Item, q: String) {
    val cabeza = if (r.esCinta) "${r.title.ifBlank { "cinta" }} · ${duracion(r.dur)}" else r.title.ifBlank { r.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "nota" }
    val fragmento = fragmento(r.text, q)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
            Sonidos.tic()
            if (r.esCinta) { Nav.cintaElegida = r.id; Nav.pestana = "cinta" } else Nav.abrirNota(r.id)
        }.padding(vertical = 2.dp),
    ) {
        Row {
            Text(if (r.esCinta) "▶ " else "N ", color = Calido, fontFamily = Mono, fontSize = 13.sp)
            Text(formato("dd MMM", r.created).uppercase() + "  " + cabeza, color = Claro, fontFamily = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fragmento.isNotBlank()) {
            Text(fragmento, color = Tenue, fontFamily = Mono, fontStyle = FontStyle.Italic, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 22.dp))
        }
    }
}

private fun fragmento(texto: String, q: String): String {
    val t = texto.replace('\n', ' ')
    if (t.isBlank()) return ""
    val i = t.lowercase().indexOf(q.lowercase())
    if (i < 0 || q.length < 2) return "…" + t.take(48) + "…"
    val ini = (i - 18).coerceAtLeast(0)
    return "…" + t.substring(ini, (i + q.length + 30).coerceAtMost(t.length)) + "…"
}

fun duracion(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

private fun hace(t: Long): String {
    val dias = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return when {
        dias >= 730 -> "de hace ${dias / 365} años"
        dias >= 360 -> "de hace un año"
        dias >= 60 -> "de hace ${dias / 30} meses"
        else -> "de hace $dias días"
    }
}
