"""
El mar nuevo de Solaris: oleaje con física real (método de Tessendorf, el de los simuladores y el cine).

- El oleaje sale de un espectro JONSWAP (mar de viento) más un mar de fondo (swell) aparte, con su
  dirección y su dispersión, normalizados a la altura significativa real (Hs) del Drake.
- Cada ola viaja a su velocidad de verdad en aguas profundas (ω = √(g·k)); las crestas se afilan con el
  desplazamiento horizontal ("choppiness") y la espuma nace donde el agua se pliega (jacobiano < umbral)
  y se va deshaciendo con el tiempo.
- Dos cascadas: olas largas (600 m) y picado fino (67 m). La malla es polar alrededor de la cámara,
  con la curvatura de la Tierra, hasta el horizonte (a ~14 m de altura, como la cubierta de un barco).
- El cielo es el del Drake: nubes bajas en capas, con la luz donde está el sol detrás.

Uso:  python3 oceano.py prueba SALIDA_DIR          -> PNG de prueba
"""
import sys, os, math, json, time, subprocess
import numpy as np
import moderngl
from PIL import Image

G = 9.81
RT = 6.371e6

# ---------------------------------------------------------------- el espectro

def jonswap(w, wp, hs, gamma=3.3):
    """Espectro JONSWAP S(ω) sin normalizar bien: se reescala después a la Hs pedida."""
    sigma = np.where(w <= wp, 0.07, 0.09)
    r = np.exp(-((w - wp) ** 2) / (2 * sigma ** 2 * wp ** 2))
    with np.errstate(divide='ignore', invalid='ignore'):
        s = G ** 2 * w ** -5.0 * np.exp(-1.25 * (wp / w) ** 4) * gamma ** r
    return np.nan_to_num(s)

def extension(theta, theta0, s):
    """Dispersión direccional cos^2s((θ-θ0)/2), normalizada."""
    d = np.cos((theta - theta0) / 2.0) ** 2
    d = d ** s
    # normalización numérica aproximada
    norm = math.gamma(s + 1) ** 2 / math.gamma(2 * s + 1) * 2 ** (2 * s) / (2 * math.pi) if s < 80 else math.sqrt(s / math.pi) / 2
    return d * norm


class Cascada:
    def __init__(self, N, L, kmin, kmax, semilla):
        self.N, self.L = N, L
        n = np.fft.fftfreq(N, d=1.0 / N)
        kx, kz = np.meshgrid(2 * np.pi * n / L, 2 * np.pi * n / L, indexing='xy')
        self.kx, self.kz = kx, kz
        self.k = np.sqrt(kx ** 2 + kz ** 2)
        self.k[0, 0] = 1e-9
        self.w = np.sqrt(G * self.k)
        self.theta = np.arctan2(kz, kx)
        self.dk = (2 * np.pi / L) ** 2
        self.banda = (self.k >= kmin) & (self.k < kmax)
        rng = np.random.default_rng(semilla)
        self.xi = (rng.standard_normal((N, N)) + 1j * rng.standard_normal((N, N))) / np.sqrt(2)
        self.espuma = np.zeros((N, N), np.float32)

    def energia(self, comp):
        """Densidad de energía S(kx,kz)·dk de una componente (mar de viento o de fondo)."""
        w, k, th = self.w, self.k, self.theta
        S = jonswap(w, comp['wp'], 1.0, comp.get('gamma', 3.3)) * extension(th, comp['dir'], comp['s'])
        # de S(ω,θ) a S(k): dω/dk = g/(2ω), y dθ = dk/k
        Sk = S * (G / (2 * w)) / k
        Sk[~self.banda] = 0
        Sk[0, 0] = 0
        return Sk * self.dk


def campos_de(h0s, chop, cascadas, t, periodo=None):
    """Desplazamiento (Dx, h, Dz), pendientes y jacobiano de cada cascada en el instante t."""
    out = []
    for c, (h0, h0m) in zip(cascadas, h0s):
        w = c.w if periodo is None else c.wq
        e = np.exp(1j * w * t)
        ht = h0 * e + h0m * np.conj(e)
        k = c.k
        N2 = c.N * c.N
        ifft = lambda a: np.real(np.fft.ifft2(a)) * N2
        h = ifft(ht)
        dx = ifft(-1j * c.kx / k * ht) * chop
        dz = ifft(-1j * c.kz / k * ht) * chop
        sx = ifft(1j * c.kx * ht)
        sz = ifft(1j * c.kz * ht)
        dxx = ifft(c.kx * c.kx / k * ht) * chop
        dzz = ifft(c.kz * c.kz / k * ht) * chop
        dxz = ifft(c.kx * c.kz / k * ht) * chop
        J = (1 + dxx) * (1 + dzz) - dxz * dxz
        out.append(dict(h=h, dx=dx, dz=dz, sx=sx / (1 + dxx).clip(0.2), sz=sz / (1 + dzz).clip(0.2), J=J))
    return out


