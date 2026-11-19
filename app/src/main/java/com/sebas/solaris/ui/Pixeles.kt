package com.sebas.solaris.ui

import com.sebas.solaris.Mar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Algo que se dibuja en una cuadrícula pequeña de píxeles. */
interface Pixelador {
    val ancho: Int
    val alto: Int
    val px: IntArray
}

private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

private fun cl(v: Double) = if (v < 0) 0.0 else if (v > 1) 1.0 else v

/** Un color de trabajo (r, g, b en 0..255). */
private class C3(var r: Double, var g: Double, var b: Double) {
    fun set(o: C3): C3 { r = o.r; g = o.g; b = o.b; return this }
    fun set(rr: Double, gg: Double, bb: Double): C3 { r = rr; g = gg; b = bb; return this }
    fun mix(o: C3, f: Double): C3 { r += (o.r - r) * f; g += (o.g - g) * f; b += (o.b - b) * f; return this }
    fun mix(rr: Double, gg: Double, bb: Double, f: Double): C3 { r += (rr - r) * f; g += (gg - g) * f; b += (bb - b) * f; return this }
}

private fun c3(r: Int, g: Int, b: Int) = C3(r.toDouble(), g.toDouble(), b.toDouble())
private fun lerp(a: C3, b: C3, f: Double, out: C3) = out.set(a).mix(b, f)

