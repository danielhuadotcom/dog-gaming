#!/usr/bin/env python3
"""Take a host-side emulator screenshot while the game is in a given PHASE.

usage: phaseshot.py PHASE OUT.png [--tries N] [--timeout S] [--delay S] [--lag S] [--tail S]
                    [--until PH1,PH2]

Streams `adb logcat -v epoch -s PeekabooGame`, and as soon as a "PHASE <PHASE>" line
arrives waits --delay s and grabs the display with `adb emu screenrecord screenshot`
(~0.25 s; the guest's own screencap takes 5-10 s on a software emulator). It then waits
until the span ends (the next PHASE line, or with --until the first PHASE line naming one
of those phases) and accepts the shot only if it started >= --lag s after the phase began
(the software-rendered display trails game logic by up to ~1 s) and ended >= --tail s
before the span ended. Prints a JSON line; exit 0 on success.
Env: ADB, SERIAL.
"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import queue

ADB = os.environ.get("ADB", "/opt/android-sdk/platform-tools/adb")
SERIAL = os.environ.get("SERIAL", "emulator-5554")


def adb(*a, **kw):
    return subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=True, **kw)


def clock_offset():
    """device_epoch - host_epoch (seconds), estimated from `date` round trip."""
    t0 = time.time()
    out = adb("shell", "date", "+%s.%N").stdout.strip()
    t1 = time.time()
    try:
        return float(out) - (t0 + t1) / 2, t1 - t0
    except ValueError:
        return 0.0, t1 - t0


def fast_shot(dest):
    d = tempfile.mkdtemp()
    t0 = time.time()
    adb("emu", "screenrecord", "screenshot", d)
    t1 = time.time()
    files = glob.glob(os.path.join(d, "*.png"))
    if not files:
        return None, t0, t1
    shutil.move(files[0], dest)
    shutil.rmtree(d, ignore_errors=True)
    return dest, t0, t1


TOUCH_DEV = os.environ.get("TOUCH_DEV", "/dev/input/event1")  # virtio_input_multi_touch_1


def fast_tap(x=0.5, y=0.4, n=1):
    """Inject n taps by writing raw evdev events in one shell process (needs `adb root`).
    `input tap` spawns a JVM and takes ~10 s on this emulator; this lands in ~0.5 s."""
    import struct
    ev = lambda t, c, v: struct.pack("<qqHHi", 0, 0, t, c, v)
    X, Y = int(x * 32767), int(y * 32767)
    one = ev(3, 57, 9) + ev(3, 53, X) + ev(3, 54, Y) + ev(3, 58, 50) + ev(1, 330, 1) + ev(0, 0, 0) \
        + ev(3, 57, -1) + ev(1, 330, 0) + ev(0, 0, 0)
    path = os.path.join(tempfile.gettempdir(), "peekaboo_tap.bin")
    with open(path, "wb") as f:
        f.write(one * n)  # n down/up pairs in one write: a burst within a few ms
    adb("push", path, "/data/local/tmp/tap.bin")
    t0 = time.time()
    script = "cat /data/local/tmp/tap.bin > %s" % TOUCH_DEV
    r = adb("shell", script)
    return t0, time.time(), r.returncode == 0 and "denied" not in (r.stderr + r.stdout)


def main():
    args = sys.argv[1:]
    if len(args) < 2:
        print(__doc__); sys.exit(2)
    phase, out = args[0], args[1]
    tries = int(args[args.index("--tries") + 1]) if "--tries" in args else 6
    timeout = float(args[args.index("--timeout") + 1]) if "--timeout" in args else 240
    delay = float(args[args.index("--delay") + 1]) if "--delay" in args else 1.2
    lag = float(args[args.index("--lag") + 1]) if "--lag" in args else 1.0
    tail = float(args[args.index("--tail") + 1]) if "--tail" in args else 0.2
    until = args[args.index("--until") + 1].split(",") if "--until" in args else None
    off, rtt = clock_offset()
    # Only look at lines newer than now.
    since = time.time() + off
    p = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "epoch", "-T", "1", "-s", "PeekabooGame:I"],
                         stdout=subprocess.PIPE, text=True, bufsize=1)
    q = queue.Queue()
    threading.Thread(target=lambda: [q.put(l) for l in p.stdout], daemon=True).start()
    rx = re.compile(r"^\s*(\d+\.\d+)\s.*PHASE (\w+)")
    deadline = time.time() + timeout
    attempts = []
    result = None
    try:
        while time.time() < deadline and len(attempts) < tries and result is None:
            try:
                line = q.get(timeout=1)
            except queue.Empty:
                continue
            m = rx.match(line)
            if not m or float(m.group(1)) < since or m.group(2) != phase:
                continue
            start = float(m.group(1)) - off  # host clock
            tmp = out + ".try.png"
            wait = start + delay - time.time()
            if wait > 0:
                time.sleep(wait)
            f, s0, s1 = fast_shot(tmp)
            # wait for the end of this phase
            end = None
            other = []
            while time.time() < deadline:
                try:
                    l2 = q.get(timeout=1)
                except queue.Empty:
                    continue
                m2 = rx.match(l2)
                if m2:
                    other.append(m2.group(2))
                    if until is None or m2.group(2) in until:
                        end = float(m2.group(1)) - off
                        break
            a = dict(phase_start=round(start, 3), shot=[round(s0, 3), round(s1, 3)],
                     span_end=round(end, 3) if end else None, then=other)
            a["inside"] = bool(f and end and s0 - lag >= start and s1 + tail <= end)
            attempts.append(a)
            if a["inside"]:
                shutil.move(tmp, out)
                result = a
            elif f and os.path.exists(tmp):
                os.remove(tmp)
    finally:
        p.kill()
    print(json.dumps(dict(phase=phase, out=out if result else None, clock_offset=round(off, 3),
                          rtt=round(rtt, 3), attempts=attempts, ok=result is not None)))
    sys.exit(0 if result else 1)


if __name__ == "__main__":
    main()