class Mar:
    """Un estado de mar: viento, mar de fondo, y las dos cascadas."""
    def __init__(self, p, semilla=7, periodo=None):
        self.p = p
        self.periodo = periodo
        self.c1 = Cascada(512, 640.0, 2 * np.pi / 640.0, 2 * np.pi / 28.0, semilla)
        self.c2 = Cascada(256, 60.0, 2 * np.pi / 28.0, 2 * np.pi / 0.25, semilla + 1)
        if periodo:
            # cada ola gira un número entero de vueltas en el período: el bucle cierra perfecto, sin fundidos
            w0 = 2 * np.pi / periodo
            for c in (self.c1, self.c2):
                c.wq = np.maximum(np.round(c.w / w0), 1) * w0
        comps = []
        # mar de viento: su período pico sale del viento si no se da (mar desarrollado)
        # el período del mar de viento sale de su altura (relación de un mar joven, Pierson-Moskowitz)
        tp_v = p.get('tp_viento') or max(2.5, 3.8 * math.sqrt(max(p['hs_viento'], 0.05)))
        comps.append(dict(nombre='viento', hs=p['hs_viento'], wp=2 * np.pi / tp_v, dir=math.radians(p['dir_viento']), s=8, gamma=3.3))
        if p.get('hs_fondo', 0) > 0.05:
            comps.append(dict(nombre='fondo', hs=p['hs_fondo'], wp=2 * np.pi / p['tp_fondo'], dir=math.radians(p['dir_fondo']), s=35, gamma=6.0))
        self.h0 = []
        amps = []
        for c in (self.c1, self.c2):
            amp2 = np.zeros_like(c.k)
            for comp in comps:
                e1 = self.c1.energia(comp).sum() + self.c2.energia(comp).sum()
                escala = (comp['hs'] / 4.0) ** 2 / max(e1, 1e-12)
                amp2 = amp2 + c.energia(comp) * escala
            amps.append(c.xi * np.sqrt(amp2 / 2.0))
        # la realización al azar se ajusta para que la altura significativa sea exactamente la del Drake
        var = sum(2 * (np.abs(a) ** 2).sum() for a in amps)
        objetivo = (math.sqrt(p['hs_viento'] ** 2 + p.get('hs_fondo', 0) ** 2) / 4.0) ** 2
        f = math.sqrt(objetivo / max(var, 1e-12))
        for a in amps:
            h0 = a * f
            # h0(-k) conjugado
            h0m = np.conj(np.roll(np.flip(h0), 1, axis=(0, 1)))
            self.h0.append((h0, h0m))
        # cuánta superficie cubren las rompientes con este viento (Monahan: W = 3,84e-6 · U^3,41)
        U = p['viento'] * 0.5144
        self.cobertura = min(0.3, 3.84e-6 * U ** 3.41)
        self.chop = p.get('chop', 1.0)

    def campos(self, t, dt_espuma=None):
        return campos_de(self.h0, self.chop, (self.c1, self.c2), t, self.periodo)

    def actualizar_espuma(self, campos, dt):
        """La espuma nace donde el agua se pliega y se deshace poco a poco."""
        for k, (c, f) in enumerate(zip((self.c1, self.c2), campos)):
            # rompe la fracción del mar que dice el viento: donde el agua más se pliega
            frac = self.cobertura if k == 0 else min(0.04, self.cobertura * 0.25)
            if frac < 1e-4:
                c.espuma *= math.exp(-dt / 2.0); continue
            J = f['J']
            # en cada zona del mar rompen sus olas más empinadas (no solo las del grupo más alto del mar entero)
            b = 32 if k == 0 else 16
            n = J.shape[0] // b
            bloques = J.reshape(n, b, n, b).transpose(0, 2, 1, 3).reshape(n, n, b * b)
            local = np.quantile(bloques, frac, axis=2)
            local = np.kron(local, np.ones((b, b)))
            # suavizado de los bordes entre zonas
            for _ in range(2):
                local = (local + np.roll(local, b // 2, 0) + np.roll(local, -b // 2, 0) + np.roll(local, b // 2, 1) + np.roll(local, -b // 2, 1)) / 5
            umbral = 0.35 * np.quantile(J, frac) + 0.65 * local
            ancho = max(0.05, float(np.std(J)) * 0.35)
            nueva = np.clip((umbral - J) / ancho + 0.6, 0, 1) * (J < umbral)
            vida = self.p.get('espuma_vida', 2.5) * (1.0 if k == 0 else 0.4)
            c.espuma = np.maximum(c.espuma * math.exp(-dt / vida), nueva).astype(np.float32)


# ---------------------------------------------------------------- el render

VS = """
#version 330
in vec2 rp;              // (anillo, ángulo) en 0..1
uniform vec3 cam;
uniform mat4 vp;
uniform float rmin, rmax, amin, amax;
uniform sampler2D d1, d2;
uniform float L1, L2;
out vec3 mundo;
out float dist;
out float altura;
void main(){
    float r = rmin * pow(rmax / rmin, rp.x);
    float a = mix(amin, amax, rp.y);
    vec2 xz = cam.xz + r * vec2(sin(a), cos(a));
    // las olas lejanas se disuelven en promedio (evita el centelleo)
    float lod1 = clamp(log2(r / 60.0), 0.0, 9.0);
    float lod2 = clamp(log2(r / 8.0), 0.0, 8.0);
    vec4 a1 = textureLod(d1, xz / L1, lod1);
    vec4 a2 = textureLod(d2, xz / L2, lod2) * (1.0 - smoothstep(60.0, 300.0, r));
    vec3 p = vec3(xz.x + a1.x + a2.x, a1.y + a2.y, xz.y + a1.z + a2.z);
    p.y -= r * r / (2.0 * 6.371e6);
    mundo = p;
    dist = r;
    altura = a1.y;
    gl_Position = vp * vec4(p, 1.0);
}
"""

COMUN = """
uniform float t;
uniform vec3 solDir;      // hacia el sol
uniform float nubes;      // 0..1
uniform float luz;        // 1 de día, 0 de noche
uniform float vis;        // visibilidad en km
uniform vec3 cieloZenit, cieloHorizonte, colorSol;
float hash(vec2 p){ p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float ruido(vec2 p){ vec2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);
    return mix(mix(hash(i), hash(i+vec2(1,0)), f.x), mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), f.x), f.y); }
float fbm(vec2 p){ float s = 0.0, a = 0.5; for (int i = 0; i < 6; i++){ s += a * ruido(p); p = mat2(1.6,1.2,-1.2,1.6) * p; a *= 0.5; } return s; }
uniform float T;          // período del bucle: todo lo que se mueve vuelve a empezar igual
// ruido que deriva con velocidad v y aun así cierra el bucle (dos derivas cruzadas)
float fbmT(vec2 p, vec2 v){ float tau = mod(t, T); return mix(fbm(p + v * tau), fbm(p + v * (tau - T)), tau / T); }

// el cielo del Drake: nubes bajas en capas, más claras donde está el sol
vec3 cielo(vec3 d, bool detalle){
    float y = max(d.y, 0.0);
    vec3 base = mix(cieloHorizonte, cieloZenit, pow(y, 0.55));
    float haciaSol = max(dot(d, solDir), 0.0);
    base += colorSol * (pow(haciaSol, 6.0) * 0.25 + pow(haciaSol, 40.0) * 0.35) * (1.0 - nubes * 0.55);
    // con el sol en el horizonte (alba y ocaso), una franja encendida que se abre hacia su lado
    float bajo = smoothstep(0.12, 0.0, solDir.y);
    float lado = pow(max(dot(normalize(d.xz + 1e-4), normalize(solDir.xz)), 0.0), 2.0);
    base += colorSol * exp(-y * 14.0) * (0.35 + 0.65 * lado) * 0.55 * bajo;
    if (nubes > 0.01 && d.y > 0.0) {
        vec2 pc = d.xz / (d.y + 0.04) * 1.6;
        float n1 = detalle ? fbmT(pc * 0.9, vec2(0.004, 0.002)) : ruido(pc * 0.9);
        float n2 = detalle ? fbmT(pc * 2.7 + 7.0, vec2(-0.006, 0.003)) : 0.5;
        float cuerpo = smoothstep(0.62 - nubes * 0.55, 0.95 - nubes * 0.25, n1 * 0.75 + n2 * 0.35);
        // panzas oscuras, bordes iluminados del lado del sol
        vec3 gris = mix(cieloHorizonte * 0.62, cieloHorizonte * 0.95, smoothstep(0.3, 0.9, n2));
        gris += colorSol * pow(haciaSol, 4.0) * 0.18 * (1.0 - cuerpo * 0.5);
        float cerca = smoothstep(0.0, 0.25, d.y);
        base = mix(base, gris, cuerpo * mix(0.55, 0.92, cerca) * nubes);
    }
    return base * (0.12 + 0.88 * luz);
}
"""

FS = "#version 330\n" + COMUN + """
in vec3 mundo;
in float dist;
in float altura;
uniform vec3 cam;
uniform sampler2D d1, d2, n1, n2;
uniform float L1, L2, hs, vetasViento, rizo;
uniform vec2 vientoDir;
out vec4 fragColor;
void main(){
    vec2 xz = mundo.xz;
    vec4 s1 = texture(n1, xz / L1);
    vec4 s2 = texture(n2, xz / L2);
    vec2 pend = s1.xy + s2.xy * (1.0 - smoothstep(150.0, 900.0, dist));
    // el rizado capilar que el viento levanta encima de todo (más fino de lo que alcanza la cascada)
    float cerca0 = 1.0 - smoothstep(20.0, 160.0, dist);
    if (cerca0 > 0.0) {
        vec2 pq = xz * 2.3;
        vec2 vq = vec2(0.25, 0.17);
        float e0 = 0.08;
        float r0 = fbmT(pq, vq), rx = fbmT(pq + vec2(e0, 0.0), vq), rz = fbmT(pq + vec2(0.0, e0), vq);
        pend += vec2(rx - r0, rz - r0) / e0 * 0.05 * rizo * cerca0;
    }
    vec3 N = normalize(vec3(-pend.x, 1.0, -pend.y));
    vec3 V = normalize(cam - mundo);
    if (dot(N, V) < 0.02) N = normalize(N + V * (0.02 - dot(N, V)));
    vec3 R = reflect(-V, N);
    R.y = abs(R.y);
    float F = 0.035 + 0.965 * pow(1.0 - max(dot(N, V), 0.0), 5.0);
    vec3 refl = cielo(R, dist < 400.0);
    // el cuerpo del agua del océano austral: azul acero oscuro, casi negro
    vec3 amb = cielo(vec3(0.0, 1.0, 0.0), false) * 0.5 + cieloHorizonte * 0.3 * (0.12 + 0.88 * luz);
    vec3 cuerpo = vec3(0.055, 0.095, 0.115) * (amb * 1.8 + 0.1);
    // la luz que atraviesa las crestas altas (verde azulado)
    float cresta = clamp(altura / (hs * 0.5 + 0.05), 0.0, 1.0);
    float contraLuz = pow(max(dot(-V, solDir) * -1.0 + 0.6, 0.0), 2.0);
    vec3 sss = vec3(0.02, 0.10, 0.11) * cresta * cresta * (0.4 + contraLuz) * (0.2 + 0.8 * luz) * max(dot(N, -vec3(V.x, 0.0, V.z)) + 0.4, 0.0);
    // las caras que miran hacia el horizonte claro reciben más luz del cielo (se lee la forma del agua de cerca)
    vec3 haciaHorizonte = normalize(vec3(-V.x, 0.25, -V.z));
    cuerpo *= 0.55 + 1.1 * clamp(dot(N, haciaHorizonte) * 2.2 - 0.25, 0.0, 1.2);
    vec3 col = mix(cuerpo + sss, refl, F);
    // brillo del sol entre las nubes
    float esp = pow(max(dot(R, solDir), 0.0), 600.0) * smoothstep(0.9, 0.5, nubes) * luz;
    col += colorSol * esp * 4.0;
    // espuma: la rompiente (donde el agua se plegó hace poco) y lo que queda de ella, en hilos que el viento estira
    float f1 = texture(d1, xz / L1).w;
    // la espuma menuda de las olas cortas (solo de cerca, y siempre deshilachada)
    float f2 = texture(d2, xz / L2).w * (1.0 - smoothstep(40.0, 220.0, dist));
    float cerca = 1.0 - smoothstep(150.0, 2500.0, dist);
    // encaje: dos escalas de ruido, para que la espuma sea hilos y agujeros, no manchas
    float e1 = fbmT(xz * 0.45, vec2(0.02, -0.012));
    float e2 = fbmT(xz * 1.7 + 9.0, vec2(-0.03, 0.02));
    float encaje = e1 * 0.6 + e2 * 0.55;
    float rompe = smoothstep(0.5, 0.95, f1) * mix(0.8, smoothstep(0.42, 0.7, encaje), cerca);
    float queda = smoothstep(0.03, 0.5, f1) * mix(0.3, smoothstep(0.66, 0.8, encaje), cerca) * 0.65;
    // de lejos la textura se promedia: ese promedio es cuánta espuma cubre el mar ahí, y se pinta así
    float lejos = clamp(f1 * 1.5, 0.0, 1.0) * 0.85;
    // hilos: ruido "de cresta" (1 - |2n-1|) que deja filamentos en vez de manchas
    float hilo1 = 1.0 - abs(fbmT(xz * 0.9 + vec2(0.0, 2.0), vec2(0.02, 0.0)) * 2.0 - 1.0);
    float hilo2 = 1.0 - abs(fbmT(xz * 2.6 + vec2(0.0, 7.0), vec2(-0.03, 0.0)) * 2.0 - 1.0);
    float filamento = smoothstep(0.72, 0.92, hilo1 * 0.6 + hilo2 * 0.5);
    float menuda = smoothstep(0.4, 0.9, f2) * filamento * 0.45;
    rompe *= mix(1.0, 0.55 + 0.45 * filamento + 0.3 * smoothstep(0.5, 0.75, encaje), cerca);
    // vetas largas que el viento estira sobre el mar (desde temporal)
    vec2 wv = normalize(vientoDir);
    vec2 qv = vec2(dot(xz, vec2(-wv.y, wv.x)), dot(xz, wv));
    float veta = 1.0 - abs(fbmT(qv * vec2(0.45, 0.018), vec2(0.0, -0.01)) * 2.0 - 1.0);
    float vetas = smoothstep(0.9, 0.985, veta) * vetasViento * 0.65 * smoothstep(15.0, 80.0, dist);
    float f = mix(lejos, clamp(max(max(rompe, queda), menuda), 0.0, 1.0), smoothstep(400.0, 60.0, dist));
    f = max(f, vetas * (0.6 + 0.4 * filamento));
    vec3 blanco = vec3(0.80, 0.84, 0.86) * (amb * 1.6 + 0.04) * (0.72 + 0.28 * N.y);
    col = mix(col, blanco, f * 0.92);
    // aire: lo lejano se pierde en el horizonte según la visibilidad
    float bruma = 1.0 - exp(-dist / (vis * 1000.0 * 0.55));
    vec3 horiz = cielo(normalize(vec3(V.x * -1.0, 0.002, V.z * -1.0)), false);
    col = mix(col, horiz, clamp(bruma, 0.0, 1.0));
    // en temporal, el viento arranca agua de las crestas: un rocío que vela el mar a ras
    col = mix(col, horiz * 0.92, vetasViento * 0.35 * smoothstep(60.0, 1800.0, dist));
    fragColor = vec4(col, 1.0);
}
"""

VS_CIELO = """
#version 330
in vec2 p; out vec2 uv;
void main(){ uv = p * 0.5 + 0.5; gl_Position = vec4(p, 0.9999, 1.0); }
"""
FS_CIELO = "#version 330\n" + COMUN + """
in vec2 uv; uniform mat4 ivp; uniform vec3 cam; out vec4 fragColor;
void main(){
    vec4 a = ivp * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec3 d = normalize(a.xyz / a.w - cam);
    vec3 c = cielo(d, true);
    if (d.y < 0.0) c = cielo(vec3(d.x, 0.001, d.z), true);
    fragColor = vec4(c, 1.0);
}
"""

def perspectiva(fovy, asp, n, f):
    t = 1 / math.tan(fovy / 2)
    return np.array([[t / asp, 0, 0, 0], [0, t, 0, 0], [0, 0, (f + n) / (n - f), 2 * f * n / (n - f)], [0, 0, -1, 0]], np.float32)

def mirar(ojo, centro, arriba=(0, 1, 0)):
    f = np.array(centro, np.float32) - ojo; f /= np.linalg.norm(f)
    s = np.cross(f, arriba); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    m = np.eye(4, dtype=np.float32)
    m[0, :3], m[1, :3], m[2, :3] = s, u, -f
    m[:3, 3] = -m[:3, :3] @ ojo
    return m


class Camara:
    def __init__(self, W, H, ss=2, altura=9.0, inclinacion=-9.0, fovy=78.0):
        self.W, self.H, self.ss = W, H, ss
        self.ctx = moderngl.create_standalone_context(backend='egl')
        tam = (W * ss, H * ss)
        self.fbo = self.ctx.simple_framebuffer(tam, components=4)
        self.prog = self.ctx.program(vertex_shader=VS, fragment_shader=FS)
        self.cprog = self.ctx.program(vertex_shader=VS_CIELO, fragment_shader=FS_CIELO)
        # malla polar
        nr, na = 900, 520
        r = np.linspace(0, 1, nr, dtype=np.float32); a = np.linspace(0, 1, na, dtype=np.float32)
        R, A = np.meshgrid(r, a, indexing='ij')
        verts = np.stack([R, A], -1).reshape(-1, 2)
        idx = []
        i = np.arange(nr - 1)[:, None] * na + np.arange(na - 1)[None, :]
        idx = np.stack([i, i + 1, i + na, i + 1, i + na + 1, i + na], -1).reshape(-1).astype(np.int32)
        self.vao = self.ctx.vertex_array(self.prog, [(self.ctx.buffer(verts.tobytes()), '2f', 'rp')], self.ctx.buffer(idx.tobytes()))
        quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], np.float32)
        self.cvao = self.ctx.simple_vertex_array(self.cprog, self.ctx.buffer(quad.tobytes()), 'p')
        self.altura, self.incl, self.fovy = altura, inclinacion, fovy
        self.tex = {}

    def subir(self, nombre, arr):
        h, w, c = arr.shape
        t = self.tex.get(nombre)
        if t is None:
            t = self.ctx.texture((w, h), c, dtype='f4')
            t.repeat_x = t.repeat_y = True
            t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
            t.anisotropy = 16.0
            self.tex[nombre] = t
        t.write(np.ascontiguousarray(arr, dtype=np.float32).tobytes())
        t.build_mipmaps()
        return t

    def cuadro(self, mar, campos, t, cielo, altura=None, periodo=30.0):
        """Un cuadro. [mar] da las cascadas (con su espuma) y p (hs, viento, dir_viento)."""
        W, H, ss = self.W * self.ss, self.H * self.ss, self.ss
        if altura is None:
            # pruebas: el barco sube y baja con el agua que tiene debajo
            h0 = float(campos[0]['h'][0, 0] + campos[1]['h'][0, 0])
            altura = self.altura + 0.85 * h0
        ojo = np.array([0.0, altura, 0.0], np.float32)
        pitch = math.radians(self.incl)
        dirv = np.array([0, math.sin(pitch), math.cos(pitch)], np.float32)
        V = mirar(ojo, ojo + dirv)
        P = perspectiva(math.radians(self.fovy), W / H, 0.5, 40000.0)
        vp = P @ V
        for k, (c, f) in enumerate(zip((mar.c1, mar.c2), campos), 1):
            self.subir(f'd{k}', np.stack([f['dx'], f['h'], f['dz'], c.espuma], -1))
            self.subir(f'n{k}', np.stack([f['sx'], f['sz'], np.zeros_like(f['h']), np.zeros_like(f['h'])], -1))
        comunes = dict(t=t, T=periodo, **cielo)
        self.fbo.use(); self.ctx.clear(0, 0, 0, 1)
        for k, v in dict(comunes, ivp=tuple(np.linalg.inv(vp).T.reshape(-1)), cam=tuple(ojo)).items():
            if k in self.cprog: self.cprog[k].value = v
        self.ctx.disable(moderngl.DEPTH_TEST)
        self.cvao.render(moderngl.TRIANGLE_STRIP)
        self.ctx.enable(moderngl.DEPTH_TEST)
        pr = self.prog
        p = mar.p
        u = dict(comunes, cam=tuple(ojo), vp=tuple(vp.T.reshape(-1)), rmin=1.5, rmax=26000.0,
                 amin=-math.radians(48), amax=math.radians(48), L1=mar.c1.L, L2=mar.c2.L, hs=p['hs'],
                 vientoDir=(math.cos(math.radians(p['dir_viento'])), math.sin(math.radians(p['dir_viento']))),
                 vetasViento=float(np.clip((p['viento'] - 28) / 22.0, 0, 1)), rizo=float(np.clip(p['viento'] / 25.0, 0.15, 1.2)))
        for k, v in u.items():
            if k in pr: pr[k].value = v
        for i, n in enumerate(['d1', 'd2', 'n1', 'n2']):
            self.tex[n].use(i)
            if n in pr: pr[n].value = i
        self.vao.render(moderngl.TRIANGLES)
        img = np.frombuffer(self.fbo.read(components=3, dtype='f1'), np.uint8).reshape(H, W, 3)[::-1]
        return img


