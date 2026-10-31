"""Dog-vision model for an RGB display.

Display primaries are modelled as Gaussians (WLED-backlit IPS LCD), scaled so RGB
white (1,1,1) is D65 for a human observer (via the Wyman-Sloan-Shirley 2013 analytic
CIE 1931 colour-matching-function fit). Dog cone fundamentals use the Govardovskii
et al. (2000) A1 visual-pigment template. Excitations are von Kries-normalised so the
display's white gives S = L = 1 (dog adapted to the screen).

Outputs, per sRGB colour:
  L  : long-wave cone excitation  (~ dog brightness under photopic viewing)
  S  : short-wave cone excitation
  BY : blue-yellow opponent coordinate (S-L)/(S+L), -1 = yellowest, +1 = bluest
"""
import math, sys, json

# Parameters per literature review (see docs/DOG_VISION.md):
#  S 430 nm (429-435), L 555 nm: Neitz et al. 1989; Jacobs et al. 1993. Template: Govardovskii 2000.
#  Lens ~clear >=400 nm (Douglas & Jeffery 2014). Primaries: typical WLED IPS LCD (unverified norms).
P = dict(S_max=430.0, L_max=555.0,
         prim={"R": (615.0, 60.0), "G": (540.0, 80.0), "B": (450.0, 20.0)})

WL = [380 + i for i in range(0, 401)]  # 380..780 nm, 1 nm

def gov(lmax, l):
    x = lmax / l
    a = 0.8795 + 0.0459 * math.exp(-(lmax - 300) ** 2 / 11940)
    alpha = 1 / (math.exp(69.7 * (a - x)) + math.exp(28 * (0.922 - x)) + math.exp(-14.9 * (1.104 - x)) + 0.674)
    lb = 189 + 0.315 * lmax; bb = -40.5 + 0.195 * lmax
    return alpha + 0.26 * math.exp(-((l - lb) / bb) ** 2)

def g(x, mu, s1, s2):
    t = (x - mu) / (s1 if x < mu else s2)
    return math.exp(-0.5 * t * t)

def xyz_bar(l):  # Wyman, Sloan & Shirley 2013, multi-lobe fit to CIE 1931 2°
    x = 1.056 * g(l, 599.8, 37.9, 31.0) + 0.362 * g(l, 442.0, 16.0, 26.7) - 0.065 * g(l, 501.1, 20.4, 26.2)
    y = 0.821 * g(l, 568.8, 46.9, 40.5) + 0.286 * g(l, 530.9, 16.3, 31.1)
    z = 1.217 * g(l, 437.0, 11.8, 36.0) + 0.681 * g(l, 459.0, 26.0, 13.8)
    return x, y, z

def spd(mu, fwhm):
    s = fwhm / 2.3548
    return [math.exp(-0.5 * ((l - mu) / s) ** 2) for l in WL]

def build(p=P):
    prims = {k: spd(*v) for k, v in p["prim"].items()}
    # XYZ of each unit primary
    xyz = {k: [sum(sp[i] * xyz_bar(WL[i])[c] for i in range(len(WL))) for c in range(3)] for k, sp in prims.items()}
    # Solve scale factors so R+G+B = D65 white (Y=1)
    import itertools
    M = [[xyz[k][c] for k in "RGB"] for c in range(3)]
    W = [0.95047, 1.0, 1.08883]
    def solve(A, b):
        A = [row[:] + [b[i]] for i, row in enumerate(A)]
        for i in range(3):
            piv = max(range(i, 3), key=lambda r: abs(A[r][i])); A[i], A[piv] = A[piv], A[i]
            for r in range(3):
                if r != i:
                    f = A[r][i] / A[i][i]
                    A[r] = [A[r][j] - f * A[i][j] for j in range(4)]
        return [A[i][3] / A[i][i] for i in range(3)]
    k = dict(zip("RGB", solve(M, W)))
    S = [gov(p["S_max"], l) for l in WL]; L = [gov(p["L_max"], l) for l in WL]
    dog = {c: (k[c] * sum(prims[c][i] * S[i] for i in range(len(WL))),
               k[c] * sum(prims[c][i] * L[i] for i in range(len(WL)))) for c in "RGB"}
    sw = sum(dog[c][0] for c in "RGB"); lw = sum(dog[c][1] for c in "RGB")
    return {c: (dog[c][0] / sw, dog[c][1] / lw) for c in "RGB"}, k

def lin(v):
    v /= 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4

def dog_of(hexrgb, m):
    r, g_, b = (lin(int(hexrgb[i:i + 2], 16)) for i in (0, 2, 4))
    S = m["R"][0] * r + m["G"][0] * g_ + m["B"][0] * b
    L = m["R"][1] * r + m["G"][1] * g_ + m["B"][1] * b
    by = (S - L) / (S + L) if S + L > 0 else 0.0
    return S, L, by

if __name__ == "__main__":
    m, k = build()
    print("RGB->dog matrix (rows S,L; cols R,G,B):")
    print("  S:", " ".join(f"{m[c][0]:.4f}" for c in "RGB"))
    print("  L:", " ".join(f"{m[c][1]:.4f}" for c in "RGB"))
    print("primary radiance scale:", {c: round(v, 4) for c, v in k.items()})
    for name, hx in [a.split("=") for a in sys.argv[1:]]:
        S, L, by = dog_of(hx, m)
        print(f"{name:14s} #{hx}  S={S:.3f}  L={L:.3f}  BY={by:+.2f}")
