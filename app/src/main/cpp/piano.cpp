// The crude piano, a physically modeled grand.
//
// Each key has 1-3 strings, each in two polarizations. A string is a sum of its vibration modes (partials), every one
// tuned exactly to a stiff string's n F sqrt(1 + B n^2) and fading at its own rate. The hammer is a mass on a
// nonlinear, slightly lossy felt spring (F = K x^p, Stulov), solved implicitly against the strings every sample, so a
// harder blow is shorter and brighter, and partials near the strike point's nodes stay weak. The strings of a key
// meet at the bridge, which couples their modes: slightly mistuned unisons beat and decay in two stages (Weinreich).
// Part of that coupling goes through one bridge all keys share (a resistive load, so the coupling only loses
// energy): held notes take up the partials of new ones and undamped strings ring along (sympathetic resonance). The
// soundboard drains each partial at its own rate, faster the higher it is, so a blow starts bright and mellows. The
// bridge forces drive the soundboard and a small room; each blow also rings the board's low modes (the wooden body).
// After Bank, Chaigne, Weinreich, Stulov.
#include "piano.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdint>
#include <vector>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace piano {
namespace {

constexpr int LO = 21, KEYS = 88, MAXN = 3;
constexpr int MAXK = 128;   // modes per string, at most
constexpr int MAXKH = 48;   // modes of the horizontal polarization
constexpr int RESK = 12;    // modes of a string that only rings along
constexpr int BLOCK = 256;
constexpr double PI = 3.14159265358979323846;
constexpr double HARDEN = 8;    // felt stiffness ratio from velocity 0 to 1 (felt stiffens beyond its power law)
constexpr float SHARE = 0.15f;    // share of each string's bridge coupling that goes through the bridge all keys share
constexpr float SHELF = 1.5f;    // extra treble the soundboard radiates above 700 Hz (1.5: +8 dB)
constexpr double BODYK = 0.0005;  // how hard each blow rings the soundboard's low modes (voicing)
constexpr float KNOCK = 0.05f;    // the hammer's blow reaching the soundboard through the frame, rel. the string sound
constexpr float CLICK = 0.5f;     // the felt's click on the strings, rel. the string sound
constexpr float SILENT = 3e-6f;  // output level below which a key stops being computed (about -110 dB)

int rate = 0;
float T = 1;  // seconds per sample
float riseRate = 1;  // per-sample rate of each stage of a blow's build-up

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
// A4_PIANO in Overtones.kt is measured from this piano: re-measure it when this changes.
double stiffness(double key) {
    static const double t[][2] = {{21, 3.0e-4}, {36, 1.3e-4}, {48, 1.67e-4}, {55, 2.3e-4}, {60, 3.56e-4}, {64, 5.1e-4}, {69, 8.3e-4}, {72, 1.1e-3}, {84, 3.0e-3}, {96, 8.45e-3}, {108, 2.4e-2}};  // fitted to a reference
    return table(t, key);
}

// Stretched tuning in cents off equal temperament (A4 = 440 exactly). Tuners match an upper partial of the lower
// note of each octave to the upper note (the 2nd in the treble, the 4th or 6th in the bass), which stretches octaves.
double stretch(int key) {
    // Measured on a reference: wider than the stiffness alone asks for, so chords and octaves ring clean
    static const double t[][2] = {{21, -24}, {36, -7}, {48, -4.5}, {55, -1.5}, {60, -0.8}, {69, 0}, {72, 0.9}, {84, 4.6}, {96, 13.7}, {108, 30}};
    for (int i = 1; i < 10; i++)
        if (key <= t[i][0]) return t[i - 1][1] + (t[i][1] - t[i - 1][1]) * (key - t[i - 1][0]) / (t[i][0] - t[i - 1][0]);
    return t[9][1];
}

// The modes of one string polarization. Mode k is a complex phasor z: Im z is its displacement (m) at the
// string's antinode, Re z its velocity over its angular frequency. Each sample z <- p (z + input), p = r e^{iw}.
struct Modes {
    float re[MAXK] = {}, im[MAXK] = {};
    float pr[MAXK] = {}, pi[MAXK] = {};
    float h[MAXK] = {};  // displacement per newton of hammer force, before the rotation
    // Bridge force per meter of mode displacement: tension k pi / L (-1)^k, voiced, as the left ear hears it (bl) and
    // the right (br on Im z, brq on Re z: phase-shifted). Each string and polarization radiates from its own spots on
    // the soundboard, so the ears hear the strings drift in and out of phase differently.
    float bl[MAXK] = {}, br[MAXK] = {}, brq[MAXK] = {};
    float c1 = 0;        // displacement at the hammer one sample after a newton of hammer force

