package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Non-homorhythmic writing, part two: change of bass, arpeggiated chords, ornamented suspensions, rests in figures. */
class FloridEdgeTest {
    private val S = Voice.SOPRANO
    private val A = Voice.ALTO
    private val T = Voice.TENOR
    private val B = Voice.BASS

    private fun Analysis.lacks(r: Rule) = assertFalse(issues.toString(), r in rules())

    // ---- Suspensions ----

    /** 9–6: the bass moves to the third just as the ninth resolves, everything else holding. */
    @Test fun suspensionWithChangeOfBass() {
        val a = linesScore("D5:2 D5:4 C5:4 B4:2 C5:2", "B4:2 G4:2 G4:2 G4:2", "G4:2 E4:2 D4:2 E4:2", "G3:2 C3:4 E3:4 G2:2 C3:2").analysis()
        assertEquals(listOf("V", "I", "I⁶", "V", "I"), a.romans())
        assertEquals("9–6", a.nct(S, 1)?.label)
        a.lacks(Rule.UNRESOLVED_NCT)
    }

    /** The same held ninth, but the alto moves with the bass: a new chord, not a suspension. */
    @Test fun heldNinthUnderNewChordIsNoSuspension() {
        val a = linesScore("D5:2 D5:4 C5:4 B4:2 C5:2", "B4:2 G4:4 A4:4 G4:2 G4:2", "G4:2 E4:2 D4:2 E4:2", "G3:2 C3:4 A2:4 G2:2 C3:2").analysis()
        assertEquals(NctType.UNRESOLVED, a.nct(S, 1)?.type)
    }

    /** 4–3 whose resolution is ornamented by the step above (C–D–B). */
    @Test fun suspensionOrnamentedByUpperStep() {
        val a = linesScore("C5:2 C5:8 D5:8 B4:4 C5:1", "G4:2 G4:2 G4:1", "E4:2 D4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "V", "V", "I"), a.romans())
        assertEquals("4–3", a.nct(S, 1)?.label)
        assertNull(a.nct(S, 2))
    }

    /** A bass neighbour (G–F♯–G) while the 4–3 waits does not undo the suspension. */
    @Test fun bassNeighbourUnderSuspension() {
        val a = linesScore("C5:2 C5:4 B4:4", "G4:2 G4:2", "E4:2 D4:2", "C3:2 G2:8 F#2:8 G2:4").analysis()
        assertEquals(listOf("I", "V", "V"), a.romans())
        assertEquals("4–3", a.nct(S, 1)?.label)
        assertEquals(NctType.NT, a.nct(B, 2)?.type)
        a.lacks(Rule.UNRESOLVED_NCT)
    }

    /** A bass that is itself a neighbour on the beat does not turn a held chord tone into a suspension. */
    @Test fun bassOrnamentOnTheBeatMakesNoSuspension() {
        val a = linesScore("C5:1 C5:1", "G4:1 G4:1", "E4:1 D4:1", "C3:4 E3:4 F3:4 E3:4 F3:1").analysis()
        assertTrue(a.ncts.toString(), a.ncts.values.none { it.type == NctType.SUS })
    }

    /** 7–6 over a bass that leaps an octave: the alto's F struck again is the seventh of V⁴₃, not a dropped note. */
    @Test fun restruckSeventhUnderSuspension() {
        val a = linesScore("C5:2 C5:4 B4:4 C5:1", "F4:2 F4:2 E4:1", "A3:2 G3:2 G3:1", "F3:2 D3:4 D2:4 C3:1").analysis()
        assertEquals(listOf("IV", "V⁴₃", "V⁴₃", "I"), a.romans())
        assertEquals("7–6", a.nct(S, 1)?.label)
        assertNull(a.nct(A, 1))
    }

    /** An appoggiatura struck again on the next beat is still an appoggiatura, not a 9–8 prepared by a dissonance. */
    @Test fun restruckAppoggiaturaIsNoSuspension() {
        val a = linesScore("C5:2 C5:4 B4:4 D5:4 D5:4 C5:2", "E4:2 D4:2 E4:2 E4:2", "G3:2 G3:2 G3:2 G3:2", "C3:2 G2:2 C3:2 C3:2").analysis()
        assertEquals("4–3", a.nct(S, 1)?.label)
        assertEquals(NctType.APP, a.nct(S, 3)?.type)
        assertEquals(NctType.APP, a.nct(S, 4)?.type)
    }

    /** A half-note appoggiatura held over the next beat keeps its name. */
    @Test fun heldAppoggiaturaKeepsItsName() {
        val a = linesScore("E5:2 B4:2 D5:2 C5:2", "C5:2 G4:2 G4:2 G4:2", "G3:2 G3:2 E3:4 G3:4 G3:2", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "V", "I", "I", "I"), a.romans())
        assertEquals(NctType.APP, a.nct(S, 2)?.type)
    }

    // ---- Pedals and anticipations ----

