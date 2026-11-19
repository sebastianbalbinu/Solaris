package com.sebas.logia.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.logia.Borrador
import com.sebas.logia.Casa
import com.sebas.logia.Item
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Dónde está uno dentro de la casa. Sobrevive al cierre del telón. */
sealed class Vista {
    object Sala : Vista()
    data class Cuarto(val id: String) : Vista()
    class Escribir(val borrador: Borrador) : Vista()
    object Ajustes : Vista()
    object CambiarPin : Vista()
}

object Nav {
    var pila by mutableStateOf(listOf<Vista>(Vista.Sala))
    fun ir(v: Vista) { pila = pila + v }
    fun volver() { if (pila.size > 1) pila = pila.dropLast(1) }
    fun reiniciar() { pila = listOf(Vista.Sala) }
}

val ES: Locale = Locale("es", "CO")
fun formato(patron: String, cuando: Long): String = SimpleDateFormat(patron, ES).format(Date(cuando))
fun mayuscula(s: String) = s.replaceFirstChar { it.uppercase() }

@Composable
fun Interior() {
    val actual = Nav.pila.last()
    BackHandler(enabled = Nav.pila.size > 1 && actual !is Vista.Escribir) { Nav.volver() }
    when (actual) {
        is Vista.Sala -> Sala()
        is Vista.Cuarto -> CuartoVista(actual.id)
        is Vista.Escribir -> Editor(actual.borrador)
        is Vista.Ajustes -> Ajustes()
        is Vista.CambiarPin -> Fondo { PinNuevo { Nav.volver() } }
    }
}

@Composable
fun Sala() {
    val todo = Casa.items
    val cuartos = todo.filter { it.esCuarto && !it.deleted }.sortedBy { it.created }
    val entradas = todo.filter { it.esEntrada && !it.deleted }.sortedByDescending { it.created }
    val (pregunta, sugerido) = Casa.pregunta()
    val cuartoSugerido = cuartos.firstOrNull { it.title == sugerido }?.id
    var nuevoCuarto by remember { mutableStateOf(false) }

    Fondo {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Etiqueta(formato("EEEE d 'de' MMMM", System.currentTimeMillis()), Modifier.weight(1f))
                    Vela(encendida = !Casa.sincronizando && Casa.pendientes() == 0, alto = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Enlace("Ajustes", color = C.CremaSuave) { Nav.ir(Vista.Ajustes) }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .clickable { Nav.ir(Vista.Escribir(Borrador(null, cuartoSugerido))) }
                        .padding(vertical = 26.dp)
                ) {
                    Titulo(pregunta, tam = 38)
                    Spacer(Modifier.height(10.dp))
                    Nota("Toca para escribir", color = C.Humo)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Boton("Escribir", Modifier.weight(1f)) { Nav.ir(Vista.Escribir(Borrador(null, cuartoSugerido))) }
                    Boton("Foto", Modifier.weight(1f), primario = false) {
                        Nav.ir(Vista.Escribir(Borrador(null, cuartoSugerido, abrirFoto = true)))
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)); Tomo(entradas) }
            item {
                Etiqueta("Habitaciones", Modifier.padding(top = 10.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(cuartos, key = { it.id }) { c ->
                        TarjetaCuarto(c, entradas.count { it.room == c.id }) { Nav.ir(Vista.Cuarto(c.id)) }
                    }
                    item {
                        Box(
                            Modifier.size(width = 120.dp, height = 104.dp).clip(RoundedCornerShape(16.dp))
                                .border(1.dp, C.Linea, RoundedCornerShape(16.dp))
                                .clickable { nuevoCuarto = true },
                            contentAlignment = Alignment.Center,
                        ) { Nota("+ Nueva", color = C.CremaSuave) }
                    }
                }
            }
            item { Etiqueta("Últimas páginas", Modifier.padding(top = 10.dp)) }
            if (entradas.isEmpty()) {
                item { Nota("La casa está en silencio. Deja aquí lo primero que no quieras perder.", color = C.Humo) }
            }
            items(entradas.take(60), key = { it.id }) { e ->
                FilaEntrada(e, cuartos.firstOrNull { it.id == e.room }) {
                    Nav.ir(Vista.Escribir(Borrador(e.id, e.room)))
                }
            }
        }
    }
    EditorCuarto(visible = nuevoCuarto, cuarto = null) { nuevoCuarto = false }
}

/** El tomo del mes: se va llenando página a página. Al terminar el mes queda en el estante. */
@Composable
fun Tomo(entradas: List<Item>) {
    val ahora = Calendar.getInstance()
    fun clave(t: Long): Int = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }
    val mesActual = ahora.get(Calendar.YEAR) * 12 + ahora.get(Calendar.MONTH)
    val delMes = entradas.filter { clave(it.created) == mesActual }
    val paginas = delMes.sumOf { it.paginas }
    val primero = entradas.minOfOrNull { clave(it.created) } ?: mesActual
    val numero = mesActual - primero + 1
    val velas = entradas.map { formato("yyyyMMdd", it.created) }.toSet().size
    val hoy = formato("yyyyMMdd", System.currentTimeMillis())
    val hoyEncendida = entradas.any { formato("yyyyMMdd", it.created) == hoy }
    val meta = 60

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A0C0C), Color(0xFF1E0606))))
            .border(1.dp, C.Linea, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Etiqueta("Tomo ${romano(numero)}")
                Titulo(mayuscula(formato("MMMM", System.currentTimeMillis())), tam = 26)
            }
            Text(
                "$paginas ${if (paginas == 1) "página" else "páginas"}",
                color = C.Dorado, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            )
        }
        // El canto del libro: cada línea es una página.
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val n = meta
            val paso = size.width / n
            for (i in 0 until n) {
                val llena = i < paginas
                drawLine(
                    color = if (llena) C.Crema else C.Linea,
                    start = Offset(i * paso + paso / 2, if (llena) 0f else size.height * 0.25f),
                    end = Offset(i * paso + paso / 2, size.height),
                    strokeWidth = paso * 0.45f,
                )
            }
        }
        if (paginas > meta) Nota("Este tomo ya no cabe en sí mismo.", color = C.CremaSuave)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Vela(encendida = hoyEncendida)
            Spacer(Modifier.width(10.dp))
            Nota(
                when {
                    velas == 0 -> "Hoy puedes encender tu primera vela."
                    hoyEncendida -> "$velas ${if (velas == 1) "vela encendida" else "velas encendidas"} · la de hoy ya arde"
                    else -> "$velas ${if (velas == 1) "vela encendida" else "velas encendidas"} · hoy aún no enciendes la tuya"
                },
                color = C.CremaSuave,
            )
        }
    }
}