/** Escribe un píxel con tramado ordenado: así se ve pixel art y no degradado. */
private fun poner(px: IntArray, w: Int, x: Int, y: Int, c: C3, paso: Double) {
    val d = BAYER[(y and 3) * 4 + (x and 3)] / 16.0 - 0.5
    val r = ((c.r / paso + d).roundToInt() * paso).toInt().coerceIn(0, 255)
    val g = ((c.g / paso + d).roundToInt() * paso).toInt().coerceIn(0, 255)
    val b = ((c.b / paso + d).roundToInt() * paso).toInt().coerceIn(0, 255)
    px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * El océano del Paso de Drake en pixel art.
 * Seis trenes de olas con la velocidad que tendrían en mar profundo según su período.
 */
class OceanoPixel(override val ancho: Int = 130, override val alto: Int = 281) : Pixelador {
    override val px = IntArray(ancho * alto)

    private var t = 0.0
    private var ola = 3.0; private var periodo = 11.0; private var viento = 20.0; private var nubes = 0.8
    private var lluvia = 0.3; private var vis = 10.0; private var luz = 0.0; private var alba = 0.0; private var rayos = 0.0
    private var flash = 0
    private var rayo: IntArray? = null
    private val gotas = ArrayList<DoubleArray>()
    private val estrellas = Array(60) { intArrayOf(Random.nextInt(ancho), Random.nextInt(max(1, (alto * 0.28).toInt())), Random.nextInt(600)) }
    private val esc = alto / 281.0
    private val SW_ANG = doubleArrayOf(0.05, 0.42, -0.5)
    private val SW_LARGO = doubleArrayOf(1.0, 0.63, 0.41)
    private val SW_ALTO = doubleArrayOf(0.55, 0.3, 0.15)
    private val SW_FASE = doubleArrayOf(0.0, 1.7, 3.1)
    private val WI_LAMBDA = doubleArrayOf(14.0, 9.5, 6.3, 4.1, 2.7, 1.8)
    private val WI_ANG = doubleArrayOf(0.3, -0.45, 0.75, -0.9, 0.15, -0.25)
    private val WI_FASE = doubleArrayOf(0.4, 2.2, 4.1, 1.3, 5.2, 3.3)
    private var primera = true

    private val cA = c3(0, 0, 0); private val cB = c3(0, 0, 0); private val tmp = c3(0, 0, 0); private val tmp2 = c3(0, 0, 0)
    private val col = c3(0, 0, 0)

    fun cuadro(m: Mar, dt: Double) {
        t += dt
        val hora = m.horaNumero()
        val luzMeta = if (m.esDia) 1.0 else 0.0
        val albaMeta = when {
            hora in 4.5..7.5 -> 1.0 - abs(hora - 6.0) / 1.5
            hora in 17.5..20.5 -> 1.0 - abs(hora - 19.0) / 1.5
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val k = if (primera) 1.0 else 0.02
        primera = false
        ola += (m.ola - ola) * k; periodo += (m.periodo - periodo) * k; viento += (m.viento - viento) * k
        nubes += (m.nubes / 100.0 - nubes) * k; lluvia += (m.lluvia - lluvia) * k; vis += (m.visibilidad - vis) * k
        luz += (luzMeta * 0.85 - luz) * k; alba += (albaMeta - alba) * k; rayos += ((if (m.rayos) 1.0 else 0.0) - rayos) * k

        val W = ancho; val H = alto
        val hz = (H * 0.34 + sin(t * 2 * PI / periodo) * ola * 0.55 * esc).roundToInt()
        val solX = W * 0.71

        val cieloArriba = c3(0, 0, 0); lerp(c3(6, 10, 18), c3(58, 92, 126), luz, cieloArriba)
        tmp.set(12.0, 15.0, 20.0).mix(104.0, 112.0, 118.0, luz); cieloArriba.mix(tmp, nubes)
        val cieloAbajo = c3(26, 38, 54).mix(164.0, 184.0, 196.0, luz)
        tmp.set(30.0, 36.0, 44.0).mix(150.0, 156.0, 160.0, luz); cieloAbajo.mix(tmp, nubes)
        val niebla = c3(34, 42, 50).mix(168.0, 174.0, 176.0, luz)
        // el mar: abismo azul marino, cuerpo petróleo, luz turquesa en las crestas y cielo de acero en lo lejano
        val abismo = c3(2, 8, 18).mix(9.0, 34.0, 58.0, luz)
        val petroleo = c3(4, 19, 32).mix(20.0, 66.0, 88.0, luz)
        val corriente = c3(3, 16, 40).mix(16.0, 54.0, 104.0, luz)
        val turquesa = c3(12, 44, 54).mix(52.0, 126.0, 132.0, luz)
        val acero = c3(18, 30, 44).mix(104.0, 134.0, 152.0, luz)
        val espuma = c3(150, 170, 178).mix(226.0, 234.0, 236.0, luz)
        val albaC = c3(236, 186, 142)
        if (flash > 0) { cieloArriba.mix(190.0, 204.0, 224.0, 0.6); cieloAbajo.mix(210.0, 220.0, 236.0, 0.6) }

        val nubeOsc = c3(14, 18, 24).mix(118.0, 126.0, 132.0, luz)
        val nubeCla = c3(40, 46, 54).mix(176.0, 180.0, 182.0, luz)
        for (y in 0 until min(hz, H)) {
            val f = y.toDouble() / hz
            for (x in 0 until W) {
                lerp(cieloArriba, cieloAbajo, f.pow(1.4), col)
                if (alba > 0.01) {
                    val dx = (x - solX) / (W * 0.31); val dy = (hz - y) / (H * 0.093)
                    col.mix(albaC, cl(alba * (1 - dx * dx - dy) * 0.8) * (1 - nubes * 0.6))
                }
                val n = sin(x * 0.07 + t * viento * 0.004 + y * 0.03) + sin(x * 0.13 - y * 0.21 + t * 0.05) * 0.7 +
                    sin(x * 0.031 + y * 0.12 + t * viento * 0.002) * 1.2 + sin(y * 0.4 + x * 0.02) * 0.4
                val cubre = cl((n + 2.2 - (1 - nubes) * 3.6) / 2.4)
                lerp(nubeOsc, nubeCla, cl(1 - f * 1.2), tmp2)
                col.mix(tmp2, cubre * (0.5 + nubes * 0.5))
                if (flash > 0) col.mix(220.0, 228.0, 240.0, 0.35 * cubre)
                col.mix(niebla, cl(f.pow(3) * (1 - min(1.0, vis / 12)) * 0.9))
                poner(px, W, x, y, col, 12.0)
            }
        }
        if (luz < 0.3 && nubes < 0.8) {
            for (e in estrellas) {
                if (e[1] < hz - 8 && sin(t * 2 + e[2] / 100.0) > -0.2) {
                    col.set(210.0, 216.0, 226.0).mix(cieloArriba, min(1.0, nubes + luz * 2)); poner(px, W, e[0], e[1], col, 18.0)
                }
            }
        }

        val L = 9.81 * periodo * periodo / (2 * PI)
        val a0 = ola / 2
        val picado = cl(viento / 45)
        val fuerzaViento = 0.25 + picado * 0.9
        for (yy in max(hz, 0) until H) {
            val v = (yy - hz + 1).toDouble() / (H - hz)
            val z = 14 / v
            val fres = cl(0.25 + (1 - v) * 0.75)
            val nb = 1 - exp(-z / (vis * 450 + 30))
            for (x2 in 0 until W) {
                val xw = ((x2 + 0.5) / W - 0.5) * z * 1.15
                // mar de fondo: olas largas según el período real
                var h = 0.0; var hx = 0.0; var hzz = 0.0
                for (w in 0 until 3) {
                    val kk = 2 * PI / (L * SW_LARGO[w])
                    val om = sqrt(9.81 * kk)
                    val a = a0 * SW_ALTO[w]
                    val ddx = sin(SW_ANG[w]); val ddz = cos(SW_ANG[w])
                    val th = kk * (ddx * xw + ddz * z) - om * t + SW_FASE[w]
                    val co = cos(th)
                    h += a * sin(th); hx += a * kk * ddx * co; hzz += a * kk * ddz * co
                }
                // mar de viento: olas cortas que le dan textura al agua
                var hs = 0.0; var sx = 0.0; var sz = 0.0
                for (w in 0 until 6) {
                    val lam = WI_LAMBDA[w]
                    val kk = 2 * PI / lam
                    val om = sqrt(9.81 * kk)
                    val aten = exp(-z / (lam * 22))
                    val a = 0.045 * lam * fuerzaViento * 0.5 * aten
                    val ddx = sin(WI_ANG[w]); val ddz = cos(WI_ANG[w])
                    val th = kk * (ddx * xw + ddz * z) - om * t + WI_FASE[w]
                    val co = cos(th)
                    hs += a * sin(th); sx += a * kk * ddx * co; sz += a * kk * ddz * co
                }
                val refl = cl(0.42 - (hzz * 2.5 + sz) * 1.6 + (hx * 2.5 + sx) * 0.35)
                val cresta = h / (a0 * 1.05 + 0.01)
                lerp(abismo, petroleo, cl(0.42 + cresta * 0.55 + refl * 0.25), col)
                // corrientes: manchas lentas de azul más frío
                val manchas = sin(xw * 0.012 + z * 0.008 + t * 0.03) * 0.5 + sin(xw * 0.029 - z * 0.016 - t * 0.02) * 0.5
                col.mix(corriente, cl(manchas * 0.35 + 0.2))
                // la luz atraviesa la cresta: turquesa en las caras que miran hacia ti
                col.mix(turquesa, cl((cresta - 0.15) * 1.3) * cl(-hzz * 3.0 + 0.3) * 0.7)
                lerp(acero, cieloAbajo, 0.45, tmp2)
                col.mix(tmp2, refl * fres * 0.6)
                col.mix(acero, (1 - v) * (1 - v) * 0.38)
                // borreguitos: crestas cortas que rompen, en grupos, más con viento
                val hsn = hs / (0.045 * 14 * fuerzaViento * 0.5 * 1.6 + 1e-6)
                val umbral = 1.08 - picado * 0.5 - cl(cresta) * 0.3
                val grupos = cl((sin(xw * 0.07 + z * 0.05 + t * 0.2) * sin(xw * 0.031 - z * 0.09 - t * 0.15) + 0.15) * 2.5)
                val blanco = cl((hsn - umbral) * 5) * cl((picado - 0.3) * 2.2) * grupos * cl(-sz * 4 + 0.6)
                if (blanco > 0) col.mix(espuma, blanco * 0.9)
                if (alba > 0.01 && abs(x2 - solX) < (3 + v * 22) * W / 130.0 && refl > 0.55) col.mix(albaC, alba * 0.55 * (1 - nubes * 0.5) * (1 - v))
                col.mix(niebla, cl(nb))
                if (flash > 0) col.mix(170.0, 186.0, 204.0, 0.25)
                poner(px, W, x2, yy, col, 12.0)
            }
        }

        val area = W * H / (130.0 * 281.0)
        val cuantas = min((170 * area).toInt(), (lluvia * 26 * area).roundToInt())
        while (gotas.size < cuantas) gotas.add(doubleArrayOf(Random.nextDouble() * W, Random.nextDouble() * H))
        while (gotas.size > cuantas) gotas.removeAt(gotas.size - 1)
        val lado = viento / 30
        for (g in gotas) {
            g[0] += lado * 1.6; g[1] += 5.0
            if (g[1] >= H || g[0] >= W) { g[0] = Random.nextDouble() * W - 20; g[1] = -4.0 }
            for (q in 0 until 3) {
                val gx = (g[0] - lado * q * 0.6).roundToInt(); val gy = (g[1] - q * 1.6).roundToInt()
                if (gx in 0 until W && gy in 0 until H) {
                    val p = px[gy * W + gx]
                    col.set(((p shr 16) and 0xFF).toDouble(), ((p shr 8) and 0xFF).toDouble(), (p and 0xFF).toDouble())
                        .mix(176.0, 188.0, 198.0, 0.45 - q * 0.12)
                    poner(px, W, gx, gy, col, 18.0)
                }
            }
        }

        if (rayos > 0.7 && flash <= 0 && Random.nextDouble() < 0.01) {
            flash = 5
            val r = IntArray(max(2, hz) * 2)
            var rx = (W * 0.15).toInt() + Random.nextInt(max(1, (W * 0.7).toInt())); var ry = 0; var i = 0
            while (ry < hz - 2 && i + 1 < r.size) {
                r[i] = rx; r[i + 1] = ry; i += 2; ry++
                rx += Random.nextInt(3) - 1
                if (Random.nextDouble() < 0.06) rx += if (Random.nextBoolean()) -3 else 3
            }
            rayo = r.copyOf(i)
        }
        if (flash > 0) {
            val r = rayo
            if (r != null && flash > 2) {
                col.set(246.0, 250.0, 255.0)
                var i = 0
                while (i + 1 < r.size) { if (r[i] in 0 until W && r[i + 1] in 0 until H) poner(px, W, r[i], r[i + 1], col, 18.0); i += 2 }
            }
            flash--
        }
    }
}

/**
 * La Tierra vista desde la estación, en pixel art: horizonte curvo, nubes sobre la esfera (sin costuras),
 * reflejo del sol y tormentas en el lado de noche.
 */
class PlanetaPixel(override val ancho: Int = 130, override val alto: Int = 281) : Pixelador {
    override val px = IntArray(ancho * alto)

    private val perm = IntArray(512)
    private val estrellas = Array(110) { intArrayOf(Random.nextInt(ancho), Random.nextInt(max(1, (alto * 0.68).toInt())), Random.nextInt(600)) }
    private val destellos = ArrayList<DoubleArray>()
    private var t = 0.0
    private val col = c3(0, 0, 0); private val mar = c3(0, 0, 0); private val blanco = c3(0, 0, 0)

    init {
        val p = (0 until 256).shuffled()
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun h3(x: Int, y: Int, z: Int): Double = perm[(perm[(perm[x and 255] + y) and 255] + z) and 255] / 255.0
    private fun sm(f: Double) = f * f * (3 - 2 * f)
    private fun ruido(x: Double, y: Double, z: Double): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val fx = sm(x - xi); val fy = sm(y - yi); val fz = sm(z - zi)
        val a = h3(xi, yi, zi); val b = h3(xi + 1, yi, zi); val c = h3(xi, yi + 1, zi); val d = h3(xi + 1, yi + 1, zi)
        val e = h3(xi, yi, zi + 1); val f = h3(xi + 1, yi, zi + 1); val g = h3(xi, yi + 1, zi + 1); val k = h3(xi + 1, yi + 1, zi + 1)
        val x1 = a + (b - a) * fx; val x2 = c + (d - c) * fx; val x3 = e + (f - e) * fx; val x4 = g + (k - g) * fx
        val y1 = x1 + (x2 - x1) * fy; val y2 = x3 + (x4 - x3) * fy
        return y1 + (y2 - y1) * fz
    }
    private fun fbm(x: Double, y: Double, z: Double) =
        ruido(x, y, z) * 0.55 + ruido(x * 2.1, y * 2.1, z * 2.1) * 0.27 + ruido(x * 4.3, y * 4.3, z * 4.3) * 0.13 + ruido(x * 8.7, y * 8.7, z * 8.7) * 0.05

    /** [llegada] va de 0 a 1 durante la animación de acople. */
    fun cuadro(dt: Double, llegada: Double) {
        t += dt
        val W = ancho; val H = alto
        val e = 1 - (1 - llegada.coerceIn(0.0, 1.0)).pow(3)
        val R = W * 1.55; val cx = W * 0.6
        val cy = H * 0.62 + R + (1 - e) * H * 0.3 + sin(t * 0.6) * 0.7
        val giro = t * 0.012
        val cg = cos(giro); val sg = sin(giro)
        val sx = 0.78; val sy = 0.42; val sz = 0.46
        if (Random.nextDouble() < 0.05) destellos.add(doubleArrayOf(Random.nextDouble() * W * 0.45, cy - R + 8 + Random.nextDouble() * 70, 1.0))

        for (y in 0 until H) {
            for (x in 0 until W) {
                val dx = x + 0.5 - cx; val dy = y + 0.5 - cy
                val dd = sqrt(dx * dx + dy * dy)
                if (dd < R) {
                    val nx = dx / R; val ny = -dy / R; val nz = sqrt(max(0.0, 1 - nx * nx - ny * ny))
                    val luz = nx * sx + ny * sy + nz * sz
                    val dia = cl(luz * 1.8 + 0.15)
                    val qx = nx * cg + nz * sg; val qz = -nx * sg + nz * cg; val qy = ny
                    val nube = fbm(qx * 3.4 + 7, qy * 3.4, qz * 3.4)
                    val remolino = ruido(qx * 9 + t * 0.05, qy * 9, qz * 9)
                    val cub = cl((nube + remolino * 0.18 - 0.5) * 4.5)
                    // océanos con matices: azul marino, petróleo y algo de turquesa
                    val matiz = ruido(qx * 5 + 3, qy * 5, qz * 5)
                    mar.set(3.0, 8.0, 18.0).mix(14.0, 50.0, 96.0, dia)
                    blanco.set(4.0, 14.0, 22.0).mix(24.0, 88.0, 112.0, dia)
                    mar.mix(blanco, cl((matiz - 0.35) * 1.6))
                    val brillo = exp(-((nx - 0.55) * (nx - 0.55) + (ny - 0.86) * (ny - 0.86)) * 60) * dia
                    mar.mix(250.0, 238.0, 214.0, cl(brillo * 0.9))
                    blanco.set(16.0, 20.0, 28.0).mix(226.0, 232.0, 238.0, dia)
                    lerp(mar, blanco, cub, col)
                    for (d in destellos) {
                        val ex = x - d[0]; val ey = y - d[1]; val q = ex * ex + ey * ey
                        if (q < 30) col.mix(214.0, 228.0, 255.0, cl(d[2] * (1 - q / 30)) * cub)
                    }
                    val borde = (1 - nz).pow(3)
                    col.mix(96.0, 176.0, 226.0, cl(borde * (0.35 + dia * 0.65)))
                } else if (dd < R * 1.045) {
                    val f2 = (dd - R) / (R * 0.045)
                    val ax = dx / dd; val ay = -dy / dd
                    val lado = cl((ax * sx + ay * sy) * 1.6 + 0.35)
                    col.set(0.0, 1.0, 4.0).mix(130.0, 212.0, 240.0, cl((1 - f2) * (1 - f2) * lado))
                } else {
                    col.set(1.0, 2.0, 5.0)
                }
                poner(px, W, x, y, col, 16.0)
            }
        }
        val it = destellos.iterator()
        while (it.hasNext()) { val d = it.next(); d[2] -= 0.25; if (d[2] <= 0) it.remove() }
        for (s in estrellas) {
            val p = px[s[1] * W + s[0]]
            if (((p shr 16) and 0xFF) < 12 && (p and 0xFF) < 12 && sin(t * 1.4 + s[2] / 100.0) > -0.3) {
                col.set(224.0, 230.0, 240.0); poner(px, W, s[0], s[1], col, 16.0)
            }
        }
    }
}
