// SoundFont playback: TinySoundFont renders into an AAudio stream.
// Scheduled events are applied inside the audio callback at sample-exact times.
#define TSF_IMPLEMENTATION
#include "tsf.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <mutex>
#include <thread>
#include <vector>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "satb", __VA_ARGS__)

namespace {
struct Ev { int64_t t; int key; int on; };

std::mutex mx;
tsf* sf = nullptr;
AAudioStream* stream = nullptr;
int sampleRate = 48000;
std::vector<Ev> events;
size_t nextEv = 0;
int64_t pos = 0;     // frames since play() started
bool playing = false;

void render(float* out, int frames) {
    if (sf) tsf_render_float(sf, out, frames, 0);
    else std::fill(out, out + frames * 2, 0.0f);
}

aaudio_data_callback_result_t onAudio(AAudioStream*, void*, void* data, int32_t frames) {
    auto* out = static_cast<float*>(data);
    std::lock_guard<std::mutex> lock(mx);
    int done = 0;
    while (playing && sf && nextEv < events.size() && events[nextEv].t < pos + frames) {
        int at = static_cast<int>(std::max<int64_t>(events[nextEv].t - pos, done));
        if (at > done) { render(out + done * 2, at - done); done = at; }
        const Ev& e = events[nextEv++];
        if (e.on) tsf_note_on(sf, 0, e.key, e.on / 127.0f);
        else tsf_note_off(sf, 0, e.key);
    }
    if (frames > done) render(out + done * 2, frames - done);
    if (playing) {
        pos += frames;
        if (nextEv >= events.size()) playing = false;
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
    if (sf) tsf_set_output(sf, TSF_STEREO_INTERLEAVED, sampleRate, -6.0f);
    AAudioStream_requestStart(stream);
    return true;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    return stream != nullptr || openStream();
}

JNIEXPORT void JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeClose(JNIEnv*, jobject) {
    AAudioStream* s;
    {
        std::lock_guard<std::mutex> lock(mx);
        s = stream; stream = nullptr; playing = false;
        if (sf) tsf_note_off_all(sf);
    }
    if (s) { AAudioStream_requestStop(s); AAudioStream_close(s); }
}

JNIEXPORT jboolean JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeLoad(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    tsf* f = tsf_load_filename(p);
    env->ReleaseStringUTFChars(path, p);
    if (!f) return JNI_FALSE;
    tsf_set_output(f, TSF_STEREO_INTERLEAVED, sampleRate, -6.0f);
    tsf_set_max_voices(f, 96);  // preallocate so the audio thread never allocates
    tsf* old;
    {
        std::lock_guard<std::mutex> lock(mx);
        old = sf; sf = f; playing = false;
    }
    if (old) tsf_close(old);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeNote(JNIEnv*, jobject, jint key, jint vel) {
    std::lock_guard<std::mutex> lock(mx);
    if (!sf) return;
    if (vel > 0) tsf_note_on(sf, 0, key, vel / 127.0f);
    else tsf_note_off(sf, 0, key);
}

// events: flat [frameTime, key, velocity(0 = off)] triples, sorted by time.
JNIEXPORT void JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativePlay(JNIEnv* env, jobject, jintArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<jint> a(n);
    env->GetIntArrayRegion(arr, 0, n, a.data());
    std::lock_guard<std::mutex> lock(mx);
    events.clear();
    for (jsize i = 0; i + 2 < n; i += 3) events.push_back({a[i], a[i + 1], a[i + 2]});
    std::stable_sort(events.begin(), events.end(), [](const Ev& x, const Ev& y) {
        return x.t < y.t || (x.t == y.t && x.on < y.on);  // note-offs first at equal times
    });
    if (sf) tsf_note_off_all(sf);
    nextEv = 0; pos = 0; playing = sf && !events.empty();
}

JNIEXPORT void JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    playing = false;
    if (sf) tsf_note_off_all(sf);
}

/** Frames played since play(), or -1 when idle. */
JNIEXPORT jlong JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativePosition(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    return playing ? pos : -1;
}

JNIEXPORT jint JNICALL
Java_com_grothgarvc_fourpartwriting_audio_Synth_nativeSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(mx);
    return sampleRate;
}
}
