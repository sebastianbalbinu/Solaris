"""
Hornea el mar de Solaris: un bucle de 12 s por estado y una transición de 6 s por cada par de vecinos.
Todos comparten la misma base de tiempo, así que:
  - un bucle empieza en t=21.5 y su final se funde con su principio (costura invisible);
  - una transición empieza en t=21.5 con los parámetros del estado A (igual que el principio del bucle A)
    y termina en t=27.5 con los de B, que es exactamente el segundo 6 del bucle B.
Así la app encadena: bucle A → transición → bucle B desde el segundo 6 → bucle B entero, sin un solo corte.
Los bucles llevan una imagen clave cada 3 s para que se pueda empezar a mitad sin saltos.
Uso: python3 hornear.py SOMBREADOR SALIDA [solo-estos...]
"""
import sys, os, subprocess, json, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agsl import render

SRC = open(sys.argv[1]).read()
OUT = sys.argv[2]
SOLO = set(sys.argv[3:])
W, H, FPS, SEG = 450, 1000, 60, 12
FUNDE = int(1.5 * FPS)
TR = 6 * FPS
# el sueño va un poco más lento que el mar de verdad
LENTO = 0.82
N = FPS * SEG
T0 = 20.0

BASE = dict(sueno=1.0, bancos=0.6, ola=3.0, periodo=11.0, pic=0.4, nubes=0.7, vis=10.0, luz=1.0, alba=0.0, lluvia=0.0, flash=0.0,
            azul=0.0, solx=0.62, solh=0.1, luna=0.0, nieve=0.0, aurora=0.0)
ESTADOS = {
    'calma':          dict(ola=2.4, periodo=13, pic=0.22, nubes=0.6, vis=15),
    'espejo':         dict(bancos=0.8, ola=0.7, periodo=14, pic=0.04, nubes=0.35, vis=25),
    'mar_de_fondo':   dict(ola=6.0, periodo=15, pic=0.08, nubes=0.45, vis=22),
    'mar_bravo':      dict(bancos=0.4, ola=7.0, periodo=11, pic=0.8, nubes=0.85, vis=8),
    'temporal':       dict(bancos=0.25, ola=10.0, periodo=12, pic=0.95, nubes=0.95, vis=4, lluvia=1.5),
    'chubasco':       dict(bancos=0.3, ola=5.0, periodo=10, pic=0.8, nubes=1.0, vis=2.5, lluvia=5.0),
    'niebla':         dict(bancos=1.0, ola=1.8, periodo=12, pic=0.3, nubes=0.9, vis=0.35),
    'nevada':         dict(bancos=0.5, ola=3.0, periodo=11, pic=0.4, nubes=0.95, vis=2.0, nieve=1.0, luz=0.8),
    'hora_azul':      dict(bancos=0.85, ola=2.5, pic=0.35, nubes=0.5, luz=0.45, alba=0.35, azul=1.0, solh=-0.02),
    'alba':           dict(bancos=0.9, luz=0.55, alba=1.0, solx=0.72, solh=0.02, nubes=0.45, ola=2.5, pic=0.3, vis=20),
    'ocaso':          dict(luz=0.4, alba=1.0, solx=0.3, solh=-0.004, nubes=0.65, azul=0.5, ola=3.0, pic=0.35),
    'noche':          dict(ola=3.0, pic=0.45, nubes=0.55, luz=0.0),
    'luna_llena':     dict(luz=0.0, luna=1.0, solx=0.55, solh=0.16, nubes=0.3, ola=2.6, pic=0.3, vis=18),
    'aurora':         dict(luz=0.0, aurora=1.0, nubes=0.2, ola=2.0, pic=0.25, vis=25),
    'temporal_noche': dict(bancos=0.25, ola=9.0, periodo=12, pic=0.9, nubes=0.95, vis=4, lluvia=1.5, luz=0.0),
}
VECINOS = [
    ('espejo', 'calma'), ('calma', 'mar_de_fondo'), ('calma', 'mar_bravo'), ('mar_de_fondo', 'mar_bravo'),
    ('mar_bravo', 'temporal'), ('temporal', 'chubasco'), ('chubasco', 'mar_bravo'),
    ('calma', 'niebla'), ('niebla', 'nevada'), ('nevada', 'mar_bravo'),
    ('calma', 'hora_azul'), ('hora_azul', 'noche'), ('calma', 'alba'), ('noche', 'alba'),
    ('calma', 'ocaso'), ('ocaso', 'noche'), ('noche', 'luna_llena'), ('noche', 'aurora'), ('noche', 'temporal_noche'),
]

