package com.sebas.logia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.logia.Casa
import com.sebas.logia.Huella
import com.sebas.logia.MainActivity
import com.sebas.logia.Problema
import kotlinx.coroutines.launch
import java.io.IOException

/** Solo aparece si la APK se compiló sin los datos de Supabase. */
@Composable
fun Conectar() {
    var url by remember { mutableStateOf("") }
    var llave by remember { mutableStateOf("") }
    Fondo {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            Titulo("Conectar la casa")
            Nota("Pega los datos de tu proyecto de Supabase (Project Settings → API). Solo se pide una vez.")
            Campo(url, { url = it }, "Project URL", tipo = KeyboardType.Uri)
            Campo(llave, { llave = it }, "anon public key")
            Boton("Conectar", Modifier.fillMaxWidth(), habilitado = url.isNotBlank() && llave.isNotBlank()) {
                Casa.conectar(url, llave)
            }
        }
    }
}

private enum class Paso { Inicio, Crear, Codigo, Entrar, Pin, ConHuella }

@Composable
fun Bienvenida(act: MainActivity) {
    var paso by remember { mutableStateOf(Paso.Inicio) }
    var codigo by remember { mutableStateOf("") }

    Fondo {
        when (paso) {
            Paso.Inicio -> Inicio(alCrear = { paso = Paso.Crear }, alEntrar = { paso = Paso.Entrar })
            Paso.Crear -> Crear(alVolver = { paso = Paso.Inicio }) { c -> codigo = c; paso = Paso.Codigo }
            Paso.Codigo -> Codigo(codigo) { paso = Paso.Pin }
            Paso.Entrar -> Entrar(alVolver = { paso = Paso.Inicio }) { paso = Paso.Pin }
            Paso.Pin -> PinNuevo {
                if (Huella.disponible(act)) paso = Paso.ConHuella else Casa.terminarInstalacion(false)
            }
            Paso.ConHuella -> PreguntaHuella { si -> Casa.terminarInstalacion(si) }
        }
    }
}

@Composable
private fun Inicio(alCrear: () -> Unit, alEntrar: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Telon(abierto = false)
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x66100303), Color(0x22100303), Color(0xEE100303))))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Titulo("Logia", tam = 48)
            Spacer(Modifier.height(8.dp))
            Nota("Una casa para lo más tuyo: lo que sueñas, lo que cargas, lo que no quieres perder.", color = C.CremaSuave, centro = true)
            Spacer(Modifier.weight(1f))
            Boton("Crear mi casa", Modifier.fillMaxWidth(), alTocar = alCrear)
            Spacer(Modifier.height(12.dp))
            Boton("Ya tengo una casa", Modifier.fillMaxWidth(), primario = false, alTocar = alEntrar)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun mensaje(e: Exception): String = when (e) {
    is Problema -> e.message ?: "Algo salió mal."
    is IOException -> "Sin conexión. Revisa el internet e inténtalo otra vez."
    else -> "Algo salió mal: ${e.message ?: e.javaClass.simpleName}"
}

