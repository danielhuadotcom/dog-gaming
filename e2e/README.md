# Peekaboo e2e (emulator)

Black-box checks against a running emulator (`emulator-5554`, API 29, 1280x800 landscape).
Needs `adb root` (done automatically) so taps can be injected by writing raw evdev events
(`input tap` takes ~10 s on a software emulator).

    cd e2e
    ./test_main.sh                     # visuals (1a-1e) + audio (2a-2c); builds + installs debug apk
    SKIP_BUILD=1 ./test_robustness.sh  # mashing, pause/resume, orientation, crash buffer, FPS
    HOST_WAV=/path/to/emu_audio.wav SKIP_BUILD=1 ./test_main.sh   # also check the emulator's host capture

Env: `OUT` (artifacts, default `e2e/out`), `SHOTS` (screenshots, default `$OUT/shots`),
`ADB`, `SERIAL`, `SKIP_BUILD=1`, `HOST_WAV` (the file given to the emulator via
`QEMU_AUDIO_DRV=wav QEMU_WAV_PATH=...`; it is written live, header sizes stay 0 until exit).

Each script prints a PASS/FAIL table and exits non-zero on any failure.

Tools (usable on their own):
- `imgcheck.py MODE png...`: `palette` (dog-vision hue classes), `training`, `visible`, `hidden`,
  `celebrate`, `rest`, `wild`, `stats`.
- `audiocheck.py app audio_out.wav logcat.txt`: finds every PLAY'd sound in the app's mixer
  dump by template cross-correlation, checks frequencies (squeak sweep, chirp band, celebrate
  and all-done notes), clipping, silence, and that the counts match the log.
  `audiocheck.py host emu_audio.wav audio_out.wav logcat.txt` does the same on the host capture.
- `phaseshot.py PHASE out.png`: grabs a host-side screenshot (`adb emu screenrecord screenshot`,
  ~0.25 s) while the game is in PHASE, checked against logcat timing.
- `tapshot.py dir`: tap, then film the celebration.

Emulator quirks this works around:
- the display lags the game logic by ~0.5-1.5 s, so timed shots wait 1+ s into a phase;
- hammering screenshots can make HWUI log `dequeueBuffer failed ... switching to fallback`, after
  which the SurfaceView stops updating (`launch_ok` relaunches when that happens);
- audio underruns: the stream runs slower than wall clock, so events are matched to
  logcat with a piecewise-linear time map instead of a fixed offset.
