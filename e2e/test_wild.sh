#!/usr/bin/env bash
# Wild mode (spec item 4) + screen recordings with the app audio muxed in.
# Produces media/rec_wild*.mp4 and media/rec_cartoon*.mp4.
source "$(dirname "$0")/lib.sh"
prep_device
[ "${SKIP_BUILD:-1}" = 1 ] || build_and_install
W="$OUT/wild"; MEDIA="${MEDIA:-$ROOT/media}"; mkdir -p "$W" "$MEDIA"

# record NAME SECONDS : background screenrecord, remembers host start time
record() {
  adb shell rm -f /sdcard/$1.mp4
  REC_T0=$(date +%s.%N)
  adb shell screenrecord --time-limit "$2" --size 1280x800 /sdcard/$1.mp4 &
  REC_PID=$!
}
finish_record() { # NAME LOGFILE DUMP
  wait $REC_PID
  adb pull /sdcard/$1.mp4 "$MEDIA/$1.mp4" >/dev/null
  # align: video time of a sound = its logcat time - REC_T0; dump time = its matched onset
  $PY "$E2E_DIR/audiocheck.py" app "$3" "$2" --json "$W/$1_audio.json" >/dev/null
  ss=$($PY - "$W/$1_audio.json" "$2" "$REC_T0" <<'EOF'
import json, sys
sys.path.insert(0, __import__("os").path.dirname(sys.argv[0]) or ".")
d = json.load(open(sys.argv[1]))
import re
from datetime import datetime
ev = [l for l in open(sys.argv[2]) if "ENGINE start" in l]
m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)", ev[-1])
t_eng = datetime.strptime(f"{datetime.now().year}-{m.group(1)}", "%Y-%m-%d %H:%M:%S.%f").timestamp()
rec0 = float(sys.argv[3])
anch = [e for e in d["events"] if e.get("found") and e.get("match_r", 0) > 0.9 and t_eng + e["t"] >= rec0]
if not anch:
    print(max(0.0, rec0 - t_eng)); sys.exit()
e = anch[0]
print(max(0.0, e["onset_s"] - (t_eng + e["t"] - rec0)))
EOF
)
  ffmpeg -loglevel error -y -i "$MEDIA/$1.mp4" -ss "$ss" -i "$3" -map 0:v -map 1:a -c:v copy -c:a aac -shortest "$MEDIA/$1_with_audio.mp4"
  log "$1: audio offset ${ss}s -> $MEDIA/$1_with_audio.mp4"
}

if [ ! -f "$ROOT/app/src/main/res/raw/wild_manifest.txt" ]; then
  log "no wild_manifest.txt: skipping wild mode"
else
  launch_ok --el seed 4 --ef difficulty 0.2 --ef session 300 --ez ignoreCooldown true --ez audioDump true --ef wild 1
  wait_log "WILD clips=" 30; check "4.wild_clips" $? "$(glog | grep -m1 'WILD clips')"
  wait_log "WILD ready" 120; check "4.wild_ready" $? "$(glog | grep -m1 'WILD ready')"
  record rec_wild 25
  wait_log "WILD show" 120; check "4.wild_show" $? "$(glog | grep -m1 'WILD show')"
  sleep 1.5
  fastshot "$SHOTS/wild.png"
  $PY "$E2E_DIR/imgcheck.py" wild "$SHOTS/wild.png" > "$W/wild.json"; check "4.wild_footage_on_screen" $? "$(cat "$W/wild.json")"
  fast_tap
  wait_log "PRESS hit" 20; check "4.wild_press_hit" $? "$(glog | grep -E 'PRESS' | tail -1)"
  wait_log "WILD hide" 20; check "4.wild_hide" $? "$(glog | grep -m1 'WILD hide')"
  wait_log "PHASE CELEBRATE" 10 && { sleep 1.0; fastshot "$SHOTS/wild_celebrate.png"; }
  $PY "$E2E_DIR/imgcheck.py" palette "$SHOTS/wild_celebrate.png" > "$W/wild_celebrate.json"; check "4.wild_back_to_cartoon_palette" $? "$(cat "$W/wild_celebrate.json")"
  wait $REC_PID
  adb shell input keyevent 3; wait_log "ENGINE stop" 60
  glog > "$W/logcat_wild.txt"; pull_dump "$W/audio_wild.wav"
  REC_PID=:; finish_record rec_wild "$W/logcat_wild.txt" "$W/audio_wild.wav"
fi

# cartoon recording: rustle -> peek -> dart -> tap -> celebrate
launch_ok --el seed 5 --ef difficulty 0.3 --ef session 300 --ez ignoreCooldown true --ez audioDump true --ef wild 0
record rec_cartoon 25
n=$(count_log "PHASE VISIBLE")
wait_log "PHASE VISIBLE" 60 $((n + 1)); wait_log "PHASE VISIBLE" 60 $((n + 2)); sleep 1.2; fast_tap
wait_log "PRESS hit" 20; check "rec.cartoon_hit" $? "$(glog | grep -E 'PRESS' | tail -1)"
wait $REC_PID
adb shell input keyevent 3; wait_log "ENGINE stop" 60
glog > "$W/logcat_cartoon.txt"; pull_dump "$W/audio_cartoon.wav"
REC_PID=:; finish_record rec_cartoon "$W/logcat_cartoon.txt" "$W/audio_cartoon.wav"
ls -la "$MEDIA"
summary
