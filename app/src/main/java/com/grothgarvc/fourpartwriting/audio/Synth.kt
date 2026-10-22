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
    /** Seconds since playback started, or null when idle. */
    fun position(): Double? = nativePosition().takeIf { it >= 0 }?.let { it.toDouble() / nativeSampleRate() }

    /** Schedules the whole score; tempo is in quarter notes per minute. */
    fun play(score: Score) {
        val rate = nativeSampleRate()
        val ev = ArrayList<Int>()
        for (v in Voice.entries) {
            var t = Fraction.ZERO
            for (e in score.voice(v)) {
                val end = t + e.length
                e.pitch?.let { p ->
                    ev += frames(t, score.tempo, rate); ev += p.midi; ev += 90
                    ev += frames(end, score.tempo, rate) - rate / 100; ev += p.midi; ev += 0 // release 10 ms early
                }
                t = end
            }
        }
        nativePlay(ev.toIntArray())
    }

    fun seconds(t: Fraction, tempo: Int) = t.toDouble() * 4 * 60.0 / tempo
    private fun frames(t: Fraction, tempo: Int, rate: Int) = (seconds(t, tempo) * rate).toInt()

    private external fun nativeStart(): Boolean
    private external fun nativeClose()
    private external fun nativeLoad(path: String): Boolean
    private external fun nativeNote(key: Int, vel: Int)
    private external fun nativePlay(events: IntArray)
    private external fun nativeStop()
    private external fun nativePosition(): Long
    private external fun nativeSampleRate(): Int
}