def tono(img_lin):
    """De luz a pantalla: curva fílmica suave y sRGB."""
    x = img_lin
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    x = np.clip((x * (a * x + b)) / (x * (c * x + d) + e), 0, 1)
    return x


# ---------------------------------------------------------------- los estados de prueba

CIELOS = {
    # cielo cubierto del Drake, de día
    'cubierto': dict(solDir=(0.35, 0.28, 0.89), nubes=0.92, luz=1.0, vis=18.0,
                     cieloZenit=(0.36, 0.42, 0.48), cieloHorizonte=(0.66, 0.71, 0.74), colorSol=(1.0, 0.95, 0.86)),
    'claros': dict(solDir=(0.30, 0.22, 0.93), nubes=0.62, luz=1.0, vis=28.0,
                   cieloZenit=(0.22, 0.36, 0.52), cieloHorizonte=(0.70, 0.76, 0.80), colorSol=(1.0, 0.93, 0.80)),
    'temporal': dict(solDir=(0.2, 0.25, 0.95), nubes=1.0, luz=0.78, vis=6.0,
                     cieloZenit=(0.20, 0.23, 0.26), cieloHorizonte=(0.40, 0.43, 0.45), colorSol=(0.8, 0.82, 0.85)),
}

PRUEBAS = {
    # nombre: altura total, viento (nudos), mar de viento + mar de fondo
    'mar_1m':  dict(hs=1.0, viento=8,  hs_viento=0.45, hs_fondo=0.9, tp_fondo=12.0, dir_viento=255, dir_fondo=240, chop=0.8, cielo='claros', espuma_umbral=0.15),
    'mar_3m':  dict(hs=3.2, viento=18, hs_viento=1.6, hs_fondo=2.8, tp_fondo=12.5, dir_viento=250, dir_fondo=235, chop=1.0, cielo='cubierto', espuma_umbral=0.32),
    'mar_6m':  dict(hs=6.0, viento=32, hs_viento=4.6, hs_fondo=3.8, tp_fondo=13.0, dir_viento=252, dir_fondo=238, chop=1.15, cielo='cubierto', espuma_umbral=0.45),
    'temporal_10m': dict(hs=10.0, viento=50, hs_viento=8.6, hs_fondo=5.0, tp_fondo=14.0, dir_viento=255, dir_fondo=240, chop=1.25, cielo='temporal', espuma_umbral=0.6, espuma_vida=6.0),
}


