#!/usr/bin/env python3
"""
Immagini dell'auto disegnata (CarView, 1.0.4) dai fotogrammi del render 3D dell'animazione di ricarica.

Ingresso: la cartella render3/ della scena Blender (PNG con trasparenza, 1312x400), con
  carica-<colore>-giorno-aggancio/f_0001.png per ognuno degli 8 colori e
  carica-arctic-white-notte-aggancio/f_0001.png (fari accesi).
Uscita (in app/res/drawable-nodpi/):
  car_<colore>.webp  958x368: riquadro (177,12)-(1135,372) del fotogramma + 8 righe sotto per l'ombra
  car_roof.webp      tetto: grigio del render bianco + maschera (tetto nero opzionale)
  car_lights.webp    fari e fanali accesi (differenza notte - giorno, solo sull'auto)

Uso: python3 tools/auto_dal_render.py <cartella render3> [<cartella uscita>]
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

COLORI = ['arctic-white', 'black-pearl', 'camden-grey', 'cosmic-silver', 'ealing-green',
          'piccadilly-blue', 'dynamic-red', 'volcano-orange']
X0, Y0, X1, Y1 = 177, 12, 1135, 380
LUCI = [(185, 150, 320, 215), (1000, 90, 1130, 175), (1080, 225, 1130, 300)]   # coordinate del fotogramma


def frame(r3, colore, tema='giorno'):
    p = f'{r3}/carica-{colore}-{tema}-aggancio/f_0001.png'
    return np.asarray(Image.open(p)).astype(float)[Y0:Y1, X0:X1]


def save(arr, path, q=92):
    Image.fromarray(np.clip(np.rint(arr), 0, 255).astype(np.uint8), 'RGBA').save(path, quality=q, alpha_quality=100, method=6)


def main():
    r3 = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else 'app/res/drawable-nodpi'
    H, W = Y1 - Y0, X1 - X0
    xx = np.arange(W)[None, :]
    yy = np.arange(H)[:, None]
    side = np.clip(np.minimum(xx, W - 1 - xx) / 40.0, 0, 1)
    side = side * side * (3 - 2 * side)
    fade = np.where(yy >= 318, side, 1.0)                      # l'ombra sfuma ai lati
    fade = fade * np.where(yy >= H - 8, np.clip((H - 1 - yy) / 6.0, 0, 1), 1.0)
    frames = {}
    for c in COLORI:
        im = frame(r3, c)
        im[..., 3] *= fade
        frames[c] = im
        save(im, f'{out}/car_{c.replace("-", "_")}.webp')
    # vernice = pixel che cambiano tra i colori; tetto = vernice sopra i finestrini, dal montante anteriore in poi
    rgb = np.stack([frames[c][..., :3] for c in COLORI])
    a = frames['arctic-white'][..., 3]
    paint = (rgb.std(0).mean(-1) > 12) & (a > 200)
    roof = paint.copy()
    roof[60 - Y0:, :] = False
    roof[:, :585 - X0] = False
    lab, k = ndimage.label(roof)
    roof = lab == (np.argmax(ndimage.sum(roof, lab, range(1, k + 1))) + 1)
    rm = ndimage.gaussian_filter(roof.astype(float), 0.8)
    lum = frames['arctic-white'][..., :3] @ [0.2126, 0.7152, 0.0722]
    save(np.dstack([lum, lum, lum, rm * 255]), f'{out}/car_roof.webp')
    # luci: notte - giorno, solo sulla carrozzeria e nei riquadri di fari e fanali
    d = frame(r3, 'arctic-white')
    n = frame(r3, 'arctic-white', 'notte')
    diff = np.clip((n[..., :3] - d[..., :3]).max(-1), 0, None)
    car = ndimage.binary_erosion(n[..., 3] > 220, iterations=1)
    box = np.zeros((H, W), bool)
    for (x0, y0, x1, y1) in LUCI:
        box[y0 - Y0:y1 - Y0, x0 - X0:x1 - X0] = True
    la = ndimage.gaussian_filter(np.clip((diff - 10) / 50, 0, 1) * box * car, 0.6)
    save(np.dstack([n[..., :3], la * 255]), f'{out}/car_lights.webp', 95)
    print('fatto:', out)


if __name__ == '__main__':
    main()
