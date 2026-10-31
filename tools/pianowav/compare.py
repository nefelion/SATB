"""Compares the WAVs rendered by pianowav (see main.cpp) with a reference piano that played the same .mid files.
Usage: py -3 compare.py <ours dir> <reference dir>. Prints ours vs reference; lower |diff| is closer."""
import os
import sys

import numpy as np
from scipy.signal import coherence

from check import FS, peak_hz, stiffness

OURS, REF = sys.argv[1], sys.argv[2]
ONSET = 0.1  # every test's first event, s


def load2(path):
    """Stereo file as an (n, 2) float array at FS, shifted so the first sound starts at ONSET."""
    import wave
    with wave.open(path) as w:
        assert w.getframerate() == FS, f"{path}: {w.getframerate()} Hz, need {FS}"
        ch, width = w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    if width == 2:
        x = np.frombuffer(raw, dtype=np.int16) / 32768.0
    elif width == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        x = ((b[:, 0].astype(np.int32) | b[:, 1].astype(np.int32) << 8 | b[:, 2].astype(np.int32) << 16) << 8 >> 8) / 8388608.0
    else:
        x = np.frombuffer(raw, dtype=np.int32) / 2147483648.0
    x = x.reshape(-1, ch)
    if ch == 1:
        x = np.repeat(x, 2, axis=1)
    m = np.abs(x).max(axis=1)
    on = int(np.argmax(m > m.max() * 0.01))
    shift = on - int(ONSET * FS) - int(0.002 * FS)  # the threshold trips a little after the true onset
    return x[shift:] if shift > 0 else np.vstack([np.zeros((-shift, 2)), x])


def pair(name):
    a, b = os.path.join(OURS, name + ".wav"), os.path.join(REF, name + ".wav")
    if not (os.path.exists(a) and os.path.exists(b)):
        return None
    x, y = load2(a), load2(b)
    n = min(len(x), len(y))
    return x[:n], y[:n] * GAIN


def a_weight(f):
    f2 = f * f
    return (12194 ** 2 * f2 * f2) / ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194 ** 2)) / 0.7943


def a_level(s):
    spec = np.fft.rfft(s)
    f = np.fft.rfftfreq(len(s), 1 / FS)
    return 10 * np.log10((np.abs(spec * a_weight(f)) ** 2).sum() / len(s) ** 2 + 1e-20)


def seg(x, t0, t1):
    return x[int(t0 * FS):int(t1 * FS)].mean(axis=1)


def partial_db(s, hz):
    return 20 * np.log10(peak_hz(s, hz, max(0.012, 6 / hz))[1] / len(s) + 1e-20)


BANDS = 1000 * 2.0 ** (np.arange(-14, 11) / 3)  # 1/3 octaves, 40 Hz .. 10 kHz


def bands_db(s):
    p = np.abs(np.fft.rfft(s * np.hanning(len(s)))) ** 2
    f = np.fft.rfftfreq(len(s), 1 / FS)
    out = np.array([p[(f >= c * 2 ** (-1 / 6)) & (f < c * 2 ** (1 / 6))].sum() for c in BANDS])
    out = 10 * np.log10(out + 1e-30)
    return out - out.max()


def row(label, ours, ref, fmt="{:+.0f}"):
    print(f"  {label:<10}" + " ".join(f"{fmt.format(o)}/{fmt.format(r)}" for o, r in zip(ours, ref)))


# One gain for every reference file, so mf middle C is equally loud in both
x0, y0 = load2(os.path.join(OURS, "n60_80.wav")), load2(os.path.join(REF, "n60_80.wav"))
GAIN = 10 ** ((a_level(seg(x0, 0.1, 0.6)) - a_level(seg(y0, 0.1, 0.6))) / 20)
print(f"reference gain {20 * np.log10(GAIN):+.1f} dB (matches A-weighted level of n60_80)")

# Single notes: partial spectrum, decays, attack brightness, loudness
print("\nSINGLE NOTES (ours/ref)")
loud, bright = [], []
for key in (21, 36, 48, 55, 60, 64, 69, 72, 84, 96):
    B = stiffness(key)
    f0 = 440 * 2 ** ((key - 69) / 12)
    F = f0 / np.sqrt(1 + B)
    parts = [n for n in range(1, 13) if n * F * np.sqrt(1 + B * n * n) < 9000]
    for v in (40, 80, 127):
        p = pair(f"n{key}_{v}")
        if p is None:
            continue
        x, y = p
        print(f" n{key}_{v}")
        lv = []
        for s in (x, y):
            a = seg(s, 0.15, 0.65)
            lv.append([partial_db(a, n * F * np.sqrt(1 + B * n * n)) for n in parts])
        row("partials", [l - lv[0][0] for l in lv[0]], [l - lv[1][0] for l in lv[1]])
        dec = []
        for s in (x, y):
            d = []
            for n in (1, 2, 3):
                hz = n * F * np.sqrt(1 + B * n * n)
                e = partial_db(seg(s, 0.3, 0.8), hz) - partial_db(seg(s, 1.3, 1.8), hz)
                l = (partial_db(seg(s, 3.5, 4.0), hz) - partial_db(seg(s, 5.0, 5.5), hz)) / 1.5
                d += [e, l]
            dec.append(d)
        row("dB/s e,l", dec[0], dec[1])
        cen = []
        for s in (x, y):
            a = seg(s, 0.1, 0.13)
            sp = np.abs(np.fft.rfft(a * np.hanning(len(a))))
            f = np.fft.rfftfreq(len(a), 1 / FS)
            cen.append((sp * f).sum() / sp.sum())
        row("attack Hz", [cen[0]], [cen[1]])
        ar = [a_level(seg(s, 0.1, 0.6)) - a_level(seg(z, 0.1, 0.6)) for s, z in ((x, x0), (y, y0 * GAIN))]
        row("level dB", [ar[0]], [ar[1]])
        loud.append(ar[0] - ar[1])
        bright.append(cen[0] / cen[1])
