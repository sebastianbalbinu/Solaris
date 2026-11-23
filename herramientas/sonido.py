"""Prepara el sonido del mar: bucles sin costura y truenos, a partir de los avances HQ de Freesound."""
import json, subprocess, sys, numpy as np

SR = 48000

def leer(ruta, ini=0.0, dur=None):
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-ss", str(ini), "-i", ruta]
    if dur: cmd += ["-t", str(dur)]
    cmd += ["-f", "f32le", "-ac", "2", "-ar", str(SR), "-"]
    x = np.frombuffer(subprocess.run(cmd, capture_output=True, check=True).stdout, dtype=np.float32)
    return x.reshape(-1, 2).copy()

def escribir(x, ruta):
    p = subprocess.Popen(["ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "f32le", "-ac", "2", "-ar", str(SR), "-i", "-",
                          "-c:a", "libopus", "-b:a", "112k", ruta], stdin=subprocess.PIPE)
    p.communicate(x.astype(np.float32).tobytes())

def rms_db(x):
    return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-9)

def bucle(ruta, ini, dur, cruce, salida, nivel=-23.0):
    """Un bucle perfecto: el final se funde en el principio (potencia constante)."""
    x = leer(ruta, ini, dur)
    c = int(cruce * SR)
    y = x[: len(x) - c].copy()
    t = np.linspace(0, np.pi / 2, c)[:, None]
    y[:c] = x[:c] * np.sin(t) + x[len(x) - c:] * np.cos(t)
    y *= 10 ** ((nivel - rms_db(y)) / 20)
    y = np.clip(y, -0.98, 0.98)
    escribir(y, salida)
    return len(y) / SR

def golpe(ruta, salida, antes=1.2, despues=22.0, pico=-3.0):
    """Un trueno: desde poco antes de su momento más fuerte, y se apaga suave."""
    x = leer(ruta)
    env = np.convolve(np.abs(x).mean(axis=1), np.ones(4800) / 4800, mode="same")
    i = int(np.argmax(env))
    a = max(0, i - int(antes * SR)); b = min(len(x), i + int(despues * SR))
    y = x[a:b].copy()
    n = len(y); fi = int(0.05 * SR); fo = min(int(5 * SR), n // 2)
    y[:fi] *= np.linspace(0, 1, fi)[:, None]
    y[n - fo:] *= np.linspace(1, 0, fo)[:, None] ** 2
    y *= 10 ** (pico / 20) / (np.abs(y).max() + 1e-9)
    escribir(y, salida)
    return n / SR

if __name__ == "__main__":
    c = sys.argv[1]
    out = {}
    out["mar_calma.ogg"] = bucle(f"{c}/371169.mp3", 2, 60, 6, "sonido/mar_calma.ogg")
    out["mar_bravo.ogg"] = bucle(f"{c}/544119.mp3", 4, 118, 8, "sonido/mar_bravo.ogg")
    out["mar_temporal.ogg"] = bucle(f"{c}/841657.mp3", 10, 130, 8, "sonido/mar_temporal.ogg")
    out["lluvia.ogg"] = bucle(f"{c}/330408.mp3", 4, 120, 8, "sonido/lluvia.ogg", nivel=-24.0)
    out["trueno_lejano.ogg"] = golpe(f"{c}/367222.mp3", "sonido/trueno_lejano.ogg", antes=3.0, despues=24.0)
    out["trueno_cercano.ogg"] = golpe(f"{c}/446753.mp3", "sonido/trueno_cercano.ogg", antes=0.6, despues=20.0)
    json.dump(out, open("sonido/duraciones.json", "w"), indent=1)
    print(out)
