#!/usr/bin/env python3
"""Tap, then film the CELEBRATE state with fast host-side screenshots.

usage: tapshot.py OUT_DIR [--key] [--seconds S]

Sends one press (screen tap, or KEYCODE_SPACE with --key), then grabs host-side
screenshots back to back for S seconds (default 10). Reports:
  - the PRESS log line and phase transitions,
  - which shots pass the `celebrate` check, the best one copied to OUT_DIR/celebrate.png,
  - press->first-celebrate-pixel latency (display lag on this emulator),
  - the first shot after CELEBRATE ends that passes the `visible` check
    (critter back), copied to OUT_DIR/after_celebrate.png.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import imgcheck  # noqa: E402
from phaseshot import adb, clock_offset, fast_shot, fast_tap  # noqa: E402


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); sys.exit(2)
    out = args[0]
    os.makedirs(out, exist_ok=True)
    secs = float(args[args.index("--seconds") + 1]) if "--seconds" in args else 10
    tmp = os.path.join(out, "tapshot_frames")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    off, _ = clock_offset()
    since = time.time() + off
    t_press_cmd = time.time()
    if "--key" in args:
        adb("shell", "input", "keyevent", "62")  # slow (JVM start), but exercises the key path
    else:
        fast_tap(0.5, 0.4)
    frames = []
    end = time.time() + secs
    while time.time() < end:
        # spaced out: hammering the host-side screenshot can starve the software GPU, after
        # which HWUI logs "dequeueBuffer failed ... switching to fallback" and the surface freezes
        t = time.time()
        f, s0, s1 = fast_shot(os.path.join(tmp, "f%03d.png" % len(frames)))
        if f:
            frames.append((f, s0, s1))
        time.sleep(max(0, 0.4 - (time.time() - t)))
    log = adb("logcat", "-d", "-v", "epoch", "-s", "PeekabooGame:I").stdout.splitlines()
    ev = []
    for l in log:
        m = re.match(r"^\s*(\d+\.\d+)\s.*PeekabooGame: (.*)$", l)
        if m and float(m.group(1)) >= since - 0.5:
            ev.append((float(m.group(1)) - off, m.group(2).strip()))
    press = [(t, e) for t, e in ev if e.startswith("PRESS")]
    cel_start = next((t for t, e in ev if e.startswith("PHASE CELEBRATE")), None)
    cel_end = next((t for t, e in ev if cel_start and t > cel_start and e.startswith("PHASE ")), None)
    res = dict(press=[e for _, e in press], press_cmd_to_log_s=round(press[0][0] - t_press_cmd, 3) if press else None,
               phases=[(round(t - t_press_cmd, 2), e) for t, e in ev if e.startswith("PHASE")])
    shots = []
    best = None
    after = None
    for f, s0, s1 in frames:
        c = imgcheck.check("celebrate", f)
        v = imgcheck.check("visible", f)
        shots.append(dict(t=round(s0 - t_press_cmd, 2), celebrate=c["ok"], cue_px=c["cue_px"], spark_px=c["spark_px"],
                          critter=v["ok"]))
        if c["ok"] and (best is None or c["spark_px"] > best[1]["spark_px"]):
            best = (f, c)
        if cel_end and s0 > cel_end + 1.0 and after is None and v["ok"] and not c["ok"]:
            after = f
    res["shots"] = "%d shots, celebrate in %d, critter in %d; first celebrate at +%s s" % (
        len(shots), sum(s["celebrate"] for s in shots), sum(s["critter"] for s in shots),
        next((s["t"] for s in shots if s["celebrate"]), None))
    first = next((s for s in shots if s["celebrate"]), None)
    if press and first:
        res["press_to_cue_on_screen_s"] = round(first["t"] - (press[0][0] - t_press_cmd), 2)
    if best:
        shutil.copy(best[0], os.path.join(out, "celebrate.png"))
        res["celebrate_png"] = os.path.join(out, "celebrate.png")
        res["celebrate_metrics"] = {k: best[1][k] for k in ("cue_px", "spark_px", "critter_px")}
    if after:
        shutil.copy(after, os.path.join(out, "after_celebrate.png"))
        res["after_png"] = os.path.join(out, "after_celebrate.png")
    res["ok"] = bool(press and "hit" in press[0][1] and cel_start and best)
    res["critter_back"] = bool(after)
    print(json.dumps(res))
    sys.exit(0 if res["ok"] else 1)


if __name__ == "__main__":
    main()
