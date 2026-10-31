#!/usr/bin/env bash
# Visual + audio end-to-end checks (spec items 1 and 2).
#   Run A: training (difficulty 0): training screen, celebrate on tap, critter back,
#          session end -> rest screen, audio dump + host capture, cooldown persisted.
#   Run B: difficulty 0.5: HIDDEN has no critter, VISIBLE has it, hit -> celebrate,
#          RUSTLE before CHIRP, audio dump analysis, AudioFlinger/AudioService proof.
# Env: SKIP_BUILD=1 to reuse the installed apk; HOST_WAV=<emulator QEMU_WAV_PATH> for the
# host-side capture check (skipped if unset/missing).
source "$(dirname "$0")/lib.sh"
prep_device
build_and_install
adb shell pm clear $PKG >/dev/null   # fresh prefs: no cooldown, no saved difficulty
A="$OUT/runA"; B="$OUT/runB"; mkdir -p "$A" "$B"
J() { $PY -c "import json,sys; d=json.load(open('$1')); print($2)"; }

########################################################################## Run A
log "Run A: training"
launch_ok --el seed 1 --ef difficulty 0 --ef session 45 --ef cooldown 900 --ez audioDump true --ef wild 0
check "A.launch" $? "SURFACE + live frames"
sleep 2
shot "$SHOTS/training.png"
$PY "$E2E_DIR/imgcheck.py" training "$SHOTS/training.png" > "$A/training.json"; check "1a.training_screen" $? "$(cat "$A/training.json")"
$PY "$E2E_DIR/imgcheck.py" palette "$SHOTS/training.png" > "$A/palette_training.json"; check "1b.palette_training" $? "$(cat "$A/palette_training.json")"

$PY "$E2E_DIR/tapshot.py" "$A" --seconds 9 > "$A/tap.json"; rc=$?
cp -f "$A/celebrate.png" "$SHOTS/celebrate.png" 2>/dev/null
check "1c.celebrate_after_tap" $rc "$(cat "$A/tap.json")"
for f in "$A/celebrate.png"; do [ -f "$f" ] && { $PY "$E2E_DIR/imgcheck.py" palette "$f" > "$A/palette_celebrate.json"; check "1b.palette_celebrate" $? "$(cat "$A/palette_celebrate.json")"; }; done
# critter back after the celebration
wait_log "PHASE VISIBLE training" 60 2
sleep 2
fastshot "$SHOTS/after_celebrate.png"
$PY "$E2E_DIR/imgcheck.py" visible "$SHOTS/after_celebrate.png" > "$A/after.json"; check "1c.critter_back" $? "$(cat "$A/after.json")"

log "waiting for session end"
wait_log "SESSION_DONE" 400; check "A.session_done" $? "$(glog | grep SESSION_DONE)"
sleep 4
shot "$SHOTS/rest.png"
$PY "$E2E_DIR/imgcheck.py" rest "$SHOTS/rest.png" > "$A/rest.json"; check "1e.rest_screen" $? "$(cat "$A/rest.json")"
$PY "$E2E_DIR/imgcheck.py" palette "$SHOTS/rest.png" > "$A/palette_rest.json"; check "1b.palette_rest" $? "$(cat "$A/palette_rest.json")"
sleep 3   # let ALL_DONE finish (1.9 s)
adb shell input keyevent 3   # HOME -> onPause -> ENGINE stop (closes the dump)
wait_log "ENGINE stop" 60
glog > "$A/logcat.txt"
adb logcat -d -v time > "$A/logcat_full.txt"
pull_dump "$A/audio_out.wav"
for ev in "ENGINE start" "PLAY CHIRP" "PLAY SCURRY" "PLAY SQUEAK" "PLAY CELEBRATE" "PLAY ALL_DONE" "OUT active" "OUT silent"; do
  grep -q "$ev" "$A/logcat.txt"; check "2a.A.log '$ev'" $? "count=$(grep -c "$ev" "$A/logcat.txt")"