@Composable
private fun Crear(alVolver: () -> Unit, alTerminar: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var frase by remember { mutableStateOf("") }
    var frase2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }

    val problema = when {
        frase.isNotEmpty() && frase.trim().length < 12 -> "La frase llave debe tener al menos 12 caracteres. Mejor varias palabras."
        frase2.isNotEmpty() && frase != frase2 -> "Las dos frases no coinciden."
        frase.isNotEmpty() && frase == clave -> "La frase llave debe ser distinta a la contraseña de la cuenta."
        else -> null
    }
    val listo = email.contains("@") && clave.length >= 6 && frase.trim().length >= 12 && frase == frase2 && frase != clave

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Enlace("‹ Volver", Modifier.offset(x = (-10).dp)) { alVolver() }
        Titulo("Construir la casa")
        Nota("Dos secretos distintos cuidan tu casa:")
        Nota("· La contraseña de la cuenta abre la puerta de Supabase.\n· La frase llave cifra todo. Nunca sale de tu celular: ni Supabase ni nadie puede leer lo que escribes.")
        Campo(email, { email = it.trim() }, "Correo", tipo = KeyboardType.Email)
        Campo(clave, { clave = it }, "Contraseña de la cuenta", secreto = true)
        Campo(frase, { frase = it }, "Frase llave", secreto = true)
        Campo(frase2, { frase2 = it }, "Repite la frase llave", secreto = true)
        Nota("Ejemplo de frase: “la lluvia de agosto sobre el zinc”. Larga y fácil de recordar para ti.")
        if (problema != null || error != null) Nota(error ?: problema ?: "", color = C.Dorado)
        Boton(
            if (trabajando) "Construyendo la casa…" else "Construir",
            Modifier.fillMaxWidth(),
            habilitado = listo && !trabajando,
        ) {
            trabajando = true
            error = null
            scope.launch {
                try {
                    alTerminar(Casa.crearCasa(email, clave, frase))
                } catch (e: Exception) {
                    error = mensaje(e)
                }
                trabajando = false
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Codigo(codigo: String, alSeguir: () -> Unit) {
    var anotado by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        Titulo("Tu código de recuperación")
        Nota("Si algún día olvidas la frase llave, este código es la única otra forma de abrir la casa. Anótalo en papel y guárdalo en un lugar seguro. No se volverá a mostrar.")
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Color(0x44000000))
                .border(1.dp, C.Dorado, RoundedCornerShape(16.dp))
                .padding(vertical = 22.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                codigo.split("-").chunked(3).joinToString("\n") { it.joinToString("  ") },
                color = C.Crema, fontFamily = FontFamily.Monospace, fontSize = 21.sp, lineHeight = 34.sp,
                textAlign = TextAlign.Center, letterSpacing = 1.sp,
            )
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { anotado = !anotado }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (anotado) C.Rojo else Color.Transparent)
                    .border(1.dp, C.CremaSuave, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { if (anotado) Text("✓", color = C.Crema, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Nota("Ya lo anoté en papel", color = C.Crema)
        }
        Boton("Seguir", Modifier.fillMaxWidth(), habilitado = anotado, alTocar = alSeguir)
    }
}

@Composable
private fun Entrar(alVolver: () -> Unit, alTerminar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var secreto by remember { mutableStateOf("") }
    var conCodigo by remember { mutableStateOf(false) }
    var fraseNueva by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var trabajando by remember { mutableStateOf(false) }

    val listo = email.contains("@") && clave.isNotEmpty() && secreto.isNotBlank() &&
        (!conCodigo || fraseNueva.trim().length >= 12)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Enlace("‹ Volver", Modifier.offset(x = (-10).dp)) { alVolver() }
        Titulo("Volver a casa")
        Nota("Todo lo que guardaste en la nube vuelve a este celular.")
        Campo(email, { email = it.trim() }, "Correo", tipo = KeyboardType.Email)
        Campo(clave, { clave = it }, "Contraseña de la cuenta", secreto = true)
        if (!conCodigo) {
            Campo(secreto, { secreto = it }, "Frase llave", secreto = true)
            Enlace("Olvidé la frase · usar código de recuperación", Modifier.offset(x = (-10).dp)) {
                conCodigo = true; secreto = ""
            }
        } else {
            Campo(secreto, { secreto = it }, "Código de recuperación")
            Campo(fraseNueva, { fraseNueva = it }, "Frase llave nueva", secreto = true)
            Enlace("Mejor usar mi frase llave", Modifier.offset(x = (-10).dp)) { conCodigo = false; secreto = "" }
        }
        if (error != null) Nota(error ?: "", color = C.Dorado)
        Boton(
            if (trabajando) "Abriendo la casa…" else "Entrar",
            Modifier.fillMaxWidth(),
            habilitado = listo && !trabajando,
        ) {
            trabajando = true
            error = null
            scope.launch {
                try {
                    Casa.entrarCasa(email, clave, secreto, conCodigo, if (conCodigo) fraseNueva else null)
                    alTerminar()
                } catch (e: Exception) {
                    error = mensaje(e)
                }
                trabajando = false
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Crear (o cambiar) el PIN de cada día. */
@Composable
fun PinNuevo(alTerminar: () -> Unit) {
    val scope = rememberCoroutineScope()
    var largo by remember { mutableStateOf(4) }
    var primero by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.5f))
        Titulo(if (primero == null) "Tu PIN de cada día" else "Repítelo", tam = 28)
        Spacer(Modifier.height(8.dp))
        Nota("Con él (o tu huella) abres el telón. La frase llave queda para emergencias.", centro = true)
        Spacer(Modifier.height(14.dp))
        if (primero == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { n ->
                    Enlace("$n dígitos", color = if (largo == n) C.Crema else C.Humo) { largo = n; pin = "" }
                }
            }
        }
        Spacer(Modifier.weight(0.3f))
        Puntos(pin.length, largo)
        Spacer(Modifier.height(12.dp))
        Nota(error ?: " ", color = C.Dorado, centro = true)
        Spacer(Modifier.height(12.dp))
        Teclado(
            alDigito = { d ->
                if (pin.length < largo) {
                    pin += d
                    error = null
                    if (pin.length == largo) {
                        val p = primero
                        if (p == null) {
                            primero = pin; pin = ""
                        } else if (p == pin) {
                            scope.launch { Casa.fijarPin(pin); alTerminar() }
                        } else {
                            error = "No coinciden. Empecemos de nuevo."
                            primero = null; pin = ""
                        }
                    }
                }
            },
            alBorrar = { pin = pin.dropLast(1) },
        )
        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
private fun PreguntaHuella(alElegir: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Titulo("¿Abrir también con tu huella?")
        Spacer(Modifier.height(12.dp))
        Nota("Es más rápido. El PIN sigue funcionando siempre.")
        Spacer(Modifier.height(28.dp))
        Boton("Sí, con mi huella", Modifier.fillMaxWidth()) { alElegir(true) }
        Spacer(Modifier.height(12.dp))
        Boton("Solo con el PIN", Modifier.fillMaxWidth(), primario = false) { alElegir(false) }
    }
}
