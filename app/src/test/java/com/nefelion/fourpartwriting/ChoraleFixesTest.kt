package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readings fixed after checking the Bach study chorales: each test is a small model of one chorale spot. */
class ChoraleFixesTest {
    private fun c(vararg chords: String) = chordsScore(KC, *chords).analysis()

    @Test fun dominantResolvingWithAllVoicesFalling() {
        // V⁷–I with the leading tone dropping to the fifth: each voice follows its own pull
        assertFalse(c("D5 B4 F4 G3", "C5 G4 E4 C3").has(Rule.SIMILAR_MOTION))
        // I–V⁷ with every voice falling is still a warning
        assertTrue(c("E5 C5 G4 C4", "D5 B4 F4 G3").has(Rule.SIMILAR_MOTION))
    }

    @Test fun bassLeapingOntoTheTenorsNote() {
        // The bass leaps up a fourth onto the tenor's new C: no overlap
        assertFalse(c("D5 G4 B3 G3", "E5 G4 C4 C4").has(Rule.OVERLAP))
    }

    @Test fun upbeatChordOfANewPhrase() {
        // I after the fermata, held into the downbeat
        fun a(f: String) = c("E4 C4 G3 C3", "E4 C4 G3 C3", "D4$f B3$f G3$f G2$f", "E4 C4 G3 C3", "G4 E4 C4 C3", "F4 D4 B3 G2", "E4 C4 G3 C3", "E4 C4 G3 C3")
        assertFalse(a("^").has(Rule.HARMONIC_RHYTHM))
        assertTrue(a("").has(Rule.HARMONIC_RHYTHM))
    }

    @Test fun shortNeighborDippingUnderAHeldBass() {
        val a = linesScore("D5 E5", "B4 C5", "G3:8 F#3:16 G3:16 G3", "G3 C3").analysis()
        assertFalse(a.has(Rule.CROSSING))
    }

    @Test fun lastBarStoppingOnAFermata() {
        fun a(f: String) = linesScore("E5:1 C5:2$f", "G4:1 G4:2$f", "C4:1 E4:2$f", "C3:1 C3:2$f").analysis()
        assertFalse(a("^").has(Rule.STRUCTURE))
        assertTrue(a("").has(Rule.STRUCTURE))
    }

    @Test fun suspensionOverAWalkingBass() {
        // The alto strikes E again over D and falls to D as the bass walks D–E–F: a 9–6, not an unresolved dissonance
        val a = linesScore("C5 A4 A4", "E4 E4 D4", "A3 F3 F3", "A2 D3:8 E3:8 F3").analysis()
        assertEquals(NctType.SUS, a.nct(Voice.ALTO, 1)?.type)
        assertFalse(a.has(Rule.UNRESOLVED_NCT))
    }

    @Test fun newChordOnTheLastEighth() {
        // ii⁷, then tenor and bass move to B and G under the held D and F: V⁷, so no unresolved B and no ii–I retrogression
        val a = linesScore("D5 C5", "F4 E4", "C4:8 B3:8 G3", "A3:8 G3:8 C3").analysis()
        assertTrue(a.romans().toString(), "V⁷" in a.romans())
        assertFalse(a.has(Rule.UNRESOLVED_NCT))
        assertFalse(a.has(Rule.PROGRESSION))
    }

    @Test fun fourThreeSuspensionInADominantSeventh() {
        // Over G the tenor holds C (4–3) while soprano and alto step down: V⁷, not I⁶₄ made of their passing notes
        val a = linesScore("E5 D5 C5", "G4 F4 E4", "C4 C4:8 B3:8 C4", "C3 G2 C3").analysis()
        assertEquals("V⁷", a.romans()[1])
        assertFalse(a.has(Rule.DOUBLING))
    }

    @Test fun sixFourSettlingOntoTheChordOfItsBass() {
        // A cadential ⁶₄ of G (G/D–D⁷–G) and a neighbor IV⁶₄ over the tonic bass (V⁶–IV⁶₄–I)
        assertFalse(c("C5 E4 G3 C3", "B4 G4 D4 D3", "A4 F#4 C4 D3", "G4 G4 B3 G2").has(Rule.SIX_FOUR))
        val a = c("D5 G4 D4 B2", "C5 A4 F4 C3", "C5 G4 E4 C3")
        assertFalse(a.has(Rule.SIX_FOUR))
        assertFalse(a.has(Rule.PROGRESSION))
    }

    @Test fun passingEighthsMakeNoChord() {
        // Soprano and alto climb while the bass falls: their meeting on D is passing, not a doubled seventh
        val a = linesScore("C5:8 D5:8 E5", "E4:8 F4:8 G4", "G3 G3", "E3:8 D3:8 C3").analysis()
        assertEquals(2, a.points.size)
        assertFalse(a.has(Rule.DOUBLING))
        assertFalse(a.has(Rule.SEVENTH))
    }

    @Test fun chordStruckAgainOverAPassingBass() {
        // C–E–G struck again over the bass's passing A is still I: its G need not fall like a seventh
        val a = linesScore("G4 G4 A4", "E4 E4 F4", "C4 C4 C4", "C3:8 B2:8 A2:8 G2:8 F2").analysis()
        assertEquals("I", a.romans()[1])
        assertFalse(a.has(Rule.SEVENTH))
    }

    @Test fun seventhTakenDownByAnotherVoice() {
        // The bass picks up the tenor's F and falls to E while the tenor rises
        assertFalse(linesScore("D5 C5", "B4 C5", "F4 G4", "G3:8 F3:8 E3").analysis().has(Rule.SEVENTH))
    }

    @Test fun chordOverAPassingBassIsNoRetrogression() {
        // ii⁶–I⁶–V⁴₃–I over the bass F–E–D–C
        assertFalse(c("D5 A4 D4 F3", "C5 G4 C4 E3", "B4 G4 F4 D3", "C5 G4 E4 C3").has(Rule.PROGRESSION))
    }
}
