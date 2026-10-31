// Renders test WAVs of the modeled piano (app/src/main/cpp/piano.cpp) and prints per-key data, for tuning by ear;
// check.py then verifies them. Not part of the app. Build for the x86_64 emulator and run there:
//   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++ --target=x86_64-linux-android26 -O2 -std=c++17 -static-libstdc++ tools/pianowav/main.cpp -o pianowav
//   adb push pianowav /data/local/tmp && adb shell "cd /data/local/tmp && ./pianowav"
//   adb pull /data/local/tmp/wav <dir> && py -3 tools/pianowav/check.py <dir>/wav
#include <cstdio>
#include "../../app/src/main/cpp/piano.cpp"

#include <chrono>
#include <cstdio>
#include <functional>
#include <string>
#include <sys/stat.h>

using namespace piano;

constexpr int FS = 48000;

static void wav(const std::string& name, const std::vector<float>& s) {
    size_t bad = 0;
    for (size_t i = 0; i < s.size(); i++) if (!std::isfinite(s[i])) { if (!bad) std::printf("%s: not finite from %.3f s\n", name.c_str(), i / 2.0 / FS); bad++; }
    FILE* f = std::fopen(("wav/" + name + ".wav").c_str(), "wb");
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    uint32_t bytes = static_cast<uint32_t>(s.size() * 2);
    std::fwrite("RIFF", 1, 4, f); u32(36 + bytes); std::fwrite("WAVEfmt ", 1, 8, f);
    u32(16); u16(1); u16(2); u32(FS); u32(FS * 4); u16(4); u16(16);
    std::fwrite("data", 1, 4, f); u32(bytes);
    for (float x : s) { int v = static_cast<int>(std::lround(std::max(-1.f, std::min(1.f, x)) * 32767)); u16(static_cast<uint16_t>(v)); }
    std::fclose(f);
}

// An event at [t] seconds: key > 0 strikes it (vel > 0) or releases it (vel == 0); key -1 is the pedal (vel > 0: down).
struct Ev { double t; int key; float vel; };

static std::vector<float> render(std::vector<Ev> ev, double seconds, double* cpu = nullptr) {
    allOff();
    std::vector<float> junk(FS * 2);
    render(junk.data(), FS);  // let the stop fade finish
    std::sort(ev.begin(), ev.end(), [](const Ev& a, const Ev& b) { return a.t < b.t; });
    std::vector<float> out(static_cast<size_t>(seconds * FS) * 2);
    size_t at = 0, next = 0, frames = out.size() / 2;
    auto t0 = std::chrono::steady_clock::now();
    while (at < frames) {
        while (next < ev.size() && ev[next].t * FS <= at) {
            const Ev& e = ev[next++];
            if (e.key < 0) sustain(e.vel > 0);
            else if (e.vel > 0) noteOn(e.key, e.vel);
            else noteOff(e.key);
        }
        size_t until = next < ev.size() ? static_cast<size_t>(ev[next].t * FS) : frames;
        int n = static_cast<int>(std::min<size_t>(std::min(until, frames) - at, 192));
        if (n <= 0) n = 1;
        piano::render(out.data() + at * 2, n);
        at += n;
    }
    if (cpu) *cpu = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() / seconds;
    return out;
}

// The same events as a standard MIDI file, for rendering them on another piano (velocity 0..1 as 0..127, pedal as CC64)
static void mid(const std::string& name, std::vector<Ev> ev, double seconds) {
    std::stable_sort(ev.begin(), ev.end(), [](const Ev& a, const Ev& b) { return a.t < b.t; });
    std::vector<uint8_t> t;
    auto vlq = [&](uint32_t v) { uint8_t b[5]; int n = 0; do { b[n++] = v & 0x7f; v >>= 7; } while (v); while (n--) t.push_back(b[n] | (n ? 0x80 : 0)); };
    const double tps = 1920;  // ticks per second: 960 per quarter at 120 bpm
    uint32_t last = 0;
    auto at = [&](double sec) { uint32_t k = std::max(last, static_cast<uint32_t>(std::lround(sec * tps))); vlq(k - last); last = k; };
    at(0); for (uint8_t b : {0xff, 0x51, 0x03, 0x07, 0xa1, 0x20}) t.push_back(b);
    for (const Ev& e : ev) {
        at(e.t);
        if (e.key < 0) { t.push_back(0xb0); t.push_back(64); t.push_back(e.vel > 0 ? 127 : 0); }
        else { t.push_back(0x90); t.push_back(static_cast<uint8_t>(e.key)); t.push_back(static_cast<uint8_t>(e.vel > 0 ? std::max(1L, std::lround(e.vel * 127)) : 0)); }
    }
    at(seconds); for (uint8_t b : {0xff, 0x2f, 0x00}) t.push_back(b);
    FILE* f = std::fopen(("wav/" + name + ".mid").c_str(), "wb");
    auto be = [&](uint32_t v, int n) { while (n--) std::fputc((v >> (8 * n)) & 0xff, f); };
    std::fwrite("MThd", 1, 4, f); be(6, 4); be(0, 2); be(1, 2); be(960, 2);
    std::fwrite("MTrk", 1, 4, f); be(static_cast<uint32_t>(t.size()), 4); std::fwrite(t.data(), 1, t.size(), f);
    std::fclose(f);
}

