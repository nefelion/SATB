package com.nefelion.fourpartwriting.model

/**
 * A note (pitch != null) or a rest of any [length]. How it is written (tied values,
 * split at barlines and beats) is decided by the engraver, not stored here. Only [tuplet] keeps
 * the ratio the user picked, since the length alone cannot tell 5:4 from 5:2.
 * [figures]: figured-bass lines, top to bottom ("6", "#", "b7"; see FiguredBass); used on bass notes only.
 */
data class Event(val length: Fraction, val pitch: Pitch?, val tuplet: Tuplet? = null, val figures: List<String> = emptyList()) {
    constructor(d: Duration, pitch: Pitch?) : this(d.value, pitch)
}

/** [n] notes in the time of [m], of written value [unit]: a group spans [m] × [unit]. */
data class Tuplet(val n: Int, val m: Int, val unit: Fraction) {
    init { require(n > 0 && m > 0 && unit > Fraction.ZERO) }
    val span get() = unit * Fraction.of(m.toLong(), 1)
}

enum class Voice(val label: String) { SOPRANO("S"), ALTO("A"), TENOR("T"), BASS("B") }

/**
 * Staff (0 = top) of each voice S, A, T, B. Voices keep their order top to bottom, so any
 * split of SATB into consecutive groups works: "SATB", "SA/TB", keyboard style "SAT/B", ...
 */
data class Arrangement(val staffOf: List<Int>) {
    init { require(staffOf.size == 4 && staffOf[0] == 0 && staffOf.zipWithNext().all { (a, b) -> b == a || b == a + 1 }) }

    val staves get() = staffOf.last() + 1
    fun voicesOn(staff: Int) = Voice.entries.filter { staffOf[it.ordinal] == staff }

    /**
     * Stem direction per voice: 1 = up, -1 = down, 0 = from note position (a voice alone on its staff).
     * On a shared staff the top voice stems up (the top two of four), the rest down. Only [shown] voices count.
     */
    fun stems(shown: Collection<Voice> = Voice.entries): List<Int> = Voice.entries.map { v ->
        val on = voicesOn(staffOf[v.ordinal]).filter { it in shown }
        when {
            on.size == 1 -> 0
            on.indexOf(v) < maxOf(1, on.size / 2) -> 1
            else -> -1
        }
    }

    val defaultClefs: List<Clef> get() = List(staves) { s ->
        val on = voicesOn(s)
        when {
            Voice.BASS in on && Voice.SOPRANO !in on -> Clef.BASS
            on.first() == Voice.TENOR -> Clef.TREBLE_8VB
            else -> Clef.TREBLE
        }
    }

    val label get() = (0 until staves).joinToString("/") { s -> voicesOn(s).joinToString("") { it.label } }

    companion object {
        val ONE = Arrangement(listOf(0, 0, 0, 0))
        val CLOSED = Arrangement(listOf(0, 0, 1, 1))
        val KEYBOARD = Arrangement(listOf(0, 0, 0, 1))
        val THREE = Arrangement(listOf(0, 0, 1, 2))
        val OPEN = Arrangement(listOf(0, 1, 2, 3))

        /** All 8 splits, fewest staves first. Bit k set = staff break below voice k. */
        val entries: List<Arrangement> = (0 until 8).map { bits ->
            var s = 0
            Arrangement(List(4) { v -> if (v > 0 && (bits shr (v - 1)) and 1 == 1) s++; s })
        }.sortedBy { it.staves }
    }
}

/**
 * Four independent SATB voice lines. Homophonic writing is the case where all voices share
 * one rhythm, but nothing here assumes it.
 */
data class Score(
    val key: Key = Key(0, Mode.MAJOR),
    val time: TimeSig = TimeSig(4, 4),
    /** Beats per minute, in the meter's beat (dotted quarter in 6/8, half in 2/2); see [qpm]. */
    val tempo: Int = 80,
    val arrangement: Arrangement = Arrangement.CLOSED,
    val clefs: List<Clef> = arrangement.defaultClefs,
    val voices: List<List<Event>> = List(4) { emptyList() },
    /** Names of rules switched off for this score only. */
    val rulesOff: Set<String> = emptySet(),
    /** Issues ignored one by one (see VoiceLeading.ignoreKey). */
    val ignored: Set<String> = emptySet(),
    /** Voices given by an exercise: shown grey and not editable. */
    val given: Set<Voice> = emptySet(),
) {
    init { require(clefs.size == arrangement.staves) }

    /** Quarter notes per minute. */
    val qpm get() = tempo * time.beatLength.toDouble() * 4

    fun voice(v: Voice) = voices[v.ordinal]
    fun staffOf(v: Voice) = arrangement.staffOf[v.ordinal]
    fun voicesOn(staff: Int) = arrangement.voicesOn(staff)

    fun withVoice(v: Voice, events: List<Event>) =
        copy(voices = voices.toMutableList().also { it[v.ordinal] = events })

    fun withArrangement(a: Arrangement) = copy(arrangement = a, clefs = a.defaultClefs)

    fun onsets(v: Voice): List<Fraction> {
        var t = Fraction.ZERO
        return voice(v).map { e -> t.also { t += e.length } }
    }

    fun length(v: Voice): Fraction = voice(v).fold(Fraction.ZERO) { a, e -> a + e.length }
    val length get() = Voice.entries.maxOf { length(it) }

    /** Index of the event of [v] sounding at [t], or -1. */
    fun eventAt(v: Voice, t: Fraction): Int {
        val on = onsets(v)
        val i = on.indexOfLast { it <= t }
        return if (i >= 0 && t < on[i] + voice(v)[i].length) i else -1
    }

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
                if (i >= 0 && t < on[i] + evs[i].length) {
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
