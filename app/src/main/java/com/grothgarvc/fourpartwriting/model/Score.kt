package com.grothgarvc.fourpartwriting.model

/** A note (pitch != null) or a rest. */
data class Event(val duration: Duration, val pitch: Pitch?)

enum class Voice(val label: String, val staff: Int, val stemUp: Boolean) {
    SOPRANO("S", 0, true), ALTO("A", 0, false), TENOR("T", 1, true), BASS("B", 1, false)
}

/**
 * Four independent voice lines on a two-staff system. Homophonic writing is the case where
 * all voices share one rhythm, but nothing here assumes it.
 */
data class Score(
    val key: Key = Key(0, Mode.MAJOR),
    val time: TimeSig = TimeSig(4, 4),
    val tempo: Int = 80,
    val clefs: List<Clef> = listOf(Clef.TREBLE, Clef.BASS),
    val voices: List<List<Event>> = List(4) { emptyList() },
) {
    fun voice(v: Voice) = voices[v.ordinal]

    fun withVoice(v: Voice, events: List<Event>) =
        copy(voices = voices.toMutableList().also { it[v.ordinal] = events })

    fun onsets(v: Voice): List<Fraction> {
        var t = Fraction.ZERO
        return voice(v).map { e -> t.also { t += e.duration.value } }
    }

    fun length(v: Voice): Fraction = voice(v).fold(Fraction.ZERO) { a, e -> a + e.duration.value }
    val length get() = Voice.entries.maxOf { length(it) }

    /** Vertical sonorities: one per distinct onset in any voice. */
    fun slices(): List<Slice> {
        val starts = Voice.entries.map { onsets(it) }
        val times = starts.flatten().toSortedSet().toList()
        return times.map { t ->
            val notes = arrayOfNulls<Pitch>(4)
            val idx = IntArray(4) { -1 }
            val attack = BooleanArray(4)
            for (v in Voice.entries) {
                val on = starts[v.ordinal]
                val evs = voice(v)
                // last event starting at or before t that is still sounding
                val i = on.indexOfLast { it <= t }
                if (i >= 0 && t < on[i] + evs[i].duration.value) {
                    notes[v.ordinal] = evs[i].pitch
                    idx[v.ordinal] = i
                    attack[v.ordinal] = on[i] == t
                }
            }
            Slice(t, notes.toList(), idx.toList(), attack.toList())
        }
    }
}

/** Notes sounding at [time], indexed by [Voice.ordinal]. */
data class Slice(
    val time: Fraction,
    val notes: List<Pitch?>,
    val eventIndex: List<Int>,
    val attacked: List<Boolean>,
) {
    val pitches get() = notes.filterNotNull()
}
