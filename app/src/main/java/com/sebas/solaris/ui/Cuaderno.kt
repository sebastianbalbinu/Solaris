package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val ES: Locale = Locale("es", "CO")
fun formato(patron: String, cuando: Long): String = SimpleDateFormat(patron, ES).format(Date(cuando))

@Composable
fun Cuaderno() {
    val p = Tema.p
    val notas = Casa.notas()
    val (a, b) = Tema.saludo()

    Box(Modifier.fillMaxSize().background(p.fondo)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 26.dp, end = 26.dp, top = 14.dp, bottom = 170.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Senal(formato("EEEE d 'de' MMMM", System.currentTimeMillis()), Modifier.weight(1f))
                    BotonModo()
                    Spacer(Modifier.width(10.dp))
                    Redondo(alTocar = { Nav.ajustes = true }) { IconoPuntos(p.texto) }
                }
            }
            item {
                Column(Modifier.padding(top = 40.dp, bottom = 36.dp)) {
                    TituloSerif(a, tam = 46)
                    TituloSerif(b, tam = 46, italica = true)
                }
            }
            item {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Senal("Cuaderno", Modifier.weight(1f), color = p.texto)
                        Senal(if (notas.size == 1) "1 nota" else "${notas.size} notas")
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(p.texto.copy(alpha = 0.7f)))
                }
            }
            if (notas.isEmpty()) {
                item {
                    Parrafo("El cuaderno está en blanco. Escribe lo primero que no quieras perder.", Modifier.padding(top = 24.dp))
                }
            }
            items(notas, key = { it.id }) { n -> FilaNota(n) { Nav.abrirNota(n.id) } }
        }

        Pildora(
            "Escribir",
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 92.dp),
            icono = { IconoMas(p.botonTexto) },
        ) { Nav.abrirNota(null) }
    }
}

@Composable
private fun FilaNota(n: Item, alTocar: () -> Unit) {
    val p = Tema.p
    val cabeza = n.title.ifBlank { n.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "Fotos" }
    val resto = if (n.title.isBlank()) n.text.lineSequence().filter { it.isNotBlank() }.drop(1).joinToString(" ") else n.text.replace('\n', ' ')
    Column(Modifier.fillMaxWidth().clickable(onClick = alTocar)) {
        Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(cabeza, color = p.texto, fontFamily = Serif, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (resto.isNotBlank()) Text(resto, color = p.texto2, fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            n.photos.firstOrNull()?.let { f ->
                Spacer(Modifier.width(14.dp))
                Foto(f, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), lado = 200)
            }
            Spacer(Modifier.width(14.dp))
            Senal(formato("d MMM", n.created))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.linea))
    }
}

/** Una foto cifrada: la abre aquí o la trae de la nube si hace falta. */
@Composable
fun Foto(id: String, modifier: Modifier, lado: Int = 600, recortar: Boolean = true) {
    val bmp by produceState<Bitmap?>(Casa.fotoEnCache(id, lado), id, lado) {
        if (value == null) value = Casa.foto(id, lado)
    }
    Box(modifier.background(Tema.p.superficie), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = if (recortar) ContentScale.Crop else ContentScale.Fit)
    }
}

// ---------------- escribir ----------------