    void clear() { std::fill(re, re + MAXK, 0.f); std::fill(im, im + MAXK, 0.f); }
};

enum Mode { OFF, RESONATE, ON };  // RESONATE: only the low modes of the first string, moved by the bridge

bool pedal = false;

struct Key {
    // Fixed after init
    int key = 0, n = 1, K = 1, KH = 1;
    double f = 0;                       // fundamental, Hz
    float phi[MAXK] = {};               // mode shape at the hammer: sin(k pi beta)
    float cb[MAXK] = {}, cbH[MAXK] = {};  // bridge coupling of each mode of the strings, vertical and horizontal
    float own[MAXK] = {};               // the part of cb through this key's own bridge point
    // The reactive part of the bridge coupling (the bridge yields like a spring or a mass, partial by partial): it
    // pulls the strings' joint motion off their own pitch, so even well-tuned unisons beat (Weinreich)
    float cx[MAXK] = {}, cxH[MAXK] = {};
    float sq[MAXK] = {};                // sqrt(cb) (-1)^k: each mode's coupling to the shared bridge
    float outGain = 0;                  // output per newton of bridge force (voicing)
    double w[MAXN] = {};                // hammer force share per string
    double mass = 0, K0 = 0, p = 0, tau = 0;
    double Kv = 0;                      // felt stiffness for this blow: harder felt the harder it is struck
    float dampGain = 1, stopGain = 1;   // per-sample gain with the damper down / when stopping
    bool damper = true;
    float gl = 0, gr = 0;               // pan
    float thumpGain = 0, phantom = 0;
    float body = 0;                     // how hard the blow rings the soundboard's low modes, per newton
    float horiz = 0.2f;                 // share of the blow that goes into the horizontal polarization (the aftersound)
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
    float rise1 = 1, rise2 = 1, rise = 1;  // output gain building up after a blow (three one-pole stages)
    float S[MAXK] = {};                 // summed Re z of the strings, per mode (set by prep())
    float g = 0;                        // this key's load on the shared bridge (set by prep())
    float thump = 0, felt = 0, nlp1 = 0, nlp2 = 0, nlp3 = 0, nlp4 = 0, nlp5 = 0, phLp = 0;
    float click = 0;                    // the felt's click on the strings while in contact
    float clickNz[5] = {};              // its noise: the low part (taken out), then four low-pass stages
    Rng rng{1};

    bool free() const { return pedal || down || !damper; }

    void clearAll() {
        for (int s = 0; s < MAXN; s++) { v[s].clear(); h[s].clear(); }
        hammer = false; damp = 1; g = 0; thump = felt = nlp1 = nlp2 = nlp3 = nlp4 = nlp5 = phLp = click = 0;
        std::fill(clickNz, clickNz + 5, 0.f);
    }

