package com.grothgarvc.fourpartwriting.audio

import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.Voice

/** JNI bridge to the native TinySoundFont + AAudio synth (src/main/cpp/synth.cpp). */
object Synth {
    init { System.loadLibrary("satb") }

    var loaded = false
        private set

    fun start() = nativeStart()
    fun close() = nativeClose()
    fun load(path: String) = nativeLoad(path).also { loaded = loaded || it }
    fun noteOn(midi: Int, vel: Int = 90) = nativeNote(midi, vel)
    fun noteOff(midi: Int) = nativeNote(midi, 0)
    fun stop() = nativeStop()
    /** Whole notes played since playback started, or null when idle. */
    fun position(): Double? = nativePosition().takeIf { it >= 0 }

    /** Quarter notes per minute; applies at once, also to a score that is playing. */
    fun setTempo(qpm: Int) = nativeSetTempo(qpm.toDouble())

    /** Schedules the whole score in musical time; the native side applies the current tempo. */
    fun play(score: Score) {
        val ev = ArrayList<Double>()
        for (v in Voice.entries) {
            var t = Fraction.ZERO
            for (e in score.voice(v)) {
                val end = t + e.length
                e.pitch?.let { p ->
                    ev += t.toDouble(); ev += p.midi.toDouble(); ev += 90.0
                    ev += end.toDouble(); ev += p.midi.toDouble(); ev += 0.0
                }
                t = end
            }
        }
        setTempo(score.tempo)
        nativePlay(ev.toDoubleArray())
    }

    private external fun nativeStart(): Boolean
    private external fun nativeClose()
    private external fun nativeLoad(path: String): Boolean
    private external fun nativeNote(key: Int, vel: Int)
    private external fun nativePlay(events: DoubleArray)
    private external fun nativeSetTempo(qpm: Double)
    private external fun nativeStop()
    private external fun nativePosition(): Double
    private external fun nativeSampleRate(): Int
}
