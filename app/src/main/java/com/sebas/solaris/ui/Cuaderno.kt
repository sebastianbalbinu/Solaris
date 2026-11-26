@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.sebas.solaris.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
    var diagramar by remember { mutableStateOf(false) }
    // pegados: la hoja de pegar, el que está elegido, dibujar a mano, y los textos de notas y boletos
    var hojaPegar by remember { mutableStateOf(false) }
    var hojaVozPegada by remember { mutableStateOf(false) }
    var elegido by remember { mutableStateOf<String?>(null) }
    var dibujando by remember { mutableStateOf(false) }
    var colorTrazo by remember { mutableIntStateOf(0) }
    val trazos = remember { mutableStateListOf<TrazoVivo>() }
    var editandoTexto by remember { mutableStateOf<String?>(null) }
    var casetePara by remember { mutableStateOf<Bloque?>(null) }
    // arrastrar una foto o un casete manteniéndolo presionado
    var arrastrando by remember { mutableStateOf<String?>(null) }
    var desfaseArrastre by remember { mutableFloatStateOf(0f) }
    val altos = remember { HashMap<String, Int>() }
    // dónde está cada bloque en la pantalla, para pegar cosas en lo que se está viendo
    val posiciones = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
    var ventana by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var origenDibujo by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    val ctxEditor = androidx.compose.ui.platform.LocalContext.current
    val enfoque = androidx.compose.ui.platform.LocalFocusManager.current
    // la lámina se graba en una capa solo cuando se va a compartir (grabarla siempre gastaba en cada cuadro)
    val capa = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var compartiendo by remember { mutableStateOf(false) }
    var capturar by remember { mutableStateOf(false) }

    // la nota como pila de bloques: párrafos, fotos, collages y voces
    val bloques = remember(b) { mutableStateListOf<Bloque>() }
    var activo by remember(b) { mutableStateOf<Bloque?>(null) }
    LaunchedEffect(b) {
        if (!b.cargado) {
            b.id?.let { Casa.porId(it) }?.let { e ->
                b.titulo = e.title; b.texto = e.text; b.fotos = e.photos; b.estilos = e.estilos; b.creado = e.created
                b.pies = e.pies; b.lados = e.lados; b.forma = e.forma; b.voces = e.voces; b.sello = e.sello
            }
            b.cargado = true
        }
        bloques.reemplazar(bloquesDe(b))
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

    fun sincronizar() = volcar(b, bloques)
    fun cambiar(bl: Bloque, v: TextFieldValue) { bl.valor = v; sincronizar() }
    /** El párrafo donde está el cursor, o el último. */
    fun destino(): Bloque = activo?.takeIf { it in bloques && it.esTexto } ?: bloques.lastOrNull { it.esTexto }
        ?: Bloque(com.sebas.solaris.Pieza.TEXTO).also { bloques.add(it) }

    /** Pone bloques nuevos donde está el cursor, partiendo el párrafo en dos. */
    fun insertar(en: Bloque, nuevos: List<Bloque>) {
        val i = bloques.indexOf(en).let { if (it < 0) bloques.size - 1 else it }
        val t = en.valor.text
        val c = en.valor.selection.start.coerceIn(0, t.length)
        val antes = t.substring(0, c).trimEnd('\n')
        val despues = t.substring(c).trimStart('\n')
        en.valor = TextFieldValue(antes, androidx.compose.ui.text.TextRange(antes.length))
        val resto = Bloque(com.sebas.solaris.Pieza.TEXTO, texto = despues)
        bloques.addAll(i + 1, nuevos + resto)
        bloques.reemplazar(ordenar(bloques.toList()))
        activo = bloques.getOrNull(bloques.indexOf(nuevos.last()) + 1)
        sincronizar()
    }
    fun quitarFoto(id: String) {
        bloques.forEach { if (id in it.ids && it.esFoto) it.ids = it.ids - id }
        bloques.reemplazar(ordenar(bloques.toList()))
        sincronizar()
    }
    fun sacarDelCollage(id: String) {
        val bl = bloques.firstOrNull { it.tipo == com.sebas.solaris.Pieza.FOTOS && id in it.ids } ?: return
        bl.ids = bl.ids - id
        val i = bloques.indexOf(bl)
        bloques.add(i + 1, Bloque(com.sebas.solaris.Pieza.FOTO, listOf(id)))
        bloques.reemplazar(ordenar(bloques.toList()))
        sincronizar()
    }

    /** El bloque que está a la vista en [yPantalla] (px de pantalla) y cuánto más abajo de su borde (dp). */
    fun bloqueEn(yPantalla: Float): Pair<Bloque, Float>? {
        val visibles = bloques.mapNotNull { bl -> posiciones[bl.clave]?.let { bl to it } }
        if (visibles.isEmpty()) return null
        val (bl, r) = visibles.firstOrNull { (_, r) -> yPantalla >= r.top && yPantalla <= r.bottom }
            ?: visibles.minByOrNull { (_, r) -> kotlin.math.min(kotlin.math.abs(r.top - yPantalla), kotlin.math.abs(r.bottom - yPantalla)) }!!
        return bl to (yPantalla - r.top) / densidad
    }
    fun pegarAqui(tipo: String, dato: String) {
        val centro = if (ventana.height > 0) ventana.top + ventana.height * 0.45f else 600f
        val (bl, y) = bloqueEn(centro) ?: (destino() to 40f)
        val nuevo = pegadoNuevo(tipo, dato, y, if (tipo == com.sebas.solaris.Pegado.CINTA || tipo == com.sebas.solaris.Pegado.CLIP) 1f else 0.95f)
        bl.pegados.add(nuevo)
        elegido = nuevo.clave
        sincronizar()
        if (tipo == com.sebas.solaris.Pegado.NOTA || tipo == com.sebas.solaris.Pegado.BOLETO) editandoTexto = nuevo.clave
    }
    fun buscarPegado(clave: String): Pair<Bloque, Int>? {
        for (bl in bloques) { val i = bl.pegados.indexOfFirst { it.clave == clave }; if (i >= 0) return bl to i }
        return null
    }
    fun quitarPegado(clave: String) {
        buscarPegado(clave)?.let { (bl, i) -> bl.pegados.removeAt(i) }
        elegido = null
        bloques.reemplazar(ordenar(bloques.toList()))
        sincronizar()
    }
    fun terminarDibujo() {
        for (t in trazos) {
            val (centroLocal, dato) = trazoAPegado(t, densidad) ?: continue
            val centro = centroLocal + origenDibujo
            val (bl, y) = bloqueEn(centro.y) ?: continue
            val r = posiciones[bl.clave] ?: continue
            bl.pegados.add(com.sebas.solaris.Pegado(com.sebas.solaris.Pegado.TRAZO, ((centro.x - r.left) / r.width.coerceAtLeast(1f)), y, 0f, 1f, dato))
        }
        trazos.clear()
        dibujando = false
        sincronizar()
    }
    // la foto de un pegado (polaroid o con esquineros)
    var fotoPegadaTipo by remember { mutableStateOf<String?>(null) }
    val elegirFotoPegada = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        Casa.pausarCandado = false
        val tipo = fotoPegadaTipo ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val id = Casa.agregarFoto(uri) ?: return@launch
            b.estilos = b.estilos + (id to com.sebas.solaris.EstiloFoto.nueva().texto()); b.fotosNuevas.add(id)
            pegarAqui(tipo, id)
        }
    }
    fun pegar(tipo: String, dato: String) {
        hojaPegar = false
        when (tipo) {
            com.sebas.solaris.Pegado.POLAROID, com.sebas.solaris.Pegado.ESQUINAS -> {
                fotoPegadaTipo = tipo
                Casa.pausarCandado = true
                elegirFotoPegada.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            com.sebas.solaris.Pegado.CASETE -> hojaVozPegada = true
            com.sebas.solaris.Pegado.TRAZO -> { enfoque.clearFocus(); elegido = null; dibujando = true }
            else -> pegarAqui(tipo, dato)
        }
    }
    /** Grabar ahí mismo: el casete queda donde estaba el cursor. */
    fun grabarAqui() {
        val donde = destino()
        enfoque.clearFocus()
        Nav.alGrabar = { id -> insertar(donde, listOf(Bloque(com.sebas.solaris.Pieza.VOZ, listOf(id)))) }
        Nav.grabando = true
    }

    var dondeFoto by remember { mutableStateOf<Bloque?>(null) }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris ->
        Casa.pausarCandado = false
        scope.launch {
            val ids = mutableListOf<String>()
            for (u in uris) {
                val id = Casa.agregarFoto(u)
                if (id != null) { ids += id; b.estilos = b.estilos + (id to com.sebas.solaris.EstiloFoto.nueva().texto()); b.fotosNuevas.add(id) }
            }
            if (ids.isEmpty()) return@launch
            // dos o tres a la vez: un collage; una o muchas: cada una en su lugar
            val nuevos = if (ids.size in 2..3) listOf(Bloque(com.sebas.solaris.Pieza.FOTOS, ids))
            else ids.map { Bloque(com.sebas.solaris.Pieza.FOTO, listOf(it)) }
            insertar(dondeFoto ?: destino(), nuevos)
        }
    }
    fun pedirFoto() {
        dondeFoto = destino()
        Casa.pausarCandado = true
        elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    fun guardarYSalir() {
        sincronizar()
        if (b.vacio()) Casa.descartarNuevas(b) else Casa.guardarBorrador(b)
        Nav.borrador = null
    }
    fun salirDeDiagramar() {
        bloques.reemplazar(ordenar(bloques.toList()))
        sincronizar()
        diagramar = false
    }
    BackHandler(enabled = !hojaLetra && !hojaOpciones && !hojaInsertar && !hojaVoces && !hojaPegar && !hojaVozPegada && fotoAbierta == null && editandoTexto == null && casetePara == null) {
        when {
            dibujando -> terminarDibujo()
            elegido != null -> elegido = null
            diagramar -> salirDeDiagramar()
            else -> guardarYSalir()
        }
    }

    val forma = com.sebas.solaris.Forma.de(b.forma)
    fun fijarForma(f: com.sebas.solaris.Forma) { b.forma = f.texto() }

    // la lámina: arriba se ve el mar, y a través del papel (con su grano) se adivinan sus relámpagos
    Box(Modifier.fillMaxSize()) {
        val fondoPapel = colorDePapel(forma.papel, p.fondo)
        Box(
            Modifier.fillMaxSize().drawBehind {
                val a = Tema.papelLamina
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to fondoPapel.copy(alpha = a * 0.45f), 0.28f to fondoPapel.copy(alpha = a * 0.9f), 0.5f to fondoPapel.copy(alpha = a), 1f to fondoPapel.copy(alpha = (a * 1.02f).coerceAtMost(1f))))
                drawRect(Vidrio.pincelGrano, alpha = if (Tema.esNoche) 0.35f else if (forma.papel == 3) 1f else 0.7f)
            }
        )
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Redondo(vidrio = true, alTocar = { if (diagramar) salirDeDiagramar() else guardarYSalir() }) { IconoAtras(p.texto) }
                Spacer(Modifier.weight(1f))
                // diagramar: ver la pila de bloques y cambiarlos de lugar
                Redondo(vidrio = !diagramar, fondo = if (diagramar) p.boton else null, alTocar = {
                    if (diagramar) salirDeDiagramar() else {
                        enfoque.clearFocus()
                        sincronizar()
                        bloques.reemplazar(ordenar(bloques.toList(), compacta = true))
                        diagramar = true
                    }
                }) { IconoDiagramar(if (diagramar) p.botonTexto else p.texto) }
                Spacer(Modifier.width(6.dp))
                // izquierda o centrado, de un toque
                Redondo(vidrio = true, alTocar = { fijarForma(forma.copy(centro = !forma.centro, poema = if (forma.centro) false else forma.poema)) }) {
                    IconoAlinear(p.texto, forma.centrado)
                }
                Spacer(Modifier.width(6.dp))
                Redondo(vidrio = true, alTocar = { hojaLetra = true }) { Text("Aa", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
                Spacer(Modifier.width(6.dp))
                BotonModo(vidrio = true)
                Spacer(Modifier.width(6.dp))
                Redondo(vidrio = true, alTocar = { hojaOpciones = true }) { IconoPuntos(p.texto) }
            }
            val margen = forma.margenDp.dp
            val tamLetra = Tema.tamanoLetra.toFloat() + if (forma.poema) 1f else 0f
            val pasoRenglon = (tamLetra * (if (forma.poema) 1.95f else if (forma.serif || forma.letra != 0) 1.7f else 1.62f) * forma.factorAire)
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { ventana = it.boundsInRoot() }
                    .bordesQueSeDisuelven(arriba = 36.dp, abajo = 96.dp).verticalScroll(desplazamiento, enabled = !dibujando)
                    .drawWithContent {
                        if (capturar) {
                            capa.record { this@drawWithContent.drawContent() }
                            drawLayer(capa)
                        } else drawContent()
                    }
                    // los renglones, la cuadrícula o el margen rojo del papel (se deslizan con el texto)
                    .drawBehind { dibujarPapel(forma.papel, pasoRenglon.sp.toPx(), margen.toPx(), p.texto2) }
                    .padding(horizontal = margen).padding(top = 96.dp, bottom = 120.dp),
            ) {
                val alinear = if (forma.centrado) TextAlign.Center else TextAlign.Start
                Senal(
                    "Día " + Casa.diaDeBitacora(b.creado) + " · " + formato("HH:mm", b.creado) + " · " + Corriente.nombreVisible,
                    color = p.texto2,
                    modifier = if (forma.centrado) Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally) else Modifier,
                )
                Spacer(Modifier.height(10.dp))
                val fuenteTitulo = when (forma.letra) { 1 -> Maquina; 2 -> Mano; else -> Serif }
                Escribible(
                    b.titulo, { b.titulo = it }, "Título",
                    TextStyle(color = p.texto, fontFamily = fuenteTitulo, fontSize = (if (forma.letra == 2) 40 else if (forma.letra == 1) 28 else 34).sp, lineHeight = 40.sp, textAlign = alinear),
                )
                val tam = tamLetra
                val enSerif = forma.poema || forma.serif
                val fuente = when (forma.letra) { 1 -> Maquina; 2 -> Mano; else -> if (enSerif) Serif else Sans }
                val estilo = TextStyle(
                    color = Tema.colorLetra,
                    fontFamily = fuente,
                    fontSize = (when { forma.letra == 2 -> tam * 1.32f; forma.letra == 1 -> tam * 0.96f; enSerif -> tam * 1.12f; else -> tam }).sp,
                    lineHeight = pasoRenglon.sp,
                    textAlign = alinear,
                )
                val estiloLado = TextStyle(color = Tema.colorLetra, fontFamily = Sans, fontSize = (tam * 0.92f).sp, lineHeight = (tam * 1.5f * forma.factorAire).sp)
                Spacer(Modifier.height(18.dp))

                if (diagramar) {
                    Diagramar(b, bloques, alAbrirFoto = { fotoAbierta = it }) { sincronizar() }
                } else {
                    val primerTexto = bloques.firstOrNull { it.esTexto }
                    val ultimoTexto = bloques.lastOrNull { it.esTexto }
                    val todoVacio = bloques.all { !it.esTexto || it.valor.text.isEmpty() }
                    var nFoto = 0
                    bloques.forEach { bl ->
                        key(bl.clave) {
                          val arrastrado = arrastrando == bl.clave
                          Box(
                            Modifier.fillMaxWidth()
                                .zIndex(if (arrastrado) 3f else if (bl.pegados.isNotEmpty()) 1f else 0f)
                                .onGloballyPositioned { c ->
                                    posiciones[bl.clave] = c.boundsInRoot()
                                    AnchoBloques.actual = c.size.width.toFloat()
                                    altos[bl.clave] = c.size.height
                                }
                                .graphicsLayer {
                                    translationY = if (arrastrado) desfaseArrastre else 0f
                                    val e = if (arrastrado) 1.03f else 1f
                                    scaleX = e; scaleY = e
                                    shadowElevation = if (arrastrado) 18.dp.toPx() else 0f
                                }
                                .then(if (bl.esMedio) Modifier.pointerInput(bl.clave) {
                                    // mantener presionada una foto o un casete y arrastrarlo a otro lugar de la hoja
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { arrastrando = bl.clave; desfaseArrastre = 0f; com.sebas.solaris.Sonidos.tecla() },
                                        onDragEnd = { arrastrando = null; desfaseArrastre = 0f; bloques.reemplazar(ordenar(bloques.toList())); sincronizar() },
                                        onDragCancel = { arrastrando = null; desfaseArrastre = 0f },
                                    ) { cambio, d ->
                                        cambio.consume()
                                        desfaseArrastre += d.y
                                        val k = bloques.indexOfFirst { it.clave == bl.clave }
                                        if (k < 0) return@detectDragGesturesAfterLongPress
                                        if (desfaseArrastre > 0 && k < bloques.size - 1) {
                                            val h = (altos[bloques[k + 1].clave] ?: 0).toFloat()
                                            if (desfaseArrastre > h / 2) { bloques.add(k + 1, bloques.removeAt(k)); desfaseArrastre -= h; com.sebas.solaris.Sonidos.tic() }
                                        } else if (desfaseArrastre < 0 && k > 0) {
                                            val h = (altos[bloques[k - 1].clave] ?: 0).toFloat()
                                            if (-desfaseArrastre > h / 2) { bloques.add(k - 1, bloques.removeAt(k)); desfaseArrastre += h; com.sebas.solaris.Sonidos.tic() }
                                        }
                                    }
                                } else Modifier),
                          ) {
                            when (bl.tipo) {
                                com.sebas.solaris.Pieza.TEXTO -> {
                                    val formatoTexto = remember(Tema.esNoche, tam, forma.capitular, bl === primerTexto, p) {
                                        FormatoSencillo(p.texto2.copy(alpha = 0.45f), p.acento, p.texto2, tam, forma.capitular && bl === primerTexto)
                                    }
                                    val ultimo = bl === ultimoTexto
                                    // la hoja sigue al cursor: al escribir (o al abrirse el teclado) baja sola hasta donde va la letra
                                    val seguir = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                                    var capa by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                                    var conFoco by remember { mutableStateOf(false) }
                                    val densidad = androidx.compose.ui.platform.LocalDensity.current
                                    val teclado = WindowInsets.ime.getBottom(densidad)
                                    LaunchedEffect(conFoco, bl.valor.selection, bl.valor.text.length, teclado) {
                                        if (!conFoco) return@LaunchedEffect
                                        delay(30) // que el texto nuevo ya esté acomodado
                                        val r = capa ?: return@LaunchedEffect
                                        val c = r.getCursorRect(bl.valor.selection.end.coerceIn(0, r.layoutInput.text.length))
                                        // un poco de aire arriba y, abajo, más allá de la niebla del borde
                                        val aire = with(densidad) { 24.dp.toPx() }
                                        val bajo = with(densidad) { 120.dp.toPx() }
                                        seguir.bringIntoView(androidx.compose.ui.geometry.Rect(c.left, c.top - aire, c.right + 1f, c.bottom + bajo))
                                    }
                                    BasicTextField(
                                        value = bl.valor,
                                        onValueChange = { nuevo ->
                                            // en un poema el enter no arma listas
                                            cambiar(bl, if (forma.poema) nuevo else continuarLista(bl.valor, nuevo))
                                        },
                                        textStyle = estilo,
                                        cursorBrush = SolidColor(p.acento),
                                        visualTransformation = formatoTexto,
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                        onTextLayout = { capa = it },
                                        modifier = Modifier.fillMaxWidth()
                                            .bringIntoViewRequester(seguir)
                                            .onFocusChanged { conFoco = it.isFocused; if (it.isFocused) activo = bl }
                                            .defaultMinSize(minHeight = if (ultimo) 300.dp else 30.dp)
                                            .padding(vertical = 4.dp),
                                        decorationBox = { campo ->
                                            Box {
                                                if (ultimo && todoVacio) Text(
                                                    if (forma.poema) "Escribe tu poema…" else "Escribe aquí…",
                                                    style = estilo.copy(color = p.texto2.copy(alpha = 0.5f)),
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                campo()
                                            }
                                        },
                                    )
                                }
                                com.sebas.solaris.Pieza.FOTO -> {
                                    val id = bl.ids.first()
                                    FotoPegada(id, com.sebas.solaris.EstiloFoto.de(b.estilos[id]), nFoto++, b.pies[id] ?: "", b.lados[id] ?: "", estiloLado, margen) { fotoAbierta = id }
                                }
                                com.sebas.solaris.Pieza.FOTOS -> {
                                    nFoto++
                                    val giro = com.sebas.solaris.EstiloFoto.de(b.estilos[bl.ids.first()]).giro * 0.4f
                                    Collage(bl.ids, giro, b.pies[bl.ids.first()] ?: "") { fotoAbierta = it }
                                }
                                else -> {
                                    val c = remember(bl.ids, Casa.items) { bl.ids.firstOrNull()?.let { Casa.porId(it) }?.takeIf { !it.deleted } }
                                    if (c == null) Unit else CasetePegado(c, bl.opcion, alOpciones = { casetePara = bl })
                                }
                            }
                            // lo pegado encima de este bloque
                            if (bl.pegados.isNotEmpty()) CapaPegados(
                                bl.pegados, b, elegido, editable = !dibujando,
                                alElegir = { elegido = it; if (it != null) enfoque.clearFocus() },
                                alCambiar = { sincronizar() },
                                modifier = Modifier.matchParentSize(),
                            )
                          }
                        }
                    }
                }
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

        // dibujar a mano: una capa encima de la hoja que se ve
        if (dibujando) CapaDibujo(trazos, colorTrazo, Modifier.fillMaxSize().onGloballyPositioned { origenDibujo = it.positionInRoot() })

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sel = elegido?.let { buscarPegado(it) }
            if (diagramar) {
                Pildora("Listo, así queda", icono = { IconoDiagramar(p.botonTexto, 18.dp) }) { salirDeDiagramar() }
            } else if (dibujando) {
                Row(
                    Modifier.height(50.dp).vidrio(CircleShape, p.superficie, if (Tema.esNoche) 0.7f else 0.88f).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ColoresTinta.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .border(if (i == colorTrazo) 2.dp else 0.dp, p.texto, CircleShape).clickable { com.sebas.solaris.Sonidos.tic(); colorTrazo = i },
                        )
                    }
                    Enlace("Deshacer") { if (trazos.isNotEmpty()) trazos.removeAt(trazos.size - 1) }
                }
                Pildora("Listo") { terminarDibujo() }
            } else if (sel != null) {
                val (blSel, iSel) = sel
                val pg = blSel.pegados[iSel]
                Row(
                    Modifier.height(50.dp).vidrio(CircleShape, p.superficie, if (Tema.esNoche) 0.7f else 0.88f).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pg.tipo == com.sebas.solaris.Pegado.NOTA || pg.tipo == com.sebas.solaris.Pegado.BOLETO || pg.tipo == com.sebas.solaris.Pegado.SELLO) {
                        Enlace("Texto") { editandoTexto = pg.clave }
                    }
                    if (pg.tipo == com.sebas.solaris.Pegado.CASETE) Enlace("Escuchar") {
                        val c = Casa.porId(pg.dato)
                        if (c != null && Casa.archivoCinta(c.id).exists()) com.sebas.solaris.Grabadora.reproducir(c.id, Casa.archivoCinta(c.id))
                    }
                    Enlace("Enderezar") { blSel.pegados[iSel] = pg.copy(giro = 0f); sincronizar() }
                    Enlace("Quitar", color = Color(0xFFD65C48)) { quitarPegado(pg.clave) }
                }
                Pildora("Listo") { elegido = null }
            } else {
                Row(
                    Modifier.height(50.dp).vidrio(CircleShape, p.superficie, if (Tema.esNoche) 0.7f else 0.88f).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BotonFormato({ Text("B", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) }) { destino().let { cambiar(it, conNegrita(it.valor)) } }
                    BotonFormato({ IconoTachado(p.texto) }) { destino().let { cambiar(it, conTachado(it.valor)) } }
                    BotonFormato({ Text("•", color = p.texto, fontFamily = Sans, fontSize = 22.sp) }) { destino().let { cambiar(it, alternarPrefijo(it.valor, "- ")) } }
                    BotonFormato({ Text("❝", color = p.texto, fontFamily = Sans, fontSize = 18.sp) }) { destino().let { cambiar(it, alternarPrefijo(it.valor, "> ")) } }
                    BotonFormato({ IconoMas(p.texto, 17.dp) }) { hojaInsertar = true }
                    // pegar cosas encima de la hoja
                    BotonFormato({ IconoPegar(p.texto) }) { enfoque.clearFocus(); hojaPegar = true }
                }
                Pildora("Listo") { guardarYSalir() }
            }
        }

        val abierta = fotoAbierta
        if (abierta != null) {
            val enCollage = bloques.any { it.tipo == com.sebas.solaris.Pieza.FOTOS && abierta in it.ids }
            VisorFoto(
                b, abierta, enCollage,
                alQuitar = { quitarFoto(abierta) },
                alSacar = { sacarDelCollage(abierta) },
            ) { fotoAbierta = null }
        }

        HojaLetra(hojaLetra, b) { hojaLetra = false }
        HojaInsertar(hojaInsertar, alElegir = { que ->
            hojaInsertar = false
            when (que) {
                "cita" -> destino().let { cambiar(it, alternarPrefijo(it.valor, "» ")) }
                "ola" -> destino().let { cambiar(it, insertarLinea(it.valor, "~~~")) }
                "voz" -> { dondeFoto = destino(); hojaVoces = true }
                "grabar" -> grabarAqui()
                "foto" -> pedirFoto()
                "pegar" -> { enfoque.clearFocus(); hojaPegar = true }
            }
        }) { hojaInsertar = false }
        HojaPegar(hojaPegar, alElegir = { tipo, dato -> pegar(tipo, dato) }) { hojaPegar = false }
        HojaElegirVoz(hojaVozPegada, alElegir = { id -> hojaVozPegada = false; pegarAqui(com.sebas.solaris.Pegado.CASETE, id) }) { hojaVozPegada = false }
        // el texto de una nota adhesiva, un boleto o un sello
        val claveTexto = editandoTexto
        val enTexto = claveTexto?.let { buscarPegado(it) }
        var borradorTexto by remember(claveTexto) {
            mutableStateOf(enTexto?.let { (bl, i) -> bl.pegados[i].let { pg -> if (pg.tipo == com.sebas.solaris.Pegado.NOTA) pg.dato.substringAfter('|', "") else if (pg.tipo == com.sebas.solaris.Pegado.SELLO) pg.dato.split('|').drop(1).joinToString(" / ") else pg.dato } } ?: "")
        }
        Hoja(enTexto != null, { editandoTexto = null }) {
            val pg = enTexto?.let { (bl, i) -> bl.pegados.getOrNull(i) }
            Text(
                when (pg?.tipo) { com.sebas.solaris.Pegado.NOTA -> "La nota"; com.sebas.solaris.Pegado.SELLO -> "El sello (arriba / abajo)"; else -> "El boleto" },
                color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp,
            )
            Campo(borradorTexto, { borradorTexto = it }, if (pg?.tipo == com.sebas.solaris.Pegado.NOTA) "Lo que quieras recordar…" else "Un lugar, una fecha…")
            Pildora("Listo", Modifier.fillMaxWidth()) {
                enTexto?.let { (bl, i) ->
                    val q = bl.pegados.getOrNull(i)
                    if (q != null) bl.pegados[i] = q.copy(
                        dato = when (q.tipo) {
                            com.sebas.solaris.Pegado.NOTA -> q.dato.substringBefore('|', "0") + "|" + borradorTexto
                            com.sebas.solaris.Pegado.SELLO -> q.dato.substringBefore('|', "0") + "|" + borradorTexto.split(" / ", limit = 2).let { it[0] + "|" + (it.getOrNull(1) ?: "") }
                            else -> borradorTexto
                        },
                    )
                    sincronizar()
                }
                editandoTexto = null
            }
        }
        // el tamaño de un casete y lo demás
        val bc = casetePara
        Hoja(bc != null, { casetePara = null }) {
            Text("El casete", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            SegmentoTema(TamCasete.todos.map { it.second }, TamCasete.todos.indexOfFirst { it.first == (bc?.opcion ?: "") }.coerceAtLeast(0)) { i ->
                bc?.opcion = TamCasete.todos[i].first; sincronizar()
            }
            Parrafo("Escondido: asoma bajo el papel hasta que lo sacas. Para moverlo, mantenlo presionado y arrástralo.")
            Pildora("Ver la cinta (nombre, transcripción)", Modifier.fillMaxWidth(), primaria = false) {
                bc?.ids?.firstOrNull()?.let { Nav.escuchando = it }; casetePara = null
            }
            Pildora("Quitar de la nota", Modifier.fillMaxWidth(), primaria = false) {
                bc?.let { bloques.remove(it); bloques.reemplazar(ordenar(bloques.toList())); sincronizar() }
                casetePara = null
            }
        }
        HojaVoces(hojaVoces, b.voces, alAlternar = { id ->
            if (id in b.voces) {
                bloques.removeAll { it.tipo == com.sebas.solaris.Pieza.VOZ && id in it.ids }
                bloques.reemplazar(ordenar(bloques.toList()))
                sincronizar()
            } else {
                val donde = dondeFoto?.takeIf { it in bloques } ?: destino()
                insertar(donde, listOf(Bloque(com.sebas.solaris.Pieza.VOZ, listOf(id))))
                dondeFoto = activo
            }
        }) { hojaVoces = false }

        Hoja(hojaOpciones, { hojaOpciones = false }) {
            Text("Esta nota", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Pildora(if (compartiendo) "Preparando la lámina…" else "Compartir como imagen", Modifier.fillMaxWidth()) {
                if (compartiendo) return@Pildora
                compartiendo = true
                enfoque.clearFocus()
                elegido = null
                scope.launch {
                    // un par de cuadros para que se vaya el cursor, la hoja se cierre y la capa se grabe
                    hojaOpciones = false
                    capturar = true
                    delay(450)
                    try {
                        val img = capa.toImageBitmap()
                        compartirImagen(ctxEditor, b, img)
                    } catch (e: Throwable) {
                        android.widget.Toast.makeText(ctxEditor, "La lámina es muy larga para una sola imagen. Prueba con PDF.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    capturar = false
                    compartiendo = false
                }
            }
            Parrafo("Tal como la ves: tu diagramación, lo que pegaste encima y el mar de este momento detrás. El PDF también.")
            Pildora("Compartir como PDF", Modifier.fillMaxWidth(), primaria = false) {
                if (compartiendo) return@Pildora
                compartiendo = true
                enfoque.clearFocus()
                elegido = null
                sincronizar()
                scope.launch {
                    // el PDF sale de la lámina tal como se ve: con todo lo pegado encima
                    hojaOpciones = false
                    capturar = true
                    delay(450)
                    try { compartirPdfDeLamina(ctxEditor, b, capa.toImageBitmap()) } catch (e: Throwable) { compartirPdf(ctxEditor, b) }
                    capturar = false
                    compartiendo = false
                }
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

/** La foto abierta: cómo va pegada (sola, al lado del texto o a sangre), su tamaño, su giro, su pie y su texto al lado. */
@Composable
private fun VisorFoto(b: Borrador, id: String, enCollage: Boolean, alQuitar: () -> Unit, alSacar: () -> Unit, alCerrar: () -> Unit) {
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
            if (enCollage) {
                Parrafo("Esta foto va en un collage. Puedes sacarla para que vaya sola.", color = Color(0xB3EDE3CF))
                Pildora("Sacar del collage", Modifier.fillMaxWidth(), primaria = false) { alSacar(); alCerrar() }
            } else {
                Segmento(listOf("Sola", "Texto a la der.", "Texto a la izq."), when (est.pos) { com.sebas.solaris.EstiloFoto.IZQUIERDA -> 1; com.sebas.solaris.EstiloFoto.DERECHA -> 2; else -> 0 }) { i ->
                    fijar(est.copy(pos = when (i) { 1 -> com.sebas.solaris.EstiloFoto.IZQUIERDA; 2 -> com.sebas.solaris.EstiloFoto.DERECHA; else -> com.sebas.solaris.EstiloFoto.SOLA }, tam = if (i != 0 && est.tam == 3) 1 else est.tam))
                }
                if (!est.alLado) Segmento(listOf("Pequeña", "Mediana", "Grande", "A sangre"), est.tam) { t -> fijar(est.copy(tam = t)) }
                if (!est.aSangre) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("↺  Girar" to -2f, "Recta" to 0f, "Girar  ↻" to 2f).forEach { (nombre, paso) ->
                        Text(
                            nombre, color = Color.White, fontFamily = Sans, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(CircleShape).background(Color(0x26FFFFFF))
                                .clickable { fijar(est.copy(giro = if (paso == 0f) 0f else (est.giro + paso).coerceIn(-10f, 10f))) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
            CampoOscuro(b.pies[id] ?: "", { b.pies = b.pies + (id to it) }, "Pie de foto (opcional)", unaLinea = true)
            if (est.alLado && !enCollage) CampoOscuro(b.lados[id] ?: "", { b.lados = b.lados + (id to it) }, "Lo que va al lado de la foto…", unaLinea = false)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pildora("Quitar de la nota", Modifier.weight(1f), primaria = false) { alQuitar(); alCerrar() }
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
            Interruptor("Cuerpo en serif", "Todo el texto con la letra de los títulos, como un libro.", f.serif) {
                nota.forma = f.copy(serif = it).texto()
            }
            Text("Empezar desde una plantilla", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.sebas.solaris.Forma.Plantillas.forEach { (nombre, pl) ->
                    Text(
                        nombre, color = p.texto, fontFamily = when (pl.letra) { 1 -> Maquina; 2 -> Mano; else -> if (pl.serif) Serif else Sans },
                        fontSize = if (pl.letra == 2) 20.sp else 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(colorDePapel(pl.papel, p.superficie))
                            .border(1.dp, p.linea, RoundedCornerShape(14.dp))
                            .clickable { com.sebas.solaris.Sonidos.tic(); nota.forma = pl.copy(centro = f.centro, poema = f.poema).texto() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Text("Papel", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Liso", "Rayado", "Cuadriculado", "Kraft", "Cuaderno", "Fotográfico").forEachIndexed { i, nombre ->
                    val sel = f.papel == i
                    Text(
                        nombre, color = if (sel) p.botonTexto else p.texto, fontFamily = Sans, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) p.boton else p.superficie)
                            .clickable { com.sebas.solaris.Sonidos.tic(); nota.forma = f.copy(papel = i).texto() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            Text("Letra de esta nota", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            SegmentoTema(listOf("La de siempre", "Máquina", "A mano"), f.letra) { nota.forma = f.copy(letra = it).texto() }
            Text("Aire entre renglones", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            SegmentoTema(listOf("Junto", "Normal", "Amplio"), f.aire) { nota.forma = f.copy(aire = it).texto() }
            Text("Márgenes", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            SegmentoTema(listOf("Estrechos", "Normales", "Anchos"), f.margen) { nota.forma = f.copy(margen = it).texto() }
            Parrafo("Para mover fotos, voces y párrafos de lugar, toca el botón de diagramar arriba.")
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

/** Un selector de pocas opciones con los colores del papel. */
@Composable
private fun SegmentoTema(opciones: List<String>, elegido: Int, alElegir: (Int) -> Unit) {
    val p = Tema.p
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.superficie).padding(4.dp)) {
        opciones.forEachIndexed { i, nombre ->
            val sel = i == elegido
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) p.seleccion else Color.Transparent)
                    .clickable { com.sebas.solaris.Sonidos.tic(); alElegir(i) },
                contentAlignment = Alignment.Center,
            ) { Text(nombre, color = if (sel) p.texto else p.texto2, fontFamily = Sans, fontSize = 14.sp) }
        }
    }
}
