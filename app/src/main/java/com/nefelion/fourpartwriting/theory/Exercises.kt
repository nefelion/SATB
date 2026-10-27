package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.IntervalQuality
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Exercises: a generator that writes a functional progression as a figured bass (or as a given soprano,
 * the melody of one voicing of it), and a solver that writes the missing voices.
 */
object Exercises {

    enum class Level(val label: String) { EASY("Easy"), MEDIUM("Medium"), HARD("Hard") }

    /** A chord of the plan: scale degree 0..6 of the root, inversion, seventh; [of]: applied dominant of that degree. */
    private class Step(val degree: Int, val inv: Int, val seventh: Boolean = false, val of: Int? = null)

    private val STEPS = mapOf(
        "I" to Step(0, 0), "I6" to Step(0, 1), "Cad64" to Step(0, 2),
        "ii" to Step(1, 0), "ii6" to Step(1, 1), "ii65" to Step(1, 1, true),
        "IV" to Step(3, 0), "vi" to Step(5, 0),
        "V" to Step(4, 0), "V6" to Step(4, 1), "V7" to Step(4, 0, true), "V65" to Step(4, 1, true),
        "V43" to Step(4, 2, true), "V42" to Step(4, 3, true), "vii6" to Step(6, 1),
        "V/V" to Step(4, 0, of = 4), "V7/V" to Step(4, 0, true, 4), "V65/V" to Step(4, 1, true, 4),
    )

    /** Tonic → predominant → dominant → tonic, with the usual inversions and passing chords. */
    private val NEXT = mapOf(
        "I" to listOf("IV", "ii", "ii6", "ii65", "vi", "V", "I6", "V6", "V65", "V43", "vii6", "V/V"),
        "I6" to listOf("IV", "ii", "ii6", "ii65", "V", "V7", "V7/V"),
        "Cad64" to listOf("V", "V7"),
        "ii" to listOf("V", "V7", "Cad64"),
        "ii6" to listOf("V", "V7", "Cad64"),
        "ii65" to listOf("V", "V7", "Cad64"),
        "IV" to listOf("V", "V7", "ii6", "ii65", "Cad64", "I", "I6", "V/V", "V7/V", "V65/V"),
        "vi" to listOf("IV", "ii", "ii6", "ii65"),
        "V" to listOf("I", "vi"),
        "V6" to listOf("I"),
        "V7" to listOf("I", "vi"),
        "V65" to listOf("I"),
        "V43" to listOf("I", "I6"),
        "V42" to listOf("I6"),
        "vii6" to listOf("I", "I6"),
        "V/V" to listOf("V", "V7", "Cad64"),
        "V7/V" to listOf("V", "V7", "Cad64"),
        "V65/V" to listOf("V", "V7"),
    )

    private val EASY = setOf("I", "I6", "ii", "ii6", "IV", "vi", "V", "V6")
    private val MEDIUM = EASY + setOf("Cad64", "ii65", "V7", "V65", "V43", "V42", "vii6")
    private val PRE_CADENCE = setOf("IV", "ii", "ii6", "ii65", "Cad64", "V/V", "V7/V")

    /**
     * A new exercise: the bass with figures given (locked), the upper voices empty; with [soprano], the soprano
     * given instead, a moving melody ending on the tonic, and the lower voices empty. It is checked by solving it, so at least one
     * answer without errors exists; of the tries, the one whose answer has the fewest warnings wins.
     * Null only if every try failed.
     */
    fun generate(level: Level, rnd: Random = Random.Default, soprano: Boolean = false): Score? {
        val chords = when (level) { Level.EASY -> 9; Level.MEDIUM -> 13; Level.HARD -> 17 }
        var best: Score? = null
        var bestWarnings = Int.MAX_VALUE
        // Most voicings of a progression give a static soprano, so a melody takes more tries
        repeat(if (soprano) 200 else 40) {
            val minor = level != Level.EASY && rnd.nextBoolean()
            val reach = when (level) { Level.EASY -> 2; Level.MEDIUM -> 3; Level.HARD -> 4 }
            val key = Key(rnd.nextInt(-reach, reach + 1), if (minor) Mode.MINOR else Mode.MAJOR)
            val plan = progression(level, minor, chords, rnd) ?: return@repeat
            val fb = figuredBass(plan, key, rnd) ?: return@repeat
            val sc = if (!soprano) fb else {
                val melody = solve(fb)?.voice(Voice.SOPRANO) ?: return@repeat
                val ps = melody.mapNotNull { it.pitch?.midi }
                // A melody worth harmonizing: it moves (few repeated notes) over at least a fourth
                if (melody.last().pitch?.pc != key.tonic || ps.zipWithNext().count { (a, b) -> a == b } > chords / 4 || ps.max() - ps.min() < 5) return@repeat
                Score(key = key, time = fb.time, voices = listOf(melody, emptyList(), emptyList(), emptyList()), given = setOf(Voice.SOPRANO))
            }
            val solved = solve(sc) ?: return@repeat
            val issues = VoiceLeading.check(solved).issues
            if (issues.any { it.severity == Severity.ERROR } || issues.size >= bestWarnings) return@repeat
            best = sc; bestWarnings = issues.size
            if (bestWarnings == 0) return sc
        }
        return best
    }

