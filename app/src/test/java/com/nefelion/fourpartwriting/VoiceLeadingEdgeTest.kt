package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLeadingEdgeTest {
    private val Am = Key(0, Mode.MINOR)
    private val Cm = Key(-3, Mode.MINOR)

    private fun c(vararg chords: String) = chordsScore(KC, *chords).analysis()

    @Test fun retardedLeadingToneIsNotUnresolved() {
        // V → I with the leading tone struck again over the tonic and rising to C (7–8)
        val a = c("B4 D4 G3 G2", "B4 E4 G3 C3", "C5 E4 G3 C3")
        assertFalse(a.errorRules().toString(), Rule.LEADING_TONE in a.errorRules())
        // ...but repeated and then falling is still wrong
        val bad = c("B4 D4 G3 G2", "B4 E4 G3 C3", "A4 E4 G3 C3")
        assertTrue(bad.errorRules().toString(), Rule.LEADING_TONE in bad.errorRules())
    }

    @Test fun struckSusChordIsFlagged() {
        val flagged = setOf(Rule.MISSING_THIRD, Rule.UNRECOGNIZED, Rule.UNRESOLVED_NCT)
        assertTrue(c("F4 C4 G3 C3").rules().any { it in flagged })
        assertTrue(c("C5 E4 G3 C3", "F4 C4 G3 C3", "E4 C4 G3 C3").rules().any { it in flagged })
        assertTrue(c("D4 G3 G3 C3").rules().any { it in flagged })
    }

    @Test fun crossingAndSpacingAcrossARestingVoice() {
        // Alto rests: soprano below tenor is still a crossing; soprano two octaves above tenor is still too wide
        assertTrue(Rule.CROSSING in c("C4 r E4 C3").rules())
        assertTrue(Rule.SPACING in c("E5 r G3 C3").rules())
    }

    @Test fun parallelsInDisguise() {
        // Alto and tenor crossed, still a fifth apart, moving in parallel
        assertTrue(Rule.PARALLEL_FIFTHS in c("E5 C4 G4 C3", "F5 D4 A4 D3").errorRules())
        // Unison to octave by contrary motion
        assertTrue(Rule.PARALLEL_OCTAVES in c("G4 G4 C4 C3", "A4 A3 D4 F3").errorRules())
        // Compound fifth (12th) to fifth
        assertTrue(Rule.PARALLEL_FIFTHS in c("G4 E4 C4 C3", "A4 F4 A3 D3").errorRules())
        // A held voice (oblique motion) makes no parallel
        assertFalse(Rule.PARALLEL_OCTAVES in c("C5 G4 E4 C3", "C5 A4 F4 F3").errorRules())
    }

    @Test fun melodicIntervals() {
        // d7 leap
        assertTrue(Rule.MELODIC in chordsScore(Am, "G#4 E4 B3 E3", "F5 D4 B3 D3").analysis().errorRules())
        // d4 G♯–C in minor is singable
        assertFalse(Rule.MELODIC in chordsScore(Am, "G#4 E4 B3 E3", "C5 E4 A3 A2").analysis().errorRules())
        // Descending A4 is still augmented
        assertTrue(Rule.MELODIC in c("G4 D4 B3 B2", "A4 F4 C4 F2").errorRules())
    }

    @Test fun seventhResolutions() {
        // V⁴₃ → I⁶ with the seventh rising in parallel tenths with the bass
        assertFalse(Rule.SEVENTH in c("F4 B3 G3 D3", "G4 C4 G3 E3").errorRules())
        // ii⁷ → V, seventh down by step
        assertFalse(Rule.SEVENTH in c("C5 F4 A3 D3", "B4 D4 G3 G2").errorRules())
        // ii⁷ → V, seventh leaps up
        assertTrue(Rule.SEVENTH in c("C5 F4 A3 D3", "D5 G4 B3 G2").errorRules())
    }

    @Test fun augmentedSixthIntoCadentialSixFour() {
        val a = c("F#4 Eb4 C4 Ab2", "G4 E4 C4 G2", "G4 D4 B3 G2", "G4 E4 C4 C3")
        assertTrue(a.errorRules().toString(), a.errorRules().isEmpty())
        // Ger+6 straight to V: fifths
        assertTrue(Rule.PARALLEL_FIFTHS in c("F#4 Eb4 C4 Ab2", "G4 D4 B3 G2").errorRules())
    }

    @Test fun neapolitanCadenceInMinor() {
        val a = chordsScore(Cm, "Db5 Ab4 F4 F3", "C5 G4 Eb4 G3", "B4 G4 D4 G3", "C5 G4 Eb4 C3").analysis()
        assertTrue(a.errorRules().toString(), a.errorRules().isEmpty())
        assertTrue("Cad⁶₄" in a.romans())
        assertTrue(a.messages().none { "Neapolitan" in it })
    }

    @Test fun cadences() {
        // Picardy third after V
        assertFalse(Rule.CADENCE in chordsScore(Am, "B4 E4 G#3 E3", "C#5 E4 A3 A2").analysis().rules())
        // Plagal in minor
        assertFalse(Rule.CADENCE in chordsScore(Am, "D5 A4 F4 D3", "C5 A4 E4 A2").analysis().rules())
        // Ending on I⁶
        assertTrue(Rule.CADENCE in c("D4 B3 G3 G2", "E4 C4 G3 E3").rules())
        // Ending on vi (deceptive)
        assertTrue(Rule.CADENCE in c("D4 B3 G3 G2", "C4 C4 E3 A2").rules())
    }

    @Test fun directOctaves() {
        // Soprano steps into the octave with the bass: fine
        assertFalse(Rule.DIRECT in c("B4 D4 G3 G2", "C5 E4 G3 C3").rules())
        // Soprano leaps into it: direct octave
        assertTrue(Rule.DIRECT in c("G4 D4 G3 B2", "C5 E4 G3 C3").rules())
    }

    @Test fun meterAwareRules() {
        // 3/4: V on beat 3 repeated on the downbeat
        val hr = chordsScore(KC, "E4 C4 G3 C3", "F4 C4 A3 F3", "D4 B3 G3 G2", "D4 B3 G3 G2", "E4 C4 G3 C3", "E4 C4 G3 C3", time = TimeSig(3, 4)).analysis()
        assertTrue(Rule.HARMONIC_RHYTHM in hr.rules())
        // 6/8: Cad⁶₄ on the downbeat, V on beat 2: fine
        val cad = chordsScore(KC, "E4:4. C4:4. G3:4. C3:4.", "F4:4. C4:4. A3:4. F3:4.", "E4:4. C4:4. G3:4. G2:4.",
            "D4:4. B3:4. G3:4. G2:4.", "C4:2. C4:2. E3:2. C3:2.", time = TimeSig(6, 8)).analysis()
        assertTrue(cad.messages().toString(), cad.messages().none { "stronger beat" in it })
    }

    @Test fun doubledLeadingToneOfSecondaryDominant() {
        assertTrue(c("F#4 D4 A3 F#3", "G4 D4 B3 G3").messages().any { it.startsWith("Doubled leading tone (F♯)") })
    }

    @Test fun exampleStaysClean() {
        assertTrue(EXAMPLE.analysis().issues.isEmpty())
    }

    @Test fun overlapAndHeldVoices() {
        // Tenor moves above the alto's previous note
        assertTrue(Rule.OVERLAP in c("G4 E4 C4 C3", "A4 F4 F4 F3").rules())
        // One voice holding is enough to avoid "all voices in one direction"
        assertFalse(Rule.SIMILAR_MOTION in c("E4 C4 G3 C3", "F4 D4 G3 D3").rules())
    }

    @Test fun sixFourKinds() {
        // Pedal IV⁶₄ over a held C
        assertFalse(Rule.SIX_FOUR in c("E4 C4 G3 C3", "F4 C4 A3 C3", "E4 C4 G3 C3").rules())
        // Arpeggiated: I → I⁶₄
        assertFalse(Rule.SIX_FOUR in c("E4 C4 G3 C3", "E4 C4 G3 G2", "F4 C4 A3 F2").rules())
    }

    @Test fun deceptiveCadenceLeadingTone() {
        val a = c("B4 F4 D4 G2", "C5 E4 C4 A2")
        assertFalse(a.errorRules().toString(), Rule.LEADING_TONE in a.errorRules() || Rule.SEVENTH in a.errorRules())
        // Soprano leading tone falling in V → vi
        assertTrue(Rule.LEADING_TONE in c("B4 D4 G3 G2", "A4 E4 C4 A2").errorRules())
    }

    @Test fun retrogressionVToIi() {
        assertTrue(Rule.PROGRESSION in c("E4 C4 G3 C3", "D4 B3 G3 G2", "D4 A3 F3 D3", "D4 B3 G3 G2", "E4 C4 G3 C3").rules())
    }

    @Test fun tritoneOutlineAndRests() {
        fun msgs(s: String) = linesScore(s, "C4:1", "A3:1", "F3:1").analysis().messages()
        assertTrue(msgs("F4 A4 B4 A4").any { "tritone" in it })
        // A plain scale line is easy to sing
        assertTrue(msgs("F4 G4 A4 B4").none { "tritone" in it })
        assertTrue(msgs("F4 G4 B4 C5").none { "tritone" in it })
    }

    @Test fun leapsBeyondAnOctave() {
        assertTrue(Rule.MELODIC in c("C4 G3 E3 C3", "D5 G3 F3 B2").errorRules())
        // The bass may leap an octave without turning back
        assertFalse(Rule.LEAPS in c("E4 C4 G3 C3", "E4 C4 G3 C2", "F4 C4 A3 F2").rules())
    }

    @Test fun fifthsSequenceOverridesTendencies() {
        // I–IV–vii°–iii–vi–ii–V–I: doubled vii° root and the F–B bass leap belong to the pattern
        val seq = c("E5 C5 G4 C3", "C5 A4 F4 F3", "D5 B4 F4 B2", "B4 G4 E4 E3", "C5 A4 E4 A2", "A4 F4 D4 D3", "B4 G4 D4 G2", "C5 G4 E4 C3")
        assertTrue(seq.issues.toString(), seq.rules().none { it in setOf(Rule.DOUBLING, Rule.LEAPS, Rule.MELODIC) })
        // Rising tritone F–B in the bass of the sequence too
        val up = c("C5 G4 E4 C3", "C5 A4 F4 F2", "B4 F4 D4 B2", "B4 G4 E4 E2", "C5 A4 E4 A2", "A4 F4 D4 D3", "B4 G4 D4 G2", "C5 G4 E4 C3")
        assertFalse(up.issues.toString(), Rule.MELODIC in up.rules())
        // Chain of dominant sevenths: the voices fall together with the bass by pattern
        val chain = c("D5 G#4 B3 E3", "C#5 G4 A3 A2", "C5 F#4 A3 D3", "B4 F4 G3 G2", "C5:1 E4:1 G3:1 C3:1")
        assertTrue(chain.issues.toString(), chain.issues.isEmpty())
        // Too short to be a sequence: the usual rules apply
        val short = c("E5 C5 G4 C3", "C5 A4 F4 F3", "D5 B4 F4 B2", "B4 G4 E4 E3:2")
        assertTrue(Rule.DOUBLING in short.errorRules())
        assertTrue(Rule.LEAPS in short.rules())
    }

    /** After a fermata a new phrase starts: Bach's leap from a half cadence to the next phrase is not a parallel. */
    @Test fun fermataEndsThePhrase() {
        fun score(f: String) = chordsScore(KC, "C5 G4 E4 C3", "B4$f G4$f D4$f G2$f", "E4 C4 G3 C3", "D4 B3 G3 G2", "C4 C4 E3 C3")
        val plain = score("").analysis()
        assertTrue(plain.issues.toString(), plain.has(Rule.PARALLEL_FIFTHS) && plain.has(Rule.PARALLEL_OCTAVES))
        val phrased = score("^").analysis()
        assertFalse(phrased.issues.toString(), phrased.has(Rule.PARALLEL_FIFTHS) || phrased.has(Rule.PARALLEL_OCTAVES) || phrased.has(Rule.MELODIC))
    }

    /** Tenor and bass on one note, then both falling: the tenor ends below the old unison but nothing was overlapped. */
    @Test fun leavingAUnisonIsNotOverlap() {
        chordsScore(KC, "E5 C5 C4 C4", "D5 B4 G3 G2", "C5 C5 E3 C3").analysis().let { assertFalse(it.issues.toString(), it.has(Rule.OVERLAP)) }
        chordsScore(KC, "E5 C5 G4 C4", "D5 B4 B3 G3", "C5 C5 G3 C3").analysis().let { assertTrue(it.issues.toString(), it.has(Rule.OVERLAP)) }
    }

    /** A chord between two forms of one harmony over a stepping or held bass is a neighbor or passing chord, not a retrogression. */
    @Test fun neighborChordIsNoRetrogression() {
        val neighbor = c("D5 G4 G3 B2", "C5 F4 F3 A2", "D5 F4 G3 B2", "C5 E4 G3 C3")
        assertFalse(neighbor.issues.toString(), neighbor.has(Rule.PROGRESSION))
        // V⁶–IV⁶–I does not come back: still a retrogression
        assertTrue(c("D5 G4 G3 B2", "C5 F4 F3 A2", "C5 E4 G3 C3").has(Rule.PROGRESSION))
    }

    /** A seventh struck between chord points as a neighbor (D–C–D in eighths) or rising to the root inside the chord is figuration. */
    @Test fun seventhAsFiguration() {
        val neighbor = linesScore("C5 C5 B4 C5", "G4 A4 G4 G4", "E4 D4:8 C4:8 D4 E4", "C3 F3 G3 C3").analysis()
        assertFalse(neighbor.issues.toString(), neighbor.has(Rule.SEVENTH))
        val rising = linesScore("E5 D5 C5:2", "G4 G4 G4:2", "C4 B3 C4:2", "C3 F3:8 G3:8 C3:2").analysis()
        assertFalse(rising.issues.toString(), rising.has(Rule.SEVENTH))
        // Struck on the chord and rising at the change: still wrong
        assertTrue(linesScore("C5 D5 B4 C5", "G4 A4 G4 G4", "E4 C4 D4 E4", "C3 F3 G3 C3").analysis().has(Rule.SEVENTH))
    }

    /** Voices crossed after the move: one crossing, not a crossing and an overlap. */
    @Test fun crossingIsNotAlsoOverlap() {
        val a = c("C5 G4 G3 E3", "C5 G4 C3 E3")
        assertTrue(a.issues.toString(), a.has(Rule.CROSSING) && !a.has(Rule.OVERLAP))
    }

    /** A note out of range is reported once, not again on every eighth it is held under. */
    @Test fun rangeOncePerNote() {
        val a = linesScore("C5:8 D5:8 E5:8 D5:8 C5:2", "G4:1", "E4:1", "D4:2 C3:2").analysis()
        assertEquals(a.issues.toString(), 1, a.rules().count { it == Rule.RANGE })
    }
}
