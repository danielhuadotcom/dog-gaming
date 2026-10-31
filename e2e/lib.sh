# Shared helpers for the Peekaboo e2e scripts. Source me.
# Env overrides: ADB, SERIAL, OUT (artifact dir), SHOTS (screenshot dir).
set -u
E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$E2E_DIR/.." && pwd)"
ADB_BIN="${ADB:-/opt/android-sdk/platform-tools/adb}"
SERIAL="${SERIAL:-emulator-5554}"
PKG=dev.peekaboo.squirrel
ACT=$PKG/.MainActivity
OUT="${OUT:-$E2E_DIR/out}"
SHOTS="${SHOTS:-$OUT/shots}"
mkdir -p "$OUT" "$SHOTS"
PY="python3 -I"

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }
log() { echo "[$(date +%T)] $*" >&2; }

PASS=0; FAIL=0; RESULTS=()
check() { # check NAME CONDITION_EXIT_CODE DETAIL
  local name=$1 rc=$2 detail=${3:-}
  if [ "$rc" -eq 0 ]; then PASS=$((PASS+1)); RESULTS+=("PASS  $name  $detail")
  else FAIL=$((FAIL+1)); RESULTS+=("FAIL  $name  $detail"); fi
  log "$( [ "$rc" -eq 0 ] && echo PASS || echo FAIL ) $name $detail"
}
summary() {
  echo "==================== $(basename "$0") ===================="
  printf '%s\n' "${RESULTS[@]}"
  echo "pass=$PASS fail=$FAIL"
  [ "$FAIL" -eq 0 ]
}

# Make the (slow) emulator deterministic enough for screenshots.
prep_device() {
  adb wait-for-device
  # root is needed for fast evdev taps (writing /dev/input/event1)
  if [ "$(adb shell id -u | tr -d '\r')" != 0 ]; then adb root >/dev/null; sleep 3; adb wait-for-device; fi
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global $k 0 >/dev/null 2>&1
  done
  adb shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1
  adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
  adb shell media volume --stream 3 --set 15 >/dev/null 2>&1 || true
}

build_and_install() {
  local apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  if [ "${SKIP_BUILD:-0}" != 1 ]; then
    local i
    for i in 1 2 3; do
      (cd "$ROOT" && ./gradlew -q assembleDebug) && break
      log "build failed (concurrent edit?), retry in 60s"; sleep 60
    done
  fi
  adb install -r -t "$apk" >/dev/null
}

# Whole tagged log of the current run.
glog() { adb logcat -d -v time -s PeekabooGame:I PeekabooAudio:I; }

# launch [extra am args...]  (logcat is cleared first)
launch() {
  adb logcat -c
  adb shell am start -S -n "$ACT" "$@" >/dev/null
  wait_log "SURFACE" 120 || { log "no SURFACE line"; return 1; }
  wait_log "FPS" 120 || true
}

# wait_log REGEX TIMEOUT_S [MIN_COUNT]  -> 0 when REGEX matched >= MIN_COUNT lines
wait_log() {
  local re=$1 to=$2 n=${3:-1} t0=$SECONDS
  while [ $((SECONDS - t0)) -lt "$to" ]; do
    [ "$(glog | grep -cE "$re")" -ge "$n" ] && return 0
    sleep 1
  done
  return 1
}

count_log() { glog | grep -cE "$1"; }
last_phase() { glog | grep -oE 'PHASE [A-Z]+' | tail -1 | cut -d' ' -f2; }

# Guest-side screenshot (spec'd path; 5-10 s on this emulator, so it captures "some time" in
# that window — use for static screens).
shot() { adb exec-out screencap -p > "$1"; }
# Host-side display grab, ~0.25 s.
fastshot() { $PY -c "import sys; sys.path.insert(0,'$E2E_DIR'); from phaseshot import fast_shot; f=fast_shot('$1'); sys.exit(0 if f[0] else 1)"; }
# fast_tap [N] : N taps at screen centre-ish via raw evdev writes (~0.5 s instead of ~10 s)
fast_tap() { $PY -c "import sys; sys.path.insert(0,'$E2E_DIR'); from phaseshot import fast_tap; t=fast_tap(0.5,0.4,${1:-1}); sys.exit(0 if t[2] else 1)"; }
surface_dead() { adb logcat -d | grep -q "dequeueBuffer failed"; }

pull_dump() { adb exec-out run-as $PKG cat files/audio_out.wav > "$1"; }

# Surface may have stopped presenting (emulator HWUI "dequeueBuffer failed -> fallback");
# returns 0 if two screenshots taken a few seconds apart differ.
screen_alive() {
  fastshot "$OUT/.alive1.png"; sleep 2; fastshot "$OUT/.alive2.png"
  ! cmp -s "$OUT/.alive1.png" "$OUT/.alive2.png"
}

crash_log() { adb logcat -d -b crash | grep -E "$PKG" || true; }

# launch, and relaunch (up to 3x) if the software-GPU surface froze right away.
launch_ok() {
  local i
  for i in 1 2 3; do
    launch "$@" || continue
    sleep 3
    if ! surface_dead && screen_alive; then return 0; fi
    log "surface frozen after launch (emulator HWUI fallback) - relaunching"
  done
  return 1
}

# Log-time helpers: seconds (epoch) of first line matching REGEX
log_epoch() { adb logcat -d -v epoch -s PeekabooGame:I PeekabooAudio:I | grep -E "$1" | head -1 | awk '{print $1}'; }
