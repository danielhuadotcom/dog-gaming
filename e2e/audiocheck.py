#!/usr/bin/env python3
"""Audio checks for Peekaboo Squirrel.

  audiocheck.py app  APP_WAV LOGCAT_FILE [--json OUT]
      Analyse files/audio_out.wav (the app's mixer tee) against PLAY events in a
      `logcat -v time` capture: segment alignment, spectral checks per sound,
      clipping and inter-event silence.

  audiocheck.py host HOST_WAV APP_WAV LOGCAT_FILE [--json OUT]
      Find the same sounds in the emulator's host-side capture (QEMU_AUDIO_DRV=wav,
      stereo 16-bit, header sizes may be 0 while the emulator runs) by
      cross-correlating each event with the app dump, then repeat the spectral
      checks on the host signal.

Exit status 0 when every check passed.
"""
import json
import re
import struct
import sys
from datetime import datetime

import numpy as np

# Expected content (from the spec, NOT read from the app).
SQUEAK_START = (1400, 2000)   # Hz, dominant ~20 ms in (sweep starts at 1.6 kHz)
SQUEAK_END = (2900, 3550)     # Hz, dominant ~170 ms in (sweep ends at 3.4 kHz)
CHIRP_BAND = (3700, 5300)
CELEBRATE_NOTES = [1047, 1319, 1568, 2093]
ALL_DONE_NOTES = [659, 523, 392]
TOL = 0.03                    # 3 % frequency tolerance for tonal notes


