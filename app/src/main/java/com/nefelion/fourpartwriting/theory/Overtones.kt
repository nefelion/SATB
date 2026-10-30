package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The harmonic series and what it says about a voicing. A note of pitch f also sounds 2f, 3f, 4f…
 * (its partials, the n-th about 1/n as strong); how the partials of four voices line up shapes the chord's sound.
 */
object Overtones {
    /** Partials per note, as drawn on the bass and played in pure tones. */
    const val PARTIALS = 8

    private val NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Pitch of partial [n] of [midi], as a fractional MIDI note. */
    fun partial(midi: Int, n: Int) = midi + 12 * log2(n.toDouble())

    /** Cents partial [n] lies off the nearest tempered note (partial 7: −31). */
    fun cents(n: Int) = (12 * log2(n.toDouble())).let { ((it - it.roundToInt()) * 100).roundToInt() }

    /** Partials over 30 cents off any piano key, so harmony does not use them: 7, 11, 13, 14. */
    fun outOfTune(n: Int) = abs(cents(n)) > 30

    /** Name of the piano key nearest partial [n] of [midi], such as "B♭4". */
    fun name(midi: Int, n: Int) = partial(midi, n).roundToInt().let { NAMES[Math.floorMod(it, 12)] + (Math.floorDiv(it, 12) - 1) }

    /** Share of the sound on each of [tones] (root, third, fifth): the strength of every partial of [notes] that lands on it. */
    fun shares(notes: List<Pitch>, tones: List<PitchClass>): List<Double> {
        val w = DoubleArray(tones.size)
        for (p in notes) for (n in 1..PARTIALS) if (!outOfTune(n)) {
            val k = tones.indexOfFirst { it.semis == Math.floorMod(partial(p.midi, n).roundToInt(), 12) }
            if (k >= 0) w[k] += 1.0 / n
        }
        return w.map { it / w.sum() }
    }

    /** Partial [n] of a piano string: [hz], amplitude [amp] at the hammer blow (as the piano outputs it), and how fast it fades (per second). */
    class Partial(val n: Int, val hz: Double, val amp: Double, val fast: Double, val slow: Double) {
        /** Strength [t] seconds after the blow: a quick drop, then a long, quiet tail. */
        fun level(t: Double) = amp * (0.7 * exp(-fast * t) + 0.3 * exp(-slow * t))
    }

    /**
     * A4 as the built-in piano strikes it in the overtone lesson (velocity 80), measured from its output (tools/pianowav):
     * frequency, amplitude in its first moments, and fade. A stiff string stretches the partials a little sharp;
     * the hammer strikes near the 9th's still point, so that one is nearly silent; the felt and the fast fade of
     * high partials keep the note dark. Re-measure if the piano model changes.
     */
    val A4_PIANO = listOf(
        Partial(1, 439.9, 3.163e-02, fast = 1.75, slow = 1.75),
        Partial(2, 880.8, 2.564e-02, fast = 2.86, slow = 2.86),
        Partial(3, 1322.1, 9.579e-03, fast = 3.36, slow = 1.90),
        Partial(4, 1766.9, 7.512e-03, fast = 5.90, slow = 2.08),
        Partial(5, 2213.5, 5.384e-03, fast = 7.36, slow = 2.75),
        Partial(6, 2663.3, 3.378e-03, fast = 10.57, slow = 3.85),
        Partial(7, 3116.9, 2.511e-03, fast = 10.86, slow = 5.00),
        Partial(8, 3575.0, 8.615e-04, fast = 15.01, slow = 6.39),
        Partial(9, 4038.2, 9.309e-05, fast = 13.71, slow = 7.47),
        Partial(10, 4506.9, 2.283e-04, fast = 12.34, slow = 8.52),
        Partial(11, 4982.1, 1.780e-04, fast = 14.93, slow = 9.91),
        Partial(12, 5463.9, 1.506e-04, fast = 17.84, slow = 11.85),
        Partial(13, 5951.5, 9.729e-05, fast = 18.99, slow = 18.99),
        Partial(14, 6449.6, 4.767e-05, fast = 17.64, slow = 17.64),
    )

    fun parse(voicing: String) = voicing.split(" ").map { Pitch.parse(it) }

    /** A voicing habit the series explains: [good] and [bad] voicings ("S A T B") of C major, differing in one voice. */
    class Compare(val rule: Rule, val title: String, val head: String, val text: String, val good: String, val bad: String)

    val COMPARE = listOf(
        Compare(Rule.DOUBLING, "Doubling", "Double the root, not the major third.",
            "The root is overtones 1, 2, 4 and 8. The third is already overtone 5, so a second one takes over. " +
                "A minor third is not in the series: doubling it is fine.",
            good = "C5 G4 E4 C3", bad = "E5 G4 E4 C3"),
        Compare(Rule.SPACING, "Spacing", "Wide at the bottom, close at the top.",
            "Overtones lie far apart low down and close together higher up. Space a chord the same way: " +
                "the bass may lie far below, but keep soprano, alto and tenor within an octave of each other.",
            good = "E5 C5 G4 C3", bad = "E5 C5 G3 C3"),
    )
}
