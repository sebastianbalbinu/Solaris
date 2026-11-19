package com.sebas.solaris.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Sonidos

/** Botón en forma de píldora, al estilo de iOS. */
@Composable
fun Pildora(
    texto: String,
    modifier: Modifier = Modifier,
    primaria: Boolean = true,
    habilitada: Boolean = true,
    icono: (@Composable () -> Unit)? = null,
    fondo: Color? = null,
    tinta: Color? = null,
    alTocar: () -> Unit,
) {
    val p = Tema.p
    Row(
        modifier
            .presionable(habilitada) { Sonidos.tic(); alTocar() }
            .alpha(if (habilitada) 1f else 0.4f)
            .height(50.dp)
            .clip(CircleShape)
            .background(fondo ?: if (primaria) p.boton else p.superficie)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icono != null) { icono(); Spacer(Modifier.width(8.dp)) }
        Text(texto, color = tinta ?: if (primaria) p.botonTexto else p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}

/** Botón redondo con un ícono. */
@Composable
fun Redondo(modifier: Modifier = Modifier, tam: Dp = 44.dp, fondo: Color? = null, alTocar: () -> Unit, contenido: @Composable () -> Unit) {
    Box(
        modifier.presionable { Sonidos.tic(); alTocar() }.size(tam).clip(CircleShape).background(fondo ?: Tema.p.superficie),
        contentAlignment = Alignment.Center,
    ) { contenido() }
}

@Composable
fun Enlace(texto: String, modifier: Modifier = Modifier, color: Color? = null, alTocar: () -> Unit) {
    Text(
        texto, color = color ?: Tema.p.texto2, fontFamily = Sans, fontSize = 15.sp,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable { Sonidos.tic(); alTocar() }.padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

@Composable
fun Senal(texto: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(texto.uppercase(), color = color ?: Tema.p.texto2, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = modifier)
}

@Composable
fun TituloSerif(texto: String, modifier: Modifier = Modifier, tam: Int = 40, color: Color? = null, italica: Boolean = false) {
    Text(
        texto, color = color ?: Tema.p.texto, fontFamily = Serif, fontSize = tam.sp, lineHeight = (tam * 1.05f).sp,
        fontStyle = if (italica) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        modifier = modifier,
    )
}

@Composable
fun Parrafo(texto: String, modifier: Modifier = Modifier, color: Color? = null, centro: Boolean = false) {
    Text(
        texto, color = color ?: Tema.p.texto2, fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp, modifier = modifier,
        textAlign = if (centro) TextAlign.Center else TextAlign.Start,
    )
}

/** Campo de texto redondeado, para la configuración. */
@Composable
fun Campo(
    valor: String,
    alCambiar: (String) -> Unit,
    etiqueta: String,
    modifier: Modifier = Modifier,
    secreto: Boolean = false,
    tipo: KeyboardType = KeyboardType.Text,
) {
    val p = Tema.p
    Column(modifier) {
        Text(etiqueta, color = p.texto2, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        BasicTextField(
            value = valor,
            onValueChange = alCambiar,
            singleLine = true,
            textStyle = TextStyle(color = p.texto, fontSize = 17.sp, fontFamily = Sans),
            cursorBrush = SolidColor(p.acento),
            visualTransformation = if (secreto) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secreto) KeyboardType.Password else tipo),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(horizontal = 16.dp, vertical = 15.dp),
        )
    }
}

/** Hoja que sube desde abajo, con esquinas redondeadas (en lugar de diálogos del sistema). */
@Composable
fun Hoja(visible: Boolean, alCerrar: () -> Unit, contenido: @Composable ColumnScope.() -> Unit) {
    if (visible) BackHandler { alCerrar() }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { alCerrar() }
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 380f)) { it },
        exit = slideOutVertically(tween(220)) { it },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(if (Tema.esNoche) Color(0xFF111113) else Color(0xFFF8F0E3))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(horizontal = 24.dp, vertical = 14.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Tema.p.linea))
                contenido()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Teclado del PIN: círculos grandes, como en iOS. */
@Composable
fun Teclado(alDigito: (Char) -> Unit, alBorrar: () -> Unit, colorTexto: Color, fondoTecla: Color, extra: @Composable () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("123", "456", "789").forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                fila.forEach { d -> Tecla(d.toString(), colorTexto, fondoTecla) { alDigito(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { extra() }
            Tecla("0", colorTexto, fondoTecla) { alDigito('0') }
            Box(
                Modifier.size(76.dp).clip(CircleShape).clickable { Sonidos.tic(); alBorrar() },
                contentAlignment = Alignment.Center,
            ) { Text("Borrar", color = colorTexto, fontFamily = Sans, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Tecla(texto: String, colorTexto: Color, fondo: Color, alTocar: () -> Unit) {
    Box(
        Modifier.presionable { Sonidos.tic(); alTocar() }.size(76.dp).clip(CircleShape).background(fondo),
        contentAlignment = Alignment.Center,
    ) { Text(texto, color = colorTexto, fontFamily = Sans, fontSize = 30.sp) }
}

@Composable
fun Puntos(llenos: Int, total: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(total) { i ->
            Box(Modifier.size(13.dp).clip(CircleShape).background(if (i < llenos) color else Color.Transparent).border(1.2.dp, color, CircleShape))
        }
    }
}

/** El botón se hunde un poco al tocarlo, con un rebote suave. */
@Composable
fun Modifier.presionable(habilitado: Boolean = true, alTocar: () -> Unit): Modifier {
    val fuente = remember { MutableInteractionSource() }
    val presionado by fuente.collectIsPressedAsState()
    val escala by animateFloatAsState(
        if (presionado && habilitado) 0.94f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "presion",
    )
    return this
        .graphicsLayer { scaleX = escala; scaleY = escala }
        .clickable(interactionSource = fuente, indication = null, enabled = habilitado) { alTocar() }
}

// ---------- íconos dibujados a mano (trazo fino) ----------

@Composable
fun IconoSol(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.2f
        drawCircle(color, r, c, style = Stroke(size.minDimension * 0.08f))
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val d1 = size.minDimension * 0.33f; val d2 = size.minDimension * 0.46f
            drawLine(color, Offset(c.x + (d1 * Math.cos(a)).toFloat(), c.y + (d1 * Math.sin(a)).toFloat()),
                Offset(c.x + (d2 * Math.cos(a)).toFloat(), c.y + (d2 * Math.sin(a)).toFloat()), size.minDimension * 0.08f, StrokeCap.Round)
        }
    }
}

@Composable
fun IconoLuna(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.62f, w * 0.12f)
            cubicTo(w * 0.28f, w * 0.18f, w * 0.14f, w * 0.52f, w * 0.3f, w * 0.74f)
            cubicTo(w * 0.46f, w * 0.94f, w * 0.78f, w * 0.92f, w * 0.9f, w * 0.68f)
            cubicTo(w * 0.62f, w * 0.72f, w * 0.42f, w * 0.42f, w * 0.62f, w * 0.12f)
            close()
        }
        drawPath(path, color, style = Stroke(w * 0.08f))
    }
}