def prueba(salida, W=720, H=1600):
    os.makedirs(salida, exist_ok=True)
    cam = Camara(W, H, ss=2)
    for nombre, p in PRUEBAS.items():
        t0 = time.time()
        mar = Mar(p)
        # la espuma se va formando unos segundos antes del cuadro
        t = 40.0
        for k in range(60):
            tt = t - 6.0 + k * 0.1
            mar.actualizar_espuma(mar.campos(tt), 0.1)
        campos = mar.campos(t)
        img = cam.cuadro(mar, campos, t, CIELOS[p['cielo']])
        im = Image.fromarray(img).resize((W, H), Image.LANCZOS)
        ruta = os.path.join(salida, f'{nombre}.png')
        im.save(ruta)
        print(nombre, round(time.time() - t0, 1), 's', flush=True)


# ---------------------------------------------------------------- los videos (mar v3)

PERIODO = 30.0          # un bucle: 30 s que cierran perfecto
TRANSICION = 60.0       # un paso entre estados vecinos: 60 s
FPS = 60
W_VIDEO, H_VIDEO = 720, 1600
ESCALERA = [1, 1.5, 2, 3, 4, 5, 6, 8, 10]
VIENTOS = {1: 8, 1.5: 11, 2: 14, 3: 18, 4: 24, 5: 28, 6: 32, 8: 40, 10: 50}
HORIZONTE = 0.402        # dónde queda el horizonte en el cuadro (cámara 9° hacia abajo, 78° de campo)