// Renders a test to wav/<name>.wav and writes its events to wav/<name>.mid
static std::vector<float> test(const std::string& name, const std::vector<Ev>& ev, double seconds, double* cpu = nullptr) {
    mid(name, ev, seconds);
    auto s = render(ev, seconds, cpu);
    wav(name, s);
    return s;
}

static float peak(const std::vector<float>& s) { float p = 0; for (float x : s) p = std::max(p, std::fabs(x)); return p; }

int main() {
    mkdir("wav", 0755);
    piano::init(FS);

    // Per-key data and the hammer's measured contact time at three velocities
    std::printf("key   hz       n  K   KH   contact ms (0.3/0.6/1.0)  peak@0.6\n");
    for (int key = 21; key <= 108; key += 3) {
        Key& k = keys[key - LO];
        double ms[3];
        float pk = 0;
        const float vels[3] = {0.3f, 0.6f, 1.0f};
        for (int j = 0; j < 3; j++) {
            allOff();
            std::vector<float> buf(FS * 2);
            piano::render(buf.data(), FS);
            noteOn(key, vels[j]);
            int contact = 0;
            float b[2];
            for (int i = 0; i < FS / 20; i++) { piano::render(b, 1); if (k.hammer && k.sinceContact == 0) contact++; }
            ms[j] = 1000.0 * contact / FS;
            if (j == 1) { std::vector<float> s(FS / 2 * 2); piano::render(s.data(), FS / 2); pk = peak(s); }
            noteOff(key);
        }
        std::printf("%3d %8.2f  %d %3d %3d   %5.2f %5.2f %5.2f              %.3f\n", key, k.f, k.n, k.K, k.KH, ms[0], ms[1], ms[2], pk);
    }

    std::vector<Ev> scale;
    double t = 0.2;
    for (int key : {21, 36, 48, 60, 69, 84, 96, 108})
        for (float vel : {0.3f, 0.6f, 1.0f}) { scale.push_back({t, key, vel}); scale.push_back({t + 1.4, key, 0}); t += 1.8; }
    test("scale", scale, t + 1);

    // Every third key at one velocity, for evening out the loudness across the keyboard
    {
        std::vector<Ev> e;
        for (int key = 21; key <= 108; key += 3) { double t0 = 0.1 + (key - 21) / 3 * 0.8; e.push_back({t0, key, 0.6f}); e.push_back({t0 + 0.7, key, 0}); }
        test("keys", e, 0.1 + 30 * 0.8);
    }
    test("chord_close", {{0.1, 60, 0.6f}, {0.1, 64, 0.6f}, {0.1, 67, 0.6f}, {0.1, 72, 0.6f}}, 5);
    test("chord_open", {{0.1, 48, 0.6f}, {0.1, 55, 0.6f}, {0.1, 64, 0.6f}, {0.1, 72, 0.6f}}, 5);
    // A cadence I IV V I in four parts, held a beat each
    {
        std::vector<Ev> e;
        const int ch[4][4] = {{48, 64, 67, 72}, {53, 65, 69, 72}, {55, 62, 67, 71}, {48, 64, 67, 72}};
        for (int c = 0; c < 4; c++) for (int k : ch[c]) { e.push_back({0.1 + c * 0.9, k, 0.55f}); e.push_back({0.1 + c * 0.9 + (c == 3 ? 3 : 0.88), k, 0}); }
        test("cadence", e, 5.5);
    }
    double cpu = 0;
    auto fff = test("fff", {{0.1, 36, 1.f}, {0.1, 48, 1.f}, {0.1, 55, 1.f}, {0.1, 60, 1.f}, {0.1, 64, 1.f}, {0.1, 67, 1.f}, {0.1, 72, 1.f}, {0.1, 76, 1.f}}, 3, &cpu);
    std::printf("fff chord peak %.3f, cpu %.1f%% of real time\n", peak(fff), cpu * 100);

    std::vector<Ev> ped = {{0.05, -1, 1}};
    for (int i = 0; i < 8; i++) { int k = std::vector<int>{36, 48, 52, 55, 60, 64, 67, 72}[i]; ped.push_back({0.1 + i * 0.25, k, 0.5f}); ped.push_back({0.3 + i * 0.25, k, 0}); }
    ped.push_back({6.0, -1, 0});
    test("pedal", ped, 7.5, &cpu);
    std::printf("pedal cpu %.1f%% of real time\n", cpu * 100);

    test("a4", {{0.1, 69, 0.6f}, {6.1, 69, 0}}, 7);
    test("a1", {{0.1, 33, 0.8f}, {6.1, 33, 0}}, 7);
    test("c7", {{0.1, 96, 0.6f}, {4.1, 96, 0}}, 5);
    test("damp_c4", {{0.1, 60, 0.8f}, {1.1, 60, 0}}, 3);
    // Sympathetic resonance: C3 held down silently would need a silent press; with the pedal, C4 struck alone
    test("res_c4", {{0.0, -1, 1}, {0.1, 60, 0.7f}, {0.6, 60, 0}}, 4);
    // How loud the resting strings ring along with that C4 (its octaves and fifths should lead)
    std::printf("ringing along with C4 (dB rel. C4):");
    for (int key : {36, 48, 55, 59, 61, 67, 72, 79, 84})
        std::printf(" %d:%.0f", key, 20 * std::log10(keys[key - LO].level / keys[60 - LO].level + 1e-12));
    std::printf("\n");
    test("nores_c4", {{0.1, 60, 0.7f}, {0.6, 60, 0}}, 4);
    // Single notes held 6 s at three velocities, for comparing partials and decays with a reference piano
    for (int key : {21, 36, 48, 55, 60, 64, 69, 72, 84, 96})
        for (int v : {40, 80, 127}) test("n" + std::to_string(key) + "_" + std::to_string(v), {{0.1, key, v / 127.f}, {6.1, key, 0}}, 7);
    // A four-part chord (C3 G3 E4 C5) at three loudness levels
    for (auto [nm, v] : {std::pair{"satb_p", 0.35f}, {"satb_mf", 0.6f}, {"satb_f", 0.85f}}) {
        std::vector<Ev> e;
        for (int k : {48, 55, 64, 72}) { e.push_back({0.1, k, v}); e.push_back({4.1, k, 0}); }
        test(nm, e, 5);
    }
    // Interplay: C3 G3 E4 held, then C5 struck and released (the held strings should take up its partials)
    test("interplay", {{0.1, 48, 0.6f}, {0.1, 55, 0.6f}, {0.1, 64, 0.6f}, {2.0, 72, 0.7f}, {2.4, 72, 0}, {6.1, 48, 0}, {6.1, 55, 0}, {6.1, 64, 0}}, 7);
    // Stability: with the pedal down every string couples to every other through the bridge; the sound must only fade
    {
        std::vector<Ev> e = {{0.05, -1, 1}};
        for (int k = 21; k <= 108; k += 2) e.push_back({0.1, k, 1.f});
        auto s = render(e, 20);
        double prev = 1e9, rise = -1e9;
        for (int w = 2; w < 20; w++) {
            double sum = 0;
            for (int i = w * FS; i < (w + 1) * FS; i++) sum += s[2 * i] * s[2 * i] + s[2 * i + 1] * s[2 * i + 1];
            double db = 10 * std::log10(sum / FS + 1e-30);
            rise = std::max(rise, db - prev);
            prev = db;
        }
        std::printf("%s stability: largest change per second after 2 s %+.2f dB, last second %.1f dBFS\n", rise < 0 ? "PASS" : "FAIL", rise, prev);
    }
    std::printf("done\n");
}