done
# The training critter is on screen from the first frame: its CHIRP should be heard.
first_chirp_before_press=$(awk '/PLAY CHIRP/{c=1} /PRESS hit/{print c+0; exit}' "$A/logcat.txt")
[ "$first_chirp_before_press" = 1 ]; check "2a.A.chirp_on_first_appearance" $? "CHIRP logged before first PRESS hit: ${first_chirp_before_press:-0}"
$PY "$E2E_DIR/audiocheck.py" app "$A/audio_out.wav" "$A/logcat.txt" --json "$A/audio.json" > /dev/null; rc=$?
check "2b.A.audio_dump" $rc "$(J "$A/audio.json" "{k:d[k] for k in ('seconds','peak_fs','clipped_samples','silent_frame_pct','template_hits','play_counts','checks')}")"
J "$A/audio.json" "'\n'.join(str(e) for e in d['events'] if e['sound']!='SCURRY')" > "$A/audio_events.txt"
if [ -n "${HOST_WAV:-}" ] && [ -s "$HOST_WAV" ]; then
  sleep 3
  $PY "$E2E_DIR/audiocheck.py" host "$HOST_WAV" "$A/audio_out.wav" "$A/logcat.txt" --json "$A/host_audio.json" > /dev/null; rc=$?
  check "2c.A.host_capture" $rc "$(J "$A/host_audio.json" "{k:d.get(k) for k in ('channels','host_peak_fs','median_xcorr','median_gain','error')}")"
  J "$A/host_audio.json" "'\n'.join(str(e) for e in d.get('events',[]) if e['sound']!='SCURRY')" > "$A/host_events.txt"
fi

log "relaunch without ignoreCooldown: must start resting"
launch --el seed 1 --ef session 45 --ef cooldown 900 --ef wild 0
sleep 4
glog | grep -E "START|PHASE" | head -3 > "$A/relaunch_phases.txt"
grep -q "PHASE DONE" "$A/relaunch_phases.txt" && ! grep -q "PLAY ALL_DONE" <(glog); check "1e.cooldown_persisted.log" $? "$(tr '\n' ' ' < "$A/relaunch_phases.txt")"
shot "$SHOTS/rest_relaunch.png"
$PY "$E2E_DIR/imgcheck.py" rest "$SHOTS/rest_relaunch.png" > "$A/rest_relaunch.json"; check "1e.cooldown_persisted.screen" $? "$(cat "$A/rest_relaunch.json")"
adb exec-out run-as $PKG cat shared_prefs/peekaboo.xml > "$A/prefs.xml" 2>/dev/null

########################################################################## Run B
log "Run B: difficulty 0.5"
launch_ok --el seed 2 --ef difficulty 0.5 --ef session 150 --ef cooldown 30 --ez ignoreCooldown true --ez audioDump true --ef wild 0
check "B.launch" $? ""
$PY "$E2E_DIR/phaseshot.py" HIDDEN "$SHOTS/hidden.png" --until EMERGE --delay 1.4 --lag 1.3 > "$B/hidden_shot.json"; check "1d.hidden_timed_shot" $? "$(cat "$B/hidden_shot.json")"
$PY "$E2E_DIR/imgcheck.py" hidden "$SHOTS/hidden.png" > "$B/hidden.json"; check "1d.hidden_no_critter" $? "$(cat "$B/hidden.json")"
$PY "$E2E_DIR/phaseshot.py" VISIBLE "$SHOTS/visible.png" --until HIDDEN,CELEBRATE --delay 1.2 --lag 1.0 > "$B/visible_shot.json"; check "1d.visible_timed_shot" $? "$(cat "$B/visible_shot.json")"
$PY "$E2E_DIR/imgcheck.py" visible "$SHOTS/visible.png" > "$B/visible.json"; check "1d.visible_critter" $? "$(cat "$B/visible.json")"
$PY "$E2E_DIR/imgcheck.py" palette "$SHOTS/hidden.png" "$SHOTS/visible.png" > "$B/palette.json"; check "1b.palette_B" $? "$(tr '\n' ' ' < "$B/palette.json")"

# AudioService / AudioFlinger while our track plays (a sound is ~0.3-1 s; poll a few times)
for i in 1 2; do
  adb shell dumpsys audio > "$B/dumpsys_audio_$i.txt"
  adb shell dumpsys media.audio_flinger > "$B/dumpsys_af_$i.txt"
  sleep 2