@Composable
fun IconoMas(color: Color, tam: Dp = 18.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.1f
        drawLine(color, Offset(s / 2, s * 0.1f), Offset(s / 2, s * 0.9f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.1f, s / 2), Offset(s * 0.9f, s / 2), g, StrokeCap.Round)
    }
}

@Composable
fun IconoAtras(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.1f
        drawLine(color, Offset(s * 0.62f, s * 0.15f), Offset(s * 0.3f, s * 0.5f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.3f, s * 0.5f), Offset(s * 0.62f, s * 0.85f), g, StrokeCap.Round)
    }
}

@Composable
fun IconoPuntos(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width
        for (i in 0 until 3) drawCircle(color, s * 0.07f, Offset(s * (0.22f + i * 0.28f), s / 2))
    }
}

@Composable
fun IconoFoto(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.08f
        drawRoundRect(color, Offset(s * 0.08f, s * 0.2f), Size(s * 0.84f, s * 0.62f), androidx.compose.ui.geometry.CornerRadius(s * 0.12f), style = Stroke(g))
        drawCircle(color, s * 0.08f, Offset(s * 0.34f, s * 0.4f))
        val p = Path().apply { moveTo(s * 0.14f, s * 0.74f); lineTo(s * 0.42f, s * 0.5f); lineTo(s * 0.6f, s * 0.64f); lineTo(s * 0.72f, s * 0.54f); lineTo(s * 0.86f, s * 0.7f) }
        drawPath(p, color, style = Stroke(g, cap = StrokeCap.Round))
    }
}

@Composable
fun IconoHuella(color: Color, tam: Dp = 28.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.045f
        val st = Stroke(g, cap = StrokeCap.Round)
        drawArc(color, 200f, 140f, false, Offset(s * 0.18f, s * 0.12f), Size(s * 0.64f, s * 0.64f), style = st)
        drawArc(color, 190f, 160f, false, Offset(s * 0.3f, s * 0.26f), Size(s * 0.4f, s * 0.4f), style = st)
        drawLine(color, Offset(s * 0.5f, s * 0.46f), Offset(s * 0.5f, s * 0.8f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.7f, s * 0.46f), Offset(s * 0.68f, s * 0.78f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.3f, s * 0.46f), Offset(s * 0.26f, s * 0.72f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.18f, s * 0.42f), Offset(s * 0.14f, s * 0.6f), g, StrokeCap.Round)
        drawLine(color, Offset(s * 0.82f, s * 0.42f), Offset(s * 0.84f, s * 0.62f), g, StrokeCap.Round)
    }
}

/** El botón de día/noche: sol de día, luna de noche. */
@Composable
fun BotonModo(modifier: Modifier = Modifier) {
    val p = Tema.p
    Redondo(modifier, alTocar = { Tema.alternar() }) {
        if (Tema.esNoche) IconoLuna(p.texto) else IconoSol(p.texto)
    }
}

@Composable
fun IconoLupa(color: Color, tam: Dp = 20.dp) {
    Canvas(Modifier.size(tam)) {
        val s = size.width; val g = s * 0.09f
        drawCircle(color, s * 0.27f, Offset(s * 0.43f, s * 0.43f), style = Stroke(g))
        drawLine(color, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.86f, s * 0.86f), g, StrokeCap.Round)
    }
}

/** La línea del horizonte del logo: se abre desde el centro, con su marca vertical. */
@Composable
fun Horizonte(progreso: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(14.dp)) {
        val y = size.height / 2
        val medio = size.width / 2
        val mitad = medio * progreso
        if (mitad <= 0f) return@Canvas
        val pincel = androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to color.copy(alpha = 0f), 0.18f to color, 0.82f to color, 1f to color.copy(alpha = 0f),
            startX = medio - mitad, endX = medio + mitad,
        )
        drawLine(pincel, Offset(medio - mitad, y), Offset(medio + mitad, y), strokeWidth = 1.2.dp.toPx())
        drawLine(color.copy(alpha = progreso), Offset(medio, 0f), Offset(medio, size.height), strokeWidth = 1.2.dp.toPx())
    }
}
