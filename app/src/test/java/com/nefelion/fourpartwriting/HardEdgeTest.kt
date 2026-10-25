package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Textbook passages around rests, repeated notes, ties and chromatic chords. */
class HardEdgeTest {
    private val Am = Key(0, Mode.MINOR)
    private val S = Voice.SOPRANO
    private fun c(vararg chords: String) = chordsScore(KC, *chords).analysis()

    // ---- Non-chord tones ----

    /** A note tied into the chord it belongs to is an anticipation, however it was reached. */
    @Test fun tiedAnticipationByLeap() {
        val a = linesScore("E5:8 A5:4. D5:4", "G4:4 A4:4 B4:4", "C4:4 C4:4 G3:4", "C3:4 F3:4 G2:4", time = TimeSig(3, 4)).analysis()
        assertEquals(listOf("I", "IV", "V"), a.romans())
        assertEquals(a.ncts.toString(), NctType.ANT, a.nct(S, 1)?.type)
        assertFalse(a.issues.toString(), Rule.UNRESOLVED_NCT in a.errorRules())
    }

    /** 7–6 chain over a falling bass of sixth chords. */
    @Test fun sevenSixChain() {
        val a = linesScore("C5:2. B4:2 A4:2 G4:4 G4:1", "G4:2 F4:2 E4:2 D4:2 E4:1", "C4:2 D4:2 C4:2 G3:2 C4:1", "E3:2 D3:2 C3:2 B2:2 C3:1").analysis()
        assertEquals(listOf("I⁶", "vii°⁶", "vii°⁶", "vi⁶", "vi⁶", "V⁶", "V⁶", "I"), a.romans())
        assertEquals("7–6", a.nct(S, 0)?.label)
        assertEquals("7–6", a.nct(S, 1)?.label)
        assertEquals("7–6", a.nct(S, 2)?.label)
    }

