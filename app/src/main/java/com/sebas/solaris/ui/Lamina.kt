package com.sebas.solaris.ui

import android.content.ClipData
import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
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
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
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
import com.sebas.solaris.Forma
import com.sebas.solaris.Marcas
import com.sebas.solaris.Pieza
import com.sebas.solaris.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

/**
 * La lámina como imagen larga: lo que se ve en la app (diagramación, fotos, citas, sello) sobre el mar de este momento,
 * que se funde con el papel hacia abajo.
 */
suspend fun compartirImagen(ctx: Context, b: Borrador, contenido: androidx.compose.ui.graphics.ImageBitmap) {
    val mar = MarVivo.cuadro()
    val fondo = Tema.p.fondo.toArgb()
    val papel = Tema.papelLamina
    val archivo = withContext(Dispatchers.Default) {
        var c = contenido.asAndroidBitmap()
        if (c.config == android.graphics.Bitmap.Config.HARDWARE) c = c.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        val w = c.width; val h = c.height
        // muy larga: se achica un poco para que pese lo razonable
        val escala = minOf(1f, kotlin.math.sqrt(20_000_000f / (w.toFloat() * h)))
        val sal = android.graphics.Bitmap.createBitmap((w * escala).toInt(), (h * escala).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        val lz = Canvas(sal)
        lz.scale(escala, escala)
        lz.drawColor(fondo)
        if (mar != null) {
            val altoMar = w.toFloat() * mar.height / mar.width
            lz.drawBitmap(mar, null, RectF(0f, 0f, w.toFloat(), altoMar), Paint(Paint.FILTER_BITMAP_FLAG))
            // el papel encima, igual que en la app
            fun con(a: Float) = android.graphics.Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), android.graphics.Color.red(fondo), android.graphics.Color.green(fondo), android.graphics.Color.blue(fondo))
            val velo = Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, altoMar,
                    intArrayOf(con(0f), con(papel * 0.45f), con(papel * 0.9f), con(papel), con(1f)),
                    floatArrayOf(0f, 0.10f, 0.28f, 0.75f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            lz.drawRect(0f, 0f, w.toFloat(), altoMar, velo)
        }
        lz.drawBitmap(c, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        val dir = File(ctx.cacheDir, "compartir").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val nombre = b.titulo.trim().ifBlank { "lamina " + formato("d MMM yyyy", b.creado) }
            .replace(Regex("[\\\\/:*?\"<>|]"), " ").take(60).trim()
        val f = File(dir, "$nombre.jpg")
        f.outputStream().use { sal.compress(android.graphics.Bitmap.CompressFormat.JPEG, 93, it) }
        f
    }
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".archivos", archivo)
    val envio = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
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
    val forma0 = Forma.de(b.forma)
    val margen = when (forma0.margen) { 0 -> 28f; 2 -> 56f; else -> 40f }
    val ancho = anchoPag - margen * 2
    val sans = ResourcesCompat.getFont(ctx, R.font.sans_regular) ?: Typeface.SANS_SERIF
    val sansFuerte = ResourcesCompat.getFont(ctx, R.font.sans_semibold) ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val serif = ResourcesCompat.getFont(ctx, R.font.instrument_serif) ?: Typeface.SERIF
    val mono = ResourcesCompat.getFont(ctx, R.font.plex_mono) ?: Typeface.MONOSPACE
    val forma = Forma.de(b.forma)
    val alineacion = if (forma.centrado) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL

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

    /** Dibuja un texto ya armado, partiéndolo entre páginas si hace falta. */
    fun dibujar(l: StaticLayout, x: Float = margen) {
        var linea = 0
        while (linea < l.lineCount) {
            val disponible = altoPag - margen - y
            var fin = linea
            while (fin < l.lineCount && l.getLineBottom(fin) - l.getLineTop(linea) <= disponible) fin++
            if (fin == linea) { nuevaPagina(); continue }
            val c = lienzo!!
            c.save()
            c.translate(x, y - l.getLineTop(linea))
            c.clipRect(0f, l.getLineTop(linea).toFloat(), l.width.toFloat(), l.getLineBottom(fin - 1).toFloat())
            l.draw(c)
            c.restore()
            y += l.getLineBottom(fin - 1) - l.getLineTop(linea)
            linea = fin
            if (linea < l.lineCount) nuevaPagina()
        }
    }
    fun armar(t: CharSequence, paint: TextPaint, w: Float, al: Layout.Alignment = alineacion, espacio: Float = 1.5f) =
        StaticLayout.Builder.obtain(t, 0, t.length, paint, w.toInt()).setLineSpacing(0f, espacio).setAlignment(al).build()

    // el encabezado: el día de la bitácora y la hora
    val pMono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 7f; color = p.texto2.toArgb(); letterSpacing = 0.12f }
    val encabezado = ("Día " + Casa.diaDeBitacora(b.creado) + " · " + formato("d MMM yyyy · HH:mm", b.creado)).uppercase()
    lienzo!!.drawText(encabezado, if (forma.centrado) margen + (ancho - pMono.measureText(encabezado)) / 2 else margen, y + 7f, pMono)
    y += 22f

    if (b.titulo.isNotBlank()) {
        val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; textSize = 24f; color = p.texto.toArgb() }
        val l = armar(b.titulo.trim(), pTitulo, ancho, espacio = 1.05f)
        dibujar(l)
        y += 14f
    }

    val tam = Tema.tamanoLetra * 0.66f
    val pCuerpo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (forma.poema || forma.serif) serif else sans
        textSize = if (forma.poema || forma.serif) tam * 1.12f else tam
        color = Tema.colorLetra.toArgb()
    }
    val pLado = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = tam * 0.92f; color = Tema.colorLetra.toArgb() }
    val pPie = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(serif, Typeface.ITALIC); textSize = 8.5f; color = TintaPie.toArgb()
    }

    /** Una foto en su papel, con su cinta y su pie. Devuelve el alto que ocupa. */
    fun papel(c: Canvas, bmp: android.graphics.Bitmap, x0: Float, y0: Float, w: Float, giro: Float, pie: String): Float {
        val h = w * bmp.height / bmp.width.coerceAtLeast(1)
        val marco = 6f
        val lPie = if (pie.isBlank()) null else armar(pie.trim(), pPie, w, Layout.Alignment.ALIGN_CENTER, 1.1f)
        val extra = if (lPie != null) lPie.height + 7f else 0f
        c.save()
        c.rotate(giro, x0 + w / 2, y0 + 8f + h / 2)
        val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
        c.drawRect(RectF(x0 - marco + 1.5f, y0 + 8f - marco + 2.5f, x0 + w + marco + 1.5f, y0 + 8f + h + marco * 1.3f + extra + 2.5f), sombra)
        c.drawRect(RectF(x0 - marco, y0 + 8f - marco, x0 + w + marco, y0 + 8f + h + marco * 1.3f + extra), Paint().apply { color = BordeFoto.toArgb() })
        c.drawBitmap(bmp, null, RectF(x0, y0 + 8f, x0 + w, y0 + 8f + h), Paint(Paint.FILTER_BITMAP_FLAG))
        if (lPie != null) { c.save(); c.translate(x0, y0 + 8f + h + 6f); lPie.draw(c); c.restore() }
        c.rotate(-3f, x0 + w / 2, y0 + 8f)
        c.drawRect(RectF(x0 + w / 2 - 22f, y0 + 8f - marco - 5f, x0 + w / 2 + 22f, y0 + 8f - marco + 7f), Paint().apply { color = CintaFoto.toArgb() })
        c.restore()
        return h + marco * 2 + extra + 16f
    }

    /** Una foto sola, en su papel o a sangre, o diagramada con su texto al lado. */
    suspend fun unaFoto(i: Int, id: String) {
        val bmp = Casa.foto(id, 1200) ?: return
        val est = EstiloFoto.de(b.estilos[id])
        val pie = b.pies[id] ?: ""
        if (est.aSangre) {
            // de borde a borde de la página
            val w = anchoPag.toFloat()
            val h = w * bmp.height / bmp.width.coerceAtLeast(1)
            if (y + h + 20f > altoPag - margen) nuevaPagina()
            lienzo!!.drawBitmap(bmp, null, RectF(0f, y, w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h + 8f
            if (pie.isNotBlank()) { dibujar(armar(pie.trim(), TextPaint(pPie).apply { color = p.texto2.toArgb() }, ancho, Layout.Alignment.ALIGN_CENTER, 1.1f)); y += 6f }
            y += 6f
        } else if (!est.alLado) {
            val w = ancho * est.ancho - 12f
            val h = w * bmp.height / bmp.width.coerceAtLeast(1)
            if (y + h + 40f > altoPag - margen) nuevaPagina()
            val x0 = when {
                est.tam == 2 -> margen + (ancho - w) / 2
                i % 2 == 0 -> margen + 6f
                else -> margen + ancho - w - 6f
            }
            y += papel(lienzo!!, bmp, x0, y, w, est.giro, pie) + 6f
        } else {
            // diagramada: la foto a un lado y su texto rodeándola
            val w = ancho * 0.48f - 12f
            val h = w * bmp.height / bmp.width.coerceAtLeast(1)
            if (y + h + 40f > altoPag - margen) nuevaPagina()
            val derecha = est.pos == EstiloFoto.DERECHA
            val x0 = if (derecha) margen + ancho - w - 6f else margen + 6f
            val altoFoto = papel(lienzo!!, bmp, x0, y, w, est.giro * 0.5f, pie)
            val texto = (b.lados[id] ?: "").trim()
            val anchoLado = ancho * 0.52f - 12f
            var resto = ""
            if (texto.isNotEmpty()) {
                val l = armar(texto, pLado, anchoLado, Layout.Alignment.ALIGN_NORMAL, 1.4f)
                var ultima = -1
                for (k in 0 until l.lineCount) if (l.getLineBottom(k) + 10f <= altoFoto) ultima = k
                if (ultima >= 0) {
                    val c = lienzo!!
                    c.save()
                    c.translate(if (derecha) margen else margen + ancho - anchoLado, y + 10f)
                    c.clipRect(0f, 0f, anchoLado, l.getLineBottom(ultima).toFloat())
                    l.draw(c)
                    c.restore()
                }
                resto = if (ultima == l.lineCount - 1) "" else texto.substring(if (ultima < 0) 0 else l.getLineEnd(ultima)).trim()
            }
            y += altoFoto + 4f
            if (resto.isNotEmpty()) { dibujar(armar(resto, pLado, ancho, Layout.Alignment.ALIGN_NORMAL, 1.4f)); y += 10f }
        }
    }

    /** Un collage: las fotos en una tira de papel, del mismo alto. */
    suspend fun collage(ids: List<String>) {
        val bmps = ids.mapNotNull { Casa.foto(it, 900) }
        if (bmps.isEmpty()) return
        val marco = 6f; val hueco = 4f
        val alto = (ancho - marco * 2) / (if (bmps.size >= 3) 2.1f else 1.55f)
        if (y + alto + 40f > altoPag - margen) nuevaPagina()
        val c = lienzo!!
        val y0 = y + 8f
        c.drawRect(RectF(margen + 1.5f, y0 - marco + 2.5f, margen + ancho + 1.5f, y0 + alto + marco + 2.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 })
        c.drawRect(RectF(margen, y0 - marco, margen + ancho, y0 + alto + marco), Paint().apply { color = BordeFoto.toArgb() })
        val wCelda = (ancho - marco * 2 - hueco * (bmps.size - 1)) / bmps.size
        bmps.forEachIndexed { k, bm ->
            val x = margen + marco + k * (wCelda + hueco)
            // recortada al centro, como en la app
            val r = wCelda / alto
            val (sw, sh) = if (bm.width.toFloat() / bm.height > r) (bm.height * r) to bm.height.toFloat() else bm.width.toFloat() to (bm.width / r)
            val sx = ((bm.width - sw) / 2).toInt(); val sy = ((bm.height - sh) / 2).toInt()
            c.drawBitmap(bm, android.graphics.Rect(sx, sy, sx + sw.toInt(), sy + sh.toInt()), RectF(x, y0, x + wCelda, y0 + alto), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        c.drawRect(RectF(margen + ancho / 2 - 24f, y0 - marco - 5f, margen + ancho / 2 + 24f, y0 - marco + 7f), Paint().apply { color = CintaFoto.toArgb() })
        y += alto + marco * 2 + 18f
        val pie = b.pies[ids.first()] ?: ""
        if (pie.isNotBlank()) { dibujar(armar(pie.trim(), pPie, ancho, Layout.Alignment.ALIGN_CENTER, 1.1f)); y += 8f }
    }

    val pVoz = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 7.5f; color = p.texto2.toArgb(); letterSpacing = 0.08f }
    var nFoto = 0
    var primerTexto = true
    // la lámina en el orden en que se diagramó: párrafos, fotos, collages y voces
    Marcas.partir(b.texto, b.fotos, b.voces).forEach { pz ->
        when (pz.tipo) {
            Pieza.FOTO -> unaFoto(nFoto++, pz.ids.first())
            Pieza.FOTOS -> { nFoto++; collage(pz.ids) }
            // las voces en papel no suenan, pero quedan nombradas
            Pieza.VOZ -> Casa.porId(pz.ids.first())?.let { v ->
                y += 4f
                val linea = "▶  " + (if (v.esRumor) "RUMOR" else "VOZ") + " · " + v.title.ifBlank { "sin nombre" } + " · " + duracion(v.dur)
                dibujar(armar(linea, pVoz, ancho, espacio = 1.3f))
                y += 6f
            }
            else -> if (pz.texto.isNotBlank()) {
                y += 6f
                dibujar(armar(
                    conFormato(pz.texto, sansFuerte, serif, p.texto2.toArgb(), p.acento.toArgb(), tam, forma.capitular && primerTexto),
                    pCuerpo, ancho,
                    espacio = (if (forma.poema) 1.8f else 1.5f) * forma.factorAire,
                ))
                primerTexto = false
                y += 4f
            }
        }
        Unit
    }

    // el sello del mar
    if (b.sello.isNotBlank()) {
        y += 18f
        dibujar(armar("—  " + b.sello.uppercase(), pMono, ancho, espacio = 1.2f))
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

private fun tipo(sb: SpannableStringBuilder, t: Typeface, estilo: Int, a: Int, z: Int) {
    if (Build.VERSION.SDK_INT >= 28) sb.setSpan(TypefaceSpan(t), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    else sb.setSpan(StyleSpan(estilo), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** El formato del cuaderno en papel: negrita, tachado, listas, citas, citas destacadas, olas y capitular. */
private fun conFormato(texto: String, fuerte: Typeface, serif: Typeface, gris: Int, acento: Int, tam: Float, capitular: Boolean): CharSequence {
    val sb = SpannableStringBuilder()
    var primera = true
    texto.lines().forEachIndexed { i, linea0 ->
        if (i > 0) sb.append('\n')
        var linea = linea0
        val inicio = sb.length
        var cita = false
        var lista = false
        var destacada = false
        if (linea.trim() == "~~~") {
            sb.append("∿ ∿ ∿ ∿ ∿")
            sb.setSpan(ForegroundColorSpan(acento), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return@forEachIndexed
        }
        when {
            linea.startsWith("> ") -> { linea = linea.removePrefix("> "); cita = true }
            linea.startsWith("» ") -> { linea = linea.removePrefix("» "); destacada = true }
            linea.startsWith("- ") -> { linea = "•  " + linea.removePrefix("- "); lista = true }
        }
        // negritas y tachados
        val marca = Regex("\\*\\*([^*\\n]+)\\*\\*|(?<!~)~~([^~\\n]+)~~(?!~)")
        var desde = 0
        marca.findAll(linea).forEach { m ->
            sb.append(linea.substring(desde, m.range.first))
            val s0 = sb.length
            if (m.groupValues[1].isNotEmpty()) {
                sb.append(m.groupValues[1]); tipo(sb, fuerte, Typeface.BOLD, s0, sb.length)
            } else {
                sb.append(m.groupValues[2])
                sb.setSpan(StrikethroughSpan(), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(gris), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            desde = m.range.last + 1
        }
        sb.append(linea.substring(desde))
        if (capitular && primera && !cita && !destacada && !lista && linea.isNotBlank()) {
            val k = linea.indexOfFirst { it.isLetterOrDigit() }
            if (k >= 0) {
                sb.setSpan(RelativeSizeSpan(2.7f), inicio + k, inicio + k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(acento), inicio + k, inicio + k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                tipo(sb, serif, Typeface.NORMAL, inicio + k, inicio + k + 1)
            }
        }
        if (linea.isNotBlank()) primera = false
        if (cita) {
            sb.setSpan(StyleSpan(Typeface.ITALIC), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(gris), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(LeadingMarginSpan.Standard((tam * 1.2f).toInt()), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (destacada) {
            tipo(sb, Typeface.create(serif, Typeface.ITALIC), Typeface.ITALIC, inicio, sb.length)
            sb.setSpan(RelativeSizeSpan(1.45f), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(acento), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (lista) sb.setSpan(LeadingMarginSpan.Standard(0, (tam * 1.1f).toInt()), inicio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return sb
}
