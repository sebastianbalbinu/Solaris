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
bloque = ini[ini.index('BASE = dict('):ini.index('VECINOS = [')]
exec(bloque)
W, H, FPS, N, FUNDE, T0, COL = 450, 1000, 30, 360, 45, 20.0, 90
MASC = src.replace('    if (!agua) {\n', '    if (agua) { return half4(1.0, 1.0, 1.0, 1.0); } else { return half4(0.0, 0.0, 0.0, 1.0); }\n    if (!agua) {\n', 1)
for nombre in sys.argv[3:]:
    u = dict(BASE, **ESTADOS[nombre])
    filas = np.zeros((N, COL), np.uint16)
    for i in range(N):
        # el cuadro i del bucle: los últimos FUNDE se mezclan con el principio; se toma el que pesa más
        if i < N - FUNDE: t = T0 + (FUNDE + i) / FPS
        else:
            j = i - (N - FUNDE)
            t = T0 + (N + j) / FPS if j < FUNDE / 2 else T0 + j / FPS
        m = np.asarray(render(MASC, W, H, dict(u, t=t))[0])[..., 0] > 127
        cols = m.reshape(H, COL, W // COL).any(axis=2)
        primero = np.where(cols.any(axis=0), cols.argmax(axis=0), H)
        filas[i] = (primero / H * 65535).astype(np.uint16)
    ruta = os.path.join(OUT, f'{nombre}.mascara')
    filas.astype('<u2').tofile(ruta)
    print(nombre, os.path.getsize(ruta), flush=True)
