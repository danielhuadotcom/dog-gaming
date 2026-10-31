#!/usr/bin/env bash
# Robustness smoke (spec item 3): mashing during HIDDEN, pause/resume, orientation, crashes, FPS.
source "$(dirname "$0")/lib.sh"
prep_device
[ "${SKIP_BUILD:-1}" = 1 ] || build_and_install
R="$OUT/robust"; mkdir -p "$R"

launch_ok --el seed 3 --ef difficulty 0.9 --ef session 300 --ez ignoreCooldown true --ef wild 0
check "R.launch" $? ""
n=$(count_log "PHASE HIDDEN")
wait_log "PHASE HIDDEN" 120 $((n + 1))
fast_tap 20
sleep 4
glog > "$R/mash.txt"
burst=1  # logcat was cleared at launch: every PRESS line is from the burst
ign=$(tail -n +"$burst" "$R/mash.txt" | grep -c "PRESS ignored")
hit=$(tail -n +"$burst" "$R/mash.txt" | grep -c "PRESS hit")
phases=$(tail -n +"$burst" "$R/mash.txt" | grep -oE "PHASE [A-Z]+" | tr '\n' ' ')
pid=$(adb shell pidof $PKG | tr -d '\r')
[ -n "$pid" ] && [ "$hit" = 0 ] && [ "$ign" -ge 1 ]; check "3.mash_20_taps_hidden" $? "ignored=$ign hit=$hit alive_pid=$pid phases_after: $phases"
# no sound in response to an ignored press (no buzzer)
after_ign=$(awk '/PRESS ignored/{t=1; next} t && /PLAY/{print; t=0} /PHASE/{t=0}' "$R/mash.txt" | grep -vE "RUSTLE|CHIRP|SCURRY" | wc -l)
[ "$after_ign" = 0 ]; check "3.no_buzzer_on_ignored_press" $? "unexpected PLAY after ignored press: $after_ign"

# home + resume
adb logcat -c
adb shell input keyevent 3
wait_log "ENGINE stop" 60; check "3.pause_engine_stop" $? ""
sleep 3
adb shell am start -n "$ACT" >/dev/null   # no -S: same process, onResume
wait_log "ENGINE start" 90; check "3.resume_engine_start" $? "$(glog | grep -E 'ENGINE' | tr '\n' ' ')"
wait_log "FPS" 60 2; check "3.resume_render_continues" $? "$(glog | grep FPS | tail -2 | awk '{print $NF}' | tr '\n' ' ')"
screen_alive; check "3.resume_screen_changes" $? ""

# orientation: try to force portrait via user_rotation; activity is sensorLandscape
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 6
rot=$(adb shell dumpsys window displays | grep -oE "mRotation=ROTATION_[0-9]+|cur=[0-9]+x[0-9]+" | head -2 | tr '\n' ' ')
fastshot "$R/orient.png"
dims=$($PY -c "from PIL import Image; print('%dx%d' % Image.open('$R/orient.png').size)")
adb shell settings put system user_rotation 0
[ "$dims" = 1280x800 ] && ! echo "$rot" | grep -qE "ROTATION_(1|3)|cur=800x1280"; check "3.orientation_landscape" $? "$rot screenshot=$dims"

crash=$(crash_log)
[ -z "$crash" ]; check "3.crash_buffer_clean" $? "${crash:-empty}"
adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" > "$R/fatal.txt"
[ ! -s "$R/fatal.txt" ]; check "3.no_fatal_or_anr" $? "$(head -3 "$R/fatal.txt")"
glog | grep FPS | awk '{print $NF}' | tr '\n' ' ' > "$R/fps.txt"
log "FPS: $(cat "$R/fps.txt")"
summary