@Composable
fun Editor(b: Borrador) {
    val p = Tema.p
    val scope = rememberCoroutineScope()
    var hojaLetra by remember { mutableStateOf(false) }
    var hojaOpciones by remember { mutableStateOf(false) }
    var fotoAbierta by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(b) {
        if (!b.cargado) {
            b.id?.let { Casa.porId(it) }?.let { e ->
                b.titulo = e.title; b.texto = e.text; b.fotos = e.photos; b.creado = e.created
            }
            b.cargado = true
        }
    }

    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        Casa.pausarCandado = false
        scope.launch {
            for (u in uris) {
                val id = Casa.agregarFoto(u)
                if (id != null) { b.fotos = b.fotos + id; b.fotosNuevas.add(id) }
            }
        }
    }
    fun pedirFoto() {
        Casa.pausarCandado = true
        elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun guardarYSalir() {
        if (b.vacio()) Casa.descartarNuevas(b) else Casa.guardarBorrador(b)
        Nav.borrador = null
    }
    BackHandler(enabled = !hojaLetra && !hojaOpciones && fotoAbierta == null) { guardarYSalir() }

    Box(Modifier.fillMaxSize().background(p.fondo)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Redondo(alTocar = { guardarYSalir() }) { IconoAtras(p.texto) }
                Spacer(Modifier.weight(1f))
                Senal(formato("d MMM · HH:mm", b.creado))
                Spacer(Modifier.weight(1f))
                Redondo(alTocar = { hojaLetra = true }) { Text("Aa", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
                Spacer(Modifier.width(8.dp))
                BotonModo()
                if (b.id != null) {
                    Spacer(Modifier.width(8.dp))
                    Redondo(alTocar = { hojaOpciones = true }) { IconoPuntos(p.texto) }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp).padding(top = 18.dp, bottom = 120.dp),
            ) {
                Escribible(
                    b.titulo, { b.titulo = it }, "Título",
                    TextStyle(color = p.texto, fontFamily = Serif, fontSize = 34.sp, lineHeight = 38.sp),
                )
                if (b.fotos.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(b.fotos, key = { it }) { id ->
                            Foto(id, Modifier.size(width = 150.dp, height = 190.dp).clip(RoundedCornerShape(14.dp)).clickable { fotoAbierta = id }, lado = 500)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val tam = Tema.tamanoLetra
                Escribible(
                    b.texto, { b.texto = it }, "Escribe aquí…",
                    TextStyle(color = Tema.colorLetra, fontFamily = Sans, fontSize = tam.sp, lineHeight = (tam * 1.62f).sp),
                    unaLinea = false,
                    modifier = Modifier.defaultMinSize(minHeight = 360.dp),
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Pildora("Foto", primaria = false, icono = { IconoFoto(p.texto) }) { pedirFoto() }
            Pildora("Listo") { guardarYSalir() }
        }

        val abierta = fotoAbierta
        if (abierta != null) {
            BackHandler { fotoAbierta = null }
            Column(Modifier.fillMaxSize().background(Color(0xF2000000)).padding(18.dp)) {
                Foto(abierta, Modifier.weight(1f).fillMaxWidth().clickable { fotoAbierta = null }, lado = 1600, recortar = false)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pildora("Quitar de la nota", Modifier.weight(1f), primaria = false) { b.fotos = b.fotos - abierta; fotoAbierta = null }
                    Pildora("Cerrar", Modifier.weight(1f)) { fotoAbierta = null }
                }
            }
        }

        HojaLetra(hojaLetra) { hojaLetra = false }

        Hoja(hojaOpciones, { hojaOpciones = false }) {
            Text("Esta nota", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Parrafo("Borrarla la quita de este celular y de la nube, para siempre.")
            Pildora("Borrar nota", Modifier.fillMaxWidth(), primaria = false) {
                hojaOpciones = false
                b.id?.let { Casa.borrarNota(it) }
                Nav.borrador = null
            }
        }
    }
}

/** Tamaño y color de la letra, y el modo. */
@Composable
fun HojaLetra(visible: Boolean, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
        Text("Letra", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.superficie).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).clickable { Tema.fijarTamano(Tema.tamano - 1) }, contentAlignment = Alignment.Center) {
                Text("A", color = p.texto, fontFamily = Sans, fontSize = 14.sp)
            }
            Box(Modifier.width(1.dp).height(24.dp).background(p.linea))
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).clickable { Tema.fijarTamano(Tema.tamano + 1) }, contentAlignment = Alignment.Center) {
                Text("A", color = p.texto, fontFamily = Sans, fontSize = 24.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Tamanos.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 5.dp).size(if (i == Tema.tamano) 9.dp else 6.dp).clip(CircleShape).background(if (i <= Tema.tamano) p.texto else p.linea))
            }
        }
        Text("Así se verá tu letra.", color = Tema.colorLetra, fontFamily = Sans, fontSize = Tema.tamanoLetra.sp, modifier = Modifier.padding(vertical = 4.dp))

        Text("Color", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        val nombres = if (Tema.esNoche) NombresColorNoche else NombresColorDia
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            p.coloresLetra.forEachIndexed { i, c ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { Tema.fijarColor(i) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c)
                            .border(2.dp, if (i == Tema.indiceColor) p.acento else Color.Transparent, CircleShape)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(nombres[i], color = p.texto2, fontFamily = Sans, fontSize = 12.sp)
                }
            }
        }

        Text("Modo", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(4.dp)) {
            listOf("dia" to "Día", "noche" to "Noche", "auto" to "Según la hora").forEach { (clave, nombre) ->
                val sel = Tema.modo == clave
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (sel) (if (Tema.esNoche) Color(0xFF2C2C30) else Color(0xFFFBF5EB)) else Color.Transparent)
                        .clickable { Tema.fijarModo(clave) },
                    contentAlignment = Alignment.Center,
                ) { Text(nombre, color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun Escribible(
    valor: String,
    alCambiar: (String) -> Unit,
    pista: String,
    estilo: TextStyle,
    modifier: Modifier = Modifier,
    unaLinea: Boolean = true,
) {
    BasicTextField(
        value = valor,
        onValueChange = alCambiar,
        textStyle = estilo,
        singleLine = unaLinea,
        cursorBrush = SolidColor(Tema.p.acento),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { campo ->
            Box {
                if (valor.isEmpty()) Text(pista, style = estilo.copy(color = Tema.p.texto2.copy(alpha = 0.5f)))
                campo()
            }
        },
    )
}
