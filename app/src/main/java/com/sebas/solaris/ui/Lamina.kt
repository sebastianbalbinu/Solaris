package com.sebas.solaris.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.sebas.solaris.Borrador
import com.sebas.solaris.Casa
import com.sebas.solaris.EstiloFoto
import com.sebas.solaris.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** El papel de las fotos pegadas: un crema claro, como el borde de una copia. */
private val Borde = Color(0xFFF7F2E8)
/** La cinta que las sostiene. */
private val Cinta = Color(0x8CE9DFC9)

/**
 * Una foto pegada a la lámina: con borde de papel, un trozo de cinta arriba, su tamaño y un giro leve.
 * Las pares se recuestan a la izquierda y las impares a la derecha, como en un cuaderno.
 */
@Composable
fun FotoPegada(id: String, estilo: EstiloFoto, indice: Int, alTocar: () -> Unit) {
    val bmp by produceState<Bitmap?>(Casa.fotoEnCache(id, 900), id) {
        if (value == null) value = Casa.foto(id, 900)
    }
    val alineacion = when {
        estilo.tam == 2 -> Alignment.Center
        indice % 2 == 0 -> Alignment.CenterStart
        else -> Alignment.CenterEnd
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alineacion) {
        // todo gira junto: el papel, la foto y la cinta
        Box(
            Modifier.fillMaxWidth(estilo.ancho)
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .graphicsLayer { rotationZ = estilo.giro }
                .clickable(remember { MutableInteractionSource() }, null) { alTocar() },
        ) {
            Box(
                Modifier.graphicsLayer { shadowElevation = 6.dp.toPx() }
                    .background(Borde)
                    .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 9.dp),
            ) {
                val b = bmp
                val proporcion = if (b != null && b.height > 0) b.width.toFloat() / b.height else 0.8f
                Box(Modifier.fillMaxWidth().aspectRatio(proporcion.coerceIn(0.5f, 2f)).background(Color(0xFFE6DED0))) {
                    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            // la cinta que la sostiene, un poco chueca
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = (-8).dp)
                    .graphicsLayer { rotationZ = -3f }
                    .size(width = 58.dp, height = 18.dp)
                    .background(Cinta),
            )
        }
    }
}

// ---------------- la lámina en PDF ----------------