LUCES = {
    'dia': CIELOS['cubierto'],
    'crep': dict(solDir=(0.80, 0.035, 0.60), nubes=0.72, luz=0.62, vis=20.0,
                 cieloZenit=(0.19, 0.24, 0.33), cieloHorizonte=(0.64, 0.53, 0.47), colorSol=(1.0, 0.70, 0.45)),
    'noche': dict(solDir=(-0.35, 0.30, 0.88), nubes=0.9, luz=0.0, vis=14.0,
                  cieloZenit=(0.42, 0.50, 0.64), cieloHorizonte=(0.62, 0.70, 0.82), colorSol=(0.55, 0.62, 0.75)),
}
VECINOS_LUZ = [('dia', 'crep'), ('crep', 'noche')]


def params_hs(h):
    """El mar del Drake para una altura significativa: mar de fondo del oeste y mar de viento encima."""
    fondo = min(0.75 * h, 2.0 + 0.3 * h)
    viento_hs = math.sqrt(max(h * h - fondo * fondo, 0.04))
    return dict(hs=h, viento=VIENTOS[h], hs_viento=viento_hs, hs_fondo=fondo, tp_fondo=11.0 + 0.3 * h,
                dir_viento=252, dir_fondo=238, chop=0.8 + 0.045 * h, espuma_vida=2.5 + 0.35 * h)