    void strike(float vel) {
        // No two blows quite alike: the hammer leaves the action a little faster or slower each time
        vel = std::min(1.f, std::max(0.f, vel * (1 + 0.04f * rng.pm())));
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
        vh = 0.3 * std::pow(20.0, 0.6 + (vel - 0.6) * (1 - 0.4 * clamp01((key - 72) / 24.0)));  // narrower range up high
        Kv = K0 * std::pow(HARDEN, (vel - 0.6) * (1 - 0.8 * clamp01((key - 72) / 24.0)));  // less so in the treble
        double ys = 0;
        for (int s = 0; s < n; s++) for (int k = 0; k < K; k++) ys += w[s] * phi[k] * v[s].im[k];
        yh = ys - vh * T * 0.5;
        xp = 0; hammer = true; contacted = false; sinceContact = 0;
        thump = thumpGain * vel * vel;
        rise1 = rise2 = rise = 0;
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
        auto g = [&](double F) { double x = x0 - c * F; return F - Kv * ((1 + r) * (x > 0 ? std::pow(x, p) : 0) - r * xp); };
        double F = 0;
        if (g(0) < 0) {
            // g is increasing and concave: Newton from 0 climbs to the root without overshooting
            for (int it = 0; it < 40; it++) {
                double x = x0 - c * F;
                double gF = g(F);
                if (gF > -1e-9 * (1 + F)) break;
                F -= gF / (1 + (x > 0 ? Kv * (1 + r) * p * std::pow(x, p - 1) * c : 0));
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

    // Advances modes [m] one sample: bridge coupling [S] (summed Re z of all strings; [cb] resistive, [cx] reactive),
    // extra input [in], damping [d]. Adds the bridge force to [outL], [outR] (weighted for each ear).
    static void step(Modes& m, int count, const float* S, const float* cb, const float* cx, const float* in, float d,
                     float& outL, float& outR) {
        float ol = 0, orr = 0;
        for (int k = 0; k < count; k++) {
            float a = m.re[k] * d - cb[k] * S[k] + in[k], b = m.im[k] * d - cx[k] * S[k];
            m.re[k] = m.pr[k] * a - m.pi[k] * b;
            m.im[k] = m.pr[k] * b + m.pi[k] * a;
            ol += m.bl[k] * m.im[k]; orr += m.br[k] * m.im[k] + m.brq[k] * m.re[k];
        }
        outL += ol; outR += orr;
    }

    // Fixed for one block: set by begin()
    bool isFree = false;
    float target = 1, peak = 0;
    int ns = 1, kv = 1, kh = 1;

    void begin() {
        isFree = free();
        target = stopping ? stopGain : (mode == ON && (isFree || hammer)) || (mode == RESONATE && isFree) ? 1 : dampGain;
        peak = 0;
        ns = mode == ON ? n : 1;
        kv = mode == ON ? top : std::min(K, RESK);
        kh = std::min(KH, kv);
    }

    // This sample's load on the shared bridge, before any key moves on
    void prep() {
        float sum = 0;
        for (int k = 0; k < kv; k++) { float s = 0; for (int j = 0; j < ns; j++) s += v[j].re[k]; S[k] = s; sum += sq[k] * s; }
        g = sum;
    }

    // One sample; [total]: the summed load g of all keys. Adds the sound to [l], [r], the knock to [kn] and the blow
    // the soundboard's low modes take up to [kb].
    void tick(float total, float& l, float& r, float& kn, float& kb) {
        const float kd = 1 - std::exp(-1 / (0.015f * rate));
        static const float zero[MAXK] = {};
        float in[MAXK];
        const float c = -SHARE * total;
        damp += (target - damp) * kd;
        if (std::fabs(target - damp) < 1e-4f) damp = target;  // float steps stall about 2e-5 short (9 dB/s too fast)
        for (int k = 0; k < kv; k++) in[k] = c * sq[k];

        float f = 0, fR = 0;
        if (mode == ON && hammer) {
            // The strings without the hammer, then the hammer's force, then its effect
            double ys[MAXN];
            for (int s = 0; s < n; s++) {
                step(v[s], kv, S, own, cx, in, damp, f, fR);
                double y = 0;
                for (int k = 0; k < kv; k++) y += phi[k] * v[s].im[k];
                ys[s] = y;
            }
            double F = hammerForce(ys);
            kn += static_cast<float>(F) * outGain * KNOCK;
            kb += static_cast<float>(F) * outGain * body;
            click = static_cast<float>(F) * outGain * CLICK;
            for (int s = 0; s < n; s++) {
                float fs = static_cast<float>(F * w[s]);
                Modes& m = v[s];
                for (int k = 0; k < kv; k++) {
                    float a = m.h[k] * fs;
                    m.re[k] += m.pr[k] * a; m.im[k] += m.pi[k] * a;
                    f += m.bl[k] * m.pi[k] * a; fR += m.br[k] * m.pi[k] * a + m.brq[k] * m.pr[k] * a;
                }
                // The hammer is not quite vertical: a little goes into the horizontal polarization
                Modes& mh = h[s];
                for (int k = 0; k < kh; k++) { float a = horiz * mh.h[k] * fs; mh.re[k] += mh.pr[k] * a; mh.im[k] += mh.pi[k] * a; }
            }
        } else {
            click = 0;
            // A string only ringing along keeps its own pitch, so it takes up the partials it is tuned to
            for (int s = 0; s < ns; s++) step(v[s], kv, S, own, mode == ON ? cx : zero, in, damp, f, fR);
        }
        float forceH = 0, forceHR = 0;
        if (mode == ON) {
            float SH[MAXKH];
            for (int k = 0; k < kh; k++) { float s = 0; for (int j = 0; j < n; j++) s += h[j].re[k]; SH[k] = s; }
            for (int s = 0; s < n; s++) step(h[s], kh, SH, cbH, cxH, zero, damp, forceH, forceHR);
        }
        // Phantom partials: string tension rises with the square of the motion, adding sum tones in the bass
        if (phantom > 0) { float q = f * f; phLp += 0.001f * (q - phLp); float e = phantom * (q - phLp); f += e; fR += e; }
        float out = (f + forceH) * outGain, outR = (fR + forceHR) * outGain;
        // Action noises: the key and hammer thump on a strike, the felt on the strings when the damper falls
        if (thump > 1e-6f || felt > 1e-6f) {
            float nz = rng.pm();
            nlp1 += 0.06f * (nz - nlp1); nlp2 += 0.06f * (nlp1 - nlp2); nlp4 += 0.06f * (nlp2 - nlp4); nlp5 += 0.06f * (nlp4 - nlp5);
            nlp3 += 0.012f * (nlp5 - nlp3);  // thump: about 90-460 Hz, falling off steeply above (no hiss)
            float noise = (thump * 20 + felt * level) * (nlp5 - nlp3);
            out += noise; outR += noise;
            thump *= 0.9985f; felt *= 0.999f;
        }
        // A blow takes a few ms to travel down the string and get the board going: the sound builds up from silence
        // over about 4 ms (-18 dB after 1 ms, -7 dB after 2) instead of snapping on
        rise1 += (1 - rise1) * riseRate; rise2 += (rise1 - rise2) * riseRate; rise += (rise2 - rise) * riseRate;
        out *= rise; outR *= rise;
        // The felt's click on the strings while it touches them: a faint tick, most of it 1-4 kHz
        if (click > 0) {
            float nz = rng.pm(), (&c)[5] = clickNz;
            c[0] += 0.15f * (nz - c[0]);
            c[1] += 0.25f * (nz - c[0] - c[1]); c[2] += 0.25f * (c[1] - c[2]); c[3] += 0.25f * (c[2] - c[3]); c[4] += 0.25f * (c[3] - c[4]);
            out += click * c[4]; outR += click * c[4];
        }
        l += out * gl; r += outR * gr;
        peak = std::max(peak, std::max(std::fabs(out), std::fabs(outR)));
    }

    void end() {
        if (!(peak < 100)) { clearAll(); mode = OFF; stopping = false; peak = 0; }  // never let a blow-up ring on
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
        // A damped key stops once its own sound is gone (about -80 dB), not kept faintly alive by the shared bridge
        if (peak < (isFree ? SILENT : 30 * SILENT) && !hammer && damp < 1.01f) {
            // Rung down: stop computing, or keep the first string listening if its damper is up
            if (isFree && !stopping) {
                if (mode == ON) {
                    for (int s = 1; s < n; s++) v[s].clear();
                    for (int s = 0; s < n; s++) h[s].clear();
                    for (int k = RESK; k < K; k++) v[0].re[k] = v[0].im[k] = 0;
                    mode = RESONATE;
                }
            } else { mode = OFF; stopping = false; damp = 1; g = 0; }
        }
    }
};

Key keys[KEYS];

// Room: a feedback delay network of 8 lines (Hadamard mixing), each line losing highs faster.
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

// Soundboard: dense wooden modes the bridge drives. They color every note a little differently and ring briefly
// after each blow (the "wood" between the partials). Each mode reaches the two ears with its own gain, which also
// spreads the sound in stereo.
struct Mode2 { float a1, a2, b0, gl, gr, x1, x2, y1, y2; };  // two-pole resonator
constexpr int MODES = 96;
constexpr float BOARD = 0.2f;  // share of the sound that comes through the soundboard modes, the rest straight
Mode2 modes[MODES];
// The soundboard's low modes (60-700 Hz) as each blow rings them: the wooden body under a note, most of it below the
// pitch of a treble note. Each fades at its own rate, all with about the same energy, the higher ones faster (10 to
// 85 dB/s near 100 Hz, 110 to 340 dB/s at 500 Hz): a knock that leaves a short wooden ring, and under it the board's
// lowest modes (the strongest near 100 Hz) blooming on for a second or two.
constexpr int BODY = 96;
Mode2 bodyModes[BODY];
// The blow reaches the board with the string's sound (about 1.5 ms on), not ahead of it
float knockLine[256];
int knockAt = 0, knockLen = 1;
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
// The soundboard radiates highs better than lows (below its coincidence frequency): a high shelf of +8 dB above
// about 700 Hz; then a gentle high cut
float shL = 0, shR = 0, shA = 0, hiL = 0, hiR = 0, hiA = 0;

// Linear up to -3 dBFS, then bends smoothly toward full scale: no gain riding, so loud chords never pump
float softClip(float x) {
    const float knee = 0.7f, span = 0.29f;
    float a = std::fabs(x);
    return a <= knee ? x : std::copysign(knee + span * std::tanh((a - knee) / span), x);
}

// Silences the shared body and room (after a blow-up, so it cannot ring on)
void quietOutput() {
    for (auto& d : room.d) std::fill(d.begin(), d.end(), 0.f);
    std::fill(room.lp, room.lp + Fdn::N, 0.f);
    for (Mode2& m : modes) m.x1 = m.x2 = m.y1 = m.y2 = 0;
    for (Mode2& m : bodyModes) m.x1 = m.x2 = m.y1 = m.y2 = 0;
    std::fill(knockLine, knockLine + 256, 0.f);
    hpL = hpR = hpB = Biquad();
    hpL.highpass(25, 0.7f); hpR.highpass(25, 0.7f); hpB.highpass(25, 0.7f);
    shL = shR = hiL = hiR = 0;
}

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
    // Through the bridge (the prompt sound): the soundboard drains every partial, the more the higher it is (the board
    // is more mobile up high), each partial meeting its own mix of board resonances. All strings of a key moving
    // together, the fundamental fades at promptDb dB/s. So a blow starts bright, mellows within a fraction of a second,
    // and leaves the slow aftersound: the strings drifting apart and the horizontal polarization.
    const double a2 = 0.03 + 0.06 * clamp01((45 - key) / 24.0) + 0.08 * clamp01((key - 55) / 24.0);
    static const double a0T[][2] = {{84, 0.3}, {96, 1.5}, {108, 3.0}};  // the top octave rings short
    const double a0 = table(a0T, key);
    auto internal = [&](double hz) { return a0 + a2 * (hz / 1000) * (hz / 1000); };  // nepers per second
    static const double promptT[][2] = {{21, 4}, {36, 6}, {48, 9}, {60, 14}, {72, 19}, {84, 70}, {96, 80}, {108, 110}};  // fitted to a reference
    const double toBridge = std::max(0.05, table(promptT, key) / 8.686 - internal(pitch)) / n;  // per string, Np/s
    // Rise with frequency (Np/s per string): about 4.5 dB/s more at 1 kHz, steeper on the wound bass strings
    const double gam = 0.8 + 0.4 * clamp01((48 - key) / 24.0), rise = (1 + 1.5 * clamp01((key - 72) / 24.0)) * 4.5 / 8.686;
    auto bridgeLoss = [&](double hz) { return toBridge + rise * (std::pow(hz / 1000, gam) - std::pow(pitch / 1000, gam)); };
    Rng board{static_cast<uint32_t>(key * 31 + 5)}, ears{static_cast<uint32_t>(key * 131 + 7)};
    Rng react{static_cast<uint32_t>(key * 977 + 11)}, spots{static_cast<uint32_t>(key * 383 + 29)};
    double bL[MAXK], bR[MAXK], bRq[MAXK];  // each partial's weights for the ears, as all strings moving together give them

    k.K = 0;
    while (k.K < MAXK && partial(k.K + 1) < std::min(0.45 * rate, 10000.0)) k.K++;
    k.K = std::max(k.K, 1);
    k.KH = std::min(k.K, MAXKH);
    const double beta = key <= 60 ? 0.122 : 0.122 - 0.047 * (key - 60) / 48.0;  // strike point, share of the length
    for (int m = 0; m < k.K; m++) {
        int j = m + 1;
        double sign = (j & 1) ? -1 : 1;
        k.phi[m] = static_cast<float>(std::sin(j * PI * beta));
        const double hz = partial(j);
        // The board radiates little below its lowest modes (about 45 Hz)
        const double rad = (hz / 45) * (hz / 45) / std::sqrt(1 + std::pow(hz / 45, 4));
        const double bm = tension * j * PI / len * sign * rad;
        // Bridge losses: the board's resonances make some partials fade much faster than their neighbors (log-normal)
        double u = 0;
        for (int i = 0; i < 4; i++) u += board.next();
        const double irr = j == 1 ? 1 : std::min(3.0, std::max(0.3, std::exp(0.6 * 1.732 * (u - 2))));
        const double cb = 2 * bridgeLoss(hz) * irr * T;
        k.cb[m] = static_cast<float>(cb);
        k.cbH[m] = static_cast<float>(cb / 10);
        k.own[m] = static_cast<float>((1 - SHARE) * cb);
        k.sq[m] = static_cast<float>(std::sqrt(cb) * sign);
        // Reactive coupling, as strong as the resistive part give or take, either sign (the bridge is springy below a
        // board resonance and massy above it): splits a unison partial into a fast and a slow mode that beat
        auto reactive = [&] { return (0.6 + 1.0 * react.next()) * (react.next() < 0.5 ? -1 : 1); };
        k.cx[m] = static_cast<float>(reactive() * (1 - SHARE) * cb);
        k.cxH[m] = static_cast<float>(reactive() * cb / 10);
        // Left/right weights as the ears get them from a soundboard: each partial radiates from its own spots, so it is
        // a little louder on one side (about 4 dB either way, no side favored) and phase-shifted, alike in the bass and
        // unrelated up high (the mono sum, a phone speaker, loses at most 3 dB)
        const double cor = 0.95 - 0.85 * clamp01(std::log2(hz / 150) / std::log2(2000.0 / 150));
        const double ph = std::acos(cor) * (rng.next() < 0.5 ? -1 : 1);
        double e = 0;
        for (int i = 0; i < 4; i++) e += ears.next();
        const double ild = std::min(10.0, std::max(-10.0, 4 * 1.732 * (e - 2)));  // dB, right over left
        const double aL = std::sqrt(2 / (1 + std::pow(10.0, ild / 10))), aR = aL * std::pow(10.0, ild / 20);
        bL[m] = bm * aL;
        bR[m] = bm * aR * std::cos(ph);
        bRq[m] = bm * aR * std::sin(ph);
    }

    // Unisons about a cent apart, and no two wires quite alike in stiffness: the strings of a key drift in and
    // out of phase, the upper partials faster (beats of 1-2 Hz around 2-3 kHz in the middle); the horizontal
    // polarizations a little sharper still
    double spread = 0.45 + 0.45 * rng.next();
    double det[MAXN] = {0, 0, 0};
    if (n == 2) { det[0] = -spread / 2; det[1] = spread / 2; }
    if (n == 3) { det[0] = -spread; det[1] = 0.15 * spread; det[2] = 0.85 * spread; }
    const double modalMass = mu * len / 2;
    for (int s = 0; s < n; s++) {
        // this string's stiffness, rel. the key's (less spread on the bass strings)
        const double wire = 1 + 0.015 * (0.5 + 0.5 * clamp01((key - 40) / 24.0)) * rng.pm();
        for (int pol = 0; pol < 2; pol++) {
            Modes& md = pol ? k.h[s] : k.v[s];
            double ratio = std::pow(2.0, (det[s] + (pol ? 0.08 + 0.08 * rng.next() : 0)) / 1200);
            md.c1 = 0;
            for (int m = 0; m < k.K; m++) {
                double hz = (m + 1) * F * ratio * std::sqrt(1 + B * wire * (m + 1) * (m + 1)), w = 2 * PI * hz;
                double r = std::exp(-internal(hz) * T);
                md.pr[m] = static_cast<float>(r * std::cos(w * T));
                md.pi[m] = static_cast<float>(r * std::sin(w * T));
                md.h[m] = static_cast<float>(T * k.phi[m] / (modalMass * w));
                md.c1 += k.phi[m] * md.pi[m] * md.h[m];
                // This string's own spots on the board: the strings of a key differ a little, more so up high; the
                // horizontal motion rocks the bridge and sounds from other spots altogether
                // (most in the mids: the ears hear the high partials of the strings nearly alike, as on a reference)
                const double up = clamp01(std::log2(hz / 80) / std::log2(400.0 / 80)) * (1 - 0.8 * clamp01(std::log2(hz / 1500)));
                const double dph = (pol ? 1.6 : 0.6) * up * spots.pm();
                const double gL = std::pow(10.0, (pol ? 3 : 1) * up * spots.pm() / 20), gR = std::pow(10.0, (pol ? 3 : 1) * up * spots.pm() / 20);
                const double c = std::cos(dph), sn = std::sin(dph);
                md.bl[m] = static_cast<float>(bL[m] * gL);
                md.br[m] = static_cast<float>((bR[m] * c - bRq[m] * sn) * gR);
                md.brq[m] = static_cast<float>((bRq[m] * c + bR[m] * sn) * gR);
            }
            md.clear();
        }
    }
    double wsum = 0;
    const double share[MAXN] = {1.0, 0.9, 1.1};
    for (int s = 0; s < n; s++) wsum += share[s];
    for (int s = 0; s < n; s++) k.w[s] = share[s] / wsum;

    // Hammer: heavier and softer in the bass. K0 is set so a 2 m/s blow stays on a rigid string contact[] seconds.
    static const double massT[][2] = {{21, 0.011}, {108, 0.004}};
    static const double contactT[][2] = {{21, 0.0014}, {36, 0.0012}, {48, 0.0011}, {60, 0.0009}, {72, 0.0008}, {84, 0.0008}, {108, 0.0004}};
    k.mass = table(massT, key);
    k.p = 3.2 + 0.5 * (key - 21) / 87.0;
    k.tau = 5e-6;
    {
        double tc = table(contactT, key), v = 2, q = 1 / (k.p + 1);
        double I = std::sqrt(PI) * std::tgamma(1 + q) / std::tgamma(0.5 + q);
        double xm = tc * v / (2 * I);
        k.K0 = (k.p + 1) * k.mass * v * v / (2 * std::pow(xm, k.p + 1));
    }

    // Dampers up to F#6; they act slower on heavy bass strings
    k.damper = key <= 90;
    static const double dampT[][2] = {{21, 0.8}, {90, 0.28}};  // s to fall 60 dB (fitted to a reference)
    k.dampGain = static_cast<float>(std::exp(-6.91 / table(dampT, key) * T));
    k.stopGain = static_cast<float>(std::exp(-6.91 / 0.06 * T));

    // Output: bridge force, voiced so the keyboard sounds even; every register centered (the soundboard spreads each
    // over both ears, partial by partial)
    // (voiceDb: measured loudness corrections, A-weighted, so every register sounds about as loud)
    static const double voiceDb[][2] = {{21, 1.75}, {33, 1.67}, {45, 1.33}, {57, 0.94}, {64, 0.92}, {69, 0.6}, {72, 1.23}, {81, 1.5}, {89, 1.55}, {93, 1.65}, {102, 2.8}, {108, 3.5}};
    k.outGain = static_cast<float>(0.02 / (n * Z) * std::pow(2.0, -(key - 60) / 60.0) * table(voiceDb, key));
    k.silentMode = static_cast<float>(SILENT * 0.03 / (k.outGain * tension * PI / len * k.K));
    k.gl = k.gr = 1;
    k.horiz = static_cast<float>(0.2 + 0.3 * clamp01((84 - key) / 36.0));
    k.thumpGain = static_cast<float>(0.02 * std::pow(0.5, std::max(0, key - 72) / 12.0));
    static const double bodyT[][2] = {{21, 1.1}, {60, 1.1}, {72, 1}, {84, 1}, {96, 0.85}, {108, 0.85}};  // fitted to a reference
    k.body = static_cast<float>(BODYK * table(bodyT, key));
    k.phantom = 0;
}

}  // namespace

void init(int sampleRate) {
    if (sampleRate == rate || sampleRate <= 0) return;
    rate = sampleRate;
    T = 1.0f / rate;
    riseRate = 1 - std::exp(-1 / (0.0008f * rate));
    knockLen = std::min(256, std::max(1, static_cast<int>(0.0015 * rate))); knockAt = 0;
    for (int i = 0; i < KEYS; i++) {
        keys[i] = Key(); setupKey(keys[i], LO + i);
        if (!keys[i].damper) keys[i].mode = RESONATE;  // the top strings have no dampers: always free to ring along
    }
    const float rm[] = {23.3f, 29.1f, 33.7f, 39.1f, 43.9f, 51.7f, 57.1f, 63.7f};
    room.init(rm, 1.2f, 0.5f);
    Rng rng{12345};
    // Modes 250 Hz to 6 kHz (below, the straight sound carries the bass: low wooden modes only boom), about as wide as
    // they are apart, so they overlap into an uneven but continuous response
    for (int m = 0; m < MODES; m++) {
        double hz = 250 * std::pow(6000.0 / 250, (m + rng.next()) / MODES);
        double q = 6 + 6 * rng.next();
        double r = std::exp(-PI * hz / (q * rate));
        auto gain = [&] { return (0.4f + 0.6f * rng.next()) * (rng.next() < 0.5f ? -1.f : 1.f) * 1.6f; };
        // The ears hear the low modes nearly alike, the high ones differently
        float c = static_cast<float>(0.9 - 0.6 * m / MODES), gl = gain(), gr = c * gl + std::sqrt(1 - c * c) * gain();
        modes[m] = {static_cast<float>(2 * r * std::cos(2 * PI * hz / rate)), static_cast<float>(-r * r),
                    static_cast<float>((1 - r * r) / 2), gl, gr, 0, 0, 0, 0};
    }
    // Body modes: an impulse rings each at amplitude |gain|; strongest from 120 to 300 Hz and near 100 Hz, weaker below
    for (int m = 0; m < BODY; m++) {
        double hz = 60 * std::pow(700.0 / 60, (m + rng.next()) / BODY);
        const double spread = 3 + 5 * clamp01((200 - hz) / 80);  // fastest over slowest (fitted to a reference)
        double fade = 66 * std::pow(hz / 80, 0.6) * std::pow(spread, rng.next() - 0.5);  // dB/s
        const double strong = std::exp(-(hz - 100) * (hz - 100) / 144);  // the board's strongest low modes, near 100 Hz
        double shape = (hz < 120 ? std::pow(hz / 120, 1.5) : hz > 300 ? std::pow(300 / hz, 0.8) : 1) * std::sqrt(fade / 100) * (1 + 0.3 * strong);
        fade *= 1 - 0.6 * strong;  // (and they ring longer)
        double r = std::exp(-(fade / 8.686) / rate), w = 2 * PI * hz / rate;
        auto gain = [&] { return static_cast<float>(shape * std::sqrt(64.0 / BODY) * (0.5 + rng.next()) * (rng.next() < 0.5 ? -1 : 1)); };
        const float c = 0.25f;  // the two ears hear the board's low modes nearly unrelated
        float gl = gain(), gr = c * gl + std::sqrt(1 - c * c) * gain();
        bodyModes[m] = {static_cast<float>(2 * r * std::cos(w)), static_cast<float>(-r * r), static_cast<float>(std::sin(w)), gl, gr, 0, 0, 0, 0};
    }
    hiA = std::exp(-2 * static_cast<float>(PI) * 9000 / rate);
    shA = 1 - std::exp(-2 * static_cast<float>(PI) * 700 / rate);
    // Voicing: the soundboard makes some notes louder than their neighbors; even them out over each key's lower partials,
    // keeping the level the straight sound alone gives
    for (Key& k : keys) {
        const double B = stiffness(k.key), F = k.f / std::sqrt(1 + B);
        double sum = 0, want = 0;
        for (int j = 1; j <= std::min(k.K, 8); j++) {
            double w = 2 * PI * j * F * std::sqrt(1 + B * j * j) / rate;
            std::complex<double> z1 = std::polar(1.0, -w), z2 = z1 * z1, hl = 0, hr = 0;
            for (const Mode2& m : modes) {
                std::complex<double> h = static_cast<double>(m.b0) * (1.0 - z2) / (1.0 - static_cast<double>(m.a1) * z1 - static_cast<double>(m.a2) * z2);
                hl += static_cast<double>(m.gl) * h; hr += static_cast<double>(m.gr) * h;
            }
            // The straight path: the high shelf, then the high cut (as in render())
            const double a = hiA, sa = shA, bd = BOARD;
            std::complex<double> d = (1 - a) / (1.0 - a * z1) * (1.0 + SHELF - SHELF * sa / (1.0 - (1 - sa) * z1));
            sum += std::norm((1 - bd) * d + bd * (hl + hr) / 2.0) / j;  // as a mono speaker plays it
            want += std::norm(d) / j;
        }
        float c = static_cast<float>(std::sqrt(want / sum));
        k.outGain *= c; k.silentMode /= c;
    }
    quietOutput();
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
    float L[BLOCK], R[BLOCK], KN[BLOCK], KB[BLOCK];
    Key* on[KEYS];
    while (frames > 0) {
        int nf = std::min(frames, BLOCK), non = 0;
        std::fill(L, L + nf, 0.f); std::fill(R, R + nf, 0.f); std::fill(KN, KN + nf, 0.f); std::fill(KB, KB + nf, 0.f);
        for (Key& k : keys) if (k.mode != OFF) { k.begin(); on[non++] = &k; }
        // Sample by sample across the keys, so the shared bridge couples them without delay
        for (int i = 0; i < nf; i++) {
            float total = 0;
            for (int j = 0; j < non; j++) { on[j]->prep(); total += on[j]->g; }
            for (int j = 0; j < non; j++) on[j]->tick(total, L[i], R[i], KN[i], KB[i]);
        }
        for (int j = 0; j < non; j++) on[j]->end();
        for (int i = 0; i < nf; i++) {
            float b = hpB.tick(0.5f * (L[i] + R[i]) + KN[i] / BOARD);
            float kb = knockLine[knockAt];
            knockLine[knockAt] = KB[i];
            if (++knockAt >= knockLen) knockAt = 0;
            float kl = 0, kr = 0;
            for (Mode2& m : bodyModes) {
                float y = m.b0 * kb + m.a1 * m.y1 + m.a2 * m.y2;
                m.y2 = m.y1; m.y1 = y;
                kl += m.gl * y; kr += m.gr * y;
            }
            float bl = 0, br = 0;
            for (Mode2& m : modes) {
                float y = m.b0 * (b - m.x2) + m.a1 * m.y1 + m.a2 * m.y2;
                m.x2 = m.x1; m.x1 = b; m.y2 = m.y1; m.y1 = y;
                bl += m.gl * y; br += m.gr * y;
            }
            float rl, rr;
            room.tick(0.5f * (L[i] + R[i]) * (1 - BOARD) + 0.5f * (bl + br) * BOARD, rl, rr);
            float dl = hpL.tick(L[i]), dr = hpR.tick(R[i]);
            shL += shA * (dl - shL); shR += shA * (dr - shR);
            hiL = (1 - hiA) * (dl + SHELF * (dl - shL)) + hiA * hiL;
            hiR = (1 - hiA) * (dr + SHELF * (dr - shR)) + hiA * hiR;
            float l = (1 - BOARD) * hiL + BOARD * bl + 0.08f * rl + kl;
            float r = (1 - BOARD) * hiR + BOARD * br + 0.08f * rr + kr;
            if (!(std::fabs(l) + std::fabs(r) < 1e4f)) { quietOutput(); l = r = 0; }  // (fast-math: no isfinite)
            out[2 * i] = softClip(l);
            out[2 * i + 1] = softClip(r);
        }
        out += nf * 2;
        frames -= nf;
    }
}

}  // namespace piano