    /** An inner pedal struck again where the chord comes back is still a pedal. */
    @Test fun invertedPedalStruckAgain() {
        val a = linesScore("E5:2 D5:2 E5:1", "G4:2 B4:2 G4:1", "C4:1 C4:1", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "V", "I"), a.romans())
        assertEquals(NctType.PED, a.nct(T, 0)?.type)
        a.lacks(Rule.UNRESOLVED_NCT)
    }

    /** A note struck on beat 3 and again on beat 4 anticipates nothing: beat 3 is the stronger beat. Cad⁶₄ moves to V⁷. */
    @Test fun noAnticipationFromAStrongerBeat() {
        val a = linesScore("D5:4 E5:4 F5:4 D5:4 C5:1", "B4:4 C5:4 B4:4 B4:4 C5:1", "G4:4 G4:4 G4:4 G4:4 E4:1", "G2:1 C3:1").analysis()
        assertEquals(listOf("V", "Cad⁶₄", "V⁷", "V", "I"), a.romans())
        assertNull(a.nct(A, 2))
    }

    /** Soprano and bass both anticipate the tonic on the last eighth. */
    @Test fun outerVoicesAnticipateTogether() {
        val a = linesScore("D5:4. C5:8 C5:2", "B4:2 G4:2", "G4:2 E4:2", "G2:4. C3:8 C3:2").analysis()
        assertEquals(listOf("V", "I"), a.romans())
        assertEquals(NctType.ANT, a.nct(S, 1)?.type)
        assertEquals(NctType.ANT, a.nct(B, 1)?.type)
    }

    // ---- Arpeggiated chords ----

    /** The alto arpeggiates G–E: the open fifth on the beat gets its third an eighth later. */
    @Test fun thirdArrivesLaterInTheBeat() {
        val a = linesScore("C5:2 B4:2 C5:1", "G4:8 E4:8 G4:4 G4:2 G4:1", "C4:2 D4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "I", "V", "I"), a.romans())
        a.lacks(Rule.MISSING_THIRD)
    }

    /** The bass leaves the third (C–E–C): the chord before still sounds. */
    @Test fun bassLeavesTheThird() {
        val a = linesScore("C5:2 B4:2 C5:1", "G4:2 G4:2 G4:1", "C4:2 D4:2 E4:1", "C3:8 E3:8 C3:4 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "I", "V", "I"), a.romans())
        a.lacks(Rule.MISSING_THIRD)
    }

    /** In 3/4, a quarter-note arpeggio brings the third only on beat 2. */
    @Test fun thirdArrivesOnTheNextBeat() {
        val a = linesScore("C5:2.", "G4:2.", "C4:4 E4:4 G4:4", "C3:2.", time = com.nefelion.fourpartwriting.model.TimeSig(3, 4)).analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        a.lacks(Rule.MISSING_THIRD)
    }

    /** A real open fifth (every voice struck, nothing else coming) is still reported. */
    @Test fun openFifthStillReported() {
        val a = chordsScore(KC, "E5 C5 G4 C3", "C5 G4 C4 C3").analysis()
        assertTrue(a.issues.toString(), Rule.MISSING_THIRD in a.errorRules())
    }

    /** A–C on the beat, F♯ coming in the bass: ii°, so the F♯ is no dissonance (E minor). */
    @Test fun bareThirdCompletedBelow() {
        val a = linesScore("C5:2.", "A4:2.", "C4:2.", "A2:8 F#2:8 A2:4 F#2:4", key = Key(1, Mode.MINOR), time = com.nefelion.fourpartwriting.model.TimeSig(3, 4)).analysis()
        assertTrue(a.romans().toString(), a.romans().all { it.startsWith("ii°") })
        assertTrue(a.ncts.toString(), a.ncts.isEmpty())
    }

    // ---- Leading tone ----

    /** The leading tone reached on the last eighth of V and left by leap in the soprano is unresolved. */
    @Test fun leadingToneReachedLateAndLeft() {
        val a = linesScore("E5:2 D5:4. B4:8 G4:1", "C5:2 B4:4 G4:4 G4:1", "G4:2 G4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        assertTrue(a.issues.toString(), Rule.LEADING_TONE in a.errorRules())
    }

    /** The same leading tone rising to the tonic is fine. */
    @Test fun leadingToneReachedLateAndResolved() {
        val a = linesScore("E5:2 D5:4. B4:8 C5:1", "C5:2 G4:2 G4:1", "G4:2 D4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        a.lacks(Rule.LEADING_TONE)
    }

    // ---- Rests ----

    /** C–D–(rest)–E: a short rest does not break the passing tone. */
    @Test fun passingToneAcrossShortRest() {
        val a = linesScore("C5:4 D5:8 r:8 E5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        a.lacks(Rule.UNRECOGNIZED)
    }

    /** A rest of a whole beat does break it. */
    @Test fun longRestBreaksTheFigure() {
        val a = linesScore("C5:4 D5:8 r:8 r:4 E5:4", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I", "I"), a.romans())
        assertEquals(NctType.UNRESOLVED, a.nct(S, 1)?.type)
        a.lacks(Rule.UNRECOGNIZED)
    }
}
