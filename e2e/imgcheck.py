#!/usr/bin/env python3
"""Screenshot checks for Peekaboo Squirrel.

usage: imgcheck.py MODE FILE [FILE...]
modes: palette | training | celebrate | hidden | visible | rest | wild | stats
Prints one JSON line per file and exits non-zero if any file fails.
Colours mirror app/src/main/java/dev/peekaboo/squirrel/Palette.kt.
"""
import json
import sys

import numpy as np
from PIL import Image

HEX = dict(
    SKY_TOP=0x0B1A4A, SKY_BOTTOM=0x1F3C96, SKY_FLASH=0x3A62D8, GROUND=0x0A1433,
    BUSH=0x7C7C7C, BUSH_SHADE=0x5A5A5A, CRITTER=0xFFD60A, CRITTER_TAIL=0xFFC300,
    CRITTER_DETAIL=0x0A1433, SPARK=0xFFE866, HUD=0x8C9BC7, REST_BG=0x060B22, MOON=0x9A9A9A,
)
C = {k: np.array([(v >> 16) & 255, (v >> 8) & 255, v & 255], float) for k, v in HEX.items()}


def load(path):
    return np.asarray(Image.open(path).convert("RGB")).astype(float)


def near(img, name, tol):
    return np.sqrt(((img - C[name]) ** 2).sum(-1)) <= tol


def hsv(img):
    r, g, b = img[..., 0] / 255, img[..., 1] / 255, img[..., 2] / 255
    mx = np.max(img, -1) / 255
    mn = np.min(img, -1) / 255
    d = mx - mn
    s = np.where(mx > 0, d / np.where(mx > 0, mx, 1), 0)
    dd = np.where(d == 0, 1, d)
    h = np.where(mx == r, ((g - b) / dd) % 6, np.where(mx == g, (b - r) / dd + 2, (r - g) / dd + 4)) * 60
    h = np.where(d == 0, 0, h)
    return h, s, mx


def palette(img):
    h, s, v = hsv(img)
    grey = (s < 0.12) | (v < 0.05)  # near-black has no meaningful hue
    blue = ~grey & (h >= 200) & (h <= 250)
    yellow = ~grey & (h >= 40) & (h <= 60)
    other = ~(grey | blue | yellow)
    n = img.shape[0] * img.shape[1]
    # "red/green regions": count off-palette pixels that are clearly saturated
    sat_other = other & (s > 0.3) & (v > 0.2)
    oh = h[sat_other]
    return dict(
        blue=round(100 * blue.sum() / n, 3), yellow=round(100 * yellow.sum() / n, 3),
        grey=round(100 * grey.sum() / n, 3), other=round(100 * other.sum() / n, 3),
        sat_other=round(100 * sat_other.sum() / n, 3),
        red_px=int(((oh < 20) | (oh > 330)).sum()), green_px=int(((oh > 70) & (oh < 170)).sum()),
        ok=bool(other.sum() / n < 0.02),
    )


def critter_mask(img):
    return near(img, "CRITTER", 45) | near(img, "CRITTER_TAIL", 45)


def hud_free(mask, img):
    h, w = img.shape[:2]
    m = mask.copy()
    m[: int(h * 0.1), : int(w * 0.3)] = False  # HUD text (top-left)
    return m


def centroid(mask):
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    return float(xs.mean()), float(ys.mean())


def sky_expected(y, h):
    t = y / h
    return C["SKY_TOP"] + (C["SKY_BOTTOM"] - C["SKY_TOP"]) * t


