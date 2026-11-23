package com.sebas.solaris.ui

import android.graphics.Bitmap
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * El vidrio de la estación: todo lo que flota sobre el mar lo deja ver desenfocado, como un vidrio esmerilado.
 * El mar (y su oscuridad cuando uno se hunde) es la única fuente: lo demás lo mira a través.
 * En Android 11 o menos el sistema no sabe desenfocar en vivo; ahí el vidrio es un velo del mismo color.
 */
object Vidrio {
    val estado = HazeState()
    /** Qué tan hondo va uno en la orilla (0 superficie, 1 abajo): oscurece el agua detrás de todo. */
    var hondura by mutableFloatStateOf(0f)
    /** Qué tanto está presente la orilla (se retira mientras se escribe). */
    var presencia by mutableFloatStateOf(1f)
    /** Si el mar está debajo (fuera de la estación no hay mar que desenfocar). */
    var activo by mutableStateOf(false)

    /** Una tesela de ruido para el grano del papel. */
    val grano: ImageBitmap by lazy {
        val n = 192
        val px = IntArray(n * n)
        val r = kotlin.random.Random(11)
        for (i in px.indices) {
            val g = r.nextFloat() + r.nextFloat() - 1f
            val a = (kotlin.math.abs(g) * 26f).toInt().coerceIn(0, 255)
            px[i] = if (g > 0) (a shl 24) or 0xFFFFFF else (a shl 24) or 0x1A1208
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val pincelGrano: ShaderBrush by lazy { ShaderBrush(ImageShader(grano, TileMode.Repeated, TileMode.Repeated)) }
}

/** El filo de luz del vidrio: más claro arriba, casi nada abajo, como en iOS. */
fun filoDeLuz(fuerza: Float = 1f) = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.22f * fuerza),
    0.5f to Color.White.copy(alpha = 0.06f * fuerza),
    1f to Color.White.copy(alpha = 0.03f * fuerza),
)

/**
 * Vidrio esmerilado sobre el mar.
 * [tinte] es el color del vidrio y [opacidad] cuánto de ese color se ve encima del mar desenfocado.
 */
fun Modifier.vidrio(
    forma: Shape,
    tinte: Color,
    opacidad: Float = 0.45f,
    radio: Dp = 24.dp,
    filo: Float = 1f,
): Modifier {
    if (!Vidrio.activo) {
        val b = this.clip(forma).background(tinte.copy(alpha = max(opacidad, 0.92f)))
        return if (filo > 0f) b.border(0.8.dp, filoDeLuz(filo), forma) else b
    }
    val estilo = HazeStyle(
        backgroundColor = Azules.Abismo,
        tint = HazeTint(tinte.copy(alpha = opacidad)),
        blurRadius = radio,
        noiseFactor = 0.07f,
        fallbackTint = HazeTint(tinte.copy(alpha = max(opacidad, 0.88f))),
    )
    val base = this.clip(forma).hazeChild(Vidrio.estado, estilo)
    return if (filo > 0f) base.border(0.8.dp, filoDeLuz(filo), forma) else base
}

/** Un borde que se oscurece hacia el agua honda: lo que se desliza se disuelve ahí (un degradado, nada más). */
fun Modifier.nieblaDeBorde(arriba: Boolean, tinte: Color = Azules.Abismo): Modifier =
    this.background(
        if (arriba) Brush.verticalGradient(0f to tinte.copy(alpha = 0.92f), 0.6f to tinte.copy(alpha = 0.55f), 1f to Color.Transparent)
        else Brush.verticalGradient(0f to Color.Transparent, 0.5f to tinte.copy(alpha = 0.6f), 1f to tinte.copy(alpha = 0.95f))
    )

/** Lo que se desliza se desvanece en los bordes de arriba y de abajo (sin cortarse en seco). */
fun Modifier.bordesQueSeDisuelven(arriba: Dp = 40.dp, abajo: Dp = 110.dp): Modifier =
    this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = size.height
            if (h <= 0f) return@drawWithContent
            val a = (arriba.toPx() / h).coerceIn(0.001f, 0.4f)
            val b = (1f - abajo.toPx() / h).coerceIn(0.6f, 0.999f)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, a to Color.Black, b to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

/** Lo que entra o sale sobre el mar lo hace saliendo de un desenfoque, como si se enfocara. */
@Composable
fun Modifier.enfoqueAlEntrar(transicion: Transition<EnterExitState>, maximo: Dp = 18.dp): Modifier {
    val radio by transicion.animateDp({ tween(if (targetState == EnterExitState.Visible) 420 else 260) }, label = "enfoque") {
        if (it == EnterExitState.Visible) 0.dp else maximo
    }
    return if (radio > 0.5.dp) this.blur(radio) else this
}