def nombre_estado(luz, h):
    return f'{luz}_{h:g}m'


def todos_los_videos():
    """Bucles, pasos de ola (en cada luz) y pasos de luz (en cada altura)."""
    v = []
    for luz in LUCES:
        for h in ESCALERA:
            v.append(('bucle', (luz, h), None))
    for luz in LUCES:
        for a, b in zip(ESCALERA, ESCALERA[1:]):
            v.append(('paso', (luz, a), (luz, b)))
            v.append(('paso', (luz, b), (luz, a)))
    for h in ESCALERA:
        for la, lb in VECINOS_LUZ:
            v.append(('paso', (la, h), (lb, h)))
            v.append(('paso', (lb, h), (la, h)))
    return v


def archivo(v):
    tipo, a, b = v
    return nombre_estado(*a) + ('' if tipo == 'bucle' else '__' + nombre_estado(*b))


def lerp(a, b, s):
    if isinstance(a, (tuple, list)):
        return tuple(x + (y - x) * s for x, y in zip(a, b))
    return a + (b - a) * s


class Paso:
    """Un mar que puede estar entre dos estados: las mismas olas (mismas fases), otra energía."""
    def __init__(self, a, b=None):
        self.A = Mar(params_hs(a[1]), periodo=PERIODO)
        self.B = Mar(params_hs(b[1]), periodo=PERIODO) if b else None
        self.la, self.lb = LUCES[a[0]], LUCES[b[0]] if b else None
        self.c1, self.c2 = self.A.c1, self.A.c2
        self.s = 0.0

    def fijar(self, s):
        self.s = s
        A, B = self.A, self.B
        if B is None:
            self.h0, self.chop, self.cobertura, self.p = A.h0, A.chop, A.cobertura, A.p
            self.cielo = self.la
            return
        self.h0 = [(ha * (1 - s) + hb * s, hma * (1 - s) + hmb * s) for (ha, hma), (hb, hmb) in zip(A.h0, B.h0)]
        self.chop = lerp(A.chop, B.chop, s)
        self.cobertura = lerp(A.cobertura, B.cobertura, s)
        self.p = {k: lerp(A.p[k], B.p[k], s) for k in ('hs', 'viento', 'dir_viento', 'espuma_vida')}
        self.cielo = {k: lerp(self.la[k], self.lb[k], s) for k in self.la}

    def campos(self, t):
        return campos_de(self.h0, self.chop, (self.c1, self.c2), t, PERIODO)

    def espuma(self, campos, dt):
        Mar.actualizar_espuma(self, campos, dt)

    @property
    def altura(self):
        return 9.0 + 0.5 * self.p['hs']


