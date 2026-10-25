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

class MoreEdgeTest {
    private val S = Voice.SOPRANO
    private val A = Voice.ALTO
    private val Am = Key(0, Mode.MINOR)
    private fun c(vararg chords: String) = chordsScore(KC, *chords).analysis()

    // ---- parallels hidden by ornaments ----

    @Test fun fifthsBrokenByAPassingToneAreStillParallel() {
        // S E5–(D5)–C5 over A3 → F3: fifths (12ths) on the two beats
        val a = linesScore("E5:8 D5:8 C5:4", "C5:4 A4:4", "E4:4 F4:4", "A3:4 F3:4").analysis()
        assertTrue(a.messages().toString(), Rule.PARALLEL_FIFTHS in a.errorRules())
        assertEquals(1, a.issues.count { it.rule == Rule.PARALLEL_FIFTHS })
    }

    @Test fun octavesBrokenByAPassingToneAreStillParallel() {
        val a = linesScore("C5:8 B4:8 A4:4", "G4:4 A4:4", "E4:4 C4:4", "C3:4 A2:4").analysis()
        assertTrue(a.messages().toString(), Rule.PARALLEL_OCTAVES in a.errorRules())
    }

    @Test fun ornamentNotBetweenTheSameVoicesIsFine() {
        // Only the soprano passes; no perfect interval repeats on the beats
        val a = linesScore("E5:8 D5:8 F5:4", "C5:4 C5:4", "E4:4 A4:4", "A3:4 F3:4").analysis()
        assertFalse(a.messages().toString(), Rule.PARALLEL_FIFTHS in a.errorRules())
    }

    // ---- leading tones of secondary dominants ----

    @Test fun secondaryLeadingToneIntoCadentialSixFour() {
        val good = c("A4 F#4 D4 D3", "C5 G4 E4 G2", "B4 G4 D4 G2", "C5 G4 E4 C3")
        assertEquals("Cad⁶₄", good.romans()[1])
        assertFalse(good.messages().toString(), Rule.LEADING_TONE in good.rules())
        // F♯ drops to E instead of rising to G
        val bad = c("A4 F#4 D4 D3", "C5 E4 C4 G2", "B4 D4 G3 G2", "C5 E4 G3 C3")
        assertTrue(bad.messages().toString(), Rule.LEADING_TONE in bad.rules())
    }

    @Test fun secondaryLeadingToneMayFallChromaticallyToTheSeventh() {
        // V⁷/V → V⁷: F♯ → F♮ in the soprano becomes the seventh of V⁷
        val a = c("F#4 C4 A3 D3", "F4 B3 G3 G2")
        assertFalse(a.messages().toString(), Rule.LEADING_TONE in a.rules())
        // I → V⁷/IV: B → B♭
        val b = c("B4 G4 D4 G3", "Bb4 G4 E4 C4")
        assertFalse(b.messages().toString(), Rule.LEADING_TONE in b.rules())
    }

    // ---- melodic messages ----

    @Test fun diminishedOctaveIsNotCalledLargerThanAnOctave() {
        val m = c("C4 G3 E3 C3", "Cb5 Ab4 Eb4 Ab3").messages().filter { it.startsWith("S:") }
        assertTrue(m.toString(), m.any { "d8" in it } && m.none { "larger than an octave" in it })
    }

    // ---- non-chord tones ----

    @Test fun doubleSuspension() {
        val a = linesScore("C5:4. B4:8", "A4:4. G4:8", "F4:4 D4:4", "F3:4 G3:4").analysis()
        assertEquals(listOf("IV", "V"), a.romans())
        assertEquals("4–3", a.nct(S, 0)?.label)
        assertEquals("9–8", a.nct(A, 0)?.label)
    }

    @Test fun chromaticPassingDownThroughTheThird() {
        // E → E♭ → D over a C chord
        val a = linesScore("E5:8 Eb5:8 D5:4", "G4:4 G4:4", "C4:4 B3:4", "C3:4 G2:4").analysis()
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
    }

    @Test fun tritoneOutlineDownwards() {
        val m = linesScore("B4 A4 G4 F4", "D4:1", "B3:1", "G3:1").analysis().messages()
        assertTrue(m.any { "tritone" in it })
    }

    // ---- regression guards over longer phrases ----

    @Test fun minorKeyCadentialPhrase() {
        // i – iv⁶ – V – i in A minor
        val a = chordsScore(Am, "C5 A4 E4 A3", "D5 A4 D4 F3", "B4 G#4 E4 E3", "C5 A4 E4 A2").analysis()
        assertTrue(a.errorRules().toString(), a.errorRules().isEmpty())
    }

    @Test fun compoundMeterSuspension() {
        val a = linesScore("C5:4. C5:8 B4:4", "G4:4. G4:4.", "E4:4. D4:4.", "C3:4. G2:4.", time = TimeSig(6, 8)).analysis()
        assertEquals("4–3", a.nct(S, 1)?.label)
    }

    @Test fun suspensionWithOrnamentedResolution() {
        // C5 held over G, dips to A4, then resolves to B4: still a 4–3
        val a = linesScore("C5:4. A4:16 B4:16", "G4:2", "E4:4 D4:4", "C3:4 G2:4").analysis()
        assertEquals("4–3", a.nct(S, 0)?.label)
        assertEquals("V", a.romans()[1])
        assertFalse(a.errorRules().toString(), Rule.UNRESOLVED_NCT in a.errorRules())
        // Leaping away with no resolution afterwards is still unresolved
        val bad = linesScore("C5:4. A4:16 G4:16", "G4:2", "E4:4 D4:4", "C3:4 G2:4").analysis()
        assertTrue(Rule.UNRESOLVED_NCT in bad.errorRules())
    }
}
