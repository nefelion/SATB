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
    wav("scale", render(scale, t + 1));

    // Every third key at one velocity, for evening out the loudness across the keyboard
    {
        std::vector<Ev> e;
        for (int key = 21; key <= 108; key += 3) { double t0 = 0.1 + (key - 21) / 3 * 0.8; e.push_back({t0, key, 0.6f}); e.push_back({t0 + 0.7, key, 0}); }
        wav("keys", render(e, 0.1 + 30 * 0.8));
    }
    wav("chord_close", render({{0.1, 60, 0.6f}, {0.1, 64, 0.6f}, {0.1, 67, 0.6f}, {0.1, 72, 0.6f}}, 5));
    wav("chord_open", render({{0.1, 48, 0.6f}, {0.1, 55, 0.6f}, {0.1, 64, 0.6f}, {0.1, 72, 0.6f}}, 5));
    // A cadence I IV V I in four parts, held a beat each
    {
        std::vector<Ev> e;
        const int ch[4][4] = {{48, 64, 67, 72}, {53, 65, 69, 72}, {55, 62, 67, 71}, {48, 64, 67, 72}};
        for (int c = 0; c < 4; c++) for (int k : ch[c]) { e.push_back({0.1 + c * 0.9, k, 0.55f}); e.push_back({0.1 + c * 0.9 + (c == 3 ? 3 : 0.88), k, 0}); }
        wav("cadence", render(e, 5.5));
    }
    double cpu = 0;
    auto fff = render({{0.1, 36, 1.f}, {0.1, 48, 1.f}, {0.1, 55, 1.f}, {0.1, 60, 1.f}, {0.1, 64, 1.f}, {0.1, 67, 1.f}, {0.1, 72, 1.f}, {0.1, 76, 1.f}}, 3, &cpu);
    wav("fff", fff);
    std::printf("fff chord peak %.3f, cpu %.1f%% of real time\n", peak(fff), cpu * 100);

    std::vector<Ev> ped = {{0.05, -1, 1}};
    for (int i = 0; i < 8; i++) { int k = std::vector<int>{36, 48, 52, 55, 60, 64, 67, 72}[i]; ped.push_back({0.1 + i * 0.25, k, 0.5f}); ped.push_back({0.3 + i * 0.25, k, 0}); }
    ped.push_back({6.0, -1, 0});
    auto pd = render(ped, 7.5, &cpu);
    wav("pedal", pd);
    std::printf("pedal cpu %.1f%% of real time\n", cpu * 100);

    wav("a4", render({{0.1, 69, 0.6f}, {6.1, 69, 0}}, 7));
    wav("a1", render({{0.1, 33, 0.8f}, {6.1, 33, 0}}, 7));
    wav("c7", render({{0.1, 96, 0.6f}, {4.1, 96, 0}}, 5));
    wav("damp_c4", render({{0.1, 60, 0.8f}, {1.1, 60, 0}}, 3));
    // Sympathetic resonance: C3 held down silently would need a silent press; with the pedal, C4 struck alone
    wav("res_c4", render({{0.0, -1, 1}, {0.1, 60, 0.7f}, {0.6, 60, 0}}, 4));
    // How loud the resting strings ring along with that C4 (its octaves and fifths should lead)
    std::printf("ringing along with C4 (dB rel. C4):");
    for (int key : {36, 48, 55, 59, 61, 67, 72, 79, 84})
        std::printf(" %d:%.0f", key, 20 * std::log10(keys[key - LO].level / keys[60 - LO].level + 1e-12));
    std::printf("\n");
    wav("nores_c4", render({{0.1, 60, 0.7f}, {0.6, 60, 0}}, 4));
    std::printf("done\n");
}
