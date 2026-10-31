// The crude piano, a physically modeled grand: no samples, every sound computed from strings, hammers and a soundboard.
// Not thread-safe: the caller serializes all calls (synth.cpp holds its mutex).
#pragma once

namespace piano {
// Sets up every key for [sampleRate]; allocates, so call it off the audio callback. Repeating it with the same rate is free.
void init(int sampleRate);
// MIDI [key] struck at [velocity] 0..1 (keys outside A0..C8 are ignored).
void noteOn(int key, float velocity);
void noteOff(int key);
// Sustain pedal: while down, released keys keep ringing and every string resonates with what plays.
void sustain(bool down);
// Releases all keys and the pedal, and quickly silences every string.
void allOff();
// Writes [frames] interleaved stereo samples to [out].
void render(float* out, int frames);
}