def params(nombre): return dict(BASE, **ESTADOS[nombre])
def cuadro(u, k): return np.asarray(render(SRC, W, H, dict(u, t=tiempo(k)))[0])
def tiempo(k): return T0 + k / FPS * LENTO

def codificador(ruta, gop):
    return subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}',
        '-r', str(FPS), '-i', '-', '-c:v', 'libx264', '-preset', 'slow', '-crf', '26', '-pix_fmt', 'yuv420p',
        '-profile:v', 'main', '-g', str(gop), '-x264-params', 'ipratio=1.05:pbratio=1.05:scenecut=0:aq-mode=1',
        '-movflags', '+faststart', '-an', ruta], stdin=subprocess.PIPE)

def bucle(nombre):
    ruta = os.path.join(OUT, f'{nombre}.mp4')
    if os.path.exists(ruta): return
    u = params(nombre)
    ff = codificador(ruta + '.tmp.mp4', 3 * FPS)
    primeros = [cuadro(u, i) for i in range(FUNDE)]
    for i in range(FUNDE, N):
        ff.stdin.write(cuadro(u, i).tobytes())
    for i in range(FUNDE):
        a = i / FUNDE
        f = cuadro(u, N + i).astype(np.float32) * (1 - a) + primeros[i].astype(np.float32) * a
        ff.stdin.write(np.clip(f, 0, 255).astype(np.uint8).tobytes())
    ff.stdin.close(); ff.wait(); os.rename(ruta + '.tmp.mp4', ruta)
    print('bucle', nombre, os.path.getsize(ruta) // 1024, 'KB', flush=True)

def transicion(a, b):
    ruta = os.path.join(OUT, f'{a}__{b}.mp4')
    if os.path.exists(ruta): return
    ua, ub = params(a), params(b)
    ff = codificador(ruta + '.tmp.mp4', TR)
    for j in range(TR):
        k = j / (TR - 1); k = k * k * (3 - 2 * k)
        u = {x: ua[x] * (1 - k) + ub[x] * k for x in ua}
        ff.stdin.write(cuadro(u, FUNDE + j).tobytes())
    ff.stdin.close(); ff.wait(); os.rename(ruta + '.tmp.mp4', ruta)
    print('transicion', a, '→', b, os.path.getsize(ruta) // 1024, 'KB', flush=True)

os.makedirs(OUT, exist_ok=True)
for e in ESTADOS:
    if not SOLO or e in SOLO: bucle(e)
for a, b in VECINOS:
    for x, y in ((a, b), (b, a)):
        if not SOLO or (x in SOLO and y in SOLO): transicion(x, y)

indice = {
    'version': 2, 'fps': FPS, 'segundos': SEG, 'entrada_ms': TR * 1000 // FPS,
    'estados': {e: f'{e}.mp4' for e in ESTADOS},
    'transiciones': {f'{x}>{y}': f'{x}__{y}.mp4' for a, b in VECINOS for x, y in ((a, b), (b, a))},
    'noche': ['noche', 'luna_llena', 'aurora', 'temporal_noche'],
    'mascaras': {e: f'{e}.mascara' for e in ('temporal', 'temporal_noche', 'chubasco')},
    'mascara_columnas': 90,
}
indice['bytes'] = {f: os.path.getsize(os.path.join(OUT, f)) for f in list(indice['estados'].values()) + list(indice['transiciones'].values()) + list(indice['mascaras'].values()) if os.path.exists(os.path.join(OUT, f))}
json.dump(indice, open(os.path.join(OUT, 'indice.json'), 'w'), ensure_ascii=False, indent=1)
print('listo', flush=True)
