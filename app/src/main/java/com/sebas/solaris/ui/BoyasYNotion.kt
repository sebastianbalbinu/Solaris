package com.sebas.solaris.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sebas.solaris.Boya
import com.sebas.solaris.Boyas
import com.sebas.solaris.Casa
import com.sebas.solaris.Notion
import com.sebas.solaris.Sonidos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------- las boyas en la orilla ----------------

/**
 * Las boyas que flotan ahora: pequeñas, en mono, sin colores de alarma.
 * Se tocan cuando ya se cumplió; se hunden hasta mañana.
 */
@Composable
fun FlotanBoyas(modifier: Modifier = Modifier) {
    // cada minuto se mira si alguna llegó a su hora (o si cambió el día)
    var minuto by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) { Boyas.refrescarHechas(); minuto++; delay(60_000) }
    }
    val visibles = remember(minuto, Boyas.lista, Boyas.hechasHoy) { Boyas.visibles() }
    var tocadas by remember { mutableStateOf(setOf<String>()) }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in Boyas.lista) {
            key(b.id) {
                AnimatedVisibility(
                    b in visibles,
                    enter = fadeIn(tween(1400)),
                    exit = fadeOut(tween(900)) + shrinkHorizontally(tween(900, delayMillis = 400)),
                ) {
                    val hecha = b.id in tocadas
                    val forma = RoundedCornerShape(50)
                    Row(
                        Modifier.clip(forma).background(Color(0x330B1620)).border(1.dp, Color(0x33B9C6CC), forma)
                            .clickable {
                                if (!hecha) {
                                    Sonidos.tic()
                                    tocadas = tocadas + b.id
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(if (hecha) Azules.Crema else Color.Transparent)
                                .border(1.2.dp, Azules.Crema, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(b.nombre, color = Azules.Crema.copy(alpha = if (hecha) 0.5f else 0.9f), fontFamily = Sans, fontSize = 13.sp, maxLines = 1)
                    }
                    if (hecha) LaunchedEffect(Unit) { delay(1100); Boyas.marcar(b.id); tocadas = tocadas - b.id }
                }
            }
        }
    }
}

// ---------------- las boyas en Ajustes ----------------

@Composable
fun GrupoBoyas(alEditar: (Boya?) -> Unit) {
    val p = Tema.p
    Column {
        Text("Boyas", color = p.texto2, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.superficie)) {
            if (Boyas.lista.isEmpty()) {
                Parrafo(
                    "Recordatorios tranquilos: aparecen en la orilla a su hora y se hunden cuando los tocas. Por ejemplo: la pastilla, tomar agua, llamar a mamá.",
                    Modifier.padding(16.dp),
                )
            }
            for (b in Boyas.lista) {
                Row(
                    Modifier.fillMaxWidth().clickable { Sonidos.tic(); alEditar(b) }.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.nombre, color = p.texto, fontFamily = Sans, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(b.hora.ifBlank { "todo el día" }, if (b.aviso) "aviso" else null).joinToString(" · "),
                        color = p.texto2, fontFamily = Sans, fontSize = 15.sp,
                    )
                }
            }
            Text(
                "Nueva boya", color = p.acento, fontFamily = Sans, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable { Sonidos.tic(); alEditar(null) }.padding(horizontal = 16.dp, vertical = 15.dp),
            )
        }
    }
}

/** Crear o cambiar una boya. [boya] nula = nueva. */
@Composable
fun HojaBoya(visible: Boolean, boya: Boya?, alCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val p = Tema.p
    var nombre by remember(boya, visible) { mutableStateOf(boya?.nombre ?: "") }
    var hora by remember(boya, visible) { mutableStateOf(boya?.hora ?: "") }
    var aviso by remember(boya, visible) { mutableStateOf(boya?.aviso ?: false) }
    var error by remember(boya, visible) { mutableStateOf<String?>(null) }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun minutos(): Int? {
        val t = hora.trim()
        if (t.isEmpty()) return null
        val partes = t.split(":", ".", "h").map { it.trim() }.filter { it.isNotEmpty() }
        val h = partes.getOrNull(0)?.toIntOrNull() ?: return -1
        val m = partes.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    Hoja(visible, alCerrar) {
        Text(if (boya == null) "Nueva boya" else "Esta boya", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        Campo(nombre, { nombre = it; error = null }, "Qué recordar (la pastilla, tomar agua…)")
        Campo(hora, { hora = it; error = null }, "Desde qué hora flota (08:00) · vacío: todo el día")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Aviso silencioso", color = p.texto, fontFamily = Sans, fontSize = 16.sp)
                Text("Una notificación sin sonido a esa hora, si todavía no la tocaste", color = p.texto2, fontFamily = Sans, fontSize = 13.sp)
            }
            Switch(
                checked = aviso,
                onCheckedChange = { aviso = it; Sonidos.tic() },
                colors = SwitchDefaults.colors(checkedTrackColor = Azules.Cianotipo, checkedThumbColor = Color.White),
            )
        }
        error?.let { Text(it, color = Color(0xFFE0745F), fontFamily = Sans, fontSize = 13.sp) }
        Pildora("Guardar", Modifier.fillMaxWidth(), habilitada = nombre.isNotBlank()) {
            val m = minutos()
            when {
                m == -1 -> error = "Escribe la hora así: 08:00"
                aviso && m == null -> error = "Para el aviso hace falta una hora"
                else -> {
                    if (boya == null) Boyas.nueva(nombre, m, aviso) else Boyas.guardar(boya.copy(nombre = nombre.trim(), minuto = m, aviso = aviso))
                    if (aviso && android.os.Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) permiso.launch(Manifest.permission.POST_NOTIFICATIONS)
                    alCerrar()
                }
            }
        }
        if (boya != null) Pildora("Quitar esta boya", Modifier.fillMaxWidth(), primaria = false) { Boyas.borrar(boya.id); alCerrar() }
    }
}

