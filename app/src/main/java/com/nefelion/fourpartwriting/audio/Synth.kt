package com.nefelion.fourpartwriting.audio

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice

/** JNI bridge to the native TinySoundFont + AAudio synth (src/main/cpp/synth.cpp). */
object Synth {
    init { System.loadLibrary("satb") }

    var loaded = false
        private set

    fun start() = nativeStart()
    fun close() = nativeClose()
    fun load(path: String) = nativeLoad(path).also { loaded = loaded || it }
    /** Loads from an open file descriptor (not closed here); the file is read in place. */
    fun loadFd(fd: Int) = nativeLoadFd(fd).also { loaded = loaded || it }
    fun noteOn(midi: Int, vel: Int = 90) = nativeNote(midi, vel)
    fun noteOff(midi: Int) = nativeNote(midi, 0)
    fun stop() = nativeStop()
    /** Whole notes played since playback started, or null when idle. */
    fun position(): Double? = nativePosition().takeIf { it >= 0 }

    /** Quarter notes per minute; applies at once, also to a score that is playing. */
    fun setTempo(qpm: Double) = nativeSetTempo(qpm)

    /** Fermata holds of the score playing: (where the held chord ends, extra time), by time. */
    private var holds = emptyList<Pair<Fraction, Fraction>>()

    /** Played time (whole notes) of score time [t]: fermatas before it add their hold. */
    fun played(t: Fraction): Double = t.toDouble() + holds.filter { it.first <= t }.sumOf { it.second.toDouble() }

    /** Schedules the whole score in musical time; the native side applies the current tempo. A fermata doubles its chord. */
    fun play(score: Score) {
        holds = Voice.entries.flatMap { v -> score.voice(v).zip(score.onsets(v)).filter { (e, _) -> e.fermata && e.pitch != null } }
            .groupBy({ it.second }, { it.first.length })
            .map { (on, lens) -> lens.max().let { on + it to it } }.sortedBy { it.first }
        val ev = ArrayList<Double>()
        for (v in Voice.entries) {
            var t = Fraction.ZERO
            for (e in score.voice(v)) {
                val end = t + e.length
                e.pitch?.let { p ->
                    ev += played(t); ev += p.midi.toDouble(); ev += 90.0
                    ev += played(end); ev += p.midi.toDouble(); ev += 0.0
                }
                t = end
            }
        }
        setTempo(score.qpm)
        nativePlay(ev.toDoubleArray())
    }

    private external fun nativeStart(): Boolean
    private external fun nativeClose()
    private external fun nativeLoad(path: String): Boolean
    private external fun nativeLoadFd(fd: Int): Boolean
    private external fun nativeNote(key: Int, vel: Int)
    private external fun nativePlay(events: DoubleArray)
    private external fun nativeSetTempo(qpm: Double)
    private external fun nativeStop()
    private external fun nativePosition(): Double
    private external fun nativeSampleRate(): Int
}
