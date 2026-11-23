package com.sebas.solaris

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * Trae páginas de una exportación de Notion (Markdown y CSV, en .zip). Todo pasa en el celular:
 * se lee el zip, cada página se vuelve un Escrito y se cifra como cualquier otro.
 */
object Notion {

    /** Una página encontrada en el zip. */
    class Pagina(
        val notionId: String?,
        val ruta: String,
        val titulo: String,
        val texto: String,
        val imagenes: List<String>,
        val fecha: Long,
        var elegida: Boolean,
    )

    /**
     * Las páginas personales de Sebas (notas sueltas, diarios, vivencias), con la fecha en que se tocaron en Notion.
     * Las que no están aquí aparecen sin marcar.
     */
    private val personales: Map<String, Long> = mapOf(
        "156981697339808b98ebdeae4aae3ae0" to 1734358080000L,
        "157981697339803dad36f9f161e1c00a" to 1783132860000L,
        "15f9816973398099886ff444f0639327" to 1737035760000L,
        "16098169733980a5a111ec5d84d58507" to 1737066549130L,
        "1649816973398096b76dcd759fef9229" to 1737189705652L,
        "178981697339803aa30aca35b9832382" to 1737805488260L,
        "18d98169733980eda223cd3a37de234a" to 1738452060000L,
        "194981697339803f9328c9de83d726c2" to 1739041380000L,
        "19d98169733980f7b56ed5a806947f20" to 1739851320000L,
        "1a0981697339808abbbdf78ceb5a177c" to 1743981360000L,
        "1a198169733980bcbea0e053c5c4e0c6" to 1740147420000L,
        "1b698169733980a9b79bc8a00ffa852b" to 1741943516842L,
        "1c79816973398059a4e1d9a45a257a66" to 1743397500000L,
        "1cc9816973398078ba31ccab44e39b5b" to 1743843548275L,
        "1dc9816973398056ac7cd22e93b63fd7" to 1745270902758L,
        "1f29816973398039ad64fe0419fd043d" to 1747233515172L,
        "20198169733980f1994cef9d39ec8d2e" to 1748571660000L,
        "21898169733980b1bb0dcf275155734f" to 1750689915483L,
        "23f9816973398042947ced6cd60baf14" to 1754281740000L,
        "2489816973398056be43e7fada3868f8" to 1755110622580L,
        "2509816973398026b937ffa182ca6086" to 1755847407096L,
        "265981697339806e8dd5c37536b4aa5d" to 1757781466451L,
        "2869816973398077914ce18523fa0267" to 1760820702580L,
        "28698169733980dcad30ca96b0afa757" to 1760820702580L,
        "2889816973398012b329cb173b877828" to 1761004898709L,
        "29b981697339801d9b40d2c18fa75917" to 1762754761935L,
        "29c981697339800b8dbcc79514f1cc7d" to 1776208860000L,
        "2a898169733980198613f771437299ef" to 1762846860000L,
        "2c0981697339807d95c2f9f776b52cc8" to 1764898306153L,
        "2c098169733980c5ae46f627a52a6f1a" to 1764898306153L,
        "2c29816973398063bd29c91dc0730b9d" to 1765069260000L,
        "2d698169733980b09cecdb030c4f8127" to 1766808240000L,
        "2e598169733980bbb795e39e3ad06a28" to 1768860720000L,
        "34398169733980caabb4e79d00b5d100" to 1776335581747L,
        "34c98169733980459146dde2f82c3a92" to 1777051260000L,
        "36098169733980de9408ec7d28c66f47" to 1781193720000L,
        "39098169733980ee9deef536b389dac9" to 1783033260000L,
        "39198169733980aa92d3e7e4630d118c" to 1783033860000L,
        "39198169733980bd8d38c2920a7aa2a6" to 1783036920000L,
        "392981697339804698e9d4d53f5939ac" to 1783040760000L,
        "392981697339804e88b6ddd9f51af98a" to 1783091040000L,
        "39298169733980a6a65fc5d470550ef8" to 1783040760000L,
        "393981697339806d942afda597b22dfc" to 1787782560000L,
        "3c89816973398054a386f81cebda4efc" to 1787784780000L,
        "3d498169733980159600eb335c75d1ad" to 1788746640000L,
        "3d798169733980bd90e2c281d0613766" to 1788746640000L,
        "3dc9816973398094a959ebfd436ca69c" to 1788746640000L,
    )

