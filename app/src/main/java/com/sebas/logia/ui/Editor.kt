package com.sebas.logia.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.logia.Borrador
import com.sebas.logia.Casa
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Editor(b: Borrador) {
    val scope = rememberCoroutineScope()
    val cuartos = Casa.items.filter { it.esCuarto && !it.deleted }.sortedBy { it.created }

    // Si es una página que ya existía, se carga una sola vez (y no se pisa si el telón se cerró a mitad).
    LaunchedEffect(b) {
        if (!b.cargado) {
            b.id?.let { Casa.porId(it) }?.let { e ->
                b.titulo = e.title
                b.texto = e.text
                b.cuarto = e.room
                b.fotos = e.photos
                b.creado = e.created
            }
            b.cargado = true
        }
    }

    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        Casa.pausarCandado = false
        scope.launch {
            for (u in uris) {
                val id = Casa.agregarFoto(u)
                if (id != null) {
                    b.fotos = b.fotos + id
                    b.fotosNuevas.add(id)
                }
            }
        }
    }
    fun pedirFoto() {
        Casa.pausarCandado = true
        elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(Unit) {
        if (b.abrirFoto && !b.fotoPedida) {
            b.fotoPedida = true
            delay(300)
            pedirFoto()
        }
    }

    var preguntarQuemar by remember { mutableStateOf(false) }
    var quemando by remember { mutableStateOf(false) }
    var fotoAbierta by remember { mutableStateOf<String?>(null) }
    val fuego = remember { Animatable(0f) }

    fun guardarYSalir() {
        if (b.vacio()) Casa.descartarNuevas(b) else Casa.guardarBorrador(b)
        Nav.volver()
    }
    BackHandler(enabled = !quemando && fotoAbierta == null && !preguntarQuemar) { guardarYSalir() }

    LaunchedEffect(quemando) {
        if (quemando) {
            fuego.animateTo(1f, tween(durationMillis = 2200, easing = LinearEasing))
            Casa.quemar(b)
            delay(900)
            Nav.volver()
        }
    }

    val f = fuego.value
    val colorTinta = if (f < 0.5f) lerp(C.Crema, C.Brasa, f * 2f) else lerp(C.Brasa, Color(0xFF3A1A10), (f - 0.5f) * 2f)
    val tinta = (1f - f * 1.15f).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Fondo {
            Column(Modifier.fillMaxSize()) {
                // barra superior
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Enlace("‹ Guardar y volver", color = C.CremaSuave) { if (!quemando) guardarYSalir() }
                    Spacer(Modifier.weight(1f))
                    Etiqueta(formato("d MMM · HH:mm", b.creado), Modifier.padding(end = 10.dp))
                }
                // habitaciones
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cuartos, key = { it.id }) { c ->
                        val elegida = b.cuarto == c.id
                        Text(
                            c.title, color = C.Crema, fontFamily = Inter, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (elegida) C.luz(c.color) else Color.Transparent)
                                .border(1.dp, if (elegida) C.luz(c.color) else C.Linea, RoundedCornerShape(50))
                                .clickable { b.cuarto = if (elegida) null else c.id }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                // la página
                Column(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                        .graphicsLayer { alpha = tinta; translationY = -f * 60f },
                ) {
                    Escribible(
                        b.titulo, { b.titulo = it }, "Sin título",
                        TextStyle(color = colorTinta, fontFamily = Playfair, fontSize = 28.sp, lineHeight = 34.sp),
                    )
                    if (b.fotos.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(b.fotos, key = { it }) { id ->
                                Foto(
                                    id,
                                    Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(12.dp))
                                        .clickable { fotoAbierta = id },
                                    lado = 400,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Escribible(
                        b.texto, { b.texto = it },
                        if (b.id == null) Casa.pregunta().first else "",
                        TextStyle(color = colorTinta, fontFamily = Playfair, fontSize = 19.sp, lineHeight = 31.sp),
                        unaLinea = false,
                        modifier = Modifier.defaultMinSize(minHeight = 320.dp),
                    )
                }
                // barra inferior
                Row(
                    Modifier.fillMaxWidth().background(Color(0x66100303)).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Boton("Foto", Modifier.weight(1f), primario = false, habilitado = !quemando) { pedirFoto() }
                    Boton("Quemar", Modifier.weight(1f), primario = false, habilitado = !quemando && !b.vacio()) { preguntarQuemar = true }
                    Boton("Guardar", Modifier.weight(1f), habilitado = !quemando) { guardarYSalir() }
                }
            }
        }

        if (quemando) Fuego(f)

        Capa(preguntarQuemar, { preguntarQuemar = false }) {
            Titulo("¿Quemar esta página?", tam = 24)
            Nota(
                if (b.id == null) "Lo que escribiste arderá y no quedará guardado en ningún lado. A veces solo hace falta soltarlo."
                else "Esta página se borrará de este celular y de la nube, para siempre."
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Boton("Seguir escribiendo", Modifier.weight(1f), primario = false) { preguntarQuemar = false }
                Boton("Quemar", Modifier.weight(1f)) { preguntarQuemar = false; quemando = true }
            }
        }

        val abierta = fotoAbierta
        if (abierta != null) {
            BackHandler { fotoAbierta = null }
            Column(Modifier.fillMaxSize().background(Color(0xF20A0202)).padding(16.dp)) {
                Foto(abierta, Modifier.weight(1f).fillMaxWidth().clickable { fotoAbierta = null }, lado = 1600, recortar = false)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Boton("Quitar de la página", Modifier.weight(1f), primario = false) {
                        b.fotos = b.fotos - abierta
                        fotoAbierta = null
                    }
                    Boton("Cerrar", Modifier.weight(1f)) { fotoAbierta = null }
                }
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
        cursorBrush = SolidColor(C.Crema),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { campo ->
            Box {
                if (valor.isEmpty()) Text(pista, style = estilo.copy(color = C.Humo.copy(alpha = 0.4f)))
                campo()
            }
        },
    )
}

/** Las llamas suben desde abajo y se llevan la página. */
@Composable
private fun Fuego(f: Float) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            val altura = size.height * (0.15f + f * 1.1f)
            val tope = size.height - altura
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.25f to C.Dorado.copy(alpha = 0.55f),
                    0.45f to C.Brasa.copy(alpha = 0.8f),
                    0.75f to C.Rojo.copy(alpha = 0.9f),
                    1f to Color(0xFF0A0202),
                    startY = tope,
                    endY = size.height,
                ),
                topLeft = Offset(0f, tope),
                size = Size(size.width, altura),
            )
            if (f > 0.85f) drawRect(Color(0xFF0A0202).copy(alpha = ((f - 0.85f) / 0.15f).coerceIn(0f, 1f)))
        },
        contentAlignment = Alignment.Center,
    ) {
        if (f >= 0.95f) Text("Ya no pesa.", color = C.CremaSuave, fontFamily = Playfair, fontSize = 24.sp, modifier = Modifier.alpha((f - 0.95f) * 20f))
    }
}