/** Arma un PDF con la nota tal como se ve (papel, letra, fotos pegadas) y abre el menú de compartir. */
suspend fun compartirPdf(ctx: Context, b: Borrador) {
    val archivo = withContext(Dispatchers.IO) { pdf(ctx, b) } ?: return
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", archivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(archivo.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    Casa.pausarCandado = true
    ctx.startActivity(Intent.createChooser(envio, "Compartir lámina").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private suspend fun pdf(ctx: Context, b: Borrador): File? {
    val p = Tema.p
    val anchoPag = 420; val altoPag = 595 // A5, en puntos
    val margen = 40f
    val ancho = anchoPag - margen * 2
    val sans = ResourcesCompat.getFont(ctx, R.font.sans_regular) ?: Typeface.SANS_SERIF
    val sansFuerte = ResourcesCompat.getFont(ctx, R.font.sans_semibold) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val serif = ResourcesCompat.getFont(ctx, R.font.instrument_serif) ?: Typeface.SERIF
    val mono = ResourcesCompat.getFont(ctx, R.font.plex_mono) ?: Typeface.MONOSPACE

    val doc = PdfDocument()
    var numero = 0
    var pagina: PdfDocument.Page? = null
    var lienzo: Canvas? = null
    var y = margen

    fun nuevaPagina() {
        pagina?.let { doc.finishPage(it) }
        numero++
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(anchoPag, altoPag, numero).create())
        pagina = pg
        lienzo = pg.canvas.also { it.drawColor(p.fondo.toArgb()) }
        y = margen
    }
    nuevaPagina()

    // el encabezado: el día de la bitácora y la hora
    val pMono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 7f; color = p.texto2.toArgb(); letterSpacing = 0.12f }
    val encabezado = ("Día " + Casa.diaDeBitacora(b.creado) + " · " + formato("d MMM yyyy · HH:mm", b.creado)).uppercase()
    lienzo!!.drawText(encabezado, margen, y + 7f, pMono)
    y += 22f

    if (b.titulo.isNotBlank()) {
        val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; textSize = 24f; color = p.texto.toArgb() }
        val l = StaticLayout.Builder.obtain(b.titulo.trim(), 0, b.titulo.trim().length, pTitulo, ancho.toInt()).setLineSpacing(0f, 1.05f).build()
        lienzo!!.save(); lienzo!!.translate(margen, y); l.draw(lienzo!!); lienzo!!.restore()
        y += l.height + 14f
    }

    // las fotos pegadas
    b.fotos.forEachIndexed { i, id ->
        val bmp = Casa.foto(id, 1200) ?: return@forEachIndexed
        val est = EstiloFoto.de(b.estilos[id])
        val w = ancho * est.ancho - 12f
        val h = w * bmp.height / bmp.width.coerceAtLeast(1)
        val marco = 6f
        if (y + h + marco * 2 + 16f > altoPag - margen) nuevaPagina()
        val x0 = when {
            est.tam == 2 -> margen + (ancho - w) / 2
            i % 2 == 0 -> margen + 6f
            else -> margen + ancho - w - 6f
        }
        val c = lienzo!!
        c.save()
        c.rotate(est.giro, x0 + w / 2, y + 8f + h / 2)
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
        c.drawRect(RectF(x0 - marco + 1.5f, y + 8f - marco + 2.5f, x0 + w + marco + 1.5f, y + 8f + h + marco * 1.3f + 2.5f), sombra)
        c.drawRect(RectF(x0 - marco, y + 8f - marco, x0 + w + marco, y + 8f + h + marco * 1.3f), Paint().apply { color = Borde.toArgb() })
        c.drawBitmap(bmp, null, RectF(x0, y + 8f, x0 + w, y + 8f + h), Paint(Paint.FILTER_BITMAP_FLAG))
        c.rotate(-3f, x0 + w / 2, y + 8f)
        c.drawRect(RectF(x0 + w / 2 - 22f, y + 8f - marco - 5f, x0 + w / 2 + 22f, y + 8f - marco + 7f), Paint().apply { color = Cinta.toArgb() })
        c.restore()
        y += h + marco * 2 + 22f
    }

    // el cuerpo, con su formato sencillo
    if (b.texto.isNotBlank()) {
        val tam = Tema.tamanoLetra * 0.66f
        val pCuerpo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = tam; color = Tema.colorLetra.toArgb() }
        val texto = conFormato(b.texto, sansFuerte, p.texto2.toArgb(), tam)
        val l = StaticLayout.Builder.obtain(texto, 0, texto.length, pCuerpo, ancho.toInt())
            .setLineSpacing(0f, 1.5f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        y += 6f
        var linea = 0
        while (linea < l.lineCount) {
            // cuántas líneas caben en lo que queda de esta página
            val disponible = altoPag - margen - y
            var fin = linea
            while (fin < l.lineCount && l.getLineBottom(fin) - l.getLineTop(linea) <= disponible) fin++
            if (fin == linea) { nuevaPagina(); continue }
            val c = lienzo!!
            c.save()
            c.translate(margen, y - l.getLineTop(linea))
            c.clipRect(0f, l.getLineTop(linea).toFloat(), ancho, l.getLineBottom(fin - 1).toFloat())
            l.draw(c)
            c.restore()
            y += l.getLineBottom(fin - 1) - l.getLineTop(linea)
            linea = fin
            if (linea < l.lineCount) nuevaPagina()
        }
    }

    pagina?.let { doc.finishPage(it) }
    val dir = File(ctx.cacheDir, "compartir").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
    val nombre = b.titulo.trim().ifBlank { "lamina " + formato("d MMM yyyy", b.creado) }
        .replace(Regex("[\\\\/:*?\"<>|]"), " ").take(60).trim()
    val archivo = File(dir, "$nombre.pdf")
    return try {
        archivo.outputStream().use { doc.writeTo(it) }
        archivo
    } catch (e: Exception) {
        null
    } finally {
        doc.close()
    }
}

/** **negrita**, listas con "- " y citas con "> ", como en el cuaderno. */
private fun conFormato(texto: String, fuerte: Typeface, gris: Int, tam: Float): CharSequence {
    val sb = SpannableStringBuilder()
    texto.lines().forEachIndexed { i, linea0 ->
        if (i > 0) sb.append('\n')
        var linea = linea0
        val inicio = sb.length
        var cita = false
        var lista = false
        when {
            linea.startsWith("> ") -> { linea = linea.removePrefix("> "); cita = true }
            linea.startsWith("- ") -> { linea = "•  " + linea.removePrefix("- "); lista = true }
        }
        // negritas
        var resto = linea
        while (true) {
            val a = resto.indexOf("**")
            val bb = if (a >= 0) resto.indexOf("**", a + 2) else -1
            if (a < 0 || bb < 0) { sb.append(resto); break }
            sb.append(resto.substring(0, a))
            val s0 = sb.length
            sb.append(resto.substring(a + 2, bb))
            if (Build.VERSION.SDK_INT >= 28) sb.setSpan(TypefaceSpan(fuerte), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            resto = resto.substring(bb + 2)
        }
        if (cita) {
            sb.setSpan(StyleSpan(Typeface.ITALIC), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(gris), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(LeadingMarginSpan.Standard((tam * 1.2f).toInt()), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (lista) sb.setSpan(LeadingMarginSpan.Standard(0, (tam * 1.1f).toInt()), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}