    private val reId = Regex("([0-9a-f]{32})\\.md$")
    private val reImagen = Regex("!\\[[^\\]]*]\\(([^)]+)\\)")
    private val reEnlace = Regex("(?<!!)\\[([^\\]]*)]\\([^)]*\\)")

    /** Lee el zip (también los zips dentro del zip, como los arma Notion). */
    fun leer(ctx: Context, uri: Uri): Pair<List<Pagina>, Map<String, ByteArray>> {
        val paginas = mutableListOf<Pagina>()
        val archivos = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(uri)?.use { recorrer(it, paginas, archivos) }
        return paginas.sortedByDescending { it.fecha } to archivos
    }

    private fun recorrer(entrada: InputStream, paginas: MutableList<Pagina>, archivos: MutableMap<String, ByteArray>) {
        val zip = ZipInputStream(entrada)
        while (true) {
            val e = zip.nextEntry ?: break
            if (e.isDirectory) continue
            val nombre = e.name
            val bajo = nombre.lowercase()
            when {
                bajo.endsWith(".zip") -> recorrer(ByteArrayInputStream(zip.readBytes()), paginas, archivos)
                bajo.endsWith(".md") -> paginas += pagina(nombre, String(zip.readBytes(), Charsets.UTF_8), e.time)
                bajo.endsWith(".png") || bajo.endsWith(".jpg") || bajo.endsWith(".jpeg") || bajo.endsWith(".webp") || bajo.endsWith(".gif") ->
                    archivos[nombre] = zip.readBytes()
            }
        }
    }

    private fun pagina(ruta: String, md: String, cuando: Long): Pagina {
        val id = reId.find(ruta)?.groupValues?.get(1)
        val lineas = md.replace("\r", "").lines()
        val primera = lineas.indexOfFirst { it.isNotBlank() }
        val titulo = (if (primera >= 0 && lineas[primera].startsWith("# ")) lineas[primera].removePrefix("# ").trim()
            else ruta.substringAfterLast('/').removeSuffix(".md").replace(Regex("\\s*[0-9a-f]{32}$"), ""))
        // Notion llama "Untitled" a las páginas sin nombre: aquí son notas sin título
        .let { if (it.equals("Untitled", ignoreCase = true) || it.equals("Sin título", ignoreCase = true)) "" else it }
        val cuerpo = if (primera >= 0 && lineas[primera].startsWith("# ")) lineas.drop(primera + 1) else lineas
        val carpeta = ruta.substringBeforeLast('/', "")
        val imagenes = mutableListOf<String>()
        val texto = cuerpo.joinToString("\n") { l ->
            reImagen.findAll(l).forEach { m ->
                val rel = try { URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (x: Exception) { m.groupValues[1] }
                imagenes += if (carpeta.isEmpty()) rel else "$carpeta/$rel"
            }
            var t = l.replace(reImagen, "").replace(reEnlace) { it.groupValues[1] }
            // los títulos de Notion se vuelven negrita; las casillas, listas
            if (t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ")) t = "**" + t.trimStart('#', ' ') + "**"
            if (t.startsWith("- [ ] ") || t.startsWith("- [x] ")) t = "- " + t.substring(6)
            if (t.startsWith("* ")) t = "- " + t.substring(2)
            t
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        val fecha = id?.let { personales[it] } ?: cuando.takeIf { it > 0 } ?: System.currentTimeMillis()
        return Pagina(id, ruta, titulo.ifBlank { "Sin título" }, texto, imagenes, fecha, id != null && id in personales)
    }

    /** Entrega al mar las páginas elegidas. Devuelve cuántas entraron. */
    suspend fun traer(paginas: List<Pagina>, archivos: Map<String, ByteArray>, alAvanzar: (Int) -> Unit): Int {
        var n = 0
        for (p in paginas.filter { it.elegida }) {
            val fotos = p.imagenes.mapNotNull { ruta -> archivos[ruta]?.let { Casa.agregarFotoBytes(it) } }
            if (p.texto.isBlank() && fotos.isEmpty() && p.titulo == "Sin título") continue
            val id = "notion-" + (p.notionId ?: p.ruta.hashCode().toUInt().toString(16))
            Casa.importarEscrito(id, if (p.titulo == "Sin título") "" else p.titulo, p.texto, fotos, p.fecha)
            n++
            alAvanzar(n)
        }
        Casa.sincronizar()
        return n
    }
}