// ---------------- traer de Notion ----------------

@Composable
fun GrupoNotion(alLeer: (List<Notion.Pagina>, Map<String, ByteArray>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var aviso by remember { mutableStateOf<String?>(null) }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        Casa.pausarCandado = false
        if (uri != null) scope.launch {
            aviso = "Leyendo el zip…"
            val r = try { withContext(Dispatchers.IO) { Notion.leer(ctx, uri) } } catch (e: Exception) { null }
            if (r == null || r.first.isEmpty()) aviso = "No encontré páginas en ese archivo. ¿Es la exportación en Markdown?"
            else { aviso = null; alLeer(r.first, r.second) }
        }
    }
    val p = Tema.p
    Column {
        Text("Traer de Notion", color = p.texto2, fontFamily = Sans, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.superficie)) {
            Parrafo(
                "En Notion: Configuración → Exportar todo el contenido → Markdown y CSV, con subpáginas. Luego elige aquí el .zip. " +
                    "Tus notas personales vienen marcadas; todo se cifra en este celular.",
                Modifier.padding(16.dp),
            )
            Text(
                "Elegir el .zip de Notion", color = p.acento, fontFamily = Sans, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    Sonidos.tic(); Casa.pausarCandado = true
                    elegir.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }.padding(horizontal = 16.dp, vertical = 15.dp),
            )
            aviso?.let { Parrafo(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) }
        }
    }
}

/** La lista de páginas del zip, con casillas, y el botón para entregarlas al mar. */
@Composable
fun HojaNotion(paginas: List<Notion.Pagina>?, archivos: Map<String, ByteArray>, alCerrar: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = Tema.p
    var cambio by remember(paginas) { mutableIntStateOf(0) }
    var trayendo by remember(paginas) { mutableStateOf(false) }
    var hechas by remember(paginas) { mutableIntStateOf(-1) }
    Hoja(paginas != null, { if (!trayendo) alCerrar() }) {
        val ps = paginas ?: return@Hoja
        val elegidas = remember(cambio, ps) { ps.count { it.elegida } }
        Text("Páginas de Notion", color = p.texto, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp)
        if (hechas >= 0 && !trayendo) {
            Parrafo("Entraron $hechas escritos al mar, cada uno a la profundidad de su fecha. Para verlos juntos, escribe «notion» en el sonar de Recordar.")
            Pildora("Listo", Modifier.fillMaxWidth()) { alCerrar() }
            return@Hoja
        }
        Parrafo("$elegidas de ${ps.size} marcadas. Las personales ya vienen marcadas; toca para cambiar.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            items(ps, key = { it.ruta }) { pg ->
                val sel = cambio.let { pg.elegida }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { pg.elegida = !pg.elegida; cambio++ }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (sel) p.acento else Color.Transparent)
                            .border(1.5.dp, if (sel) p.acento else p.texto2, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (sel) Text("✓", color = p.fondo, fontSize = 12.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pg.titulo, color = p.texto, fontFamily = Sans, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formato("d MMM yyyy", pg.fecha) + " · " + pg.texto.replace('\n', ' ').take(60),
                            color = p.texto2, fontFamily = Sans, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Pildora(
            if (trayendo) "Entregando… ${hechas.coerceAtLeast(0)}" else "Entregar $elegidas al mar",
            Modifier.fillMaxWidth(), habilitada = elegidas > 0 && !trayendo,
        ) {
            trayendo = true
            scope.launch {
                hechas = 0
                hechas = Notion.traer(ps, archivos) { hechas = it }
                trayendo = false
            }
        }
    }
}

// ---------------- la marea nueva ----------------

/** Una línea discreta bajo el saludo cuando hay una versión nueva de Solaris. Tocarla la instala. */
@Composable
fun AvisoMarea(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val n = com.sebas.solaris.Marea.nueva
    val prog = com.sebas.solaris.Marea.progreso
    AnimatedVisibility(n != null, modifier, enter = fadeIn(tween(1400)), exit = fadeOut(tween(900))) {
        val forma = RoundedCornerShape(50)
        Row(
            Modifier.padding(top = 10.dp).clip(forma).background(Color(0x330B1620)).border(1.dp, Color(0x33B9C6CC), forma)
                .clickable { Sonidos.tic(); com.sebas.solaris.Marea.tocar(ctx) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Azules.Crema.copy(alpha = if (prog != null) 0.4f else 0.9f)))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    prog != null -> "llega la marea nueva · " + (prog * 100).toInt() + " %"
                    com.sebas.solaris.Marea.lista -> "marea nueva 0.2.$n · toca para instalar"
                    else -> "hay marea nueva · 0.2.$n"
                },
                color = Azules.Crema.copy(alpha = 0.9f), fontFamily = Sans, fontSize = 13.sp, maxLines = 1,
            )
        }
    }
}