fun romano(n: Int): String {
    var x = n.coerceIn(1, 3999)
    val v = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    val sb = StringBuilder()
    for ((valor, letra) in v) while (x >= valor) { sb.append(letra); x -= valor }
    return sb.toString()
}

@Composable
private fun TarjetaCuarto(c: Item, cuantas: Int, alTocar: () -> Unit) {
    Column(
        Modifier.size(width = 150.dp, height = 104.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(C.luz(c.color), C.luz(c.color).copy(alpha = 0.45f))))
            .clickable(onClick = alTocar)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(c.title, color = C.Crema, fontFamily = Playfair, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
        Nota(if (cuantas == 1) "1 página" else "$cuantas páginas", color = C.CremaSuave)
    }
}

@Composable
fun FilaEntrada(e: Item, cuarto: Item?, alTocar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(C.Velo)
            .clickable(onClick = alTocar)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cuarto != null) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(C.luz(cuarto.color).copy(alpha = 1f)).border(1.dp, C.Linea, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Etiqueta(listOfNotNull(cuarto?.title, formato("d MMM · HH:mm", e.created)).joinToString(" · "))
            }
            val cabeza = e.title.ifBlank { e.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Fotos" }
            Text(cabeza, color = C.Crema, fontFamily = Playfair, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val resto = if (e.title.isBlank()) e.text.lineSequence().filter { it.isNotBlank() }.drop(1).joinToString(" ") else e.text
            if (resto.isNotBlank()) {
                Text(resto, color = C.Humo, fontFamily = Inter, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            }
        }
        e.photos.firstOrNull()?.let { f ->
            Spacer(Modifier.width(12.dp))
            Foto(f, Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)), lado = 200)
        }
    }
}

/** Muestra una foto cifrada (la abre o la trae de la nube si hace falta). */
@Composable
fun Foto(id: String, modifier: Modifier, lado: Int = 600, recortar: Boolean = true) {
    val bmp by produceState<Bitmap?>(Casa.fotoEnCache(id, lado), id, lado) {
        if (value == null) value = Casa.foto(id, lado)
    }
    Box(modifier.background(C.Vino), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (recortar) ContentScale.Crop else ContentScale.Fit,
            )
        }
    }
}

@Composable
fun CuartoVista(id: String) {
    val cuarto = Casa.porId(id)
    val entradas = Casa.items.filter { it.esEntrada && !it.deleted && it.room == id }.sortedByDescending { it.created }
    var editar by remember { mutableStateOf(false) }
    if (cuarto == null) {
        LaunchedEffect(Unit) { Nav.volver() }
        return
    }
    Box(Modifier.fillMaxSize()) {
        Fondo {
            Box(
                Modifier.fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(C.luz(cuarto.color).copy(alpha = 0.55f), Color.Transparent)))
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Enlace("‹ Sala", Modifier.offset(x = (-10).dp), color = C.CremaSuave) { Nav.volver() }
                        Spacer(Modifier.weight(1f))
                        Enlace("Cambiar", color = C.CremaSuave) { editar = true }
                    }
                }
                item {
                    Column(Modifier.padding(vertical = 18.dp)) {
                        Etiqueta("Habitación")
                        Titulo(cuarto.title, tam = 36)
                        Nota(if (entradas.size == 1) "1 página" else "${entradas.size} páginas", color = C.CremaSuave)
                    }
                }
                item { Boton("Escribir aquí", Modifier.fillMaxWidth()) { Nav.ir(Vista.Escribir(Borrador(null, id))) } }
                if (entradas.isEmpty()) item { Nota("Todavía no hay nada aquí.", color = C.Humo) }
                items(entradas, key = { it.id }) { e ->
                    FilaEntrada(e, null) { Nav.ir(Vista.Escribir(Borrador(e.id, e.room))) }
                }
            }
        }
        EditorCuarto(visible = editar, cuarto = cuarto) { editar = false }
    }
}

/** Crear o renombrar una habitación. */
@Composable
fun EditorCuarto(visible: Boolean, cuarto: Item?, alCerrar: () -> Unit) {
    var nombre by remember(visible) { mutableStateOf(cuarto?.title ?: "") }
    var luz by remember(visible) { mutableStateOf(cuarto?.color ?: 1) }
    Capa(visible, alCerrar) {
        Titulo(if (cuarto == null) "Nueva habitación" else "Cambiar habitación", tam = 24)
        Campo(nombre, { nombre = it }, "Nombre")
        Etiqueta("Su luz")
        Luces(luz) { luz = it }
        Boton("Guardar", Modifier.fillMaxWidth(), habilitado = nombre.isNotBlank()) {
            if (cuarto == null) Casa.nuevoCuarto(nombre, luz) else Casa.renombrarCuarto(cuarto, nombre, luz)
            alCerrar()
        }
    }
}
