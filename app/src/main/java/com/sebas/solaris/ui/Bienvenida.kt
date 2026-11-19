package com.sebas.solaris.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Casa
import com.sebas.solaris.Huella
import com.sebas.solaris.MainActivity
import com.sebas.solaris.Problema
import kotlinx.coroutines.launch
import java.io.IOException

/** Solo aparece si la APK se compiló sin los datos de Supabase. */
@Composable
fun Conectar() {
    var url by remember { mutableStateOf("") }
    var llave by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Tema.p.fondo).systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        TituloSerif("Conectar la estación", tam = 34)
        Parrafo("Pega los datos de tu proyecto de Supabase (Project Settings → API). Solo se pide una vez.")
        Campo(url, { url = it }, "Project URL", tipo = KeyboardType.Uri)
        Campo(llave, { llave = it }, "anon public key")
        Pildora("Conectar", Modifier.fillMaxWidth(), habilitada = url.isNotBlank() && llave.isNotBlank()) { Casa.conectar(url, llave) }
    }
}

private enum class Paso { Inicio, Crear, Codigo, Entrar, Pin, ConHuella }

@Composable
fun Bienvenida(act: MainActivity) {
    var paso by remember { mutableStateOf(Paso.Inicio) }
    var codigo by remember { mutableStateOf("") }
    SideEffect { Nav.enPortada = paso == Paso.Inicio }
    Box(Modifier.fillMaxSize().background(if (paso == Paso.Inicio) Color.Black else Tema.p.fondo).systemBarsPadding().imePadding()) {
        when (paso) {
            Paso.Inicio -> Inicio(alCrear = { paso = Paso.Crear }, alEntrar = { paso = Paso.Entrar })
            Paso.Crear -> Crear(alVolver = { paso = Paso.Inicio }) { c -> codigo = c; paso = Paso.Codigo }
            Paso.Codigo -> Codigo(codigo) { paso = Paso.Pin }
            Paso.Entrar -> Entrar(alVolver = { paso = Paso.Inicio }) { paso = Paso.Pin }
            Paso.Pin -> PinNuevo { if (Huella.disponible(act)) paso = Paso.ConHuella else Casa.terminarInstalacion(false) }
            Paso.ConHuella -> PreguntaHuella { si -> Casa.terminarInstalacion(si) }
        }
    }
}

