package com.sebas.solaris

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.sebas.solaris.ui.Nav
import com.sebas.solaris.ui.Tema
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * El laboratorio de Solaris: arma una estación de ejemplo (notas, fotos y voces de mentira), fotografía cada
 * pantalla de día y de noche, y mide cuántos cuadros se traban al deslizar, escribir y abrir notas.
 * Deja todo en Android/data/com.sebas.solaris/files/lab (el workflow lo baja como artefacto).
 */
@RunWith(AndroidJUnit4::class)
class Laboratorio {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val dispositivo = UiDevice.getInstance(inst)
    private val dir = File(ctx.getExternalFilesDir(null), "lab").apply { deleteRecursively(); mkdirs() }
    private val paquete = ctx.packageName

    private fun esperar(ms: Long) = Thread.sleep(ms)
    private fun foto(nombre: String) { esperar(700); dispositivo.takeScreenshot(File(dir, "$nombre.png")) }
    private fun enLaApp(bloque: () -> Unit) = inst.runOnMainSync(bloque)
    private fun shell(c: String): String = dispositivo.executeShellCommand(c)

    private val resumen = StringBuilder()

    /**
     * Mide los cuadros mientras corre [bloque]. Además del informe de gfxinfo, saca del detalle por cuadro
     * (framestats) cuánto trabajó el hilo de la app en cada uno: eso no depende de que el celular virtual
     * dibuje lento, y sirve para comparar el antes y el después.
     */
    private fun medir(nombre: String, bloque: () -> Unit) {
        shell("dumpsys gfxinfo $paquete reset")
        bloque()
        val todo = shell("dumpsys gfxinfo $paquete framestats")
        File(dir, "medida_$nombre.txt").writeText(todo)
        val lineas = todo.lines()
        val cab = lineas.indexOfFirst { it.startsWith("Flags,") }
        if (cab < 0) { resumen.append("$nombre: sin datos\n"); return }
        val col = lineas[cab].split(',')
        val iIn = col.indexOf("HandleInputStart"); val iSync = col.indexOf("SyncQueued"); val iVs = col.indexOf("IntendedVsync"); val iFin = col.indexOf("FrameCompleted")
        val hilo = mutableListOf<Double>(); val total = mutableListOf<Double>()
        for (l in lineas.drop(cab + 1)) {
            if (l.startsWith("---")) break
            val v = l.split(',').mapNotNull { it.trim().toLongOrNull() }
            if (v.size < col.size - 1 || v[0] != 0L) continue
            hilo += (v[iSync] - v[iIn]) / 1e6
            total += (v[iFin] - v[iVs]) / 1e6
        }
        fun pct(l: List<Double>, q: Double) = if (l.isEmpty()) 0.0 else l.sorted()[((l.size - 1) * q).toInt()]
        resumen.append(
            String.format(
                java.util.Locale.US, "%-20s cuadros %4d | hilo de la app: mediana %6.1f ms, p90 %6.1f ms, >16 ms %5.1f%% | cuadro completo: mediana %6.1f ms, p90 %6.1f ms\n",
                nombre, hilo.size, pct(hilo, 0.5), pct(hilo, 0.9), if (hilo.isEmpty()) 0.0 else hilo.count { it > 16.7 } * 100.0 / hilo.size, pct(total, 0.5), pct(total, 0.9),
            )
        )
        File(dir, "resumen.txt").writeText(resumen.toString())
    }

    /** Toca un punto de la pantalla (en px de un Pixel 6: 1080 × 2400). */
    private fun tocar(x: Int, y: Int) { dispositivo.click(x * dispositivo.displayWidth / 1080, y * dispositivo.displayHeight / 2400) }

    // ---------------- la estación de ejemplo ----------------

    private val ahora = System.currentTimeMillis()
    private val dia = 24 * 3600_000L

