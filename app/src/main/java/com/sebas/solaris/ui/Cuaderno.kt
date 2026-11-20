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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val ES: Locale = Locale("es", "CO")
fun formato(patron: String, cuando: Long): String = SimpleDateFormat(patron, ES).format(Date(cuando))

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
    var hojaInsertar by remember { mutableStateOf(false) }
    var hojaVoces by remember { mutableStateOf(false) }
    var fotoAbierta by remember { mutableStateOf<String?>(null) }
    val ctxEditor = androidx.compose.ui.platform.LocalContext.current
    val enfoque = androidx.compose.ui.platform.LocalFocusManager.current
    // la lámina entera se va grabando en una capa: así se puede compartir tal como se ve, de arriba abajo
    val capa = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var compartiendo by remember { mutableStateOf(false) }

    var cuerpo by remember(b) { mutableStateOf(TextFieldValue(b.texto)) }
    LaunchedEffect(b) {
        if (!b.cargado) {
            b.id?.let { Casa.porId(it) }?.let { e ->
                b.titulo = e.title; b.texto = e.text; b.fotos = e.photos; b.estilos = e.estilos; b.creado = e.created
                b.pies = e.pies; b.lados = e.lados; b.forma = e.forma; b.voces = e.voces; b.sello = e.sello
            }
            b.cargado = true
            cuerpo = TextFieldValue(b.texto)
        }
        // una nota nueva queda sellada con el mar de este momento
        if (b.id == null && b.sello.isBlank()) b.sello = selloDelMar()
    }
    // autoguardado: unos segundos después de dejar de escribir
    LaunchedEffect(b.titulo, b.texto, b.fotos, b.estilos, b.pies, b.lados, b.forma, b.voces) {
        delay(2500)
        if (b.cargado && !b.vacio()) Casa.guardarBorrador(b)
    }
    val desplazamiento = rememberScrollState(b.desplazamiento)
    DisposableEffect(b) { onDispose { b.desplazamiento = desplazamiento.value } }

    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        Casa.pausarCandado = false
        scope.launch {
            for (u in uris) {
                val id = Casa.agregarFoto(u)
                if (id != null) { b.fotos = b.fotos + id; b.estilos = b.estilos + (id to com.sebas.solaris.EstiloFoto.nueva().texto()); b.fotosNuevas.add(id) }
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
    fun cambiar(v: TextFieldValue) { cuerpo = v; b.texto = v.text }
    BackHandler(enabled = !hojaLetra && !hojaOpciones && !hojaInsertar && !hojaVoces && fotoAbierta == null) { guardarYSalir() }

    val forma = com.sebas.solaris.Forma.de(b.forma)
    fun fijarForma(f: com.sebas.solaris.Forma) { b.forma = f.texto() }

    // la lámina: arriba se ve el mar, y a través del papel se adivinan sus relámpagos
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Tema.papelLamina.let { a ->
                    Brush.verticalGradient(0f to Color.Transparent, 0.10f to p.fondo.copy(alpha = a * 0.45f), 0.28f to p.fondo.copy(alpha = a * 0.9f), 0.5f to p.fondo.copy(alpha = a), 1f to p.fondo.copy(alpha = (a * 1.02f).coerceAtMost(1f)))
                }
            )
        )
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Redondo(alTocar = { guardarYSalir() }) { IconoAtras(p.texto) }
                Spacer(Modifier.weight(1f))
                // izquierda o centrado, de un toque
                Redondo(alTocar = { fijarForma(forma.copy(centro = !forma.centro, poema = if (forma.centro) false else forma.poema)) }) {
                    IconoAlinear(p.texto, forma.centrado)
                }
                Spacer(Modifier.width(8.dp))
                Redondo(alTocar = { hojaLetra = true }) { Text("Aa", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
                Spacer(Modifier.width(8.dp))
                BotonModo()
                Spacer(Modifier.width(8.dp))
                Redondo(alTocar = { hojaOpciones = true }) { IconoPuntos(p.texto) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(desplazamiento)
                    .drawWithContent {
                        capa.record { this@drawWithContent.drawContent() }
                        drawLayer(capa)
                    }
                    .padding(horizontal = 24.dp).padding(top = 96.dp, bottom = 120.dp),
            ) {
                val alinear = if (forma.centrado) TextAlign.Center else TextAlign.Start
                Senal(
                    "Día " + Casa.diaDeBitacora(b.creado) + " · " + formato("HH:mm", b.creado) + " · " + Corriente.nombreVisible,
                    color = p.texto2,
                    modifier = if (forma.centrado) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally) else Modifier,
                )
                Spacer(Modifier.height(10.dp))
                Escribible(
                    b.titulo, { b.titulo = it }, "Título",
                    TextStyle(color = p.texto, fontFamily = Serif, fontSize = 34.sp, lineHeight = 38.sp, textAlign = alinear),
                )
                val tam = Tema.tamanoLetra.toFloat() + if (forma.poema) 1f else 0f
                val estilo = TextStyle(
                    color = Tema.colorLetra,
                    fontFamily = if (forma.poema) Serif else Sans,
                    fontSize = (if (forma.poema) tam * 1.12f else tam).sp,
                    lineHeight = (tam * if (forma.poema) 1.95f else 1.62f).sp,
                    textAlign = alinear,
                )
                val estiloLado = TextStyle(color = Tema.colorLetra, fontFamily = Sans, fontSize = (tam * 0.92f).sp, lineHeight = (tam * 1.5f).sp)
                // las fotos van pegadas a la lámina: cada una con su tamaño, su giro, su pie y, si va al lado, su texto
                b.fotos.forEachIndexed { i, id ->
                    Spacer(Modifier.height(if (i == 0) 22.dp else 14.dp))
                    FotoPegada(id, com.sebas.solaris.EstiloFoto.de(b.estilos[id]), i, b.pies[id] ?: "", b.lados[id] ?: "", estiloLado) { fotoAbierta = id }
                }
                // las voces pegadas
                if (b.voces.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    val cintas = remember(b.voces, Casa.items) { b.voces.mapNotNull { Casa.porId(it) }.filter { !it.deleted } }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cintas.forEach { c ->
                            CintaPegada(c, alQuitar = { b.voces = b.voces - c.id }) { Nav.escuchando = c.id }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val formatoTexto = remember(Tema.esNoche, tam, forma.capitular) {
                    FormatoSencillo(p.texto2.copy(alpha = 0.45f), p.acento, p.texto2, tam, forma.capitular)
                }
                BasicTextField(
                    value = cuerpo,
                    onValueChange = { nuevo ->
                        if (nuevo.text != cuerpo.text) Ritmo.tecla(kotlin.math.abs(nuevo.text.length - cuerpo.text.length))
                        // en un poema el enter no arma listas
                        cambiar(if (forma.poema) nuevo else continuarLista(cuerpo, nuevo))
                    },
                    textStyle = estilo,
                    cursorBrush = SolidColor(p.acento),
                    visualTransformation = formatoTexto,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 300.dp),
                    decorationBox = { campo ->
                        Box {
                            if (cuerpo.text.isEmpty()) Text(
                                if (forma.poema) "Escribe tu poema…" else "Escribe aquí…",
                                style = estilo.copy(color = p.texto2.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            campo()
                        }
                    },
                )
                // el sello: cómo estaba el mar cuando se escribió
                if (b.sello.isNotBlank()) {
                    Spacer(Modifier.height(28.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (forma.centrado) Arrangement.Center else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(18.dp).height(1.dp).background(p.texto2.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(10.dp))
                        Senal(b.sello, color = p.texto2.copy(alpha = 0.75f))
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(50.dp).clip(CircleShape).background(p.superficie).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotonFormato({ Text("B", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }) { cambiar(conNegrita(cuerpo)) }
                BotonFormato({ IconoTachado(p.texto) }) { cambiar(conTachado(cuerpo)) }
                BotonFormato({ Text("•", color = p.texto, fontFamily = Sans, fontSize = 22.sp) }) { cambiar(alternarPrefijo(cuerpo, "- ")) }
                BotonFormato({ Text("❝", color = p.texto, fontFamily = Sans, fontSize = 18.sp) }) { cambiar(alternarPrefijo(cuerpo, "> ")) }
                BotonFormato({ IconoMas(p.texto, 17.dp) }) { hojaInsertar = true }
            }
            Pildora("Listo") { guardarYSalir() }
        }

        val abierta = fotoAbierta
        if (abierta != null) VisorFoto(b, abierta) { fotoAbierta = null }

        HojaLetra(hojaLetra, b) { hojaLetra = false }
        HojaInsertar(hojaInsertar, alElegir = { que ->
            hojaInsertar = false
            when (que) {
                "cita" -> cambiar(alternarPrefijo(cuerpo, "» "))
                "ola" -> cambiar(insertarLinea(cuerpo, "~~~"))
                "voz" -> hojaVoces = true
                "foto" -> pedirFoto()
            }
        }) { hojaInsertar = false }
        HojaVoces(hojaVoces, b.voces, alAlternar = { id -> b.voces = if (id in b.voces) b.voces - id else b.voces + id }) { hojaVoces = false }

        Hoja(hojaOpciones, { hojaOpciones = false }) {
            Text("Esta nota", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Pildora(if (compartiendo) "Preparando la lámina…" else "Compartir como imagen", Modifier.fillMaxWidth()) {
                if (compartiendo) return@Pildora
                compartiendo = true
                enfoque.clearFocus()
                scope.launch {
                    // un par de cuadros para que se vaya el cursor y la hoja se cierre antes de fotografiar
                    hojaOpciones = false
                    delay(450)
                    try {
                        val img = capa.toImageBitmap()
                        compartirImagen(ctxEditor, b, img)
                    } catch (e: Throwable) {
                        android.widget.Toast.makeText(ctxEditor, "La lámina es muy larga para una sola imagen. Prueba con PDF.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    compartiendo = false
                }
            }
            Parrafo("Tal como la ves: tu diagramación, tus fotos y el mar de este momento detrás.")
            Pildora("Compartir como PDF", Modifier.fillMaxWidth(), primaria = false) {
                hojaOpciones = false
                scope.launch { compartirPdf(ctxEditor, b) }
            }
            if (b.id != null) Parrafo("Borrarla la quita de este celular y de la nube, para siempre.")
            if (b.id != null) Pildora("Borrar nota", Modifier.fillMaxWidth(), primaria = false) {
                hojaOpciones = false
                b.id?.let { Casa.borrarNota(it) }
                Nav.borrador = null
            }
        }
    }
    }
}

/** La foto abierta: cómo va pegada (sola o al lado del texto), su tamaño, su giro, su pie y su texto al lado. */
@Composable
private fun VisorFoto(b: Borrador, id: String, alCerrar: () -> Unit) {
    BackHandler { alCerrar() }
    val est = com.sebas.solaris.EstiloFoto.de(b.estilos[id])
    fun fijar(e: com.sebas.solaris.EstiloFoto) { b.estilos = b.estilos + (id to e.texto()); com.sebas.solaris.Sonidos.tic() }
    val claro = Color(0xFFEDE3CF)
    Column(
        Modifier.fillMaxSize().background(Color(0xF4000000)).systemBarsPadding().imePadding().padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Foto(id, Modifier.weight(1f).fillMaxWidth().clickable { alCerrar() }, lado = 1600, recortar = false)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmento(listOf("Sola", "Texto a la der.", "Texto a la izq."), when (est.pos) { com.sebas.solaris.EstiloFoto.IZQUIERDA -> 1; com.sebas.solaris.EstiloFoto.DERECHA -> 2; else -> 0 }) { i ->
                fijar(est.copy(pos = when (i) { 1 -> com.sebas.solaris.EstiloFoto.IZQUIERDA; 2 -> com.sebas.solaris.EstiloFoto.DERECHA; else -> com.sebas.solaris.EstiloFoto.SOLA }))
            }
            if (!est.alLado) Segmento(listOf("Pequeña", "Mediana", "Grande"), est.tam) { t -> fijar(est.copy(tam = t)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("↺  Girar" to -2f, "Recta" to 0f, "Girar  ↻" to 2f).forEach { (nombre, paso) ->
                    Text(
                        nombre, color = Color.White, fontFamily = Sans, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(CircleShape).background(Color(0x26FFFFFF))
                            .clickable { fijar(est.copy(giro = if (paso == 0f) 0f else (est.giro + paso).coerceIn(-10f, 10f))) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
            CampoOscuro(b.pies[id] ?: "", { b.pies = b.pies + (id to it) }, "Pie de foto (opcional)", unaLinea = true)
            if (est.alLado) CampoOscuro(b.lados[id] ?: "", { b.lados = b.lados + (id to it) }, "Lo que va al lado de la foto…", unaLinea = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pildora("Quitar de la nota", Modifier.weight(1f), primaria = false) { b.fotos = b.fotos - id; alCerrar() }
                Pildora("Listo", Modifier.weight(1f), fondo = claro, tinta = Color(0xFF0E1B26)) { alCerrar() }
            }
        }
    }
}

@Composable
private fun Segmento(opciones: List<String>, elegido: Int, alElegir: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color(0x1FFFFFFF)).padding(3.dp)) {
        opciones.forEachIndexed { i, nombre ->
            val sel = i == elegido
            Text(
                nombre, color = if (sel) Color(0xFF0E1B26) else Color.White, fontFamily = Sans, fontSize = 13.sp,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).clip(CircleShape).background(if (sel) Color(0xFFEDE3CF) else Color.Transparent)
                    .clickable { alElegir(i) }.padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CampoOscuro(valor: String, alCambiar: (String) -> Unit, pista: String, unaLinea: Boolean) {
    val estilo = TextStyle(color = Color(0xFFEDE3CF), fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 17.sp, lineHeight = 22.sp)
    BasicTextField(
        value = valor,
        onValueChange = alCambiar,
        singleLine = unaLinea,
        textStyle = estilo,
        cursorBrush = SolidColor(Color(0xFFEDE3CF)),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x1FFFFFFF))
            .padding(horizontal = 16.dp, vertical = 12.dp).heightIn(max = 150.dp),
        decorationBox = { campo ->
            Box {
                if (valor.isEmpty()) Text(pista, style = estilo.copy(color = Color(0x80EDE3CF)))
                campo()
            }
        },
    )
}

/** Tamaño y color de la letra, y el modo. */
@Composable
fun HojaLetra(visible: Boolean, nota: Borrador? = null, alCerrar: () -> Unit) {
    val p = Tema.p
    Hoja(visible, alCerrar) {
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (nota != null) {
            val f = com.sebas.solaris.Forma.de(nota.forma)
            Text("Esta nota", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(4.dp)) {
                listOf(false to "Izquierda", true to "Centrado").forEach { (centro, nombre) ->
                    val sel = f.centrado == centro
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(if (sel) p.seleccion else Color.Transparent)
                            .clickable { com.sebas.solaris.Sonidos.tic(); nota.forma = f.copy(centro = centro, poema = if (centro) f.poema else false).texto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconoAlinear(if (sel) p.texto else p.texto2, centro, 16.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(nombre, color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 14.sp)
                        }
                    }
                }
            }
            Interruptor("Modo poema", "Centrado, en serif, con aire entre versos y sin listas automáticas.", f.poema) {
                nota.forma = f.copy(poema = it).texto()
            }
            Interruptor("Letra capitular", "La primera letra, grande, como en los libros.", f.capitular) {
                nota.forma = f.copy(capitular = it).texto()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.linea))
        }
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

        if (!Tema.esNoche) {
            Text("Papel", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Papeles.forEachIndexed { i, papel ->
                    val sel = i == Tema.papel
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { com.sebas.solaris.Sonidos.tic(); Tema.fijarPapel(i) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(papel.fondo)
                                .border(if (sel) 2.dp else 1.dp, if (sel) p.acento else p.linea, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = papel.texto, fontFamily = Sans, fontSize = 17.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text(NombresPapel[i], color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 12.sp)
                    }
                }
            }
        }

        if (Tema.esNoche) {
            Text("De noche", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Oscuros.forEachIndexed { i, modo ->
                    val sel = i == Tema.oscuro
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { com.sebas.solaris.Sonidos.tic(); Tema.fijarOscuro(i) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(modo.fondo)
                                .border(if (sel) 2.dp else 1.dp, if (sel) p.acento else p.linea, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = modo.texto, fontFamily = Sans, fontSize = 17.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text(NombresOscuro[i], color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 12.sp)
                    }
                }
            }
        }

        Text("Cuánto mar se ve al escribir", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("mar", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            androidx.compose.material3.Slider(
                value = Tema.papelLamina,
                onValueChange = { Tema.fijarPapelLamina(it) },
                valueRange = 0.4f..1f,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = p.acento, activeTrackColor = p.acento, inactiveTrackColor = p.linea),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("papel", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        }

        Text("Modo", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(4.dp)) {
            listOf("dia" to "Día", "noche" to "Noche", "auto" to "Según la hora").forEach { (clave, nombre) ->
                val sel = Tema.modo == clave
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (sel) p.seleccion else Color.Transparent)
                        .clickable { Tema.fijarModo(clave) },
                    contentAlignment = Alignment.Center,
                ) { Text(nombre, color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 14.sp) }
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

@Composable
private fun BotonFormato(contenido: @Composable () -> Unit, alTocar: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).clickable { com.sebas.solaris.Sonidos.tic(); alTocar() },
        contentAlignment = Alignment.Center,
    ) { contenido() }
}