def read_wav(path, max_seconds=None):
    with open(path, "rb") as f:
        hdr = f.read(12)
        assert hdr[:4] == b"RIFF" and hdr[8:12] == b"WAVE", "not a wav"
        ch = sr = bits = None
        while True:
            h = f.read(8)
            if len(h) < 8:
                raise ValueError("no data chunk")
            cid, size = h[:4], struct.unpack("<I", h[4:])[0]
            if cid == b"fmt ":
                fmt = f.read(size)
                _, ch, sr, _, _, bits = struct.unpack("<HHIIHH", fmt[:16])
            elif cid == b"data":
                start = f.tell()
                break
            else:
                f.seek(size, 1)
        f.seek(0, 2)
        end = f.tell()
        frame = ch * bits // 8
        nframes = (end - start) // frame  # ignore header size (may be 0 / stale)
        first = 0
        if max_seconds:
            first = max(0, nframes - int(max_seconds * sr))
        f.seek(start + first * frame)
        raw = f.read((nframes - first) * frame)
    x = np.frombuffer(raw[: len(raw) // frame * frame], "<i2").astype(np.float64)
    x = x.reshape(-1, ch)
    return x, sr, first


def parse_log(path):
    ev = []
    year = datetime.now().year
    for line in open(path, errors="replace"):
        m = re.match(r"(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)\s.*?(ENGINE start|ENGINE stop|PLAY (\w+)|OUT active|OUT silent)", line)
        if not m:
            continue
        t = datetime.strptime(f"{year}-{m.group(1)}", "%Y-%m-%d %H:%M:%S.%f").timestamp()
        ev.append((t, m.group(3) or m.group(2)))
    return ev


def frames_rms(x, sr, hop=0.01):
    n = int(sr * hop)
    m = len(x) // n
    return np.sqrt((x[: m * n].reshape(m, n) ** 2).mean(1)), n


def segments(x, sr, thr_db=-50, gap=0.06):
    rms, n = frames_rms(x, sr)
    act = 20 * np.log10(rms / 32768 + 1e-12) > thr_db
    segs, i = [], 0
    while i < len(act):
        if act[i]:
            j = i
            while j < len(act) and act[j]:
                j += 1
            segs.append([i * n, j * n])
            i = j
        else:
            i += 1
    merged = []
    for s in segs:
        if merged and s[0] - merged[-1][1] < gap * sr:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    return merged


def peak_freq(x, sr, a, b, lo, hi):
    seg = x[max(0, a): max(0, b)]
    if len(seg) < 64:
        return None
    w = seg * np.hanning(len(seg))
    nfft = 1 << int(np.ceil(np.log2(len(seg) * 8)))
    sp = np.abs(np.fft.rfft(w, nfft))
    f = np.fft.rfftfreq(nfft, 1 / sr)
    band = (f >= lo) & (f <= hi)
    if not band.any() or sp[band].max() <= 0:
        return None
    return float(f[band][np.argmax(sp[band])])


def centroid(x, sr, a, b):
    seg = x[a:b]
    sp = np.abs(np.fft.rfft(seg * np.hanning(len(seg)))) ** 2
    f = np.fft.rfftfreq(len(seg), 1 / sr)
    return float((sp * f).sum() / max(sp.sum(), 1e-12))


def find_onset(x, sr, guess, search=0.6):
    """First 5 ms frame in [guess-search, guess+search] whose energy jumps by >6 dB over the
    preceding 20 ms and exceeds -45 dBFS; returns the one closest to guess."""
    n = int(sr * 0.005)
    a = max(0, int(guess - search * sr))
    b = min(len(x) - n, int(guess + search * sr))
    best = None
    rms_prev = None
    cands = []
    for i in range(a, b, n):
        r = np.sqrt((x[i:i + n] ** 2).mean()) + 1e-9
        pre = np.sqrt((x[max(0, i - 4 * n):i] ** 2).mean()) + 1e-9 if i > 0 else 1e-9
        if 20 * np.log10(r / 32768) > -45 and r > 2 * pre:
            cands.append(i)
    if cands:
        best = min(cands, key=lambda c: abs(c - guess))
    return best


def analyse_event(x, sr, name, o):
    """Spectral checks for one sound starting at sample o. Returns (ok, details)."""
    s = lambda t: o + int(t * sr)
    d = {}
    if name == "SQUEAK":
        track = [peak_freq(x, sr, s(t - 0.01), s(t + 0.01), 1000, 6000) for t in np.arange(0.02, 0.18, 0.02)]
        d["sweep_hz"] = [round(f) if f else None for f in track]
        ak = peak_freq(x, sr, s(0.25), s(0.33), 1500, 4000)
        d["ak_hz"] = round(ak) if ak else None
        ok = all(track) and SQUEAK_START[0] <= track[0] <= SQUEAK_START[1] \
            and SQUEAK_END[0] <= track[-1] <= SQUEAK_END[1] \
            and sum(b >= a - 80 for a, b in zip(track, track[1:])) >= len(track) - 2
        return bool(ok), d
    if name == "CHIRP":
        fs = []
        for k in range(3):
            for t in (0.012, 0.043):
                fs.append(peak_freq(x, sr, s(k * 0.1 + t - 0.008), s(k * 0.1 + t + 0.008), 2000, 8000))
        d["chirp_hz"] = [round(f) if f else None for f in fs]
        ok = all(f and CHIRP_BAND[0] <= f <= CHIRP_BAND[1] for f in fs)
        return bool(ok), d
    if name == "CELEBRATE":
        fs = [peak_freq(x, sr, s(0.38 + k * 0.09 + 0.006), s(0.38 + k * 0.09 + 0.086), 900, 2300)
              for k in range(4)]
        d["notes_hz"] = [round(f) if f else None for f in fs]
        ok = all(f and abs(f - e) / e < TOL for f, e in zip(fs, CELEBRATE_NOTES))
        return bool(ok), d
    if name == "ALL_DONE":
        fs = [peak_freq(x, sr, s(k * 0.5 + 0.04), s(k * 0.5 + 0.46), 300, 900) for k in range(3)]
        d["notes_hz"] = [round(f) if f else None for f in fs]
        ok = all(f and abs(f - e) / e < TOL for f, e in zip(fs, ALL_DONE_NOTES))
        return bool(ok), d
    if name == "RUSTLE":
        c = centroid(x, sr, s(0.1), s(0.7))
        d["centroid_hz"] = round(c)
        return bool(c > 1500), d
    if name == "SCURRY":
        c = centroid(x, sr, s(0.0), s(0.27))
        d["centroid_hz"] = round(c)
        return bool(c > 2000), d
    return True, d


# ---------------------------------------------------------------- templates
# Waveforms re-derived from the sound spec (deterministic sounds only) and used purely to
# *locate* each sound in a recording via normalised cross-correlation. The frequency checks
# in analyse_event() are independent of these.
def _squeak(sr):
    out = np.zeros(int(sr * 0.36))
    ph = [0.0]
    def seg(start, dur, f0, f1, amp):
        s0, n = int(start * sr), int(dur * sr)
        i = np.arange(n)
        t = i / n
        f = f0 * np.exp(np.log(f1 / f0) * t) + 70.0 * np.sin(2 * np.pi * 32.0 * i / sr)
        p = ph[0] + np.cumsum(2 * np.pi * f / sr)
        ph[0] = p[-1]
        reed = np.sin(p) + 0.35 * np.sin(2 * p) + 0.15 * np.sin(3 * p)
        env = np.minimum(1, t * 12) * np.minimum(1, (1 - t) * 8)
        out[s0:s0 + n] += amp * env * reed / 1.5
    seg(0.0, 0.19, 1600.0, 3400.0, 0.85)
    seg(0.23, 0.12, 3100.0, 2200.0, 0.75)
    return out


def _chirp(sr):
    out = np.zeros(int(sr * 0.32))
    n = int(0.055 * sr)
    t = np.arange(n) / n
    p = np.cumsum(2 * np.pi * (5200.0 - 1400.0 * t) / sr)
    for k in range(3):
        s0 = int(k * 0.1 * sr)
        out[s0:s0 + n] += 0.6 * np.sin(np.pi * t) * np.sin(p)
    return out


def _celebrate(sr):
    notes, nd = [1047.0, 1319.0, 1568.0, 2093.0], 0.09
    out = np.zeros(int(sr * (0.38 + len(notes) * nd + 0.25)))
    for k, f in enumerate(notes):
        s0 = int((0.38 + k * nd) * sr)
        n = min(int((nd + 0.2) * sr), len(out) - s0)
        t = np.arange(n) / sr
        env = np.minimum(1, t * 200) * np.exp(-t * 14)
        out[s0:s0 + n] += 0.42 * env * (np.sin(2 * np.pi * f * t) + 0.3 * np.sin(4 * np.pi * f * t))
    return out


def _all_done(sr):
    notes, nd = [659.0, 523.0, 392.0], 0.5
    out = np.zeros(int(sr * len(notes) * nd + sr * 0.4))
    for k, f in enumerate(notes):
        s0 = int(k * nd * sr)
        n = min(int((nd + 0.35) * sr), len(out) - s0)
        t = np.arange(n) / sr
        out[s0:s0 + n] += 0.35 * np.minimum(1, t * 30) * np.exp(-t * 3.5) * np.sin(2 * np.pi * f * t)
    return out


class KRandom:
    """kotlin.random.Random(seed: Int) (XorWow) so the seeded noise sounds can be templated."""
    M = 0xFFFFFFFF

    def __init__(self, seed):
        s1 = seed & self.M
        s2 = (seed >> 31) & self.M
        self.x, self.y, self.z, self.w = s1, s2, 0, 0
        self.v = (~s1) & self.M
        self.add = ((s1 << 10) ^ (s2 >> 4)) & self.M
        for _ in range(64):
            self.next_int()

    def next_int(self):
        t = self.x
        t ^= t >> 2
        self.x, self.y, self.z = self.y, self.z, self.w
        v0 = self.v
        self.w = v0
        t = (t ^ ((t << 1) & self.M) ^ v0 ^ ((v0 << 4) & self.M)) & self.M
        self.v = t
        self.add = (self.add + 362437) & self.M
        return (t + self.add) & self.M

    def bits(self, n):
        return self.next_int() >> (32 - n)

    def next_float(self):
        return self.bits(24) / float(1 << 24)

    def next_double(self):
        hi = self.bits(26); lo = self.bits(27)
        return ((hi << 27) + lo) / float(1 << 53)


def _rustle(sr):
    rng = KRandom(7)
    n = int(sr * 0.8)
    out = np.zeros(n)
    rc = 1.0 / (2 * np.pi * 1500.0)
    a = rc / (rc + 1.0 / sr)
    px = py = grain = 0.0
    thr = 40.0 / sr * 10.0
    for i in range(n):
        t = i / n
        if rng.next_float() < thr:
            grain = 0.6 + rng.next_double() * 0.4
        grain *= 0.9993
        xx = rng.next_double() * 2 - 1
        y = a * (py + xx - px)
        px, py = xx, y
        env = min(1.0, t * 10) * min(1.0, (1 - t) * 4)
        out[i] = env * (0.25 + grain) * y
    return out * (0.6 / np.abs(out).max())


def _scurry(sr):
    rng = KRandom(11)
    out = np.zeros(int(sr * 0.3))
    tick = int(0.008 * sr)
    for k in range(8):
        s0 = int(k * 0.034 * sr)
        px = 0.0
        for i in range(tick):
            xx = rng.next_double() * 2 - 1
            y = xx - px
            px = xx
            if s0 + i < len(out):
                out[s0 + i] += 0.22 * (1.0 - i / tick) * y
    return out


TEMPLATES = {"SQUEAK": _squeak, "CHIRP": _chirp, "CELEBRATE": _celebrate, "ALL_DONE": _all_done,
             "RUSTLE": _rustle, "SCURRY": _scurry}
_tcache = {}


def template(name, sr):
    if name not in TEMPLATES:
        return None
    if (name, sr) not in _tcache:
        _tcache[(name, sr)] = TEMPLATES[name](sr) * 32767
    return _tcache[(name, sr)]


def locate(x, tmpl, guess, search):
    """Best normalised-xcorr position of tmpl in x within guess±search samples."""
    L = len(tmpl)
    lo = max(0, int(guess - search))
    hi = min(len(x), int(guess + search) + L)
    win = x[lo:hi]
    if len(win) < L:
        return None, 0.0
    nfft = 1 << int(np.ceil(np.log2(len(win) + L)))
    cc = np.fft.irfft(np.fft.rfft(win, nfft) * np.conj(np.fft.rfft(tmpl, nfft)), nfft)[: len(win) - L + 1]
    # local energy normalisation
    c2 = np.concatenate([[0.0], np.cumsum(win ** 2)])
    e = np.sqrt(np.maximum(c2[L:] - c2[:-L], 0)) + 1e-9
    r = cc / (e * np.linalg.norm(tmpl))
    k = int(np.argmax(r))
    return lo + k, float(r[k])


def mono(x):
    return x.mean(1) if x.ndim == 2 else x


def scan(x, tmpl, thr=0.9):
    """All positions where tmpl matches x with normalised xcorr > thr (whole file)."""
    L = len(tmpl)
    if len(x) < L:
        return []
    nfft = 1 << int(np.ceil(np.log2(len(x) + L)))
    cc = np.fft.irfft(np.fft.rfft(x, nfft) * np.conj(np.fft.rfft(tmpl, nfft)), nfft)[: len(x) - L + 1]
    c2 = np.concatenate([[0.0], np.cumsum(x ** 2)])
    e = np.sqrt(np.maximum(c2[L:] - c2[:-L], 0)) + 1e-9
    r = cc / (e * np.linalg.norm(tmpl))
    r[e < 1e-3 * np.linalg.norm(tmpl)] = 0  # ignore near-silence
    hits = []
    idx = np.nonzero(r > thr)[0]
    i = 0
    while i < len(idx):
        j = i
        while j + 1 < len(idx) and idx[j + 1] - idx[i] < L // 2:
            j += 1
        k = idx[i] + int(np.argmax(r[idx[i]: idx[j] + 1]))
        hits.append((int(k), float(r[k])))
        i = j + 1
    return hits


NON_SOUND = ("ENGINE start", "ENGINE stop", "OUT active", "OUT silent")
CHECKED = ("SQUEAK", "CHIRP", "CELEBRATE", "ALL_DONE", "RUSTLE")
DURATIONS = {"SQUEAK": .36, "CHIRP": .32, "RUSTLE": .8, "SCURRY": .3, "CELEBRATE": 1.0, "ALL_DONE": 1.9}


def app_mode(wav, logf):
    xs, sr, _ = read_wav(wav)
    x = mono(xs)
    ev = parse_log(logf)
    starts = [i for i, (t, n) in enumerate(ev) if n == "ENGINE start"]
    if not starts:
        raise SystemExit("no ENGINE start in log")
    # The dump is truncated at every ENGINE start; only events after the last one are in it.
    ev = ev[starts[-1]:]
    t0 = ev[0][0]
    stop = [t for t, n in ev if n == "ENGINE stop"]
    t_end = stop[0] if stop else float("inf")
    # sounds started < 2 s before ENGINE stop may be cut off in the dump: ignore them
    plays = [(t - t0, n) for t, n in ev if n not in NON_SOUND and t < t_end - 2.0]
    res = dict(file=wav, sr=sr, seconds=round(len(x) / sr, 2), wall_seconds_logged=round(plays[-1][0], 2) if plays else 0)
    peak = float(np.abs(x).max()) if len(x) else 0.0
    res["peak_fs"] = round(peak / 32767, 4)
    res["clipped_samples"] = int((np.abs(x) >= 32767).sum())
    segs = segments(x, sr)
    res["segments"] = len(segs)
    rms, n = frames_rms(x, sr)
    silent = 20 * np.log10(rms / 32768 + 1e-12) < -60
    res["silent_frame_pct"] = round(100 * float(silent.mean()), 1)
    gaps = [(b[0] - a[1]) / sr for a, b in zip(segs, segs[1:])]
    res["gaps_ge_200ms"] = int(sum(g >= 0.2 for g in gaps))

    # 1) Locate deterministic sounds by template over the whole file, pair them in order with
    #    the PLAY lines of the same type.
    det = {name: scan(x, template(name, sr)) for name in TEMPLATES if name != "CELEBRATE"}
    # CELEBRATE starts with 0.38 s of silence under the SQUEAK it is always paired with:
    # match only its note part, then shift back to the nominal start.
    lead = int(0.38 * sr)
    det["CELEBRATE"] = [(o - lead, r) for o, r in scan(x, template("CELEBRATE", sr)[lead:], thr=0.8)]
    res["template_hits"] = {k: len(v) for k, v in det.items()}
    res["play_counts"] = {}
    for t, nme in plays:
        res["play_counts"][nme] = res["play_counts"].get(nme, 0) + 1
    pos = {}
    used = {k: 0 for k in det}
    pairs = []
    for i, (t, nme) in enumerate(plays):
        if nme in det and used[nme] < len(det[nme]):
            o, r = det[nme][used[nme]]
            used[nme] += 1
            pos[i] = (o, r)
            pairs.append((t, o))
    pairs.sort()
    # 2) Wall-clock -> sample map (piecewise linear through matched pairs). On a slow emulator
    #    the stream runs slower than real time, so a fixed offset is not enough.
    if pairs:
        pt = np.array([p[0] for p in pairs]); po = np.array([p[1] for p in pairs], float)
        def tmap(t):
            if t <= pt[0]:
                return po[0] + (t - pt[0]) * sr
            if t >= pt[-1]:
                return po[-1] + (t - pt[-1]) * sr
            return float(np.interp(t, pt, po))
        lags = po / sr - pt
        res["stream_lag_s"] = dict(first=round(float(lags[0]), 3), last=round(float(lags[-1]), 3))
    else:
        tmap = lambda t: t * sr
    events, all_ok = [], True
    for i, (t, nme) in enumerate(plays):
        e = dict(t=round(t, 3), sound=nme)
        if i in pos:
            o, r = pos[i]
            e["match_r"] = round(r, 4)
        elif nme in det:
            o = None  # deterministic sound with no matching waveform in the recording
        else:
            o = find_onset(x, sr, tmap(t), search=0.35)
        if o is None:
            e.update(found=False, ok=False)
        else:
            ok, d = analyse_event(x, sr, nme, o)
            e.update(found=True, onset_s=round(o / sr, 3), ok=ok, **d)
        if nme in CHECKED:
            all_ok &= e["ok"]
        events.append(e)
    res["events"] = events
    spans = [(e["onset_s"] - 0.05, e["onset_s"] + DURATIONS.get(e["sound"], 1) + 0.1) for e in events if e.get("found")]
    unexplained = [round(a / sr, 2) for a, b in segs if not any(s0 <= a / sr <= s1 for s0, s1 in spans)]
    res["unexplained_segments"] = len(unexplained)
    res["unexplained_segments_s"] = unexplained[:20]
    # SCURRY overlaps other sounds a lot (lower xcorr), so allow 10 % misses there.
    counts_ok = all(res["template_hits"][k] == res["play_counts"].get(k, 0) for k in det if k != "SCURRY") \
        and abs(res["template_hits"]["SCURRY"] - res["play_counts"].get("SCURRY", 0)) <= max(1, res["play_counts"].get("SCURRY", 0) // 10)
    res["checks"] = dict(
        spectral_all_events=bool(all_ok),
        template_count_matches_play_count=bool(counts_ok),
        no_clipping=bool(res["clipped_samples"] == 0 and peak / 32767 <= 0.98),
        silence_between_events=bool(res["gaps_ge_200ms"] >= 1 and res["silent_frame_pct"] > 20),
        # SCURRY onsets are located heuristically, so allow a few strays.
        segments_explained=bool(len(unexplained) <= max(2, len(segs) // 20)),
    )
    res["ok"] = all(res["checks"].values())
    return res


def host_mode(host, wav, logf, app_res):
    ax, asr, _ = read_wav(wav)
    a = mono(ax)
    app_secs = len(a) / asr
    hx, hsr, first = read_wav(host, max_seconds=app_secs * 1.5 + 60)
    h = mono(hx)
    res = dict(file=host, sr=hsr, channels=hx.shape[1], tail_seconds_read=round(len(h) / hsr, 1))
    if hsr != asr:
        res.update(ok=False, error=f"sample rate mismatch host={hsr} app={asr}")
        return res
    res["host_peak_fs"] = round(float(np.abs(h).max()) / 32767, 4)
    res["host_clipped"] = int((np.abs(h) >= 32767).sum())
    # Global coarse alignment on 10 ms envelopes.
    ea, _ = frames_rms(a, asr)
    eh, n = frames_rms(h, hsr)
    if len(eh) < len(ea):
        res.update(ok=False, error="host capture shorter than app dump")
        return res
    ea0 = ea - ea.mean()
    corr = np.correlate(eh - eh.mean(), ea0, "valid")
    lag_frames = int(np.argmax(corr))
    base = lag_frames * n
    res["coarse_offset_s_from_tail_start"] = round(base / hsr, 2)
    events, all_ok, corrs, gains = [], True, [], []
    for e in app_res["events"]:
        if not e.get("found"):
            continue
        o = int(e["onset_s"] * asr)
        L = int(0.3 * asr)
        tmpl = a[o:o + L]
        if np.abs(tmpl).max() < 300:
            continue
        g = base + o
        lo, hi = max(0, g - int(0.5 * hsr)), min(len(h), g + L + int(0.5 * hsr))
        win = h[lo:hi]
        if len(win) < L:
            continue
        # FFT cross-correlation of the template within ±0.5 s.
        nfft = 1 << int(np.ceil(np.log2(len(win) + L)))
        cc = np.fft.irfft(np.fft.rfft(win, nfft) * np.conj(np.fft.rfft(tmpl, nfft)), nfft)[: len(win) - L + 1]
        k = int(np.argmax(cc))
        seg = win[k:k + L]
        r = float(np.dot(seg, tmpl) / (np.linalg.norm(seg) * np.linalg.norm(tmpl) + 1e-9))
        gain = float(np.dot(seg, tmpl) / (np.dot(tmpl, tmpl) + 1e-9))
        ho = lo + k
        ok, d = analyse_event(h, hsr, e["sound"], ho)
        ee = dict(sound=e["sound"], app_onset_s=e["onset_s"], host_drift_ms=round(1000 * (ho - g) / hsr, 1),
                  xcorr=round(r, 3), gain=round(gain, 3), ok=bool(ok and r > 0.4), **d)
        corrs.append(r); gains.append(gain)
        if e["sound"] in ("SQUEAK", "CHIRP", "CELEBRATE", "ALL_DONE", "RUSTLE"):
            all_ok &= ee["ok"]
        events.append(ee)
    res["events"] = events
    res["median_xcorr"] = round(float(np.median(corrs)), 3) if corrs else None
    res["median_gain"] = round(float(np.median(gains)), 3) if gains else None
    res["ok"] = bool(events) and all_ok
    return res


def main():
    args = sys.argv[1:]
    out = None
    if "--json" in args:
        i = args.index("--json"); out = args[i + 1]; del args[i:i + 2]
    if not args:
        print(__doc__); sys.exit(2)
    if args[0] == "app" and len(args) == 3:
        res = app_mode(args[1], args[2])
    elif args[0] == "host" and len(args) == 4:
        app = app_mode(args[2], args[3])
        res = host_mode(args[1], args[2], args[3], app)
    else:
        print(__doc__); sys.exit(2)
    s = json.dumps(res, indent=1)
    if out:
        open(out, "w").write(s)
    print(s)
    sys.exit(0 if res["ok"] else 1)


if __name__ == "__main__":
    main()
