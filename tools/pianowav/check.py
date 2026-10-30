"""Checks the WAVs rendered by pianowav (see main.cpp) and prints what to listen for. Usage: py -3 check.py <wav dir>"""
import sys
import wave

import numpy as np

FS = 48000
D = sys.argv[1]


def load(name):
    with wave.open(f"{D}/{name}.wav") as w:
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).reshape(-1, 2) / 32768.0
    return x.mean(axis=1)


def stiffness(key):
    t = [(21, 2.4e-4), (33, 1.7e-4), (45, 1.6e-4), (57, 3.0e-4), (69, 4.93e-4), (81, 1.0e-3), (93, 3.0e-3), (108, 1.6e-2)]
    for (k0, b0), (k1, b1) in zip(t, t[1:]):
        if key <= k1:
            u = (key - k0) / (k1 - k0)
            return float(np.exp(np.log(b0) * (1 - u) + np.log(b1) * u))
    return t[-1][1]


def peak_hz(x, hz, width=0.015):
    """Frequency of the strongest spectral peak within +-width (relative) of hz, interpolated."""
    n = len(x)
    spec = np.abs(np.fft.rfft(x * np.hanning(n), n * 4))
    f = np.fft.rfftfreq(n * 4, 1 / FS)
    sel = np.where((f > hz * (1 - width)) & (f < hz * (1 + width)))[0]
    i = sel[np.argmax(spec[sel])]
    a, b, c = np.log(spec[i - 1:i + 2] + 1e-20)
    return f[i] + (a - c) / (2 * (a - 2 * b + c)) * (f[1] - f[0]), spec[i]


def band_db(x, hz, t0, t1, width=0.01):
    """Level (dB) of the partial near hz between t0 and t1 seconds, from a short-time band energy."""
    seg = x[int(t0 * FS):int(t1 * FS)]
    return 20 * np.log10(peak_hz(seg, hz, width)[1] / len(seg) + 1e-20)


ok = True


def check(cond, msg):
    global ok
    print(("PASS " if cond else "FAIL ") + msg)
    ok = ok and cond


# 1. A4's partials sit where a stiff string puts them: n F sqrt(1 + B n^2)
a4 = load("a4")
B = stiffness(69)
F = 440 / np.sqrt(1 + B)
seg = a4[int(0.15 * FS):int(1.15 * FS)]
worst, checked, top = 0, 0, None
for n in range(1, 13):
    want = n * F * np.sqrt(1 + B * n * n)
    got, amp = peak_hz(seg, want, 0.006)
    top = top or amp
    if amp > top / 100:  # only partials above -40 dB: the rest is too weak to measure
        worst = max(worst, abs(1200 * np.log2(got / want)))
        checked += 1
check(worst < 2.0 and checked >= 6, f"A4: {checked} strong partials within 2 cents of a stiff string (worst {worst:.2f} c)")

# 2. A fortissimo chord does not clip
check(np.abs(load("fff")).max() < 1.0, f"fff chord peak {np.abs(load('fff')).max():.3f} < 1")

# 3. A damped C4 is silent 1 s after release
d = load("damp_c4")
top = np.abs(d[:int(1.1 * FS)]).max()
tail = np.abs(d[int(2.1 * FS):int(2.3 * FS)]).max()
check(20 * np.log10(tail / top + 1e-12) < -60, f"C4 1 s after release: {20 * np.log10(tail / top + 1e-12):.1f} dB")

# 4. Two-stage decay: A4's fundamental fades faster early than late
early = (band_db(a4, 440, 0.3, 0.8) - band_db(a4, 440, 1.3, 1.8))
late = (band_db(a4, 440, 4.0, 4.5) - band_db(a4, 440, 5.0, 5.5))
print(f"     A4 fundamental decay: {early:.1f} dB/s early, {late:.1f} dB/s late")

# 5. Brightness rises with velocity: amplitude-weighted mean partial number of C4 in the scale (vel 0.3, 0.6, 1.0)
sc = load("scale")
B = stiffness(60)
F = 440 * 2 ** (-9 / 12) / np.sqrt(1 + B)
cents = []
for t in (0.2 + 9 * 1.8, 0.2 + 10 * 1.8, 0.2 + 11 * 1.8):
    s = sc[int((t + 0.02) * FS):int((t + 0.5) * FS)]
    a = np.array([peak_hz(s, n * F * np.sqrt(1 + B * n * n), 0.01)[1] for n in range(1, 16)])
    cents.append(float((a * np.arange(1, 16)).sum() / a.sum()))
    print("     C4 partials (dB rel. 1st): " + " ".join(f"{20 * np.log10(x / a[0]):+.0f}" for x in a))
check(cents[0] < cents[1] < cents[2], "C4 brighter as it is struck harder: mean partial " + " / ".join(f"{c:.2f}" for c in cents))

# 6. Loudness across the keyboard (A-weighted RMS of the first 0.5 s), for voicing
def a_weight(f):
    f2 = f * f
    r = (12194 ** 2 * f2 * f2) / ((f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194 ** 2))
    return r / 0.7943


k = load("keys")
levels = []
for j, key in enumerate(range(21, 109, 3)):
    t0 = 0.1 + j * 0.8
    s = k[int(t0 * FS):int((t0 + 0.5) * FS)]
    spec = np.fft.rfft(s)
    f = np.fft.rfftfreq(len(s), 1 / FS)
    levels.append(10 * np.log10((np.abs(spec * a_weight(f)) ** 2).sum() / len(s) ** 2 + 1e-20))
levels = np.array(levels)
print("     A-weighted level by key (dB rel. C4): " + " ".join(f"{key}:{l - levels[13]:+.0f}" for key, l in zip(range(21, 109, 3), levels)))
check(levels.max() - levels.min() < 12, f"keyboard loudness spread {levels.max() - levels.min():.1f} dB < 12")

print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