def muestrear(h, L, x, z):
    """La altura del agua en (x, z) metros, con interpolación bilineal sobre la cascada."""
    N = h.shape[0]
    u, v = (x / L * N) % N, (z / L * N) % N
    i0, j0 = int(v), int(u)
    fu, fv = u - j0, v - i0
    i1, j1 = (i0 + 1) % N, (j0 + 1) % N
    return (h[i0, j0] * (1 - fu) * (1 - fv) + h[i0, j1] * fu * (1 - fv) + h[i1, j0] * (1 - fu) * fv + h[i1, j1] * fu * fv)


def movimiento(campos, L):
    """Cómo se mueve un barco de unos 70 m sobre este mar: cabeceo, balanceo y subida (grados, grados, metros)."""
    h = campos[0]['h']
    m = lambda x, z: muestrear(h, L, x, z)
    proa, popa = m(0, 30) + m(3, 30) + m(-3, 30), m(0, -30) + m(3, -30) + m(-3, -30)
    babor, estribor = m(-7, 0) + m(-7, 10) + m(-7, -10), m(7, 0) + m(7, 10) + m(7, -10)
    sube = (m(0, 0) + m(0, 15) + m(0, -15) + m(5, 0) + m(-5, 0)) / 5
    cabeceo = math.degrees(math.atan((proa - popa) / 3 / 60.0))
    balanceo = math.degrees(math.atan((estribor - babor) / 3 / 14.0)) * 0.55
    return [round(cabeceo, 3), round(balanceo, 3), round(sube, 3)]


def suavizar(curva, ventana, ciclico):
    """La inercia del barco: no sigue cada ondita, sino el promedio del agua bajo su casco."""
    a = np.array(curva, np.float64)
    n = len(a)
    k = np.ones(ventana) / ventana
    if ciclico:
        ext = np.concatenate([a[-ventana:], a, a[:ventana]])
    else:
        ext = np.concatenate([np.repeat(a[:1], ventana, 0), a, np.repeat(a[-1:], ventana, 0)])
    out = np.stack([np.convolve(ext[:, j], k, mode='same') for j in range(a.shape[1])], -1)
    return np.round(out[ventana:ventana + n], 3).tolist()