    private fun progression(level: Level, minor: Boolean, n: Int, rnd: Random): List<String>? {
        val allowed = when (level) { Level.EASY -> EASY; Level.MEDIUM -> MEDIUM; Level.HARD -> STEPS.keys }
            .let { if (minor) it - "ii" else it } // ii° in root position is avoided
        repeat(2000) {
            val p = mutableListOf("I")
            while (p.size < n) {
                val next = NEXT.getValue(p.last()).filter { it in allowed }
                // The last three chords make the cadence: a predominant (or cadential ⁶₄), V, I
                val opts = when (p.size) {
                    n - 1 -> next.filter { it == "I" }
                    n - 2 -> next.filter { it == "V" || it == "V7" }
                    n - 3 -> next.filter { it in PRE_CADENCE }
                    else -> next - "Cad64" // a cadential ⁶₄ away from the cadence falls on a weak beat
                }
                if (opts.isEmpty()) break
                p += opts[rnd.nextInt(opts.size)]
            }
            if (p.size == n) return p
        }
        return null
    }

    /** Pitch classes of [st] in [key], root first (harmonic minor for V and vii°). */
    private fun pcs(st: Step, key: Key): List<PitchClass> {
        if (st.of != null) {
            val root = ChordAnalyzer.transpose(key.degree(st.of), 4, 7)
            return listOfNotNull(root, ChordAnalyzer.transpose(root, 2, 4), ChordAnalyzer.transpose(root, 4, 7),
                if (st.seventh) ChordAnalyzer.transpose(root, 6, 10) else null)
        }
        return Voicer.diatonic(key, st.degree, st.seventh)
    }

    /** Bass notes (quarters, the last a whole note in 4/4) with figures; null if the figures would not spell the chord. */
    private fun figuredBass(plan: List<String>, key: Key, rnd: Random): Score? {
        val bass = mutableListOf<Event>()
        var prev: Pitch? = null
        for ((i, name) in plan.withIndex()) {
            val st = STEPS.getValue(name)
            val chord = pcs(st, key)
            val pc = chord[st.inv]
            // The octave nearest the last bass note (D3 to start), inside the bass range
            val range = VoiceLeading.RANGES.getValue(Voice.BASS)
            val b = (1..4).map { Pitch(pc.step, pc.alter, it) }.filter { it.midi in range }
                .minByOrNull { abs(it.midi - (prev?.midi ?: 50)) + rnd.nextDouble() } ?: return null
            val figs = figures(b, chord, st, key)
            if (FiguredBass.realize(b, figs, key).values.toSet() != chord.toSet() - b.pc) return null
            bass += Event(if (i == plan.lastIndex) Fraction.of(1, 1) else Fraction.of(1, 4), b, figures = figs)
            prev = b
        }
        return Score(key = key, time = TimeSig(4, 4), voices = listOf(emptyList(), emptyList(), emptyList(), bass), given = setOf(Voice.BASS))
    }

    /** The usual figures for the inversion, plus an accidental on each note the key signature does not give. */
    private fun figures(bass: Pitch, chord: List<PitchClass>, st: Step, key: Key): List<String> {
        val lines = (if (st.seventh) listOf(listOf("7"), listOf("6", "5"), listOf("4", "3"), listOf("4", "2"))
        else listOf(emptyList(), listOf("6"), listOf("6", "4")))[st.inv].toMutableList()
        for (pc in chord) {
            if (pc == bass.pc) continue
            val sig = key.sigAlter(pc.step)
            if (pc.alter == sig) continue
            val acc = when { pc.alter == 0 -> "n"; pc.alter > sig -> "#"; else -> "b" }
            val n = Math.floorMod(pc.step - bass.step, 7) + 1
            val at = lines.indexOfFirst { it == "$n" }
            when {
                at >= 0 -> lines[at] = acc + lines[at]
                n == 3 -> lines += acc
                else -> lines.add(lines.indexOfFirst { l -> (l.filter { it.isDigit() }.toIntOrNull() ?: 3) < n }.let { if (it < 0) lines.size else it }, "$acc$n")
            }
        }
        return lines
    }

    /** The score with its missing voices written around the given one (see [Voicer]); null if no voicing fits. */
    fun solve(score: Score): Score? = Voicer.fill(score)
}
