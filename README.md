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

- **The dog** just touches the screen (anywhere) or presses any big button while the critter is out.
- **Training level:** easy appearances that hide and re-pop every few seconds on their own, so a dog that
  isn't booping yet still keeps seeing fresh critters and real footage.
- **Levels 2+:** rustle → peek → start-stop darting → hides again; shorter windows, more bushes and
  fake-out rustles as the dog improves. Auto-level keeps the dog winning ~70–80% (can be switched off).
- **Real footage:** Creative Commons squirrel/rabbit/raccoon clips with real squirrel/chipmunk calls,
  credited on screen. The first appearance is footage as soon as a clip has loaded.
- **No penalty buzzer.** At higher levels, mashing just quietly delays the next peek.

## Trainer controls (nothing is locked)

Press **Back** (or tap the ⚙ in the top-left corner) to open the trainer panel; the game pauses.

- **Settings** (tap to cycle): cartoon / mixed / footage-only · level · auto-adjust on/off ·
  session 2/5/10 min/unlimited · rest after session off/5/10 min (default off) · treat screen
  1.5/3.5/6 s · tap dismisses treat screen · training re-pop interval · animal sounds on/off.
- **Do it now:** show critter · show real footage · **mark a win** (for shaping: reward a look or a
  nose-touch) · skip treat screen · restart session · reset level · exit.

### Suggested first sessions
1. Footage only, Training level, treat screen 3.5 s. Sit with the dog.
2. Hit **Mark a win** the moment the dog *looks* at the screen; hand a treat on the squeak.
3. Then only for moving toward it, then only for touching it. Soon the squeak itself means "treat!".

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
