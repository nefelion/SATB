package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Whether [p]'s note name is among [bass]'s in-tune partials, in any octave. */
    fun inSeries(bass: Pitch, p: Pitch) = (1..PARTIALS).any { !outOfTune(it) && Math.floorMod(partial(bass.midi, it).roundToInt() - p.midi, 12) == 0 }

    /**
     * How much the partials of [notes] rub against each other (Plomp–Levelt curve as modeled by Sethares):
     * about 0.4 for an open C major chord over C3, 1.5 for a close one over C3.
     */
    fun roughness(notes: List<Pitch>): Double {
        var d = 0.0
        for (i in notes.indices) for (j in i + 1 until notes.size) for (a in 1..PARTIALS) for (b in 1..PARTIALS) {
            val f1 = hz(notes[i].midi) * a; val f2 = hz(notes[j].midi) * b
            val s = 0.24 / (0.0207 * min(f1, f2) + 18.96); val df = abs(f1 - f2)
            d += min(1.0 / a, 1.0 / b) * (exp(-3.51 * s * df) - exp(-5.75 * s * df))
        }
        return d
    }

    /** Share of the sound on each of [tones] (root, third, fifth): the strength of every partial of [notes] that lands on it. */
    fun shares(notes: List<Pitch>, tones: List<PitchClass>): List<Double> {
        val w = DoubleArray(tones.size)
        for (p in notes) for (n in 1..PARTIALS) if (!outOfTune(n)) {
            val k = tones.indexOfFirst { it.semis == Math.floorMod(partial(p.midi, n).roundToInt(), 12) }
            if (k >= 0) w[k] += 1.0 / n
        }
        return w.map { it / w.sum() }
    }

    /** Partial [n] of a piano string: [hz], strength [amp] at the hammer blow, and how fast it fades (per second). */
    class Partial(val n: Int, val hz: Double, val amp: Double, val fast: Double, val slow: Double) {
        /** Strength [t] seconds after the blow: a quick drop, then a long, quiet tail. */
        fun level(t: Double) = amp * (0.7 * exp(-fast * t) + 0.3 * exp(-slow * t))
    }

    /**
     * The partials of a piano note, up to [upTo] and below 10 kHz. A stiff string stretches them a little sharp
     * (inharmonicity, stronger up high); a hammer striking about 1/8 along the string makes partials near 8 weak;
     * the felt softens the high ones; high partials fade faster, so a piano note darkens as it rings.
     */
    fun piano(midi: Int, upTo: Int = 30): List<Partial> {
        val f0 = hz(midi)
        val b = 3.8e-4 * 2.0.pow((midi - 60) / 24.0)
        val strike = 0.12
        fun shape(n: Int) = (0.25 + 0.75 * abs(sin(n * PI * strike))) * n.toDouble().pow(-1.1) * exp(-(n - 1) / 18.0)
        return (1..upTo).map { n ->
            val f = n * f0 * sqrt(1 + b * n * n)
            Partial(n, f, shape(n) / shape(1), fast = 1.2 + f / 700, slow = 0.25 + f / 5000)
        }.filter { it.hz < 10_000 }
    }

    fun parse(voicing: String) = voicing.split(" ").map { Pitch.parse(it) }

    /** A voicing habit the series explains: [good] and [bad] voicings ("S A T B") of C major; [resolve] follows [bad]. */
    class Compare(val rule: Rule, val tab: String, val head: String, val text: String, val good: String, val bad: String, val resolve: String? = null)

    val COMPARE = listOf(
        Compare(Rule.DOUBLING, "Doubling", "Double the root, not the major third.",
            "The root is overtones 1, 2, 4 and 8. The third is already overtone 5, so a second one takes over. " +
                "A minor third is not in the series: doubling it is fine.",
            good = "C5 G4 E4 C3", bad = "E5 G4 E4 C3"),
        Compare(Rule.SPACING, "Spacing", "Wide at the bottom, close at the top.",
            "That is how overtones are spaced. Pack notes low and their overtones rub.",
            good = "E5 C5 G4 C3", bad = "C4 G3 E3 C3"),
        Compare(Rule.SIX_FOUR, "Six-four", "Let the bass be the root.",
            "Over C every note is one of its overtones. Over G, C sits a fourth above, and a fourth's root is its top note: the chord leans and wants to move on.",
            good = "E5 C5 G4 C3", bad = "E5 C5 G4 G3", resolve = "D5 B4 G4 G3"),
    )
}
