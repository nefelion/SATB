// Physically modeled grand piano.
//
// Each key has 1-3 strings, each in two polarizations. A string is a sum of its vibration modes (partials), every one
// tuned exactly to a stiff string's n F sqrt(1 + B n^2) and fading at its own rate. The hammer is a mass on a
// nonlinear, slightly lossy felt spring (F = K x^p, Stulov), solved implicitly against the strings every sample, so a
// harder blow is shorter and brighter, and partials near the strike point's nodes stay weak. The strings of a key
// meet at the bridge, which couples their modes: slightly mistuned unisons beat and decay in two stages (Weinreich).
// The bridge forces of all keys drive the soundboard (its low modes) and a small room; the
// bridge also moves undamped strings, which then ring along (sympathetic resonance). After Bank, Chaigne, Weinreich,
// Stulov.
#include "piano.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace piano {
namespace {

constexpr int LO = 21, KEYS = 88, MAXN = 3;
constexpr int MAXK = 128;   // modes per string, at most
constexpr int MAXKH = 24;   // modes of the horizontal polarization
constexpr int RESK = 12;    // modes of a string that only rings along
constexpr int BLOCK = 256;  // also the delay of the shared bridge signal fed back to the strings
constexpr double PI = 3.14159265358979323846;
constexpr float SILENT = 3e-6f;  // output level below which a key stops being computed (about -110 dB)

int rate = 0;
float T = 1;  // seconds per sample

struct Rng {
    uint32_t s;
    float next() { s = s * 1664525u + 1013904223u; return static_cast<float>(s >> 8) * (1.0f / 16777216.0f); }  // 0..1
    float pm() { return next() * 2 - 1; }  // -1..1
};

// Log-linear interpolation in a table of (key, value).
template <int N> double table(const double (&t)[N][2], double key) {
    if (key <= t[0][0]) return t[0][1];
    for (int i = 1; i < N; i++) if (key <= t[i][0]) {
        double u = (key - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
        return std::exp(std::log(t[i - 1][1]) * (1 - u) + std::log(t[i][1]) * u);
    }
    return t[N - 1][1];
}

double clamp01(double x) { return std::min(1.0, std::max(0.0, x)); }

// String stiffness B: partial n sounds at n F sqrt(1 + B n^2). Lowest in the tenor, steep in the treble;
// at A4 it equals Overtones.piano's, so the overtone lesson draws what it plays.
double stiffness(double key) {
    static const double t[][2] = {{21, 2.4e-4}, {33, 1.7e-4}, {45, 1.6e-4}, {57, 3.0e-4}, {69, 4.93e-4}, {81, 1.0e-3}, {93, 3.0e-3}, {108, 1.6e-2}};
    return table(t, key);
}

// Stretched tuning in cents off equal temperament (A4 = 440 exactly). Tuners match an upper partial of the lower
// note of each octave to the upper note (the 2nd in the treble, the 4th or 6th in the bass), which stretches octaves.
double stretch(int key) {
    auto perOctave = [](double k) {
        double n2 = k >= 60 ? 1 : k >= 48 ? 4 - 3 * (k - 48) / 12 : k >= 36 ? 9 - 5 * (k - 36) / 12 : 9;
        return 1200 / std::log(2.0) * 1.5 * n2 * stiffness(k);
    };
    double c = 0, h = 0.25;
    if (key > 69) for (double k = 69; k < key; k += h) c += perOctave(k - 12 + h / 2) * h / 12;
    else for (double k = 69; k > key; k -= h) c -= perOctave(k - h / 2) * h / 12;
    return c;
}

// The modes of one string polarization. Mode k is a complex phasor z: Im z is its displacement (m) at the
// string's antinode, Re z its velocity over its angular frequency. Each sample z <- p (z + input), p = r e^{iw}.
struct Modes {
    float re[MAXK] = {}, im[MAXK] = {};
    float pr[MAXK] = {}, pi[MAXK] = {};
    float h[MAXK] = {};  // displacement per newton of hammer force, before the rotation
    float c1 = 0;        // displacement at the hammer one sample after a newton of hammer force

    void clear() { std::fill(re, re + MAXK, 0.f); std::fill(im, im + MAXK, 0.f); }
};

enum Mode { OFF, RESONATE, ON };  // RESONATE: only the low modes of the first string, moved by the bridge

float board[BLOCK];      // this block's summed bridge force of the struck keys, N
float boardRing[BLOCK];  // the last BLOCK samples of it, so the resting strings hear it one block late
int ringPos = 0;
bool pedal = false;

struct Key {
    // Fixed after init
    int key = 0, n = 1, K = 1, KH = 1;
    double f = 0;                       // fundamental, Hz
    float phi[MAXK] = {};               // mode shape at the hammer: sin(k pi beta)
    float beta[MAXK] = {};              // bridge force per meter of mode displacement: tension k pi / L (-1)^k
    float drive[MAXK] = {};             // mode input per meter of bridge displacement: 4 f T (-1)^k
    float cb = 0, cbH = 0;              // bridge coupling of the modes of the strings, vertical and horizontal
    float outGain = 0;                  // output per newton of bridge force (voicing)
    double w[MAXN] = {};                // hammer force share per string
    double mass = 0, K0 = 0, p = 0, tau = 0;
    float dampGain = 1, stopGain = 1;   // per-sample gain with the damper down / when stopping
    bool damper = true;
    float gl = 0, gr = 0;               // pan
    float thumpGain = 0, phantom = 0;
    float silentMode = 0;               // mode amplitude (m) below which a top mode is dropped
    Modes v[MAXN], h[MAXN];

    // State
    Mode mode = OFF;
    int top = 0;                        // modes still computed (the highest fade first)
    bool down = false, stopping = false;
    bool hammer = false, contacted = false;
    double yh = 0, vh = 0, xp = 0;
    int sinceContact = 0;
    float damp = 1, level = 0;          // level: peak output of the last block
    float ub = 0;                       // bridge displacement felt by this key, m
    float thump = 0, felt = 0, nlp1 = 0, nlp2 = 0, phLp = 0;
    Rng rng{1};

    bool free() const { return pedal || down || !damper; }

    void clearAll() {
        for (int s = 0; s < MAXN; s++) { v[s].clear(); h[s].clear(); }
        hammer = false; damp = 1; ub = 0; thump = felt = nlp1 = nlp2 = phLp = 0;
    }

    void strike(float vel) {
        if (mode != ON) {
            if (mode == OFF) clearAll();
            for (int s = 1; s < n; s++) v[s].clear();
            for (int s = 0; s < n; s++) h[s].clear();
            for (int k = RESK; k < K; k++) { v[0].re[k] = v[0].im[k] = 0; }
            mode = ON;
        }
        top = K;
        stopping = false;
        down = true;
        // Hammer speed: about 0.5 m/s pianissimo to 6 m/s fortissimo
        vh = 0.3 * std::pow(20.0, vel);
        double ys = 0;
        for (int s = 0; s < n; s++) for (int k = 0; k < K; k++) ys += w[s] * phi[k] * v[s].im[k];
        yh = ys - vh * T * 0.5;
        xp = 0; hammer = true; contacted = false; sinceContact = 0;
        thump = thumpGain * vel * vel;
    }

    void release() {
        down = false;
        if (mode == ON && damper && !pedal) felt = 0.02f;
    }

    // Hammer force over the next sample, solved implicitly: the compression one sample on depends on the force
    // through both the hammer and the strings. [ys]: string displacements at the hammer one sample on without it.
    double hammerForce(const double* ys) {
        double xs = 0, c = T * T / mass;
        for (int s = 0; s < n; s++) { xs += w[s] * ys[s]; c += w[s] * w[s] * v[s].c1; }
        double x0 = yh + T * vh - xs;
        double r = tau / T;
        auto g = [&](double F) { double x = x0 - c * F; return F - K0 * ((1 + r) * (x > 0 ? std::pow(x, p) : 0) - r * xp); };
        double F = 0;
        if (g(0) < 0) {
            // g is increasing and concave: Newton from 0 climbs to the root without overshooting
            for (int it = 0; it < 40; it++) {
                double x = x0 - c * F;
                double gF = g(F);
                if (gF > -1e-9 * (1 + F)) break;
                F -= gF / (1 + (x > 0 ? K0 * (1 + r) * p * std::pow(x, p - 1) * c : 0));
            }
        }
        double x = x0 - c * F;
        vh -= T * F / mass;
        yh += T * vh;
        xp = x > 0 ? std::pow(x, p) : 0;
        if (F > 0) { contacted = true; sinceContact = 0; } else sinceContact++;
        // Done once the hammer has bounced off and stayed clear for 10 ms
        if (sinceContact > rate / 100 && (contacted ? vh < 0 : sinceContact > rate / 10)) hammer = false;
        return F;
    }

    // Advances modes [m] one sample: bridge coupling [S] (summed Re z of all strings), extra input [in], damping [d].
    // Returns the bridge force.
    static float step(Modes& m, int count, const float* S, float cb, const float* in, float d, const float* beta) {
        float out = 0;
        for (int k = 0; k < count; k++) {
            float a = m.re[k] * d - cb * S[k] + in[k], b = m.im[k] * d;
            m.re[k] = m.pr[k] * a - m.pi[k] * b;
            m.im[k] = m.pr[k] * b + m.pi[k] * a;
            out += beta[k] * m.im[k];
        }
        return out;
    }

    // Adds this key's sound for [frames] samples to board, L and R.
    void run(int frames, float* L, float* R) {
        const float cs = 0.03f / 3000;  // soundboard admittance felt by resting strings (m/s per N), scaled down
        const float kd = 1 - std::exp(-1 / (0.015f * rate));
        const float leak = 1 - 20 * T;
        bool isFree = free();
        float target = stopping ? stopGain : (mode == ON && (isFree || hammer)) || (mode == RESONATE && isFree) ? 1 : dampGain;
        float peak = 0;
        const int ns = mode == ON ? n : 1;
        const int kv = mode == ON ? top : std::min(K, RESK);
        const int kh = std::min(KH, kv);
        float S[MAXK], in[MAXK], zero[MAXK];
        std::fill(zero, zero + kv, 0.f);
        for (int i = 0; i < frames; i++) {
            const int idx = (ringPos + i) & (BLOCK - 1);
            // Only resting strings are moved by the bridge, and they do not move it back: no feedback loop can build up
            if (mode == RESONATE && isFree && !stopping) ub = ub * leak + T * cs * boardRing[idx];
            else ub = 0;
            damp += (target - damp) * kd;
            for (int k = 0; k < kv; k++) { float s = 0; for (int j = 0; j < ns; j++) s += v[j].re[k]; S[k] = s; in[k] = drive[k] * ub; }

            float force = 0;
            if (mode == ON && hammer) {
                // The strings without the hammer, then the hammer's force, then its effect
                double ys[MAXN];
                for (int s = 0; s < n; s++) {
                    force += step(v[s], kv, S, cb, in, damp, beta);
                    double y = 0;
                    for (int k = 0; k < kv; k++) y += phi[k] * v[s].im[k];
                    ys[s] = y;
                }
                double F = hammerForce(ys);
                for (int s = 0; s < n; s++) {
                    float fs = static_cast<float>(F * w[s]);
                    Modes& m = v[s];
                    for (int k = 0; k < kv; k++) {
                        float a = m.h[k] * fs;
                        m.re[k] += m.pr[k] * a; m.im[k] += m.pi[k] * a;
                        force += beta[k] * m.pi[k] * a;
                    }
                    // The hammer is not quite vertical: a little goes into the horizontal polarization
                    Modes& mh = h[s];
                    for (int k = 0; k < kh; k++) { float a = 0.08f * mh.h[k] * fs; mh.re[k] += mh.pr[k] * a; mh.im[k] += mh.pi[k] * a; }
                }
            } else {
                for (int s = 0; s < ns; s++) force += step(v[s], kv, S, cb, in, damp, beta);
            }
            float forceH = 0;
            if (mode == ON) {
                for (int k = 0; k < kh; k++) { float s = 0; for (int j = 0; j < n; j++) s += h[j].re[k]; S[k] = s; }
                for (int s = 0; s < n; s++) forceH += step(h[s], kh, S, cbH, zero, damp, beta);
            }
            // Phantom partials: string tension rises with the square of the motion, adding sum tones in the bass
            if (phantom > 0) { float q = force * force; phLp += 0.001f * (q - phLp); force += phantom * (q - phLp); }
            if (mode == ON) board[i] += force;
            float out = (force + 0.25f * forceH) * outGain;
            // Action noises: the key and hammer thump on a strike, the felt on the strings when the damper falls
            if (thump > 1e-6f || felt > 1e-6f) {
                float nz = rng.pm();
                nlp1 += 0.03f * (nz - nlp1); nlp2 += 0.03f * (nlp1 - nlp2);
                float noise = thump * nlp2 * 20 + felt * (nz - nlp1) * 0.2f * level;
                out += noise;
                thump *= 0.9985f; felt *= 0.999f;
            }
            L[i] += out * gl; R[i] += out * gr;
            peak = std::max(peak, std::fabs(out));
        }
        level = peak;
        // The highest modes fade first: drop them once inaudible
        if (mode == ON && !hammer)
            while (top > 1) {
                float a = 0;
                for (int s = 0; s < n; s++) a = std::max(a, std::fabs(v[s].re[top - 1]) + std::fabs(v[s].im[top - 1]));
                if (a > silentMode) break;
                for (int s = 0; s < n; s++) v[s].re[top - 1] = v[s].im[top - 1] = 0;
                top--;
            }
        if (peak < SILENT && !hammer && damp < 1.01f) {
            // Rung down: stop computing, or keep the first string listening if its damper is up
            if (isFree && !stopping) {
                if (mode == ON) {
                    for (int s = 1; s < n; s++) v[s].clear();
                    for (int s = 0; s < n; s++) h[s].clear();
                    for (int k = RESK; k < K; k++) v[0].re[k] = v[0].im[k] = 0;
                    mode = RESONATE;
                }
            } else { mode = OFF; stopping = false; damp = 1; }
        }
    }
};

Key keys[KEYS];

// Room: a feedback delay network of 8 lines (Hadamard mixing), each line losing highs faster. (A short one for the
// soundboard's dense high modes colored single notes by up to 8 dB, so the soundboard is its low modes only.)
struct Fdn {
    static constexpr int N = 8;
    std::vector<float> d[N];
    int at[N] = {};
    float g[N] = {}, lp[N] = {}, damp = 0;

    void init(const float* ms, float t60, float hfDamp) {
        damp = hfDamp;
        for (int k = 0; k < N; k++) {
            int len = std::max(2, static_cast<int>(ms[k] * 0.001f * rate));
            d[k].assign(len, 0.f); at[k] = 0; lp[k] = 0;
            g[k] = std::pow(10.f, -3.f * len / (rate * t60));
        }
    }
    void tick(float in, float& l, float& r) {
        float o[N];
        for (int k = 0; k < N; k++) { float x = d[k][at[k]]; lp[k] += (1 - damp) * (x - lp[k]); o[k] = lp[k]; }
        l = o[0] - o[2] + o[4] - o[6] + 0.5f * (o[1] - o[5]);
        r = o[1] - o[3] + o[5] - o[7] + 0.5f * (o[2] - o[6]);
        // Fast Hadamard transform, scaled to stay lossless
        for (int s = 1; s < N; s <<= 1)
            for (int i = 0; i < N; i += s << 1)
                for (int j = i; j < i + s; j++) { float a = o[j], b = o[j + s]; o[j] = a + b; o[j + s] = a - b; }
        const float norm = 0.35355339f;
        for (int k = 0; k < N; k++) {
            d[k][at[k]] = in * ((k & 1) ? -1.f : 1.f) + o[k] * norm * g[k];
            if (++at[k] == static_cast<int>(d[k].size())) at[k] = 0;
        }
    }
};

struct Mode2 { float a1, a2, b0, x1, x2, y1, y2; };  // two-pole resonator (a soundboard mode)
constexpr int MODES = 24;
Mode2 modes[MODES];
Fdn room;

struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    void highpass(float hz, float q) {
        float w = static_cast<float>(2 * PI * hz / rate), al = std::sin(w) / (2 * q), c = std::cos(w), a0 = 1 + al;
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    float tick(float x) { float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2; x2 = x1; x1 = x; y2 = y1; y1 = y; return y; }
};
Biquad hpL, hpR, hpB;
// The soundboard radiates highs better than lows (below its coincidence frequency): a high shelf of +9.5 dB above
// about 700 Hz; then a gentle high cut
float shL = 0, shR = 0, shA = 0, hiL = 0, hiR = 0, hiA = 0;
float limEnv = 0, limRel = 0;

void setupKey(Key& k, int key) {
    k.key = key;
    Rng rng{static_cast<uint32_t>(key * 7919 + 17)};
    k.rng = Rng{static_cast<uint32_t>(key * 104729 + 3)};
    const double B = stiffness(key);
    const double pitch = 440 * std::pow(2.0, (key - 69) / 12.0 + stretch(key) / 1200);
    k.f = pitch;
    k.n = key <= 28 ? 1 : key <= 41 ? 2 : 3;
    const int n = k.n;
    const double F = pitch / std::sqrt(1 + B);  // partial m at m F sqrt(1 + B m^2)
    auto partial = [&](int m) { return m * F * std::sqrt(1 + B * m * m); };

    // Scale: speaking length (bass shortened, wound) and tension; mass per length and wave impedance follow
    double Lraw = 0.62 * std::pow(2.0, -(key - 60) * 0.95 / 12);
    double len = Lraw / std::sqrt(1 + std::pow(Lraw / 1.95, 2));
    double tension = 800 + 600 * clamp01((48 - key) / 27.0);
    double Z = tension / (2 * len * F);
    double mu = Z / (2 * len * F);

    // Losses. Inside the string (aftersound): air and internal friction, worse up high and for wound bass strings.
    // Through the bridge (prompt sound): all strings moving together decay at promptDb dB/s.
    const double a2 = 0.45 + 1.2 * clamp01((45 - key) / 24.0);
    auto internal = [&](double hz) { return 0.30 + a2 * (hz / 1000) * (hz / 1000); };  // nepers per second
    double promptDb = 2.5 * std::pow(2.0, (key - 21) / 20.0);
    double toBridge = std::max(0.05, promptDb / 8.686 - internal(pitch)) / n;  // per string
    k.cb = static_cast<float>(2 * toBridge * T);
    k.cbH = k.cb / 5;

    k.K = 0;
    while (k.K < MAXK && partial(k.K + 1) < std::min(0.45 * rate, 10000.0)) k.K++;
    k.K = std::max(k.K, 1);
    k.KH = std::min(k.K, MAXKH);
    const double beta = key <= 60 ? 0.122 : 0.122 - 0.047 * (key - 60) / 48.0;  // strike point, share of the length
    for (int m = 0; m < k.K; m++) {
        int j = m + 1;
        double sign = (j & 1) ? -1 : 1;
        k.phi[m] = static_cast<float>(std::sin(j * PI * beta));
        k.beta[m] = static_cast<float>(tension * j * PI / len * sign);
        k.drive[m] = static_cast<float>(4 * F * T * sign);
    }

    // Unisons a hair apart (cents); the horizontal polarizations a little sharper still
    double spread = 0.35 + 0.55 * rng.next();
    double det[MAXN] = {0, 0, 0};
    if (n == 2) { det[0] = -spread / 2; det[1] = spread / 2; }
    if (n == 3) { det[0] = -spread; det[1] = 0.15 * spread; det[2] = 0.85 * spread; }
    const double modalMass = mu * len / 2;
    for (int s = 0; s < n; s++) {
        for (int pol = 0; pol < 2; pol++) {
            Modes& md = pol ? k.h[s] : k.v[s];
            double ratio = std::pow(2.0, (det[s] + (pol ? 0.08 + 0.08 * rng.next() : 0)) / 1200);
            md.c1 = 0;
            for (int m = 0; m < k.K; m++) {
                double hz = partial(m + 1) * ratio, w = 2 * PI * hz;
                double r = std::exp(-internal(hz) * T);
                md.pr[m] = static_cast<float>(r * std::cos(w * T));
                md.pi[m] = static_cast<float>(r * std::sin(w * T));
                md.h[m] = static_cast<float>(T * k.phi[m] / (modalMass * w));
                md.c1 += k.phi[m] * md.pi[m] * md.h[m];
            }
            md.clear();
        }
    }
    double wsum = 0;
    const double share[MAXN] = {1.0, 0.97, 1.03};
    for (int s = 0; s < n; s++) wsum += share[s];
    for (int s = 0; s < n; s++) k.w[s] = share[s] / wsum;

    // Hammer: heavier and softer in the bass. K0 is set so a 2 m/s blow stays on a rigid string contact[] seconds.
    static const double massT[][2] = {{21, 0.011}, {108, 0.004}};
    static const double contactT[][2] = {{21, 0.0028}, {60, 0.001}, {84, 0.0005}, {108, 0.0002}};
    k.mass = table(massT, key);
    k.p = 2.3 + 1.2 * (key - 21) / 87.0;
    k.tau = 5e-6;
    {
        double tc = table(contactT, key), v = 2, q = 1 / (k.p + 1);
        double I = std::sqrt(PI) * std::tgamma(1 + q) / std::tgamma(0.5 + q);
        double xm = tc * v / (2 * I);
        k.K0 = (k.p + 1) * k.mass * v * v / (2 * std::pow(xm, k.p + 1));
    }

    // Dampers up to F#6; they act slower on heavy bass strings
    k.damper = key <= 90;
    static const double dampT[][2] = {{21, 0.5}, {90, 0.12}};
    k.dampGain = static_cast<float>(std::exp(-6.91 / table(dampT, key) * T));
    k.stopGain = static_cast<float>(std::exp(-6.91 / 0.06 * T));

    // Output: bridge force, voiced so the keyboard sounds even; bass to the left, as the player hears it
    // (voiceDb: measured loudness corrections, A-weighted, so every register sounds about as loud)
    static const double voiceDb[][2] = {{21, 3.5}, {33, 2.1}, {45, 1.33}, {57, 0.94}, {69, 0.75}, {81, 0.67}, {93, 0.63}, {102, 0.67}, {108, 1.06}};
    k.outGain = static_cast<float>(0.02 / (n * Z) * std::pow(2.0, -(key - 60) / 60.0) * table(voiceDb, key));
    k.silentMode = static_cast<float>(SILENT * 0.03 / (k.outGain * tension * PI / len * k.K));
    double pan = (key - 64.5) / 87 * 0.7;
    k.gl = static_cast<float>(std::cos((pan + 0.5) * PI / 2) * 1.4142);
    k.gr = static_cast<float>(std::sin((pan + 0.5) * PI / 2) * 1.4142);
    k.thumpGain = 0.004f;
    k.phantom = 0;
}

}  // namespace

void init(int sampleRate) {
    if (sampleRate == rate || sampleRate <= 0) return;
    rate = sampleRate;
    T = 1.0f / rate;
    for (int i = 0; i < KEYS; i++) {
        keys[i] = Key(); setupKey(keys[i], LO + i);
        if (!keys[i].damper) keys[i].mode = RESONATE;  // the top strings have no dampers: always free to ring along
    }
    std::fill(boardRing, boardRing + BLOCK, 0.f);
    ringPos = 0;
    const float rm[] = {23.3f, 29.1f, 33.7f, 39.1f, 43.9f, 51.7f, 57.1f, 63.7f};
    room.init(rm, 1.6f, 0.5f);
    Rng rng{12345};
    for (int m = 0; m < MODES; m++) {
        double hz = 48 * std::pow(600.0 / 48, (m + 0.5 * rng.next()) / MODES);
        double q = 8 + 12 * rng.next();
        double r = std::exp(-PI * hz / (q * rate));
        float gain = (0.5f + 0.5f * rng.next()) * (rng.next() < 0.5f ? -1.f : 1.f);
        modes[m] = {static_cast<float>(2 * r * std::cos(2 * PI * hz / rate)), static_cast<float>(-r * r),
                    static_cast<float>((1 - r * r) / 2 * gain), 0, 0, 0, 0};
    }
    hpL = Biquad(); hpR = Biquad(); hpB = Biquad();
    hpL.highpass(55, 0.7f); hpR.highpass(55, 0.7f); hpB.highpass(55, 0.7f);
    hiA = std::exp(-2 * static_cast<float>(PI) * 9000 / rate);
    shA = 1 - std::exp(-2 * static_cast<float>(PI) * 700 / rate);
    limRel = std::exp(-1 / (0.15f * rate));
    limEnv = 0;
}

void noteOn(int key, float velocity) {
    if (rate == 0 || key < LO || key >= LO + KEYS) return;
    keys[key - LO].strike(std::min(1.f, std::max(0.f, velocity)));
}

void noteOff(int key) {
    if (rate == 0 || key < LO || key >= LO + KEYS) return;
    keys[key - LO].release();
}

void sustain(bool down) {
    if (rate == 0 || down == pedal) return;
    pedal = down;
    for (Key& k : keys) {
        if (down && k.mode == OFF && !k.stopping) { k.clearAll(); k.mode = RESONATE; }
        if (!down && k.mode == ON && !k.down && k.damper) k.felt = 0.02f;
    }
}

void allOff() {
    pedal = false;
    for (Key& k : keys) { k.down = false; k.hammer = false; if (k.mode != OFF) k.stopping = true; }
}

void render(float* out, int frames) {
    if (rate == 0) { std::fill(out, out + frames * 2, 0.f); return; }
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    asm volatile("msr fpcr, %0" ::"r"(fpcr | (1ull << 24)));  // flush denormals: long decays would crawl
#elif defined(__x86_64__) || defined(__i386__)
    _mm_setcsr(_mm_getcsr() | 0x8040);
#endif
    float L[BLOCK], R[BLOCK];
    while (frames > 0) {
        int nf = std::min(frames, BLOCK);
        std::fill(board, board + nf, 0.f);
        std::fill(L, L + nf, 0.f); std::fill(R, R + nf, 0.f);
        for (Key& k : keys) if (k.mode != OFF) k.run(nf, L, R);
        for (int i = 0; i < nf; i++) {
            boardRing[(ringPos + i) & (BLOCK - 1)] = board[i];
            float b = hpB.tick(0.5f * (L[i] + R[i]));
            float body = 0;
            for (Mode2& m : modes) {
                float y = m.b0 * (b - m.x2) + m.a1 * m.y1 + m.a2 * m.y2;
                m.x2 = m.x1; m.x1 = b; m.y2 = m.y1; m.y1 = y;
                body += y;
            }
            float rl, rr;
            room.tick(0.5f * (L[i] + R[i]) + 0.3f * body, rl, rr);
            float dl = hpL.tick(L[i]), dr = hpR.tick(R[i]);
            shL += shA * (dl - shL); shR += shA * (dr - shR);
            hiL = (1 - hiA) * (dl + 2 * (dl - shL)) + hiA * hiL;
            hiR = (1 - hiA) * (dr + 2 * (dr - shR)) + hiA * hiR;
            float l = hiL + 0.3f * body + 0.15f * rl;
            float r = hiR + 0.3f * body + 0.15f * rr;
            // Peak limiter: linear below -1 dBFS, so chords stay clean
            limEnv = std::max(std::max(std::fabs(l), std::fabs(r)), limEnv * limRel);
            float gain = limEnv > 0.89f ? 0.89f / limEnv : 1.f;
            out[2 * i] = l * gain;
            out[2 * i + 1] = r * gain;
        }
        ringPos = (ringPos + nf) & (BLOCK - 1);
        out += nf * 2;
        frames -= nf;
    }
}

}  // namespace piano
