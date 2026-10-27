package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice

/**
 * A short wrong passage and its corrected version, used to show what a [Rule] means.
 * Both are full four-voice scores (the checker needs them), but only the [show] voices are drawn;
 * [roman]: also show roman numerals, for rules about harmony.
 */
class RuleExample(val bad: Score, val good: Score, val show: Set<Voice>, val roman: Boolean)

/**
 * One example per rule, in C major. Each voice is a line of notes ("C5", "C5:8" for an eighth, "r" for a rest,
 * "C3@6" for a bass note with figures, "E5!Am" for a note with a chord name);
 * the default length is a quarter (drawn as a half, see [line]), and the time signature fits the whole example in one bar unless given.
 */
object RuleExamples {

    private fun line(s: String) = s.split(" ").map { full ->
        val token = full.substringBefore('!')
        val tok = token.substringBefore('@')
        val (p, d) = tok.split(":").let { it[0] to (it.getOrNull(1) ?: "4") }
        // Written values are doubled (a quarter shows as a half) so the chords spread out on a small card
        Event(Duration(d.toInt() / 2).value, if (p == "r") null else Pitch.parse(p),
            figures = token.substringAfter('@', "").split(",").filter { it.isNotEmpty() }, chord = full.substringAfter('!', "").ifEmpty { null })
    }

    private fun score(v: List<String>, time: TimeSig?): Score {
        val voices = v.map(::line)
        val q = voices[0].fold(Fraction.ZERO) { a, e -> a + e.length } / Fraction.of(1, 2)
        return Score(time = time ?: TimeSig((q.num / q.den).toInt(), 2), voices = voices)
    }

    private fun voices(show: String) = show.map { c -> Voice.entries.first { it.label == "$c" } }.toSet()

    /** Chords as "S A T B" strings, wrong then right. */
    private fun chords(bad: List<String>, good: List<String>, show: String, roman: Boolean = false, time: TimeSig? = null) =
        RuleExample(score(cols(bad), time), score(cols(good), time), voices(show), roman)

    private fun cols(cs: List<String>) = (0 until 4).map { v -> cs.joinToString(" ") { it.split(" ")[v] } }

    /** Voice lines "S", "A", "T", "B", wrong then right. */
    private fun lines(bad: List<String>, good: List<String>, show: String, roman: Boolean = false) =
        RuleExample(score(bad, null), score(good, null), voices(show), roman)

