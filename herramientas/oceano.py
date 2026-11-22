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
import sys, os, math, json, time
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


class Mar:
    """Un estado de mar: viento, mar de fondo, y las dos cascadas."""
    def __init__(self, p, semilla=7):
        self.p = p
        self.c1 = Cascada(512, 640.0, 2 * np.pi / 640.0, 2 * np.pi / 28.0, semilla)
        self.c2 = Cascada(256, 60.0, 2 * np.pi / 28.0, 2 * np.pi / 0.25, semilla + 1)
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
        """Desplazamiento (Dx, h, Dz), pendientes y espuma de cada cascada en el instante t."""
        out = []
        for c, (h0, h0m) in zip((self.c1, self.c2), self.h0):
            e = np.exp(1j * c.w * t)
            ht = h0 * e + h0m * np.conj(e)
            k = c.k
            N2 = c.N * c.N
            ifft = lambda a: np.real(np.fft.ifft2(a)) * N2
            h = ifft(ht)
            dx = ifft(-1j * c.kx / k * ht) * self.chop
            dz = ifft(-1j * c.kz / k * ht) * self.chop
            sx = ifft(1j * c.kx * ht)
            sz = ifft(1j * c.kz * ht)
            dxx = ifft(c.kx * c.kx / k * ht) * self.chop
            dzz = ifft(c.kz * c.kz / k * ht) * self.chop
            dxz = ifft(c.kx * c.kz / k * ht) * self.chop
            J = (1 + dxx) * (1 + dzz) - dxz * dxz
            out.append(dict(h=h, dx=dx, dz=dz, sx=sx / (1 + dxx).clip(0.2), sz=sz / (1 + dzz).clip(0.2), J=J))
        return out

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

// el cielo del Drake: nubes bajas en capas, más claras donde está el sol
vec3 cielo(vec3 d, bool detalle){
    float y = max(d.y, 0.0);
    vec3 base = mix(cieloHorizonte, cieloZenit, pow(y, 0.55));
    float haciaSol = max(dot(d, solDir), 0.0);
    base += colorSol * (pow(haciaSol, 6.0) * 0.25 + pow(haciaSol, 40.0) * 0.35) * (1.0 - nubes * 0.55);
    if (nubes > 0.01 && d.y > 0.0) {
        vec2 pc = d.xz / (d.y + 0.04) * 1.6;
        float n1 = detalle ? fbm(pc * 0.9 + vec2(t * 0.004, t * 0.002)) : ruido(pc * 0.9);
        float n2 = detalle ? fbm(pc * 2.7 + vec2(-t * 0.006, t * 0.003) + 7.0) : 0.5;
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
        vec2 pq = xz * 2.3 + vec2(t * 0.9, t * 0.6);
        float e0 = 0.08;
        float r0 = fbm(pq), rx = fbm(pq + vec2(e0, 0.0)), rz = fbm(pq + vec2(0.0, e0));
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
    float e1 = fbm(xz * 0.45 + vec2(t * 0.05, -t * 0.03));
    float e2 = fbm(xz * 1.7 + vec2(-t * 0.08, t * 0.06) + 9.0);
    float encaje = e1 * 0.6 + e2 * 0.55;
    float rompe = smoothstep(0.5, 0.95, f1) * mix(0.8, smoothstep(0.42, 0.7, encaje), cerca);
    float queda = smoothstep(0.03, 0.5, f1) * mix(0.3, smoothstep(0.66, 0.8, encaje), cerca) * 0.65;
    // de lejos la textura se promedia: ese promedio es cuánta espuma cubre el mar ahí, y se pinta así
    float lejos = clamp(f1 * 1.5, 0.0, 1.0) * 0.85;
    // hilos: ruido "de cresta" (1 - |2n-1|) que deja filamentos en vez de manchas
    float hilo1 = 1.0 - abs(fbm(xz * 0.9 + vec2(t * 0.06, 2.0)) * 2.0 - 1.0);
    float hilo2 = 1.0 - abs(fbm(xz * 2.6 + vec2(-t * 0.1, 7.0)) * 2.0 - 1.0);
    float filamento = smoothstep(0.72, 0.92, hilo1 * 0.6 + hilo2 * 0.5);
    float menuda = smoothstep(0.3, 0.8, f2) * filamento * 0.75;
    rompe *= mix(1.0, 0.55 + 0.45 * filamento + 0.3 * smoothstep(0.5, 0.75, encaje), cerca);
    // vetas largas que el viento estira sobre el mar (desde temporal)
    vec2 wv = normalize(vientoDir);
    vec2 qv = vec2(dot(xz, vec2(-wv.y, wv.x)), dot(xz, wv));
    float veta = 1.0 - abs(fbm(qv * vec2(0.45, 0.018) + vec2(0.0, -t * 0.02)) * 2.0 - 1.0);
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
        self.fbo = self.ctx.simple_framebuffer((W * ss, H * ss), components=4)
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

    def cuadro(self, mar, campos, t, cielo, balanceo=(0.0, 0.0)):
        W, H, ss = self.W * self.ss, self.H * self.ss, self.ss
        # el barco sube y baja con el agua que tiene debajo (si no, en un temporal la cámara quedaría bajo una cresta)
        h0 = float(campos[0]['h'][0, 0] + campos[1]['h'][0, 0])
        ojo = np.array([0.0, self.altura + 0.85 * h0, 0.0], np.float32)
        pitch = math.radians(self.incl + balanceo[0])
        roll = math.radians(balanceo[1])
        dirv = np.array([0, math.sin(pitch), math.cos(pitch)], np.float32)
        arriba = (math.sin(roll), math.cos(roll), 0)
        V = mirar(ojo, ojo + dirv, arriba)
        P = perspectiva(math.radians(self.fovy), W / H, 0.5, 40000.0)
        vp = P @ V
        for k, (c, f) in enumerate(zip((mar.c1, mar.c2), campos), 1):
            self.subir(f'd{k}', np.stack([f['dx'], f['h'], f['dz'], c.espuma], -1))
            self.subir(f'n{k}', np.stack([f['sx'], f['sz'], np.zeros_like(f['h']), np.zeros_like(f['h'])], -1))
        comunes = dict(t=t, **cielo)
        self.fbo.use(); self.ctx.clear(0, 0, 0, 1)
        self.ctx.enable(moderngl.DEPTH_TEST)
        # cielo
        for k, v in dict(comunes, ivp=tuple(np.linalg.inv(vp).T.reshape(-1)), cam=tuple(ojo)).items():
            if k in self.cprog: self.cprog[k].value = v
        self.ctx.disable(moderngl.DEPTH_TEST)
        self.cvao.render(moderngl.TRIANGLE_STRIP)
        self.ctx.enable(moderngl.DEPTH_TEST)
        # mar
        pr = self.prog
        u = dict(comunes, cam=tuple(ojo), vp=tuple(vp.T.reshape(-1)), rmin=1.5, rmax=26000.0,
                 amin=-math.radians(48), amax=math.radians(48), L1=mar.c1.L, L2=mar.c2.L, hs=mar.p['hs'],
                 vientoDir=(math.cos(math.radians(mar.p['dir_viento'])), math.sin(math.radians(mar.p['dir_viento']))),
                 vetasViento=float(np.clip((mar.p['viento'] - 28) / 22.0, 0, 1)), rizo=float(np.clip(mar.p['viento'] / 25.0, 0.15, 1.2)))
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


if __name__ == '__main__':
    if sys.argv[1] == 'prueba':
        prueba(sys.argv[2])
