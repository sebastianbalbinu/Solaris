package com.sebas.logia.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sebas.logia.Casa
import com.sebas.logia.Huella
import com.sebas.logia.MainActivity
import com.sebas.logia.R
import kotlinx.coroutines.launch

/** Raíz de la app. */
@Composable
fun Logia(act: MainActivity) {
    Box(Modifier.fillMaxSize().background(C.Noche)) {
        when {
            !Casa.conectada -> Conectar()
            !Casa.lista -> Bienvenida(act)
            else -> {
                if (Casa.abierta) Interior()
                Telon(abierto = Casa.abierta)
                if (!Casa.abierta) Candado(act)
            }
        }
    }
}

/** Las dos mitades del telón rojo. Se abren despacio al entrar y se cierran al salir. */
@Composable
fun Telon(abierto: Boolean) {
    val avance by animateFloatAsState(
        targetValue = if (abierto) 1f else 0f,
        animationSpec = tween(durationMillis = if (abierto) 1700 else 500, easing = FastOutSlowInEasing),
        label = "telon",
    )
    if (avance >= 0.999f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mitad = maxWidth / 2
        Mitad(Modifier.align(Alignment.CenterStart).width(mitad + 2.dp).fillMaxHeight()
            .graphicsLayer { translationX = -size.width * avance })
        Mitad(Modifier.align(Alignment.CenterEnd).width(mitad + 2.dp).fillMaxHeight()
            .graphicsLayer { translationX = size.width * avance })
    }
}

@Composable
private fun Mitad(modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        Image(painterResource(R.drawable.telon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // sombra en el borde donde se juntan
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0x33000000), Color.Transparent, Color(0x55000000)))
            )
        )
    }
}

/** La entrada: PIN, huella o frase llave. */
@Composable
fun Candado(act: MainActivity) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var modoFrase by remember { mutableStateOf(Casa.pinBloqueado()) }
    var frase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    val conHuella = remember { Casa.huellaActiva() && Huella.disponible(act) }

    LaunchedEffect(Unit) {
        if (conHuella && !modoFrase) Huella.pedir(act) { Casa.abrirConCelular() }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x99100303), Color(0x33100303), Color(0xCC100303))))
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.7f))
        Titulo("Logia", tam = 46)
        Spacer(Modifier.height(6.dp))
        Nota("lo que no quieres perder entre el sueño y la vigilia", color = C.CremaSuave, centro = true)
        Spacer(Modifier.weight(0.5f))

        if (!modoFrase) {
            Puntos(pin.length, Casa.largoPin())
            Spacer(Modifier.height(14.dp))
            Nota(error ?: " ", color = C.Dorado, centro = true)
            Spacer(Modifier.height(14.dp))
            Teclado(
                alDigito = { d ->
                    if (!trabajando && pin.length < Casa.largoPin()) {
                        pin += d
                        error = null
                        if (pin.length == Casa.largoPin()) {
                            trabajando = true
                            val intento = pin
                            scope.launch {
                                val ok = Casa.probarPin(intento)
                                if (!ok) {
                                    pin = ""
                                    if (Casa.pinBloqueado()) {
                                        modoFrase = true
                                        error = "Demasiados intentos. Entra con tu frase llave."
                                    } else {
                                        val quedan = Casa.INTENTOS_PIN - Casa.fallosPin()
                                        error = "Ese no es tu PIN. Quedan $quedan intentos."
                                    }
                                }
                                trabajando = false
                            }
                        }
                    }
                },
                alBorrar = { pin = pin.dropLast(1) },
                extra = {
                    if (conHuella) Enlace("Huella", color = C.Crema) { Huella.pedir(act) { Casa.abrirConCelular() } }
                },
            )
            Spacer(Modifier.height(12.dp))
            Enlace("Olvidé mi PIN · usar frase llave") { modoFrase = true; error = null }
        } else {
            Campo(frase, { frase = it; error = null }, "Frase llave", secreto = true, tipo = KeyboardType.Password)
            Spacer(Modifier.height(10.dp))
            Nota(error ?: " ", color = C.Dorado, centro = true)
            Spacer(Modifier.height(10.dp))
            Boton(
                if (trabajando) "Abriendo…" else "Abrir el telón",
                Modifier.fillMaxWidth(),
                habilitado = frase.isNotBlank() && !trabajando,
            ) {
                trabajando = true
                scope.launch {
                    val ok = Casa.abrirConFrase(frase)
                    if (!ok) error = "Esa frase no abre esta casa."
                    else frase = ""
                    trabajando = false
                }
            }
            if (!Casa.pinBloqueado()) {
                Spacer(Modifier.height(8.dp))
                Enlace("Volver al PIN") { modoFrase = false; error = null }
            }
        }
        Spacer(Modifier.weight(0.4f))
    }
}
