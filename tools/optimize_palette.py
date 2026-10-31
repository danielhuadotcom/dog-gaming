"""Pick sky/critter/bush colours that maximise worst-case dog contrast across plausible
display spectra (primary peaks/widths) and eye parameters (S 429-435 nm, dim-room rod shift)."""
import itertools, dogvision as dv

GRID = list(itertools.product([429, 435], [605, 625], [(530, 70), (545, 100)], [445, 455], [False, True]))
MODELS = []
for S, rmu, (gmu, gw), bmu, rod in GRID:
    P = dict(S_max=S, L_max=530.0 if rod else 555.0, prim={"R": (rmu, 60.0), "G": (gmu, gw), "B": (bmu, 20.0)})
    MODELS.append(dv.build(P)[0])

def h(r, g, b): return f"{r:02X}{g:02X}{b:02X}"

def score(crit, sky, bush):
    worst = 1e9
    for m in MODELS:
        c, s, u = dv.dog_of(crit, m), dv.dog_of(sky, m), dv.dog_of(bush, m)
        # Each term normalised so 1.0 = our minimum target.
        terms = [
            (c[1] / s[1]) / 8.0,          # critter vs sky brightness >= 8:1
            (s[2] - c[2]) / 1.3,          # blue-yellow separation
            (c[1] / u[1]) / 2.5,          # critter vs bush brightness
            (u[1] / s[1]) / 2.0,          # bush vs sky brightness
            s[2] / 0.6,                   # sky is clearly blue to a dog
        ]
        worst = min(worst, min(terms))
    return worst

best = []
for crit in ["FFFF00", "FFF200", "FFE800", "FFD60A"]:
    for B in range(120, 256, 15):
        for G in (0, 8, 16, 32):
            for R in (0, 8, 16):
                sky = h(R, G, B)
                for gv in (0x70, 0x7C, 0x88, 0x96):
                    bush = h(gv, gv, gv)
                    best.append((score(crit, sky, bush), crit, sky, bush))
best.sort(reverse=True)
for row in best[:8]: print(f"{row[0]:.3f}  critter #{row[1]}  skyBottom #{row[2]}  bush #{row[3]}")
print("current:", f"{score('FFD60A', '1F3C96', '7C7C7C'):.3f}")