def check(mode, path):
    img = load(path)
    h, w = img.shape[:2]
    r = dict(file=path, mode=mode, size=[w, h])
    if mode in ("palette", "stats"):
        r.update(palette(img))
        if mode == "stats":
            r["ok"] = True
        return r
    if mode in ("training", "visible"):
        crit = hud_free(near(img, "CRITTER", 10), img)
        tail = hud_free(near(img, "CRITTER_TAIL", 10), img)
        c = centroid(crit | tail)
        r.update(critter_px=int(crit.sum()), tail_px=int(tail.sum()),
                 centroid=[round(c[0] / w, 3), round(c[1] / h, 3)] if c else None)
        ok = crit.sum() + tail.sum() > 3000 and tail.sum() > 300 and c is not None
        if mode == "training":
            bush = near(img, "BUSH", 12).sum()
            ground = near(img[int(h * 0.82):, :], "GROUND", 14).mean()
            # sky samples on the right edge (away from bushes/critter/HUD)
            xs = slice(int(w * 0.93), int(w * 0.99))
            top = img[2:8, xs].reshape(-1, 3).mean(0)
            low = img[int(h * 0.72):int(h * 0.74), xs].reshape(-1, 3).mean(0)
            et, el = sky_expected(5, h), sky_expected(h * 0.73, h)
            dt, dl = float(np.abs(top - et).max()), float(np.abs(low - el).max())
            r.update(bush_px=int(bush), ground_frac=round(float(ground), 3),
                     sky_top=[int(x) for x in top], sky_low=[int(x) for x in low],
                     sky_err=[round(dt, 1), round(dl, 1)])
            ok = ok and 0.1 < c[0] / w < 0.9 and 0.45 < c[1] / h < 0.85 and bush > 5000 \
                and ground > 0.9 and dt < 14 and dl < 14 and low[2] > top[2] + 40
        r["ok"] = bool(ok)
        return r
    if mode == "celebrate":
        cue = near(img[: int(h * 0.3), int(w * 0.35): int(w * 0.65)], "CRITTER", 40).sum()
        spark = near(img, "SPARK", 60) & ~near(img, "CRITTER", 25)
        spark = hud_free(spark, img)
        spark[: int(h * 0.3), int(w * 0.35): int(w * 0.65)] = False  # exclude cue area
        crit = hud_free(critter_mask(img), img).sum()
        r.update(cue_px=int(cue), spark_px=int(spark.sum()), critter_px=int(crit))
        r["ok"] = bool(cue > 1500 and spark.sum() > 100)
        return r
    if mode == "hidden":
        m = hud_free(critter_mask(img) | near(img, "SPARK", 40), img)
        r.update(critter_px=int(m.sum()), bush_px=int(near(img, "BUSH", 12).sum()))
        r["ok"] = bool(m.sum() < 50)
        return r
    if mode == "rest":
        bg = near(img, "REST_BG", 10).mean()
        moon = near(img, "MOON", 14)
        c = centroid(moon)
        yel = critter_mask(img).sum()
        r.update(rest_bg_frac=round(float(bg), 3), moon_px=int(moon.sum()),
                 moon_centroid=[round(c[0] / w, 3), round(c[1] / h, 3)] if c else None,
                 yellow_px=int(yel))
        r["ok"] = bool(bg > 0.85 and moon.sum() > 1500 and c and abs(c[0] / w - 0.5) < 0.1
                       and abs(c[1] / h - 0.38) < 0.1 and yel < 50)
        return r
    if mode == "wild":
        p = palette(img)
        q = (img // 8).astype(int)
        uniq = len(np.unique(q[..., 0] * 1024 + q[..., 1] * 32 + q[..., 2]))
        pal = np.zeros(img.shape[:2], bool)
        for k in C:
            pal |= near(img, k, 10)
        # the sky gradient is not a single palette colour; include it
        sky = C["SKY_TOP"][None, :] + (C["SKY_BOTTOM"] - C["SKY_TOP"])[None, :] * (np.arange(h) / h)[:, None]
        pal |= np.abs(img - sky[:, None, :]).max(-1) < 10
        r.update(palette_match_frac=round(float(pal.mean()), 3), colour_bins=uniq, **{
            "off_dog_palette_pct": p["other"]})
        r["ok"] = bool(pal.mean() < 0.5 and uniq > 800)
        return r
    raise SystemExit(f"unknown mode {mode}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    mode = sys.argv[1]
    ok = True
    for f in sys.argv[2:]:
        r = check(mode, f)
        ok &= r["ok"]
        print(json.dumps(r))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