done
uid=$(adb shell dumpsys package $PKG | grep -m1 userId= | sed 's/.*userId=\([0-9]*\).*/\1/')
pid=$(adb shell pidof $PKG | tr -d '\r')
grep -E "u/pid:$uid/.*state:started" "$B/dumpsys_audio_1.txt" >/dev/null; check "2c.dumpsys_audio_player_started" $? "$(grep -E "u/pid:$uid/" "$B/dumpsys_audio_1.txt" | head -2 | tr -s ' ')"
# our track = AudioFlinger track line whose Client column is our pid; Server column = frames consumed (hex)
t1=$(awk -v p="$pid" '$4==p && $3=="yes"{print $19; exit}' "$B/dumpsys_af_1.txt")
t2=$(awk -v p="$pid" '$4==p && $3=="yes"{print $19; exit}' "$B/dumpsys_af_2.txt")
w1=$(grep -m1 "Frames written:" "$B/dumpsys_af_1.txt" | awk '{print $3}'); w2=$(grep -m1 "Frames written:" "$B/dumpsys_af_2.txt" | awk '{print $3}')
ur=$(grep -m1 "underruns=" "$B/dumpsys_af_2.txt" | tr -s ' ')
echo "track_server_frames: 0x$t1 -> 0x$t2 ; AudioOut_D frames written: $w1 -> $w2 ; $ur" > "$B/af.txt"
[ -n "$t1" ] && [ -n "$t2" ] && [ $((16#$t2)) -gt $((16#$t1)) ] && [ "$w2" -gt "$w1" ]
check "2c.audioflinger_track_frames_advance" $? "$(cat "$B/af.txt")"

# hit during VISIBLE -> celebrate (non-training goes back to HIDDEN afterwards)
n=$(count_log "PHASE VISIBLE")
wait_log "PHASE VISIBLE" 120 $((n + 1)); sleep 0.6; fast_tap
wait_log "PRESS hit" 20; check "B.hit_during_visible" $? "$(glog | grep -E 'PRESS' | tail -2 | tr '\n' ' ')"
wait_log "PHASE CELEBRATE" 10 && { sleep 1.2; fastshot "$SHOTS/celebrate_B.png"; $PY "$E2E_DIR/imgcheck.py" celebrate "$SHOTS/celebrate_B.png" > "$B/celebrate.json"; check "1c.celebrate_B" $? "$(cat "$B/celebrate.json")"; }
sleep 15
adb shell input keyevent 3
wait_log "ENGINE stop" 60
glog > "$B/logcat.txt"
pull_dump "$B/audio_out.wav"
for ev in "PLAY RUSTLE" "PLAY CHIRP" "PLAY SQUEAK" "PLAY CELEBRATE" "OUT active" "OUT silent"; do
  grep -q "$ev" "$B/logcat.txt"; check "2a.B.log '$ev'" $? "count=$(grep -c "$ev" "$B/logcat.txt")"
done
# every non-training CHIRP (emergence) is preceded by a RUSTLE; no sound right after PRESS ignored
awk '/PLAY RUSTLE/{r=1} /PLAY CHIRP/{n++; if(!r) bad++; r=0} END{printf "chirps=%d without_rustle=%d\n", n, bad+0; exit (bad>0)}' "$B/logcat.txt" > "$B/rustle_order.txt"
check "2a.B.rustle_before_every_chirp" $? "$(cat "$B/rustle_order.txt") (fake rustles allowed)"
$PY "$E2E_DIR/audiocheck.py" app "$B/audio_out.wav" "$B/logcat.txt" --json "$B/audio.json" > /dev/null; rc=$?
check "2b.B.audio_dump" $rc "$(J "$B/audio.json" "{k:d[k] for k in ('seconds','peak_fs','clipped_samples','silent_frame_pct','template_hits','play_counts','checks')}")"
J "$B/audio.json" "'\n'.join(str(e) for e in d['events'] if e['sound']!='SCURRY')" > "$B/audio_events.txt"
if [ -n "${HOST_WAV:-}" ] && [ -s "$HOST_WAV" ]; then
  sleep 3
  $PY "$E2E_DIR/audiocheck.py" host "$HOST_WAV" "$B/audio_out.wav" "$B/logcat.txt" --json "$B/host_audio.json" > /dev/null; rc=$?
  check "2c.B.host_capture" $rc "$(J "$B/host_audio.json" "{k:d.get(k) for k in ('channels','host_peak_fs','median_xcorr','median_gain','error')}")"
  J "$B/host_audio.json" "'\n'.join(str(e) for e in d.get('events',[]) if e['sound']!='SCURRY')" > "$B/host_events.txt"
fi
glog | grep FPS | awk '{print $NF}' | tr '\n' ' ' > "$OUT/fps_main.txt"
summary
