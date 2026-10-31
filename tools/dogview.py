"""Render an image as a dog would roughly perceive it on an LCD (nominal model).
Dog S/L cone catches are re-displayed for humans as  R=G=L, B=S  (linear light), so
L-only = yellow, S-only = blue, both = white. Optional palette remap for previews."""
import sys, numpy as np
from PIL import Image
import dogvision as dv

def dog_image(img):
    m, _ = dv.build()
    a = np.asarray(img.convert("RGB")).astype(np.float64) / 255.0
    lin = np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)
    S = sum(m[c][0] * lin[..., i] for i, c in enumerate("RGB"))
    L = sum(m[c][1] * lin[..., i] for i, c in enumerate("RGB"))
    out = np.stack([L, L, S], -1).clip(0, 1)
    out = np.where(out <= 0.0031308, out * 12.92, 1.055 * out ** (1 / 2.4) - 0.055)
    return Image.fromarray((out * 255).astype(np.uint8))

def remap(img, pairs, tol=40):
    a = np.asarray(img.convert("RGB")).astype(np.int32)
    out = a.copy()
    for src, dst in pairs:
        s = np.array([int(src[i:i + 2], 16) for i in (0, 2, 4)]); d = np.array([int(dst[i:i + 2], 16) for i in (0, 2, 4)])
        mask = np.abs(a - s).sum(-1) < tol
        out[mask] = d
    return Image.fromarray(out.astype(np.uint8))

if __name__ == "__main__":
    dog_image(Image.open(sys.argv[1])).save(sys.argv[2])