if loud:
    print(f" level diff rms {np.sqrt(np.mean(np.square(loud))):.1f} dB; attack centroid ratio ours/ref median {np.median(bright):.2f}")

# Chords: 1/3-octave long-term spectrum (shape only); the mean difference is the radiation EQ to fit
print("\nCHORD SPECTRA, ours - ref, dB per 1/3 octave from " + f"{BANDS[0]:.0f} Hz to {BANDS[-1]:.0f} Hz")
print("  " + " ".join(f"{c:>4.0f}" if c < 1000 else f"{c / 1000:>3.1f}k" for c in BANDS))
diffs = []
for name in ("chord_close", "chord_open", "satb_p", "satb_mf", "satb_f", "cadence", "fff", "pedal"):
    p = pair(name)
    if p is None:
        continue
    x, y = p
    d = bands_db(seg(x, 0.1, 2.5)) - bands_db(seg(y, 0.1, 2.5))
    diffs.append(d)
    print("  " + " ".join(f"{v:>+4.0f}" for v in d) + f"  {name}")
if diffs:
    m = np.mean(diffs, axis=0)
    sel = (BANDS >= 100) & (BANDS <= 5000)
    print("  " + " ".join(f"{v:>+4.0f}" for v in m) + "  MEAN")
    print(f"  worst |mean| 100 Hz-5 kHz: {np.abs(m[sel]).max():.1f} dB")

# Stereo width: L/R coherence by band (1 = mono)
p = pair("satb_mf")
if p:
    print("\nSTEREO COHERENCE satb_mf (ours/ref) at 100 / 300 / 1k / 3k Hz")
    co = []
    for s in p:
        f, c = coherence(s[int(0.1 * FS):int(2.5 * FS), 0], s[int(0.1 * FS):int(2.5 * FS), 1], FS, nperseg=4096)
        co.append([c[np.argmin(np.abs(f - h))] for h in (100, 300, 1000, 3000)])
    row("coherence", co[0], co[1], "{:.2f}")

# Peaks and crest factor of loud chords (clipping, limiting)
print("\nPEAK dBFS / CREST dB (ours/ref)")
for name in ("fff", "satb_f"):
    p = pair(name)
    if p:
        pk = [20 * np.log10(np.abs(s).max() + 1e-12) for s in p]
        cr = [20 * np.log10(np.abs(s).max() / (np.sqrt(np.mean(s[int(0.1 * FS):int(1.1 * FS)] ** 2)) + 1e-12)) for s in p]
        print(f"  {name:<10}peak {pk[0]:+.1f}/{pk[1]:+.1f}  crest {cr[0]:.1f}/{cr[1]:.1f}")

# Interplay: held C3 G3 E4 taking up C5 (struck 2.0 s, damped 2.4 s); its 1st and 2nd partial, after vs before
p = pair("interplay")
if p:
    print("\nINTERPLAY: held chord's level at C5's partials, 2.8-3.8 s minus 1.4-1.9 s, dB (ours/ref)")
    r = [[partial_db(seg(s, 2.8, 3.8), hz) - partial_db(seg(s, 1.4, 1.9), hz) for hz in (523.3, 1046.5)] for s in p]
    row("C5 1st,2nd", r[0], r[1])

# Sympathetic ring: pedal down vs up after C4's release
p, q = pair("res_c4"), pair("nores_c4")
if p and q:
    r = [a_level(seg(a, 0.8, 2.0)) - a_level(seg(b, 0.8, 2.0)) for a, b in ((p[0], q[0]), (p[1], q[1]))]
    print(f"\nPEDAL RING after release (with - without pedal): ours {r[0]:+.1f} dB, ref {r[1]:+.1f} dB")

# Envelopes: A-weighted level over time, dB rel. the first window (decay of a held chord, tail after release)
print("\nENVELOPES dB (ours/ref)")
for name, wins in (("satb_mf", ((0.1, 0.3), (0.5, 0.7), (1, 1.2), (2, 2.2), (3.5, 3.7), (4.3, 4.5), (4.6, 4.8))),
                   ("damp_c4", ((0.1, 0.3), (0.8, 1.0), (1.15, 1.25), (1.3, 1.4), (1.5, 1.7), (2.0, 2.3)))):
    p = pair(name)
    if p:
        e = [[a_level(seg(s, a, b)) for a, b in wins] for s in p]
        row(name, [v - e[0][0] for v in e[0]], [v - e[1][0] for v in e[1]])
        print("            at " + " ".join(f"{a:g}-{b:g}" for a, b in wins))
