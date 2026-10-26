package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tuplets and cross-rhythms ("C5:12" is a triplet eighth, ":20" a quintuplet sixteenth, ":6" a quarter triplet). */
class TupletEdgeTest {
    private val S = Voice.SOPRANO
    private val A = Voice.ALTO
    private val T = Voice.TENOR
    private val B = Voice.BASS
    private val T68 = TimeSig(6, 8)

    private fun Analysis.noErrors() = assertTrue(issues.toString(), errorRules().isEmpty())
    private fun Analysis.clean() = assertTrue(issues.toString(), issues.isEmpty())
    private fun Analysis.lacks(r: Rule) = assertFalse(issues.toString(), r in rules())
    private fun Analysis.type(v: Voice, i: Int) = nct(v, i)?.type
    private fun f(n: Long, d: Long) = Fraction.of(n, d)

    // ---- Ornaments in tuplets over a held chord ----

    @Test fun tripletPassingAndNeighbourOverHeldTonic() {
        val a = linesScore("C5:12 D5:12 E5:12 F5:12 E5:12 D5:12 C5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(NctType.NT, a.type(S, 3))
        assertEquals(NctType.PT, a.type(S, 5))
        a.clean()
    }

    /** Three against two: triplets in the soprano, eighths in the alto. */
    @Test fun tripletsAgainstEighths() {
        val a = linesScore("E5:12 D5:12 C5:12 D5:4 E5:2", "G4:8 A4:8 G4:2.", "C4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(NctType.PT, a.type(S, 3))
        assertEquals(NctType.NT, a.type(A, 1))
        a.clean()
    }

    @Test fun quintupletRun() {
        val a = linesScore("C5:20 D5:20 E5:20 F5:20 G5:20 G5:4 C5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(NctType.PT, a.type(S, 3))
        a.clean()
    }

    @Test fun septupletScaleThroughTwoPassingTones() {
        val a = linesScore("G4:28 A4:28 B4:28 C5:28 D5:28 E5:28 F5:28 G5:4 C5:2", "G4:1", "E4:1", "C3:1").analysis()
        for (i in listOf(1, 2, 4, 6)) assertEquals("$i", NctType.PT, a.type(S, i))
        a.clean()
    }

    @Test fun sextupletArpeggio() {
        val a = linesScore("C5:24 E5:24 G5:24 E5:24 C5:24 G4:24 C5:2.", "G4:1", "E4:1", "C3:1").analysis()
        assertTrue(a.ncts.toString(), a.ncts.isEmpty())
        a.clean()
    }

    @Test fun passingToneAcrossARestInATriplet() {
        val a = linesScore("C5:12 D5:12 r:12 E5:4 E5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(NctType.PT, a.type(S, 1))
        a.noErrors()
    }

    /** Parallel sixths in triplets, alto and soprano passing together. */
    @Test fun parallelSixthsInTriplets() {
        val a = linesScore("C5:12 D5:12 E5:12 E5:2.", "E4:12 F4:12 G4:12 G4:2.", "C4:1", "C3:1").analysis()
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(NctType.PT, a.type(A, 1))
        a.clean()
    }

    /** I – IV⁶₄ – I neighbour chords in triplets over a held bass: a pedal ⁶₄, nothing wrong. */
    @Test fun neighbourSixFourInTripletsOverPedal() {
        val a = linesScore("C5:12 C5:12 C5:12 C5:2.", "G4:12 A4:12 G4:12 G4:2.", "E4:12 F4:12 E4:12 E4:2.", "C3:1").analysis()
        a.lacks(Rule.SIX_FOUR)
        a.lacks(Rule.HARMONIC_RHYTHM)
        a.noErrors()
    }

    /** A bass arpeggio in quarter triplets under a passing soprano. */
    @Test fun bassArpeggioInQuarterTriplets() {
        val a = linesScore("E5:4 D5:4 C5:2", "G4:1", "C4:1", "C3:6 E3:6 G3:6 C3:2").analysis()
        assertEquals(NctType.PT, a.type(S, 1))
        a.noErrors()
    }

    // ---- Compound meters: duplets and quadruplets ----

    @Test fun dupletAgainstEighthsInSixEight() {
        val a = linesScore("E5:8. D5:8. C5:4.", "G4:8 A4:8 G4:8 G4:4.", "C4:2.", "C3:2.", time = T68).analysis()
        assertEquals(listOf("I", "I"), a.romans())
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(NctType.NT, a.type(A, 1))
        a.clean()
    }

    @Test fun quadrupletInSixEight() {
        val a = linesScore("E5:16. F5:16. E5:16. D5:16. C5:4.", "G4:2.", "C4:2.", "C3:2.", time = T68).analysis()
        assertEquals(NctType.NT, a.type(S, 1))
        assertEquals(NctType.PT, a.type(S, 3))
        a.clean()
    }

    // ---- Cadences ornamented in tuplets ----

    /** The leading tone anticipates the tonic on the last triplet eighth. */
    @Test fun tripletAnticipationOfTheTonic() {
        val a = linesScore("B4:2 B4:4 B4:12 B4:12 C5:12 C5:1", "G4:1 G4:1", "F4:1 E4:1", "G3:1 C3:1").analysis()
        assertEquals(NctType.ANT, a.type(S, 4))
        assertEquals("I", a.romans().last())
        a.lacks(Rule.LEADING_TONE)
        a.noErrors()
    }

    /** The anticipation as the last quarter triplet. */
    @Test fun quarterTripletAnticipation() {
        val a = linesScore("B4:2 B4:6 B4:6 C5:6 C5:1", "G4:1 G4:1", "F4:1 E4:1", "G3:1 C3:1").analysis()
        assertEquals(NctType.ANT, a.type(S, 3))
        a.lacks(Rule.LEADING_TONE)
        a.noErrors()
    }

    /** B–A–B triplet turn on beat 4 of V, then C: the leading tone resolves. */
    @Test fun leadingToneTripletTurn() {
        val a = linesScore("B4:2. B4:12 A4:12 B4:12 C5:1", "G4:1 G4:1", "D4:1 E4:1", "G3:1 C3:1").analysis()
        assertEquals(listOf("V", "V", "I"), a.romans())
        assertEquals(NctType.NT, a.type(S, 2))
        a.lacks(Rule.LEADING_TONE)
        a.noErrors()
    }

    /** Escape tone and anticipation in one triplet (D–E–C | C). */
    @Test fun escapeAndAnticipationInATriplet() {
        val a = linesScore("D5:2. D5:12 E5:12 C5:12 C5:1", "B4:1 G4:1", "F4:1 E4:1", "G3:1 C3:1").analysis()
        assertEquals(NctType.ESC, a.type(S, 2))
        assertEquals(NctType.ANT, a.type(S, 3))
        a.noErrors()
    }

    /** 4–3 suspension whose resolution is ornamented by a triplet lower neighbour. */
    @Test fun suspensionWithTripletTurn() {
        val a = linesScore("C5:2 C5:4 B4:12 A4:12 B4:12 C5:1", "G4:2 G4:2 G4:1", "E4:2 D4:2 E4:1", "C3:2 G3:2 C3:1").analysis()
        assertEquals("4–3", a.nct(S, 1)?.label)
        assertEquals(listOf("I", "V", "V", "I"), a.romans())
        a.noErrors()
    }

    /** 4–3 resolving on the second triplet eighth of the beat. */
    @Test fun suspensionResolvingInsideATriplet() {
        val a = linesScore("C5:2 C5:12 B4:12 B4:12 B4:4 C5:1", "G4:2 G4:2 G4:1", "E4:2 D4:2 E4:1", "C3:2 G3:2 C3:1").analysis()
        assertEquals(NctType.SUS, a.type(S, 1))
        a.noErrors()
    }

    /** The seventh of V⁷ resolves through a triplet upper neighbour (F–G–F–E). */
    @Test fun seventhWithTripletNeighbour() {
        val a = linesScore("B4:1 C5:1", "G4:1 G4:1", "F4:2. F4:12 G4:12 F4:12 E4:1", "G3:1 C3:1").analysis()
        a.lacks(Rule.SEVENTH)
        a.noErrors()
    }

    // ---- Parallels in and around tuplets ----

    @Test fun parallelFifthsInTriplets() {
        val a = linesScore("E5:1", "C5:1", "G3:12 A3:12 B3:12 C4:2.", "C3:12 D3:12 E3:12 A2:2.").analysis()
        assertTrue(a.messages().toString(), a.messages().count { it == "Parallel fifths between T–B." } >= 2)
    }

    /** A triplet arpeggio between two beats does not hide fifths from beat to beat. */
    @Test fun fifthsHiddenByATripletArpeggio() {
        val a = linesScore("E5:4 F5:2.", "C5:4 D5:2.", "G3:12 C4:12 E4:12 A3:2.", "C3:4 D3:2.").analysis()
        assertTrue(a.issues.toString(), a.has(Rule.PARALLEL_FIFTHS))
    }

    /** Three against two: the voices never attack together off the beat, so no parallels there. */
    @Test fun threeAgainstTwoHasNoFalseParallels() {
        val a = linesScore("G4:12 A4:12 B4:12 C5:2.", "C4:8 D4:8 E4:2.", "C4:1", "C3:1").analysis()
        a.lacks(Rule.PARALLEL_FIFTHS)
        a.lacks(Rule.PARALLEL_OCTAVES)
    }

    @Test fun crossingInsideATriplet() {
        val a = linesScore("E5:4 E5:2.", "C5:12 D5:12 F5:12 C5:2.", "G4:1", "C3:1").analysis()
        assertTrue(a.issues.toString(), a.has(Rule.CROSSING))
    }

    // ---- Metre ----

    @Test fun tripletPositionsAreWeakerThanTheBeat() {
        val c44 = TimeSig(4, 4)
        assertTrue(VoiceLeading.weight(f(1, 12), c44) < VoiceLeading.weight(f(1, 4), c44))
        assertTrue(VoiceLeading.weight(f(1, 6), c44) < VoiceLeading.weight(f(1, 4), c44))
        assertTrue(VoiceLeading.weight(f(5, 6), c44) < VoiceLeading.weight(f(1, 1), c44))
        assertEquals(VoiceLeading.weight(f(1, 12), c44), VoiceLeading.weight(f(1, 8), TimeSig(12, 8)))
    }

    /** A tuplet spanning the bar line (quarter triplets from beat 4) still completes the bars. */
    @Test fun tupletAcrossTheBarLine() {
        val a = linesScore("C5:2. C5:6 D5:6 E5:6 E5:4 E5:2", "G4:1 G4:1", "E4:1 C4:1", "C3:1 C3:1").analysis()
        a.lacks(Rule.STRUCTURE)
        a.noErrors()
    }

    /** An unfinished tuplet leaves the voices unequal. */
    @Test fun unfinishedTupletIsReported() {
        val a = linesScore("C5:12 D5:12 E5:2.", "G4:1", "E4:1", "C3:1").analysis()
        assertTrue(a.issues.toString(), a.has(Rule.STRUCTURE))
    }

    // ---- Tuplet notes sounding across a beat ----

    /** The middle quarter triplet (D) still sounds on beat 2, where the bass strikes again: a passing tone. */
    @Test fun syncopatedPassingToneInQuarterTriplets() {
        val a = linesScore("C5:6 D5:6 E5:6 E5:2", "G4:1", "E4:1", "C3:4 C3:4 C3:2").analysis()
        assertEquals(NctType.PT, a.type(S, 1))
        assertEquals(listOf("I", "I", "I"), a.romans())
        a.noErrors()
    }

    /** The same with a plain syncopation (eighth–quarter–eighth). */
    @Test fun syncopatedPassingToneInEighths() {
        val a = linesScore("C5:8 D5:4 E5:8 E5:2", "G4:1", "E4:1", "C3:4 C3:4 C3:2").analysis()
        assertEquals(NctType.PT, a.type(S, 1))
        a.noErrors()
    }

    @Test fun syncopatedNeighbourInQuarterTriplets() {
        val a = linesScore("E5:6 F5:6 E5:6 E5:2", "G4:1", "C4:1", "C3:4 C3:4 C3:2").analysis()
        assertEquals(NctType.NT, a.type(S, 1))
        a.noErrors()
    }

    /** A bass passing tone in quarter triplets, sounding on beat 2. */
    @Test fun syncopatedBassPassingTone() {
        val a = linesScore("E5:4 E5:4 E5:2", "G4:1", "C4:1", "C3:6 D3:6 E3:6 E3:2").analysis()
        assertEquals(NctType.PT, a.type(B, 1))
        a.noErrors()
    }

    /** Half-note triplets: A arrives over I and is held into IV on beat 3, an anticipation. */
    @Test fun halfNoteTripletAnticipatesTheNextChord() {
        val a = linesScore("C5:3 A4:3 C5:3", "G4:2 F4:2", "E4:2 C4:2", "C3:2 F3:2").analysis()
        assertEquals(listOf("I", "IV"), a.romans())
        assertEquals(NctType.ANT, a.type(S, 1))
        a.noErrors()
    }

    /** A quarter triplet tied over the bar line is prepared and resolves as a 4–3. */
    @Test fun suspensionPreparedByATripletOverTheBar() {
        val a = linesScore("E5:2 E5:6 D5:6 C5:5/12 B4:4 C5:2", "G4:1 G4:2 G4:2", "C4:1 D4:2 E4:2", "C3:1 G2:2 C3:2").analysis()
        assertEquals(NctType.PT, a.type(S, 2))
        assertEquals("4–3", a.nct(S, 3)?.label)
        a.noErrors()
    }

    /**
     * Accented passing B (C–B–A, in triplets) over IV, while the tenor's C is a chord tone reached by leap and left
     * by step: the dissonance against the bass is the ornament, not the tenor (not vii°⁶₄).
     */
    @Test fun accentedPassingToneBesideAnAppoggiaturaShapedChordTone() {
        val a = linesScore("E4 F4 D4 A4", "C4 B3:6 A3:12 A3 E4", "G3 C3 D3 A3", "C3 F2 F2 C3", time = TimeSig(2, 4)).analysis()
        assertEquals("IV", a.romans()[1])
        assertEquals(NctType.PT, a.type(A, 1))
        assertEquals(null, a.nct(T, 1))
    }

    /** A triplet-eighth neighbour on beat 2 making A–C–E is no vi⁶: the alto's G right after it is a chord tone. */
    @Test fun tripletNeighbourOnTheBeatMakesNoChord() {
        val a = linesScore("E5:1", "G4:4 A4:12 G4:12 E4:7/12", "C4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I"), a.romans())
        assertEquals(NctType.NT, a.type(A, 1))
        assertEquals(null, a.nct(A, 2))
        a.noErrors()
    }

    /** The bass passes F–E–D–C in triplets, E sounding over beat 3: struck as a dissonance, it is no 2–3 suspension. */
    @Test fun unpreparedSyncopationIsNoSuspension() {
        val a = linesScore("F4:1 E4:1", "A3:8 C4:4 A3:8 C4:2 C4:1", "C3:1 G3:1", "F2:3 E2:3 D2:6 C2:6 C3:1").analysis()
        assertEquals("IV", a.romans()[1])
        assertEquals(NctType.PT, a.type(B, 1))
    }

    /** A syncopated neighbour (B–C–B, C sounding on beat 2) over iii⁶: the bare third C–E on the beat is not I⁶. */
    @Test fun syncopatedNeighbourOverABareThird() {
        val a = linesScore("E4:1", "B3:8 C4:4 B3:8 G3:2", "E3:1", "G2:4 E2:12 G2:12 E2:7/12").analysis()
        assertEquals(listOf("iii⁶", "iii⁶", "iii⁶"), a.romans())
        assertEquals(NctType.NT, a.type(A, 1))
    }

    /** A syncopated tenor neighbour and a triplet bass neighbour together on beat 2 (G–B–D) still ornament I. */
    @Test fun syncopatedAndTripletNeighboursTogether() {
        val a = linesScore("C5:1", "G4:1", "E4:6 D4:3 E4:6 G4:3", "C3:4 B2:12 C3:12 E3:7/12").analysis()
        assertEquals("I", a.romans()[1])
        assertEquals(NctType.NT, a.type(T, 1))
        assertEquals(NctType.NT, a.type(B, 1))
        a.noErrors()
    }

    /** The bass leaps to A on beat 2 as the tenor moves to A: vi for an eighth, not an ornamented I. */
    @Test fun bassLeapOnTheBeatMakesAChord() {
        val a = linesScore("E5:1", "C5:1", "G4:4 A4:8 G4:8 G4:2", "C3:4 A2:8 B2:8 C3:2").analysis()
        assertEquals("vi", a.romans()[1])
    }

    /** The bass steps to D under a held C chord and leaps on to F: unresolved, and still so when the next beat would call it a pedal. */
    @Test fun dissonanceHeldOverTheNextBeatIsNoPedal() {
        for (bass in listOf("C3:2 D3:2 F3:1", "C3:2 D3:6 D3:6 D3:6 F3:1")) {
            val a = linesScore("E5:2. E5:4 E5:1", "G4:1 G4:1", "C4:1 C4:1", bass).analysis()
            assertTrue(bass + a.issues, a.issues.any { it.rule == Rule.UNRESOLVED_NCT && it.message.startsWith("B: D3") })
        }
    }

    // ---- Resolutions in tuplets ----

    /** 7–8: the leading tone held into I rises on the second triplet eighth. */
    @Test fun leadingToneRetardationInTriplets() {
        val a = linesScore("B4:1 B4:12 C5:11/12", "G4:1 G4:1", "D4:1 E4:1", "G3:1 C3:1").analysis()
        assertEquals("7–8", a.nct(S, 1)?.label)
        a.lacks(Rule.LEADING_TONE)
        a.noErrors()
    }

    /** The seventh of V⁷ held into I as a 4–3, resolving on the second triplet eighth. */
    @Test fun seventhSuspendedIntoTonicInTriplets() {
        val a = linesScore("B4:1 C5:1", "G4:1 G4:1", "F4:1 F4:12 E4:11/12", "G3:1 C3:1").analysis()
        assertEquals("4–3", a.nct(T, 1)?.label)
        a.lacks(Rule.SEVENTH)
        a.noErrors()
    }

    /** The leading tone leaps away in a triplet and never reaches the tonic. */
    @Test fun leadingToneAbandonedInATriplet() {
        val a = linesScore("D5:2. B4:12 G4:12 B4:12 E5:1", "G4:1 G4:1", "D4:1 C4:1", "G3:1 C3:1").analysis()
        assertTrue(a.issues.toString(), a.has(Rule.LEADING_TONE))
    }

    /** Octaves from beat to beat between the outer voices, a triplet arpeggio in between. */
    @Test fun octavesAroundATripletArpeggio() {
        val a = linesScore("C5:12 E5:12 C5:12 D5:2.", "G4:4 A4:2.", "E4:4 F4:2.", "C3:4 D3:2.").analysis()
        assertTrue(a.issues.toString(), a.has(Rule.PARALLEL_OCTAVES))
    }

    /** A bass arpeggio I–I⁶–I⁶₄–I under triplets keeps I from the downbeat: no weak-to-strong repetition. */
    @Test fun arpeggiatedBassKeepsTheHarmonyOverTheBar() {
        val a = linesScore("C5:12 D5:12 E5:12 E5:2. C5:12 D5:12 E5:12 E5:2.", "G4:1 G4:1", "E4:1 E4:1",
            "C3:4 E3:4 G3:4 C3:4 C3:4 E3:4 G3:4 C3:4").analysis()
        a.lacks(Rule.HARMONIC_RHYTHM)
    }

    // ---- Chords in tuplets ----

    /** I – IV – V in triplet eighths, then I on beat 2. */
    @Test fun progressionInTriplets() {
        val a = linesScore("C5:12 C5:12 B4:12 C5:2.", "G4:12 A4:12 G4:12 G4:2.", "E4:12 F4:12 D4:12 E4:2.", "C3:12 F3:12 G3:12 C3:2.").analysis()
        assertEquals(listOf("I", "IV", "V", "I"), a.romans())
        a.noErrors()
        a.lacks(Rule.CADENCE)
    }

    /** A cadential ⁶₄ on a triplet off-beat before V on the beat is metrically wrong. */
    @Test fun cadentialSixFourOffTheBeat() {
        val a = linesScore("C5:4 C5:12 C5:12 E5:12 D5:4 C5:4", "G4:4 G4:12 A4:12 G4:12 G4:4 G4:4", "E4:4 F4:12 F4:12 C4:12 B3:4 C4:4",
            "C3:4 F3:12 F3:12 G3:12 G3:4 C3:4").analysis()
        assertTrue(a.romans().toString(), "Cad⁶₄" in a.romans())
        assertTrue(a.issues.toString(), a.issues.any { it.rule == Rule.SIX_FOUR })
    }
}
