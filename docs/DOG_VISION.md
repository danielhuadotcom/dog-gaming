# Designing screen colours for dogs

A screen emits three narrow-ish bands of light (one per subpixel). What a dog perceives depends on how
strongly each band excites the dog's two cone types, so palette choices should be made in *dog cone
space*, not with a human colour-blindness simulation.

## The model (`tools/dogvision.py`, mirrored in `app/src/test/.../DogVision.kt`)

| Piece | Choice | Source |
|---|---|---|
| Dog S cone | λmax 430 nm (429–435) | Neitz, Geist & Jacobs 1989 *Vis Neurosci*; Jacobs et al. 1993 *Vis Neurosci* |
| Dog L cone | λmax 555 nm | same |
| Pigment template | Govardovskii et al. 2000 A1 (α+β bands) | as used by Kasparson et al. 2013 *Proc R Soc B* |
| Lens | ≈ clear ≥ 400 nm (dog lens even passes ~61% UVA) | Douglas & Jeffery 2014 *Proc R Soc B* |
| Display primaries | Gaussians: B 450/20 nm, G 540/80 nm, R 615/60 nm (WLED IPS LCD) | typical engineering values — **not measured on your tablet** |
| Calibration | primaries scaled so RGB white = D65 for a human (CIE 1931 analytic fit) | Wyman, Sloan & Shirley 2013 |
| Adaptation | von Kries: display white → S = L = 1 | |
| Dog brightness | L-cone catch only | inference (cat/dog flicker ERG); no measured canine V(λ) |
| Dog colour | BY = (S−L)/(S+L): −1 yellow … +1 blue | |

Resulting nominal matrix (linear sRGB → dog):

    S = 0.00·R + 0.02·G + 0.98·B          (approx.)
    L = 0.22·R + 0.68·G + 0.10·B

## What it says

* **Red subpixel ≈ 22% of dog brightness, green ≈ 68%, blue ≈ 10%.** Red is a *dim yellow* to a dog; green
  is a bright yellow; they differ only in brightness.
* **Cyan/teal sit near the dog neutral point (~480 nm)** → read as washed-out grey. Avoid chromatic
  colours with |BY| small.
* **Pure blue subpixel is the bluest a dog can see on an LCD** (BY ≈ +0.8). Mixing in red/green to make
  a "nicer" blue for humans dilutes it and raises its dog-brightness.
* **Pure #FFFF00 is not the best yellow**: the broad LCD green band leaks into the S cone, so slightly
  less green (#FFE800) is yellower to the dog while staying very bright.

## Palette selection

`tools/optimize_palette.py` searches candidate colours to maximise the **worst case over 32 variants**
(S 429/435 nm; red 605/625 nm; green 530 nm·70 wide / 545 nm·100 wide; blue 445/455 nm; ± a dim-room rod
shift of the brightness channel toward 508 nm). Targets (Weber fraction for dog brightness is ~0.25,
≈2× worse than humans — Pretterer et al. 2004 *J Vis*):

| Target (worst case) | Old palette | New palette |
|---|---|---|
| critter : sky brightness ≥ 8:1 | 6.4 ✗ | ≥ 8 ✓ |
| sky BY ≥ 0.6 (clearly blue) | 0.49 ✗ | ≥ 0.6 ✓ |
| critter : bush brightness ≥ 2.5 | 2.8 ✓ | ✓ |
| bush : sky brightness ≥ 2 | 2.3 ✓ | ✓ |
| overall score (1.0 = all targets met) | 0.79 | 1.03 |

New palette: sky #000040→#0000A5 (blue subpixel only), critter #FFE800, tail #FFC800, bushes #7C7C7C,
celebration flash #1A2CFF. `PaletteTest` enforces all targets over the same grid, plus "no chromatic
colour near the neutral point".

## Other display facts

* **Refresh rate ≠ flicker on LCDs.** Dog flicker fusion is ~75–80 Hz (Coile et al. 1989), which made
  60 Hz CRT TVs flicker for dogs. LCDs are sample-and-hold, so 60 Hz doesn't flicker; higher refresh
  only smooths motion. The game still requests the panel's fastest mode.
* **Backlight PWM is the real flicker risk.** Most budget IPS tablets use DC dimming or kHz PWM (fine).
  Keep brightness moderate–high, which also keeps the dog in cone (photopic) vision.
* Behaviourally, dogs prefer colour (blue/yellow) over brightness cues when they conflict
  (Kasparson et al. 2013: 8/8 dogs). Motion and size remain the main attention grabbers; colour makes
  the target unambiguous.

## Biggest remaining uncertainty

The tablet's actual primary spectra. A ~$30 USB spectrometer reading of red/green/blue/white patches
would replace the Gaussian assumptions; plug the peaks/widths into `P` in `tools/dogvision.py` and the
grid in `DogVision.kt`, and re-run `optimize_palette.py`.
