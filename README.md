# Peekaboo Squirrel — a video game for dogs

A critter hides behind bushes, rustles, peeks out and darts around. The dog boops the screen
(anywhere) or presses any big button while it's visible → squeak, celebration, and an on-screen
**TREAT TIME!** cue for the human. Pure Kotlin, zero dependencies, ~2 MB APK, built for budget tablets.

## Install

Grab `app-release.apk` (or build it), enable *Install unknown apps* for your browser/file manager,
and open the APK. Lay the tablet flat on the floor at full-ish brightness.

```bash
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

## How it plays

- **Level 1 (training):** the critter just sits there; any boop wins. Teaches "boop = treat".
- **Levels 2+:** rustle → peek → start-stop darting → hides again. Shorter windows, more bushes and
  fake-out rustles as the dog improves. Adaptive difficulty keeps the dog winning ~70–80%.
- **No penalty buzzer.** At higher levels, mashing just quietly delays the next peek.
- **Wild Mode:** about half of appearances use real Creative Commons footage of squirrels, a rabbit
  and a raccoon (credited on screen).
- **Sessions:** 4 minutes, then a calm "all done" tone, dim rest screen and 10-minute cooldown.
  Owner override: hold the top-right corner for 3 s.
- **Input:** touch, any keyboard/USB/Bluetooth button (Bluetooth camera shutters work), volume-up.
  Back exits.

## Designed for dog eyes and ears

Colours are chosen with a dog-cone model of an LCD (S 430 nm / L 555 nm cones × panel primaries),
optimised for worst-case contrast across plausible panels — see [docs/DOG_VISION.md](docs/DOG_VISION.md).
Sounds are matched to measured squirrel/chipmunk/mouse/leaf-litter spectra, the dog audiogram and tablet
speaker limits — see [docs/DOG_HEARING.md](docs/DOG_HEARING.md).
Sounds are synthesized squeaky-toy squeaks, chirps, leaf rustles and claw patter (no doorbells/barks).

## Testing

- `./gradlew testDebugUnitTest` — game logic, adaptive difficulty, audio spectra, dog-vision palette.
- `e2e/` — emulator end-to-end scripts: screenshot palette/visibility checks, audio dump spectral
  checks, system audio routing, robustness. See [e2e/README.md](e2e/README.md).

## Licences

Code: MIT (see `LICENSE`). Wildlife clips in `app/src/main/res/raw/wild_*.mp4` remain under their own
licences (CC BY 4.0, CC BY-SA 4.0, CC0) — see [WILDLIFE_CREDITS.md](WILDLIFE_CREDITS.md).
