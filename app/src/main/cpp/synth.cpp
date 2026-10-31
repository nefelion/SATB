// Playback into an AAudio stream: the built-in crude piano (piano.cpp) or a SoundFont (TinySoundFont).
// Scheduled events are applied inside the audio callback at sample-exact times. Event times are
// musical (whole notes) and turned into frames with the current tempo, so tempo changes apply live.
#define TSF_IMPLEMENTATION
#include "tsf.h"
#include "piano.h"

#include <aaudio/AAudio.h>
#include <unistd.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <ctime>
#include <mutex>
#include <thread>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "satb", __VA_ARGS__)

namespace {
struct Ev { double t; int key; int on; };

std::mutex mx;
tsf* sf = nullptr;
AAudioStream* stream = nullptr;
int sampleRate = 48000;
std::vector<Ev> events;
size_t nextEv = 0;
double mpos = 0;      // whole notes since play() started
double tempo = 80;    // quarter notes per minute
bool playing = false;
int64_t endWritten = -1;  // frames written when the score's last note was rendered; -1 once heard or stopped
bool usePiano = true;  // the crude piano, else the SoundFont

bool ready() { return usePiano || sf; }

void render(float* out, int frames) {
    if (usePiano) { piano::render(out, frames); return; }
    if (sf) tsf_render_float(sf, out, frames, 0);
    else std::fill(out, out + frames * 2, 0.0f);
}

void note(int key, int vel) {
    if (usePiano) { if (vel > 0) piano::noteOn(key, vel / 127.0f); else piano::noteOff(key); }
    else if (sf) { if (vel > 0) tsf_note_on(sf, 0, key, vel / 127.0f); else tsf_note_off(sf, 0, key); }
}

void allOff() {
    piano::allOff();
    if (sf) tsf_note_off_all(sf);
}

aaudio_data_callback_result_t onAudio(AAudioStream*, void*, void* data, int32_t frames) {
    auto* out = static_cast<float*>(data);
    std::lock_guard<std::mutex> lock(mx);
    int done = 0;
    const double wholePerFrame = tempo / (240.0 * sampleRate);
    while (playing && ready() && nextEv < events.size()) {
        const Ev& e = events[nextEv];
        // Note-offs 10 ms early, so repeated notes are re-attacked
        double f = (e.t - mpos) / wholePerFrame - (e.on ? 0.0 : sampleRate / 100.0);
        if (f >= frames) break;
        int at = std::max(static_cast<int>(f), done);
        if (at > done) { render(out + done * 2, at - done); done = at; }
        note(e.key, e.on);
        nextEv++;
    }
    if (frames > done) render(out + done * 2, frames - done);
    if (playing) {
        mpos += frames * wholePerFrame;
        if (nextEv >= events.size()) { playing = false; endWritten = AAudioStream_getFramesWritten(stream) + frames; }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool openStream();

void onError(AAudioStream*, void*, aaudio_result_t err) {
    // Device change (e.g. headphones unplugged): reopen on another thread, as AAudio requires.
    // Close outside the lock: close waits for the callback, which takes the lock.
    if (err == AAUDIO_ERROR_DISCONNECTED) std::thread([] {
        AAudioStream* old;
        { std::lock_guard<std::mutex> lock(mx); old = stream; stream = nullptr; }
        if (old) AAudioStream_close(old);
        std::lock_guard<std::mutex> lock(mx);
        if (!stream) openStream();
    }).detach();
}

// Caller holds mx.
bool openStream() {
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, onAudio, nullptr);
    AAudioStreamBuilder_setErrorCallback(b, onError, nullptr);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("openStream: %s", AAudio_convertResultToText(r)); stream = nullptr; return false; }
    sampleRate = AAudioStream_getSampleRate(stream);
    piano::init(sampleRate);  // set up once per rate; the stream does not run yet
    if (sf) tsf_set_output(sf, TSF_STEREO_INTERLEAVED, sampleRate, -6.0f);
    AAudioStream_requestStart(stream);
    return true;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    return stream != nullptr || openStream();
}

JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeClose(JNIEnv*, jobject) {
    AAudioStream* s;
    {
        std::lock_guard<std::mutex> lock(mx);
        s = stream; stream = nullptr; playing = false; endWritten = -1;
        allOff();
    }
    if (s) { AAudioStream_requestStop(s); AAudioStream_close(s); }
}

// Swaps in a freshly loaded font; a failed load (null) keeps the current one.
static jboolean install(tsf* f) {
    if (!f) return JNI_FALSE;
    tsf_set_output(f, TSF_STEREO_INTERLEAVED, sampleRate, -6.0f);
    tsf_set_max_voices(f, 96);  // preallocate so the audio thread never allocates
    tsf* old;
    {
        std::lock_guard<std::mutex> lock(mx);
        old = sf; sf = f; playing = false; endWritten = -1;
    }
    if (old) tsf_close(old);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeLoad(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    tsf* f = tsf_load_filename(p);
    env->ReleaseStringUTFChars(path, p);
    return install(f);
}

// Reads the font from an open file descriptor (a user's file opened through Android's document
// picker), so the file never has to be copied. The fd stays owned by the caller.
JNIEXPORT jboolean JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeLoadFd(JNIEnv*, jobject, jint fd) {
    int own = dup(fd);
    if (own < 0) return JNI_FALSE;
    FILE* fp = fdopen(own, "rb");
    if (!fp) { close(own); return JNI_FALSE; }
    fseek(fp, 0, SEEK_SET);
    tsf_stream stream = { fp, (int(*)(void*, void*, unsigned int))&tsf_stream_stdio_read, (int(*)(void*, unsigned int))&tsf_stream_stdio_skip };
    tsf* f = tsf_load(&stream);
    fclose(fp);
    return install(f);
}

JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeNote(JNIEnv*, jobject, jint key, jint vel) {
    std::lock_guard<std::mutex> lock(mx);
    note(key, vel);
}

JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeSustain(JNIEnv*, jobject, jboolean down) {
    std::lock_guard<std::mutex> lock(mx);
    piano::sustain(down);
}

// Switches between the crude piano and the SoundFont, silencing both.
JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeUsePiano(JNIEnv*, jobject, jboolean on) {
    std::lock_guard<std::mutex> lock(mx);
    allOff();
    playing = false; endWritten = -1;
    usePiano = on;
}

// events: flat [time in whole notes, key, velocity(0 = off)] triples.
JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativePlay(JNIEnv* env, jobject, jdoubleArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<jdouble> a(n);
    env->GetDoubleArrayRegion(arr, 0, n, a.data());
    std::lock_guard<std::mutex> lock(mx);
    events.clear();
    for (jsize i = 0; i + 2 < n; i += 3) events.push_back({a[i], static_cast<int>(a[i + 1]), static_cast<int>(a[i + 2])});
    std::stable_sort(events.begin(), events.end(), [](const Ev& x, const Ev& y) {
        return x.t < y.t || (x.t == y.t && x.on < y.on);  // note-offs first at equal times
    });
    allOff();
    nextEv = 0; mpos = 0; playing = ready() && !events.empty(); endWritten = -1;
}

/** Quarter notes per minute; takes effect at once, also during playback. */
JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeSetTempo(JNIEnv*, jobject, jdouble qpm) {
    std::lock_guard<std::mutex> lock(mx);
    if (qpm > 0) tempo = qpm;
}

JNIEXPORT void JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    playing = false; endWritten = -1;
    allOff();
}

// Frames the speaker has played by now, from the stream's last timestamp; -1 if it has none yet. Caller holds mx.
double heardFrames() {
    int64_t pos, ns;
    if (!stream || AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &pos, &ns) != AAUDIO_OK) return -1;
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return pos + ((t.tv_sec * 1e9 + t.tv_nsec) - ns) * sampleRate / 1e9;
}

/** Whole notes heard since play() (rendered ones minus those still in the output buffer), or -1 when idle. */
JNIEXPORT jdouble JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativePosition(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    if (!playing && endWritten < 0) return -1.0;
    double heard = heardFrames();
    if (heard < 0) return playing ? mpos : -1.0;
    // The score is rendered to its end: idle once the speaker has played that far
    int64_t written = playing ? AAudioStream_getFramesWritten(stream) : endWritten;
    if (!playing && heard >= written) { endWritten = -1; return -1.0; }
    return std::max(0.0, mpos - std::max(0.0, written - heard) * tempo / (240.0 * sampleRate));
}

JNIEXPORT jint JNICALL
Java_com_nefelion_fourpartwriting_audio_Synth_nativeSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    return sampleRate;
}
}