    /** Una "foto" pintada: cielo, horizonte, sol y siluetas, con una paleta distinta cada vez. */
    private fun fotoPintada(semilla: Int): ByteArray {
        val r = Random(semilla)
        val w = 1600; val h = 1200
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paletas = listOf(
            intArrayOf(0xFF1D2B53.toInt(), 0xFFF2A65A.toInt(), 0xFF2E4057.toInt()),
            intArrayOf(0xFF87A8C4.toInt(), 0xFFE9E4D8.toInt(), 0xFF3C5A4A.toInt()),
            intArrayOf(0xFF0E1A2B.toInt(), 0xFF8E6C88.toInt(), 0xFF101418.toInt()),
            intArrayOf(0xFFB8C9D9.toInt(), 0xFFF6D6A8.toInt(), 0xFF5B4A3A.toInt()),
            intArrayOf(0xFF2B3A55.toInt(), 0xFFD98E73.toInt(), 0xFF1E2A22.toInt()),
            intArrayOf(0xFF6E8FA8.toInt(), 0xFFDDE6EA.toInt(), 0xFF4F5D3A.toInt()),
        )
        val p = paletas[semilla % paletas.size]
        val horizonte = h * (0.5f + r.nextFloat() * 0.15f)
        c.drawRect(0f, 0f, w.toFloat(), horizonte, Paint().apply { shader = LinearGradient(0f, 0f, 0f, horizonte, p[0], p[1], Shader.TileMode.CLAMP) })
        c.drawCircle(w * (0.2f + r.nextFloat() * 0.6f), horizonte - h * 0.12f, h * 0.08f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.5f, horizonte, h * 0.3f, 0xFFFFF2D0.toInt(), 0x00FFF2D0, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, horizonte, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, horizonte, 0f, h.toFloat(), p[2], 0xFF000000.toInt(), Shader.TileMode.CLAMP) })
        // cerros o edificios
        val sil = Path().apply {
            moveTo(0f, horizonte)
            var x = 0f
            while (x < w) { x += 60f + r.nextFloat() * 160f; lineTo(x, horizonte - r.nextFloat() * h * 0.18f) }
            lineTo(w.toFloat(), horizonte); close()
        }
        c.drawPath(sil, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (p[2] and 0x00FFFFFF) or 0xE0000000.toInt() })
        // una persona
        val px = w * (0.3f + r.nextFloat() * 0.4f)
        val pie = horizonte + h * 0.25f
        val persona = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0A.toInt() }
        c.drawCircle(px, pie - h * 0.27f, h * 0.035f, persona)
        c.drawRoundRect(px - h * 0.04f, pie - h * 0.23f, px + h * 0.04f, pie, h * 0.02f, h * 0.02f, persona)
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, o)
        return o.toByteArray()
    }

    /** Un audio corto (ruido suave), para que los casetes suenen. */
    private fun audio(segundos: Int): ByteArray {
        val tasa = 16000
        val n = tasa * segundos
        val datos = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        val r = Random(3)
        for (i in 0 until n) datos.putShort(((r.nextFloat() - 0.5f) * 3000 * kotlin.math.sin(i / 2000f)).toInt().toShort())
        val cab = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        cab.put("RIFF".toByteArray()); cab.putInt(36 + n * 2); cab.put("WAVE".toByteArray())
        cab.put("fmt ".toByteArray()); cab.putInt(16); cab.putShort(1); cab.putShort(1); cab.putInt(tasa); cab.putInt(tasa * 2); cab.putShort(2); cab.putShort(16)
        cab.put("data".toByteArray()); cab.putInt(n * 2)
        return cab.array() + datos.array()
    }

    private fun nota(id: String, titulo: String, piezas: List<Pieza>, forma: Forma, hace: Long, estilos: Map<String, String> = emptyMap(), pies: Map<String, String> = emptyMap(), lados: Map<String, String> = emptyMap()): Item {
        val fotos = Marcas.fotosDe(piezas)
        val voces = Marcas.vocesDe(piezas)
        return Item(
            id = id, kind = "entrada", room = null, title = titulo, text = Marcas.unir(piezas), photos = fotos, color = 0,
            created = ahora - hace, updated = ahora - hace, estilos = estilos, pies = pies, lados = lados, forma = forma.texto(), voces = voces,
            sello = "MAR DE FONDO · 3,2 M · 18 NUDOS",
        )
    }

    private fun cinta(id: String, titulo: String, dur: Long, hace: Long, rumor: Boolean = false) = Item(
        id = id, kind = "cinta", room = if (rumor) "rumor" else null, title = titulo, text = if (rumor) "" else "Hoy el mar estaba quieto y me acordé de la casa de la playa.",
        photos = emptyList(), color = 0, created = ahora - hace, updated = ahora - hace, dur = dur,
    )

    private val parrafos = listOf(
        "Noche de caminada entre laureles, helado, café, pan, amor. La ciudad olía a lluvia que no llegó.",
        "Me quedé mirando las luces de los carros como si fueran un río. Pensé en lo poco que hace falta para estar bien: alguien al lado, una calle tranquila, el frío justo.",
        "**Lo que no quiero olvidar:** la risa en la esquina de la panadería, el perro que nos siguió dos cuadras.",
        "- caminar sin rumbo\n- el pan de queso\n- volver temprano",
        "> Seguiremos caminando aunque no sepamos a dónde.",
        "Mañana escribo más. Ahora solo quiero dormir con esta sensación.",
    )

    private fun armarEstacion(): Triple<List<Item>, Map<String, ByteArray>, Map<String, ByteArray>> {
        val fotos = (1..8).associate { "foto$it" to fotoPintada(it) }
        val cintas = mapOf("voz1" to audio(4), "voz2" to audio(3), "rumor1" to audio(5))
        val items = mutableListOf(
            cinta("voz1", "la playa", 64_000, 2 * 3600_000L),
            cinta("voz2", "ensayo", 5_000, 2 * dia),
            cinta("rumor1", "Miau", 5_000, 2 * dia + 3600_000L, rumor = true),
        )
        // la nota rica: cuaderno a mano, con fotos, casete y cosas pegadas
        items += nota(
            "rica", "Laureles",
            listOf(
                Pieza(Pieza.TEXTO, texto = parrafos[0], pegados = listOf(
                    Pegado(Pegado.CINTA, 0.82f, 8f, -8f, 1f, "1"),
                    Pegado(Pegado.SELLO, 0.86f, 70f, 12f, 0.9f, "0|22|NOV 2026"),
                )),
                Pieza(Pieza.FOTO, listOf("foto1")),
                Pieza(Pieza.TEXTO, texto = parrafos[1] + "\n\n" + parrafos[2], pegados = listOf(
                    Pegado(Pegado.POLAROID, 0.7f, 60f, 6f, 0.9f, "foto2"),
                    Pegado(Pegado.NOTA, 0.22f, 150f, -5f, 0.85f, "0|comprar pan de queso para mañana"),
                )),
                Pieza(Pieza.VOZ, listOf("voz1"), opcion = "mediano"),
                Pieza(Pieza.TEXTO, texto = parrafos[3] + "\n" + parrafos[4], pegados = listOf(
                    Pegado(Pegado.CASETE, 0.8f, 30f, -10f, 0.8f, "voz2"),
                    Pegado(Pegado.TRAZO, 0.3f, 90f, 0f, 1f, "120,40,0;0.00,0.80 0.20,0.60 0.40,0.75 0.60,0.40 0.80,0.55 1.00,0.10"),
                )),
                Pieza(Pieza.FOTOS, listOf("foto3", "foto4", "foto5")),
                Pieza(Pieza.TEXTO, texto = parrafos[5], pegados = listOf(Pegado(Pegado.BOLETO, 0.5f, 60f, 3f, 1f, "Cine Colombia · sala 4"))),
            ),
            Forma(papel = 4, letra = 2, aire = 2), 26 * 3600_000L,
            estilos = (1..5).associate { "foto$it" to EstiloFoto.nueva().texto() },
            pies = mapOf("foto1" to "la esquina de siempre", "foto2" to "nosotros"),
        )
        // revista: serif, capitular, foto a sangre
        items += nota(
            "revista", "El mar de hoy",
            listOf(
                Pieza(Pieza.TEXTO, texto = parrafos[1] + "\n\n" + parrafos[0]),
                Pieza(Pieza.FOTO, listOf("foto6")),
                Pieza(Pieza.TEXTO, texto = (parrafos + parrafos).joinToString("\n\n")),
            ),
            Forma(capitular = true, margen = 2, serif = true), 3 * dia,
            estilos = mapOf("foto6" to EstiloFoto(tam = 3, giro = 0f).texto()), pies = mapOf("foto6" to "el Drake, en mi cabeza"),
        )
        // poema
        items += nota(
            "poema", "Árbol de veneno",
            listOf(Pieza(Pieza.TEXTO, texto = "Seguiré tomando tus agrias hojas\noh árbol de veneno\nque crece en mi pecho\n\ny aun así\nte riego cada noche")),
            Forma(poema = true, papel = 1), 5 * dia,
        )
        // una nota de antes (fotos sin marcas, arriba) y muchas más para deslizar
        items += nota("vieja", "Welcome", listOf(Pieza(Pieza.FOTO, listOf("foto7")), Pieza(Pieza.TEXTO, texto = "Quisiera más que nada darme la bienvenida a mí mismo por dentro, un lugar seguro.")), Forma(), 60 * dia)
        for (k in 0 until 30) {
            val conFoto = k % 3 == 0
            items += nota(
                "n$k", listOf("Untitled", "Martes", "Lluvia", "La casa", "Sueño raro", "Café")[k % 6],
                listOfNotNull(if (conFoto) Pieza(Pieza.FOTO, listOf("foto${(k % 8) + 1}")) else null, Pieza(Pieza.TEXTO, texto = parrafos[k % parrafos.size] + "\n" + parrafos[(k + 1) % parrafos.size])),
                Forma(), (6 + k * 2) * dia,
            )
        }
        return Triple(items, fotos, cintas)
    }

    // ---------------- el recorrido ----------------

    @Test
    fun recorrido() {
        val (items, fotos, cintas) = armarEstacion()
        enLaApp { Casa.iniciar(ctx); Casa.prepararLaboratorio(items, fotos, cintas) }
        ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // que baje el mar y arranque
        esperar(25_000)
        enLaApp { Tema.fijarModo("noche") }; foto("01_entrada_noche")
        enLaApp { Tema.fijarModo("dia") }; foto("02_entrada_dia")

        // arranque: abrir la estación
        medir("abrir_estacion") { enLaApp { Casa.abrirLaboratorio() }; esperar(3000) }
        foto("03_orilla_superficie")
        enLaApp { Nav.listaOrilla.requestScrollToItem(2) }; esperar(1500); foto("04_recuerdos")
        enLaApp { Nav.listaOrilla.requestScrollToItem(14) }; esperar(1500); foto("05_recuerdos_hondo")

        // fluidez al deslizar los recuerdos
        enLaApp { Nav.listaOrilla.requestScrollToItem(0) }; esperar(1500)
        medir("deslizar_recuerdos") {
            repeat(6) { dispositivo.swipe(540, 1900, 540, 700, 22); esperar(500) }
            repeat(6) { dispositivo.swipe(540, 700, 540, 1900, 22); esperar(500) }
        }

        // las notas, de día y de noche
        for ((id, nombre) in listOf("rica" to "nota_cuaderno", "revista" to "nota_revista", "poema" to "nota_poema", "vieja" to "nota_vieja")) {
            enLaApp { Nav.abrirNota(id) }; esperar(2500); foto("10_${nombre}_dia")
            dispositivo.swipe(540, 1800, 540, 700, 40); esperar(1200); foto("11_${nombre}_dia_abajo")
            dispositivo.swipe(540, 1800, 540, 700, 40); esperar(1200); foto("12_${nombre}_dia_mas_abajo")
            enLaApp { Tema.fijarModo("noche") }; esperar(800); foto("13_${nombre}_noche")
            enLaApp { Tema.fijarModo("dia"); Nav.borrador = null }; esperar(1500)
        }

        // las hojas y modos del editor (en la nota cuaderno)
        enLaApp { Nav.abrirNota("rica") }; esperar(2500)
        tocar(700, 2224); esperar(1500); foto("17_hoja_pegar"); dispositivo.pressBack(); esperar(1000)
        tocar(588, 2224); esperar(1500); foto("18_hoja_insertar"); dispositivo.pressBack(); esperar(1000)
        tocar(984, 208); esperar(1500); foto("19_hoja_opciones"); dispositivo.pressBack(); esperar(1000)
        tocar(720, 208); esperar(1500); foto("16_hoja_letra"); dispositivo.swipe(540, 2000, 540, 1100, 40); esperar(800); foto("16_hoja_letra_abajo"); dispositivo.pressBack(); esperar(1000)
        tocar(460, 208); esperar(1500); foto("19_diagramar"); tocar(460, 208); esperar(1200)
        enLaApp { Nav.borrador = null }; esperar(1500)

        // fluidez al abrir y cerrar notas
        medir("abrir_notas") {
            repeat(4) { enLaApp { Nav.abrirNota("rica") }; esperar(1600); enLaApp { Nav.borrador = null }; esperar(1600) }
        }

        // fluidez al deslizar una nota larga y al escribir
        enLaApp { Nav.abrirNota("revista") }; esperar(2500)
        medir("deslizar_nota") {
            repeat(5) { dispositivo.swipe(540, 1900, 540, 600, 24); esperar(450) }
            repeat(5) { dispositivo.swipe(540, 600, 540, 1900, 24); esperar(450) }
        }
        dispositivo.click(540, 1500); esperar(1200)
        foto("14_escribiendo")
        medir("escribir") {
            repeat(30) { shell("input text solaris"); shell("input keyevent 62") }
            esperar(1000)
        }
        foto("15_escribiendo_despues")
        shell("input keyevent 111"); esperar(600)
        enLaApp { Nav.borrador = null }; esperar(1500)

        // la grabadora, escuchar, ajustes
        enLaApp { Nav.grabando = true }; esperar(2000); foto("20_grabadora")
        enLaApp { Nav.grabando = false }; esperar(1200)
        enLaApp { Nav.escuchando = "voz1" }; esperar(2000); foto("21_escuchar_casete")
        enLaApp { Nav.escuchando = null }; esperar(1200)
        enLaApp { Nav.ajustes = true }; esperar(2000); foto("22_ajustes")
        dispositivo.swipe(540, 1800, 540, 600, 40); esperar(1000); foto("23_ajustes_abajo")
        enLaApp { Tema.fijarModo("noche") }; esperar(800); foto("24_ajustes_noche")
        enLaApp { Nav.ajustes = false; Tema.fijarModo("dia") }; esperar(1500)

        // la memoria al final
        File(dir, "memoria.txt").writeText(shell("dumpsys meminfo $paquete"))
    }
}