    /** Lower-upper double neighbour around a held tonic. */
    @Test fun doubleNeighbour() {
        val a = linesScore("C5:8 D5:8 B4:8 C5:8 C5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertFalse(a.issues.toString(), Rule.UNRESOLVED_NCT in a.errorRules())
    }

    /** V⁷ over a tonic pedal. */
    @Test fun dominantOverTonicPedal() {
        val a = linesScore("E5:4 F5:4 F5:4 E5:4", "G4:4 A4:4 G4:4 G4:4", "C4:4 C4:4 B3:4 C4:4", "C3:1").analysis()
        assertTrue(a.issues.toString(), a.errorRules().isEmpty())
    }

    // ---- Resolutions ----

    /** The leading tone that arrives as the resolution of a 4–3 still has to rise. */
    @Test fun leadingToneAfterSuspension() {
        fun sc(s: String) = linesScore(s, "G4:4 G4:4 E4:4", "E4:4 D4:4 C4:4", "C3:4 G2:4 C3:4", time = TimeSig(3, 4)).analysis()
        val good = sc("C5:4 C5:8 B4:8 C5:4")
        assertEquals(listOf("I", "V", "I"), good.romans())
        assertEquals("4–3", good.nct(S, 1)?.label)
        assertFalse(good.issues.toString(), Rule.LEADING_TONE in good.errorRules())
        val bad = sc("C5:4 C5:8 B4:8 G4:4")
        assertTrue(bad.issues.toString(), Rule.LEADING_TONE in bad.errorRules())
    }

    /** A seventh struck twice still has to resolve down. */
    @Test fun restruckSeventh() {
        val a = linesScore("D5:4 E5:4", "F4:8 F4:8 G4:4", "B3:4 C4:4", "G2:4 C3:4").analysis()
        assertEquals(listOf("V⁷", "I"), a.romans())
        assertTrue(a.issues.toString(), Rule.SEVENTH in a.errorRules())
    }

    /** Augmented sixth whose bass is struck twice before it resolves. */
    @Test fun restruckAugmentedSixth() {
        val a = linesScore("F#4:4 G4:4", "Eb4:4 D4:4", "C4:4 B3:4", "Ab2:8 Ab2:8 G2:4").analysis()
        assertEquals("Ger⁺⁶", a.romans()[0])
        assertFalse(a.issues.toString(), Rule.TENDENCY in a.rules())
    }

    /** The fifth of V/iii (F♯ in C) is 2̂ of the tonicized key, free to fall. */
    @Test fun fifthOfAppliedDominantIsFree() {
        val a = c("F#5 B4 D#4 B2", "E5 B4 E4 G2")
        assertEquals(listOf("V/iii", "iii⁶"), a.romans())
        assertFalse(a.issues.toString(), Rule.TENDENCY in a.rules())
    }

    /** Respelling a chord enharmonically is not motion. */
    @Test fun enharmonicRespellingIsNotParallel() {
        val a = c("G#4 E#4 C#4 C#3", "Ab4 F4 Db4 Db3")
        assertFalse(a.issues.toString(), Rule.PARALLEL_FIFTHS in a.rules() || Rule.PARALLEL_OCTAVES in a.rules())
    }

    // ---- Doubling ----

    /** VI before V in minor doubles its third (doubling the root makes octaves or an augmented second). */
    @Test fun submediantBeforeDominantDoublesThird() {
        val a = chordsScore(Am, "A4 C4 A3 F2", "G#4 E4 B3 E2", "A4 E4 C4 A2").analysis()
        assertEquals(listOf("VI", "V", "i"), a.romans())
        assertTrue(a.issues.toString(), a.messages().none { "Doubled third" in it })
    }

    /** The root of a mixture chord (♭VI) is its normal doubling. */
    @Test fun mixtureRootMayBeDoubled() {
        val a = c("C5 G4 E4 C3", "C5 Ab4 Eb4 Ab2", "C5 Ab4 F4 F2", "B4 G4 D4 G2", "C5 G4 E4 C3")
        assertEquals(listOf("I", "♭VI", "iv", "V", "I"), a.romans())
        assertTrue(a.issues.toString(), a.messages().none { "Doubled" in it })
    }

    // ---- Context labels ----

    @Test fun picardyHeldOverTwoBeats() {
        val a = linesScore("B4:4 C#5:4 E5:2", "E4:4 E4:2.", "G#3:4 A3:2.", "E3:4 A2:2.", key = Am).analysis()
        assertEquals(listOf("V", "I (Picardy)", "I (Picardy)"), a.romans())
    }

    @Test fun cadentialSixFourRestruck() {
        val a = c("C5 A4 F4 F2", "C5 G4 E4 G2", "E5 G4 C4 G2", "D5 G4 B3 G2", "C5:1 G4:1 E4:1 C3:1")
        assertEquals(listOf("IV", "Cad⁶₄", "Cad⁶₄", "V", "I"), a.romans())
    }

    // ---- Rests ----

    /** A rest in every voice is silence, not a chord with missing voices. */
    @Test fun generalRestIsNotAChord() {
        val a = c("C5 G4 E4 C3", "r r r r", "B4 G4 D4 G2", "C5 G4 E4 C3")
        assertTrue(a.issues.toString(), Rule.MISSING_VOICE !in a.rules())
    }

    /** The final cadence is checked even when the piece ends with rests. */
    @Test fun finalCadenceBeforeRests() {
        val v = c("C5 G4 E4 C3", "B4 G4 D4 G2", "r:2 r:2 r:2 r:2")
        assertTrue(v.issues.toString(), Rule.CADENCE in v.rules())
        assertTrue(v.issues.toString(), Rule.MISSING_VOICE !in v.rules())
        val i = c("B4 G4 D4 G2", "C5 G4 E4 C3", "r:2 r:2 r:2 r:2")
        assertTrue(i.issues.toString(), Rule.CADENCE !in i.rules() && Rule.MISSING_VOICE !in i.rules())
    }

    // ---- Melodic ----

    /** A repeated note does not hide a big leap that is not turned back. */
    @Test fun leapAcrossRepeatedNote() {
        val a = linesScore("C4:4 A4:8 A4:8 B4:4 C5:4", "C4:1", "A3:1", "F3:1").analysis()
        assertTrue(a.messages().toString(), a.messages().any { "after the leap" in it })
    }

    // ---- Textbook passages: every chord named as written, no issues at all ----

    private fun clean(a: com.nefelion.fourpartwriting.theory.Analysis, romans: List<String>) {
        assertEquals(romans, a.romans())
        assertTrue(a.issues.joinToString(" | ") { "${it.slice} ${it.rule}: ${it.message}" }, a.issues.isEmpty())
    }

    @Test fun cleanPredominantDominantTonic() =
        clean(c("E5 G4 C4 C3", "F5 A4 C4 F2", "F5 A4 D4 F3", "F5 B4 D4 G3", "E5:1 C5:1 C4:1 C3:1"),
            listOf("I", "IV", "ii⁶", "V⁷", "I"))

    @Test fun cleanNeapolitanCadenceInMinor() =
        clean(chordsScore(Am, "E5 A4 C4 A2", "D5 Bb4 F4 D3", "C5 A4 E4 E3", "B4 G#4 D4 E3", "C5:1 A4:1 C4:1 A2:1").analysis(),
            listOf("i", "N⁶", "Cad⁶₄", "V⁷", "i"))

    @Test fun cleanAppliedDominantAndDeceptiveCadence() =
        clean(c("C5 G4 E4 C3", "C5 F#4 A3 D3", "B4 G4 G3 G2", "C5 E4 C4 A2", "D5 F4 A3 F2", "D5 D4 B3 G2", "C5:2 E4:2 C4:2 C3:2"),
            listOf("I", "V⁷/V", "V", "vi", "ii⁶", "V", "I"))

    @Test fun cleanSuspensionCadence() =
        clean(linesScore("E5:4 E5:4 C5:4. B4:8 C5:1", "G4:4 G4:4 A4:4 G4:4 E4:1", "C4:4 C4:4 F4:4 D4:4 C4:1", "C3:4 E3:4 F3:4 G3:4 C3:1").analysis(),
            listOf("I", "I⁶", "IV", "V", "I"))

    @Test fun cleanAppliedDominantOfSubdominant() =
        clean(c("C5 G4 E4 C3", "Bb4 G4 E4 C3", "A4 F4 C4 F3", "G4 F4 B3 G3", "G4:1 E4:1 C4:1 C3:1"),
            listOf("I", "V⁷/IV", "IV", "V⁷", "I"))

    @Test fun cleanDiminishedSeventhInMinor() =
        clean(chordsScore(Am, "A4:2 E4:2 C4:2 A2:2", "G#4 F4 D4 B2", "A4 E4 C4 C3", "G#4:2 E4:2 B3:2 E3:2", "A4:2 E4:2 C4:2 A2:2").analysis(),
            listOf("i", "vii°⁶₅", "i⁶", "V", "i"))

    // ---- Round 3 ----

    /** Rests starting off the beat are not attacks: no chord point there. */
    @Test fun restsDoNotMakeChordPoints() {
        val a = linesScore("E5:8 F5:8 G5:4", "G4:8 r:8 G4:4", "C4:8 r:8 C4:4", "C3:2").analysis()
        assertEquals(a.slices.map { it.time }.toString(), 2, a.points.size)
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertTrue(a.issues.toString(), Rule.MISSING_VOICE !in a.rules())
    }

    /** After silence in every voice the old chord is gone: a pickup is not its non-chord tone. */
    @Test fun pickupAfterGeneralRest() {
        val a = linesScore("C5:2 r:4. B4:8 C5:1", "E4:2 r:2 E4:1", "G3:2 r:2 G3:1", "C3:2 r:2 C3:1").analysis()
        assertFalse(a.issues.toString(), Rule.UNRESOLVED_NCT in a.rules())
    }

    /** Respelled notes around an ornament are not ornamented parallels. */
    @Test fun enharmonicRespellingAroundAnOrnament() {
        val a = linesScore("G#4:8 F#4:8 Ab4:4", "E#4:4 F4:4", "C#4:4 Db4:4", "C#3:4 Db3:4").analysis()
        assertFalse(a.issues.toString(), Rule.PARALLEL_FIFTHS in a.rules() || Rule.PARALLEL_OCTAVES in a.rules())
    }

    /** A seventh chord struck again over the same bass keeps its name; its seventh is no suspension. */
    @Test fun restruckSeventhChordKeepsItsName() {
        val a = chordsScore(Key(2, Mode.MINOR), "B4 G4 D4 F#3", "D5 B4 G4 F#3", "D5 B4 G#4 E#3").analysis()
        assertEquals(listOf("VIM⁴₂", "VIM⁴₂", "vii°⁷/V"), a.romans())
        assertTrue(a.ncts.toString(), a.ncts.isEmpty())
    }
}
