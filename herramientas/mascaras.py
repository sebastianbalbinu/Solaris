"""
Hornea la silueta del agua de los clips de tormenta: para cada cuadro del bucle y cada una de 90 columnas,
la fila más alta que es agua (0..65535 sobre el alto del cuadro). La app la usa para que los rayos queden
detrás de las crestas y el reflejo caiga solo sobre el agua.
Uso: python3 mascaras.py SOMBREADOR SALIDA estado...
"""
import sys, os, json, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agsl import render
src = open(sys.argv[1]).read()
OUT = sys.argv[2]
ini = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'hornear.py')).read()
bloque = ini[ini.index('W, H, FPS, SEG'):ini.index('VECINOS = [')]
exec(bloque)
W, H, COL = 450, 1000, 90
# el bucle va a FPS (con su tiempo más lento); la máscara se guarda a 30 cuadros por segundo de video
K = SEG * 30
MASC = src.replace('    if (!agua) {\n', '    if (agua) { return half4(1.0, 1.0, 1.0, 1.0); } else { return half4(0.0, 0.0, 0.0, 1.0); }\n    if (!agua) {\n', 1)
for nombre in sys.argv[3:]:
    u = dict(BASE, **ESTADOS[nombre])
    filas = np.zeros((K, COL), np.uint16)
    for i in range(K):
        k = i * FPS // 30
        # el cuadro k del bucle: los últimos FUNDE se mezclan con el principio; se toma el que pesa más
        if k < N - FUNDE: c = FUNDE + k
        else:
            j = k - (N - FUNDE)
            c = N + j if j < FUNDE / 2 else j
        m = np.asarray(render(MASC, W, H, dict(u, t=T0 + c / FPS * LENTO))[0])[..., 0] > 127
        cols = m.reshape(H, COL, W // COL).any(axis=2)
        primero = np.where(cols.any(axis=0), cols.argmax(axis=0), H)
        filas[i] = (primero / H * 65535).astype(np.uint16)
    ruta = os.path.join(OUT, f'{nombre}.mascara')
    filas.astype('<u2').tofile(ruta)
    print(nombre, os.path.getsize(ruta), flush=True)