def codificador(ruta):
    return subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
        '-s', f'{W_VIDEO}x{H_VIDEO}', '-r', str(FPS), '-i', '-',
        '-c:v', 'libx265', '-preset', 'medium', '-crf', '25', '-pix_fmt', 'yuv420p', '-tag:v', 'hvc1',
        '-x265-params', 'keyint=60:min-keyint=60:scenecut=0:log-level=error',
        '-movflags', '+faststart', '-an', ruta], stdin=subprocess.PIPE)


def hornear(v, salida, cam=None, segundos=None):
    """Renderiza un video (bucle o paso) y su curva de movimiento."""
    tipo, a, b = v
    nombre = archivo(v)
    ruta = os.path.join(salida, nombre + '.mp4')
    if os.path.exists(ruta) and os.path.exists(os.path.join(salida, nombre + '.mov.json')):
        return
    cam = cam or Camara(W_VIDEO, H_VIDEO, ss=1)
    paso = Paso(a, b if tipo == 'paso' else None)
    dur = segundos or (PERIODO if tipo == 'bucle' else TRANSICION)
    n = int(dur * FPS)
    paso.fijar(0.0)
    # la espuma se forma antes de empezar, igual que en el bucle de origen (así el empalme es exacto)
    for k in range(int(PERIODO * FPS)):
        tt = -PERIODO + k / FPS
        paso.espuma(paso.campos(tt), 1.0 / FPS)
    ff = codificador(ruta + '.tmp.mp4')
    mov = []
    t0 = time.time()
    for k in range(n):
        t = k / FPS
        if tipo == 'paso':
            x = t / dur
            paso.fijar(x * x * x * (x * (x * 6 - 15) + 10))
        campos = paso.campos(t)
        paso.espuma(campos, 1.0 / FPS)
        img = cam.cuadro(paso, campos, t, paso.cielo, altura=paso.altura, periodo=PERIODO)
        if cam.ss != 1:
            img = np.asarray(Image.fromarray(img).resize((W_VIDEO, H_VIDEO), Image.BILINEAR))
        ff.stdin.write(np.ascontiguousarray(img).tobytes())
        if k % 2 == 0:
            mov.append(movimiento(campos, paso.c1.L))
    ff.stdin.close(); ff.wait()
    os.rename(ruta + '.tmp.mp4', ruta)
    mov = suavizar(mov, 30, ciclico=(tipo == 'bucle'))
    with open(os.path.join(salida, nombre + '.mov.json'), 'w') as f:
        json.dump(dict(hz=30, mov=mov), f, separators=(',', ':'))
    print(nombre, n, 'cuadros', round(time.time() - t0), 's', os.path.getsize(ruta) // 1024, 'KB', flush=True)


def indice(salida):
    """El índice del paquete: qué archivo es cada estado y cada paso, y cuánto pesa."""
    est, tr, mov, peso = {}, {}, {}, {}
    for v in todos_los_videos():
        nombre = archivo(v)
        f = nombre + '.mp4'
        if not os.path.exists(os.path.join(salida, f)):
            continue
        peso[f] = os.path.getsize(os.path.join(salida, f))
        mov[nombre] = nombre + '.mov.json'
        if v[0] == 'bucle':
            est[nombre_estado(*v[1])] = f
        else:
            tr[nombre_estado(*v[1]) + '>' + nombre_estado(*v[2])] = f
    i = dict(version=3, fps=FPS, segundos=PERIODO, transicion=TRANSICION, entrada_ms=0, horizonte=HORIZONTE,
             escalera=ESCALERA, luces=list(LUCES), estados=est, transiciones=tr, movimiento=mov, bytes=peso)
    with open(os.path.join(salida, 'indice.json'), 'w') as f:
        json.dump(i, f, indent=1)
    return i


if __name__ == '__main__':
    if sys.argv[1] == 'lista':
        # cuántos videos hay y cómo se reparten entre trabajos: python3 oceano.py lista N
        todos = todos_los_videos()
        n = int(sys.argv[2])
        print(json.dumps(list(range(n))))
        sys.exit(0)
    if sys.argv[1] == 'hornear':
        # python3 oceano.py hornear SALIDA PARTE DE_CUANTAS
        salida, parte, total = sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
        os.makedirs(salida, exist_ok=True)
        cam = Camara(W_VIDEO, H_VIDEO, ss=1)
        todos = todos_los_videos()
        ya = set(os.environ.get('YA', '').split())
        todos = [v for v in todos if archivo(v) + '.mp4' not in ya or archivo(v) + '.mov.json' not in ya]
        # los pasos pesan el doble que los bucles: se reparten por turnos para equilibrar
        for i, v in enumerate(todos):
            if i % total == parte:
                hornear(v, salida, cam)
        sys.exit(0)
    if sys.argv[1] == 'indice':
        print(json.dumps(indice(sys.argv[2]))[:400])
        sys.exit(0)
    if sys.argv[1] == 'prueba':
        prueba(sys.argv[2])