@Composable
private fun Inicio(alCrear: () -> Unit, alEntrar: () -> Unit) {
    // primera vez: siempre de noche, con el logo fundido en el negro
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.sebas.solaris.R.drawable.logo),
                contentDescription = "Solaris",
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().requiredWidth(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp * 1.15f),
            )
            Spacer(Modifier.height(4.dp))
            Text("Una estación sobre el océano para guardar lo que piensas, sueñas y dices.",
                color = Color(0xFFB9C3C8), fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.weight(1.3f))
            Pildora("Crear mi estación", Modifier.fillMaxWidth(), fondo = Color(0xFFEFE3CB), tinta = Color.Black, alTocar = alCrear)
            Spacer(Modifier.height(12.dp))
            Pildora("Ya tengo una", Modifier.fillMaxWidth(), primaria = false, fondo = Color(0x1FFFFFFF), tinta = Color(0xFFEFE3CB), alTocar = alEntrar)
            Spacer(Modifier.height(8.dp))
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Redondo(alTocar = alVolver) { IconoAtras(Tema.p.texto) }
        TituloSerif("Construir la estación", tam = 34)
        Parrafo("Dos secretos distintos la cuidan. La contraseña abre la puerta de Supabase. La frase llave cifra todo y nunca sale de tu celular: nadie más puede leer lo que guardas.")
        Campo(email, { email = it.trim() }, "Correo", tipo = KeyboardType.Email)
        Campo(clave, { clave = it }, "Contraseña de la cuenta", secreto = true)
        Campo(frase, { frase = it }, "Frase llave", secreto = true)
        Campo(frase2, { frase2 = it }, "Repite la frase llave", secreto = true)
        Parrafo("Ejemplo: “la lluvia de agosto sobre el zinc”. Larga y fácil de recordar para ti.")
        if (problema != null || error != null) Text(error ?: problema ?: "", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 14.sp)
        Pildora(if (trabajando) "Construyendo…" else "Construir", Modifier.fillMaxWidth(), habilitada = listo && !trabajando) {
            trabajando = true; error = null
            scope.launch {
                try { alTerminar(Casa.crearCasa(email, clave, frase)) } catch (e: Exception) { error = mensaje(e) }
                trabajando = false
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Codigo(codigo: String, alSeguir: () -> Unit) {
    var anotado by remember { mutableStateOf(false) }
    val p = Tema.p
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(30.dp))
        TituloSerif("Tu código de recuperación", tam = 34)
        Parrafo("Si algún día olvidas la frase llave, este código es la única otra forma de entrar. Anótalo en papel y guárdalo. No se volverá a mostrar.")
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.superficie).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                codigo.split("-").chunked(3).joinToString("\n") { it.joinToString("  ") },
                color = p.texto, fontFamily = Mono, fontSize = 21.sp, lineHeight = 34.sp, textAlign = TextAlign.Center, letterSpacing = 1.sp,
            )
        }
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { anotado = !anotado }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (anotado) p.boton else Color.Transparent).border(1.5.dp, p.texto2, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) { if (anotado) Text("✓", color = p.botonTexto, fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            Text("Ya lo anoté en papel", color = p.texto, fontFamily = Sans, fontSize = 16.sp)
        }
        Pildora("Seguir", Modifier.fillMaxWidth(), habilitada = anotado, alTocar = alSeguir)
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
    val listo = email.contains("@") && clave.isNotEmpty() && secreto.isNotBlank() && (!conCodigo || fraseNueva.trim().length >= 12)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Redondo(alTocar = alVolver) { IconoAtras(Tema.p.texto) }
        TituloSerif("Volver a la estación", tam = 34)
        Parrafo("Todo lo que guardaste en la nube vuelve a este celular. Las cintas de audio no viajan: se quedan en el celular donde las grabaste.")
        Campo(email, { email = it.trim() }, "Correo", tipo = KeyboardType.Email)
        Campo(clave, { clave = it }, "Contraseña de la cuenta", secreto = true)
        if (!conCodigo) {
            Campo(secreto, { secreto = it }, "Frase llave", secreto = true)
            Enlace("Olvidé la frase · usar código de recuperación") { conCodigo = true; secreto = "" }
        } else {
            Campo(secreto, { secreto = it }, "Código de recuperación")
            Campo(fraseNueva, { fraseNueva = it }, "Frase llave nueva", secreto = true)
            Enlace("Mejor usar mi frase llave") { conCodigo = false; secreto = "" }
        }
        if (error != null) Text(error ?: "", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 14.sp)
        Pildora(if (trabajando) "Abriendo…" else "Entrar", Modifier.fillMaxWidth(), habilitada = listo && !trabajando) {
            trabajando = true; error = null
            scope.launch {
                try { Casa.entrarCasa(email, clave, secreto, conCodigo, if (conCodigo) fraseNueva else null); alTerminar() }
                catch (e: Exception) { error = mensaje(e) }
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
    var largo by remember { mutableIntStateOf(4) }
    var primero by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val p = Tema.p
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.4f))
        TituloSerif(if (primero == null) "Tu PIN de cada día" else "Repítelo", tam = 34)
        Spacer(Modifier.height(8.dp))
        Parrafo("Con él o con tu huella entras a la estación. La frase llave queda para emergencias.", centro = true)
        Spacer(Modifier.height(16.dp))
        if (primero == null) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(4.dp)) {
                listOf(4, 6).forEach { n ->
                    Box(
                        Modifier.width(110.dp).height(38.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (largo == n) (if (Tema.esNoche) Color(0xFF2C2C30) else Color(0xFFFBF5EB)) else Color.Transparent)
                            .clickable { largo = n; pin = "" },
                        contentAlignment = Alignment.Center,
                    ) { Text("$n dígitos", color = if (largo == n) p.texto else p.texto2, fontFamily = Sans, fontSize = 14.sp) }
                }
            }
        }
        Spacer(Modifier.weight(0.3f))
        Puntos(pin.length, largo, p.texto)
        Spacer(Modifier.height(12.dp))
        Text(error ?: " ", color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Teclado(
            alDigito = { d ->
                if (pin.length < largo) {
                    pin += d; error = null
                    if (pin.length == largo) {
                        val pr = primero
                        if (pr == null) { primero = pin; pin = "" }
                        else if (pr == pin) { val listo = pin; scope.launch { Casa.fijarPin(listo); alTerminar() } }
                        else { error = "No coinciden. Empecemos de nuevo."; primero = null; pin = "" }
                    }
                }
            },
            alBorrar = { pin = pin.dropLast(1) },
            colorTexto = p.texto,
            fondoTecla = p.superficie,
        )
        Spacer(Modifier.weight(0.3f))
    }
}

@Composable
private fun PreguntaHuella(alElegir: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        TituloSerif("¿Entrar también con tu huella?", tam = 34)
        Spacer(Modifier.height(12.dp))
        Parrafo("Es más rápido. El PIN sigue funcionando siempre.")
        Spacer(Modifier.height(28.dp))
        Pildora("Sí, con mi huella", Modifier.fillMaxWidth()) { alElegir(true) }
        Spacer(Modifier.height(12.dp))
        Pildora("Solo con el PIN", Modifier.fillMaxWidth(), primaria = false) { alElegir(false) }
    }
}
