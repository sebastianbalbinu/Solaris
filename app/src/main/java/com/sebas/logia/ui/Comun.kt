package com.sebas.logia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.logia.R

/** Colores de la sala roja, de noche. */
object C {
    val Noche = Color(0xFF160505)
    val Vino = Color(0xFF2B0909)
    val Rojo = Color(0xFF8E0E0E)
    val RojoClaro = Color(0xFFA8191A)
    val Crema = Color(0xFFF2E8D8)
    val CremaSuave = Color(0xCCF2E8D8)
    val Humo = Color(0x8CF2E8D8)
    val Linea = Color(0x2EF2E8D8)
    val Velo = Color(0x14F2E8D8)
    val Brasa = Color(0xFFE8742A)
    val Dorado = Color(0xFFD9B26A)

    /** Luz de cada habitación. */
    val luces = listOf(
        Color(0xFF8E0E0E), // rojo
        Color(0xFF5E1F3D), // ciruela
        Color(0xFF1F2A4A), // azul noche
        Color(0xFF34404A), // lluvia
        Color(0xFF2F4A3A), // musgo
        Color(0xFF8A5A1C), // ámbar
    )

    fun luz(i: Int): Color = luces[((i % luces.size) + luces.size) % luces.size]
}

val Playfair = FontFamily(
    Font(R.font.playfair_regular, FontWeight.Normal),
    Font(R.font.playfair_semibold, FontWeight.SemiBold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Fondo de la casa: penumbra roja y el piso en zigzag desvaneciéndose abajo. */
@Composable
fun Fondo(contenido: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Vino, C.Noche)))) {
        Image(
            painterResource(R.drawable.zigzag), null,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp).alpha(0.16f),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(C.Noche, Color.Transparent)))
        )
        contenido()
    }
}

@Composable
fun Boton(
    texto: String,
    modifier: Modifier = Modifier,
    primario: Boolean = true,
    habilitado: Boolean = true,
    alTocar: () -> Unit,
) {
    val forma = RoundedCornerShape(14.dp)
    Box(
        modifier
            .alpha(if (habilitado) 1f else 0.45f)
            .clip(forma)
            .background(if (primario) C.Rojo else Color.Transparent)
            .border(1.dp, if (primario) C.RojoClaro else C.Linea, forma)
            .clickable(enabled = habilitado, onClick = alTocar)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = C.Crema, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun Enlace(texto: String, modifier: Modifier = Modifier, color: Color = C.Humo, alTocar: () -> Unit) {
    Text(
        texto, color = color, fontFamily = Inter, fontSize = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = alTocar).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun Campo(
    valor: String,
    alCambiar: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    secreto: Boolean = false,
    tipo: KeyboardType = KeyboardType.Text,
) {
    Column(modifier) {
        Text(etiqueta.uppercase(), color = C.Humo, fontFamily = Inter, fontSize = 11.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = valor,
            onValueChange = alCambiar,
            singleLine = true,
            textStyle = TextStyle(color = C.Crema, fontSize = 17.sp, fontFamily = Inter),
            cursorBrush = SolidColor(C.Crema),
            visualTransformation = if (secreto) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secreto) KeyboardType.Password else tipo),
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x33000000))
                .border(1.dp, C.Linea, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

@Composable
fun Titulo(texto: String, modifier: Modifier = Modifier, tam: Int = 30) {
    Text(texto, color = C.Crema, fontFamily = Playfair, fontSize = tam.sp, lineHeight = (tam * 1.2f).sp, modifier = modifier)
}

@Composable
fun Nota(texto: String, modifier: Modifier = Modifier, color: Color = C.Humo, centro: Boolean = false) {
    Text(
        texto, color = color, fontFamily = Inter, fontSize = 14.sp, lineHeight = 21.sp, modifier = modifier,
        textAlign = if (centro) TextAlign.Center else TextAlign.Start,
    )
}

@Composable
fun Etiqueta(texto: String, modifier: Modifier = Modifier) {
    Text(texto.uppercase(), color = C.Humo, fontFamily = Inter, fontSize = 11.sp, letterSpacing = 1.6.sp, modifier = modifier)
}

/** Una vela pequeña. Las velas encendidas son los días en que dejaste algo en la casa. */
@Composable
fun Vela(modifier: Modifier = Modifier, encendida: Boolean = true, alto: Dp = 22.dp) {
    Canvas(modifier.size(alto * 0.64f, alto)) {
        val w = size.width
        val h = size.height
        drawRect(C.Crema, topLeft = Offset(w * 0.28f, h * 0.46f), size = Size(w * 0.44f, h * 0.54f))
        drawRect(Color(0x33000000), topLeft = Offset(w * 0.56f, h * 0.46f), size = Size(w * 0.16f, h * 0.54f))
        if (encendida) {
            drawOval(C.Brasa.copy(alpha = 0.35f), topLeft = Offset(0f, 0f), size = Size(w, h * 0.5f))
            drawOval(C.Brasa, topLeft = Offset(w * 0.3f, h * 0.06f), size = Size(w * 0.4f, h * 0.36f))
            drawOval(C.Dorado, topLeft = Offset(w * 0.4f, h * 0.2f), size = Size(w * 0.2f, h * 0.2f))
        } else {
            drawRect(C.Humo, topLeft = Offset(w * 0.47f, h * 0.34f), size = Size(w * 0.06f, h * 0.12f))
        }
    }
}

/** Teclado propio para el PIN: no pasa por el teclado del sistema. */
@Composable
fun Teclado(alDigito: (Char) -> Unit, alBorrar: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("123", "456", "789").forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                fila.forEach { d -> Tecla(d.toString()) { alDigito(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { extra() }
            Tecla("0") { alDigito('0') }
            Tecla("⌫", borde = false) { alBorrar() }
        }
    }
}

@Composable
private fun Tecla(texto: String, borde: Boolean = true, alTocar: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(if (borde) C.Velo else Color.Transparent)
            .border(1.dp, if (borde) C.Linea else Color.Transparent, CircleShape)
            .clickable(onClick = alTocar),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = C.Crema, fontFamily = Playfair, fontSize = 26.sp)
    }
}

@Composable
fun Puntos(llenos: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(total) { i ->
            Box(
                Modifier.size(13.dp).clip(CircleShape)
                    .background(if (i < llenos) C.Crema else Color.Transparent)
                    .border(1.dp, C.CremaSuave, CircleShape)
            )
        }
    }
}

/**
 * Capa encima de todo (en lugar de diálogos del sistema, que no quedan protegidos contra capturas).
 */
@Composable
fun Capa(visible: Boolean, alCerrar: () -> Unit, contenido: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    BackHandler { alCerrar() }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC0A0202))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = alCerrar),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(C.Vino)
                .border(1.dp, C.Linea, RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = contenido,
        )
    }
}

/** Para elegir la luz de una habitación. */
@Composable
fun Luces(elegida: Int, alElegir: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        C.luces.forEachIndexed { i, color ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(color)
                    .border(2.dp, if (i == elegida) C.Crema else Color.Transparent, CircleShape)
                    .clickable { alElegir(i) }
            )
        }
    }
}