    val ALL: Map<Rule, RuleExample> = mapOf(
        Rule.PARALLEL_OCTAVES to chords(listOf("C5 C4 E3 C3", "D5 D4 B3 G2"), listOf("C5 C4 E3 C3", "B4 D4 G3 G2"), "SA"),
        Rule.PARALLEL_FIFTHS to chords(listOf("G4 C4 E3 C3", "A4 D4 F3 F2"), listOf("G4 C4 E3 C3", "A4 C4 F3 F2"), "SA"),
        Rule.LEADING_TONE to chords(listOf("B4 D4 G3 G2", "G4 E4 G3 C3"), listOf("B4 D4 G3 G2", "C5 E4 G3 C3"), "S", roman = true),
        Rule.SEVENTH to chords(listOf("D5 F4 B3 G2", "E5 G4 C4 C3"), listOf("D5 F4 B3 G2", "C5 E4 C4 C3"), "A", roman = true),
        Rule.CROSSING to chords(listOf("G4 E4 C4 C3", "F4 A4 C4 F3"), listOf("G4 E4 C4 C3", "A4 F4 C4 F3"), "SA"),
        Rule.MISSING_THIRD to chords(listOf("G5 C5 G4 C4"), listOf("G5 C5 E4 C4"), "SATB", roman = true),
        Rule.DOUBLING to chords(listOf("B4 G4 D4 B3"), listOf("B4 G4 D4 G3"), "SATB", roman = true),
        Rule.SPACING to chords(listOf("E5 C4 G3 C3"), listOf("E5 G4 C4 C3"), "SA"),
        Rule.MELODIC to chords(listOf("G4 E4 C4 C3", "F5 D4 B3 G2"), listOf("G4 E4 C4 C3", "F4 D4 B3 G2"), "S"),
        Rule.OVERLAP to chords(listOf("E4 C4 G3 C3", "G4 F4 B3 G2"), listOf("E4 C4 G3 C3", "G4 D4 B3 G2"), "SA"),
        Rule.DIRECT to chords(listOf("E4 C4 G3 C3", "G4 D4 B3 G3"), listOf("E4 C4 G3 C3", "G4 D4 B3 G2"), "SB"),
        Rule.TENDENCY to chords(listOf("Ab4 F4 C4 F3", "C5 E4 C4 C3"), listOf("Ab4 F4 C4 F3", "G4 E4 C4 C3"), "S", roman = true),
        Rule.UNRESOLVED_NCT to lines(
            listOf("C5:8 F4:8 D5", "E4 D4", "G3 B3", "C3 G2"),
            listOf("C5:8 D5:8 D5", "E4 D4", "G3 B3", "C3 G2"),
            "S", roman = true,
        ),
        Rule.CADENCE to chords(listOf("D4 B3 G3 G2", "E4 C4 G3 E3"), listOf("D4 B3 G3 G2", "E4 C4 G3 C3"), "B", roman = true),
        Rule.NAMED_CHORD to chords(listOf("E5!Am C5 G4 C4"), listOf("E5!Am C5 A4 A3"), "SATB"),
        Rule.FIGURES to chords(listOf("G4 C4 G3 C3@6"), listOf("A4 C4 E3 C3@6"), "SATB"),
        Rule.PROGRESSION to chords(listOf("B4 D4 G3 G2", "A4 C4 F3 F2"), listOf("A4 C4 F3 F2", "B4 D4 G3 G2"), "B", roman = true),
        Rule.SIX_FOUR to chords(listOf("E4 C4 A3 A2", "F4 C4 A3 C3"), listOf("E4 C4 A3 A2", "F4 C4 A3 F2"), "B", roman = true),
        Rule.RANGE to chords(listOf("A5 F4 C4 F3"), listOf("A4 F4 C4 F3"), "S"),
        Rule.LEAPS to chords(listOf("E4 C4 G3 C3", "C5 F4 A3 F3", "D5 G4 B3 G2"), listOf("E4 C4 G3 C3", "C5 F4 A3 F3", "B4 G4 D4 G2"), "S"),
        Rule.CROSS_RELATION to chords(listOf("A4 F4 D4 D3", "F#4 C4 A3 D3"), listOf("A4 F4 D4 D3", "A4 F#4 C4 D3"), "SA"),
        Rule.UNEQUAL_FIFTHS to chords(listOf("G4 D4 F3 B2", "E4 C4 G3 C3"), listOf("G4 D4 F3 B2", "G4 C4 E3 C3"), "TB"),
        Rule.HARMONIC_RHYTHM to chords(
            listOf("E4 C4 G3 C3", "D4 B3 G3 G2", "D4 B3 G3 G2"),
            listOf("E4 C4 G3 C3", "D4 B3 G3 G2", "E4 C4 G3 C3"),
            "B", roman = true, time = TimeSig(2, 2),
        ),
        Rule.SIMILAR_MOTION to chords(listOf("E5 C5 G4 C4", "D5 B4 F4 G3"), listOf("E5 C5 G4 C4", "D5 B4 G4 G3"), "SATB"),
        Rule.MISSING_VOICE to chords(listOf("C5 r E4 C4"), listOf("C5 G4 E4 C4"), "SATB", roman = true),
        Rule.UNRECOGNIZED to chords(listOf("C5 Gb4 E4 C4"), listOf("C5 G4 E4 C4"), "SATB", roman = true),
        Rule.STRUCTURE to lines(listOf("C5 B4", "G4", "E4", "C4"), listOf("C5 B4", "G4 G4", "E4 D4", "C4 G3"), "SATB"),
    )
}
