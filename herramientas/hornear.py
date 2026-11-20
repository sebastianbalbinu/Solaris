# Hornea el mar: un video en bucle sin costura por cada ánimo.
import sys, os, subprocess, numpy as np
sys.path.insert(0, '/home/claude/herr')
from agsl import render
SRC = open(sys.argv[1]).read()
OUT = sys.argv[2]
W, H, FPS, SEG, FUNDE = 540, 1200, 30, 12, 45
N = FPS * SEG
base = dict(ola=3.0, periodo=11.0, pic=0.4, nubes=0.7, vis=10.0, luz=1.0, alba=0.0, lluvia=0.0, flash=0.0, azul=0.0)
animos = {
 'calma':     dict(ola=2.4, periodo=13, pic=0.22, nubes=0.6, vis=15),
 'temporal':  dict(ola=7.0, periodo=11, pic=0.95, nubes=0.95, vis=4, lluvia=1.5),
 'niebla':    dict(ola=1.8, periodo=12, pic=0.3, nubes=0.9, vis=0.35),
 'espejo':    dict(ola=0.7, periodo=14, pic=0.04, nubes=0.35, vis=25),
 'hora_azul': dict(ola=2.5, pic=0.35, nubes=0.5, luz=0.45, alba=0.35, azul=1.0),
 'chubasco':  dict(ola=4.0, periodo=10, pic=0.75, nubes=1.0, vis=3, lluvia=5.0),
 'noche':     dict(ola=3.0, pic=0.45, nubes=0.55, luz=0.0),
}
solo = sys.argv[3:] or list(animos)
for nombre in solo:
    u = dict(base, **animos[nombre])
    def cuadro(i):
        return np.asarray(render(SRC, W, H, dict(u, t=20 + i / FPS))[0])
    ff = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
                           '-c:v', 'libx264', '-preset', 'slow', '-crf', '25', '-pix_fmt', 'yuv420p', '-profile:v', 'main',
                           '-g', str(N), '-x264-params', 'ipratio=1.05:pbratio=1.05:scenecut=0:aq-mode=1', '-movflags', '+faststart', '-an', os.path.join(OUT, f'mar_{nombre}.mp4')], stdin=subprocess.PIPE)
    primeros = [cuadro(i) for i in range(FUNDE)]
    # el clip empieza en FUNDE y termina con el fundido del final sobre el principio: el bucle no tiene costura
    for i in range(FUNDE, N):
        ff.stdin.write(cuadro(i).tobytes())
    for i in range(FUNDE):
        a = i / FUNDE
        f = cuadro(N + i).astype(np.float32) * (1 - a) + primeros[i].astype(np.float32) * a
        ff.stdin.write(np.clip(f, 0, 255).astype(np.uint8).tobytes())
    ff.stdin.close(); ff.wait()
    print(nombre, os.path.getsize(os.path.join(OUT, f'mar_{nombre}.mp4')) // 1024, 'KB', flush=True)
