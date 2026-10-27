package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyEdgeTest {
    private val S = Voice.SOPRANO
    private val A = Voice.ALTO
    private val T = Voice.TENOR
    private val B = Voice.BASS

    @Test fun chromaticPassingTone() {
        // C5 – C♯5 – D5 over I → ii⁶
        val up = linesScore("C5:8 C#5:8 D5:4", "G4:4 A4:4", "E4:4 F4:4", "C3:4 F3:4").analysis()
        assertEquals(listOf("I", "ii⁶"), up.romans())
        assertEquals(NctType.PT, up.nct(S, 1)?.type)
        assertTrue(up.errorRules().toString(), Rule.UNRESOLVED_NCT !in up.errorRules())
        // D5 – D♭5 – C5 over V⁷ → I
        val down = linesScore("D5:8 Db5:8 C5:4", "B4:4 C5:4", "F4:4 E4:4", "G3:4 C3:4").analysis()
        assertEquals(NctType.PT, down.nct(S, 1)?.type)
    }

    @Test fun accentedNeighborOnTheBeat() {
        // F5 on beat 2 steps in from E5 and back: an accented neighbor, the chord stays I
        val a = linesScore("E5:4 F5:8 E5:8", "G4:2", "C4:2", "C3:2").analysis()
        assertEquals(listOf("I", "I"), a.romans())
        assertEquals(NctType.NT, a.nct(S, 1)?.type)
        assertTrue(a.errorRules().toString(), a.errorRules().isEmpty())
    }

    @Test fun omittedFifthIsStillAChordTone() {
        // C–E chord (no fifth); the alto then sounds the fifth G
        val a = linesScore("C5:4", "E4:8 G4:8", "C4:4", "C3:4").analysis()
        assertNull(a.nct(A, 1))
        // G7 without its fifth; the alto then sounds D
        val b = linesScore("F4:4", "B3:8 D4:8", "G3:4", "G2:4").analysis()
        assertNull(b.nct(A, 1))
    }

    @Test fun ninthEightSuspension() {
        val a = linesScore("D5:4. C5:8", "B4:4 G4:4", "G4:4 E4:4", "G3:4 C3:4").analysis()
        assertEquals(listOf("V", "I"), a.romans())
        assertEquals("9–8", a.nct(S, 0)?.label)
        assertTrue(a.errorRules().toString(), Rule.UNRESOLVED_NCT !in a.errorRules())
    }

    @Test fun bassSuspension() {
        // Bass C3 held under the G chord, resolving to B2: 2–3 over V⁶
        val a = linesScore("G4:2", "E4:4 D4:4", "C4:4 B3:4", "C3:4. B2:8").analysis()
        assertEquals("2–3", a.nct(B, 0)?.label)
        assertEquals("V⁶", a.romans()[1])
    }

    @Test fun suspensionThatRunsIntoARestIsUnresolved() {
        val a = linesScore("C5:4. r:8", "G4:2", "E4:4 D4:4", "C3:4 G2:4").analysis()
        assertTrue(Rule.UNRESOLVED_NCT in a.errorRules())
    }

    @Test fun compoundMeterChordPoints() {
        val a = linesScore("E5:8 F5:8 G5:8", "G4:4.", "C4:4.", "C3:4.", time = TimeSig(6, 8)).analysis()
        assertEquals(listOf(0), a.points)
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
    }

    @Test fun degenerateScoresDoNotCrash() {
        assertTrue(Score().analysis().issues.isEmpty())
        linesScore("r:4", "r:4", "r:4", "r:4").analysis()
        linesScore("r:4 E5:4", "G4:2", "C4:2", "C3:2").analysis().let { a ->
            assertTrue(Rule.MISSING_VOICE in a.rules())
        }
        linesScore("C5:2", "E4:4", "G3:4 G3:4 G3:4", "C3:1").analysis().let { a ->
            assertTrue(Rule.STRUCTURE in a.rules())
        }
    }

    @Test fun passingToneInMinor() {
        val a = linesScore("B4:4 C5:8 B4:8 A4:4", "E4:4 E4:4 E4:4", "G#3:4 A3:4 C4:4", "E3:4 A2:4 A2:4", key = com.nefelion.fourpartwriting.model.Key(0, com.nefelion.fourpartwriting.model.Mode.MINOR)).analysis()
        assertEquals(NctType.PT, a.nct(S, 2)?.type)
    }

    @Test fun chromaticNeighbors() {
        val lower = linesScore("G4:8 F#4:8 G4:4", "E4:2", "C4:2", "C3:2").analysis()
        assertEquals(NctType.NT, lower.nct(S, 1)?.type)
        val upper = linesScore("G4:8 G#4:8 G4:4", "E4:2", "C4:2", "C3:2").analysis()
        assertEquals(NctType.NT, upper.nct(S, 1)?.type)
    }

    @Test fun passingTonesInThirds() {
        val a = linesScore("E5:8 F5:8 G5:4", "C5:8 D5:8 E5:4", "G4:2", "C4:2").analysis()
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(A, 1)?.type)
        assertTrue(a.errorRules().toString(), a.errorRules().isEmpty())
    }

    @Test fun leapingAwayFromADissonanceIsUnresolved() {
        // F5 approached by step, left by a leap in the same direction
        val a = linesScore("E5:8 F5:8 A5:4", "G4:2", "C4:2", "C3:2").analysis()
        assertEquals(NctType.UNRESOLVED, a.nct(S, 1)?.type)
    }

    @Test fun fourThreeInTheAlto() {
        val a = linesScore("G4:2", "E4:4 F4:8 E4:8", "C4:4 C4:4", "C3:4 C3:4").analysis()
        // F4 struck on beat 2 from E4 and back: accented neighbor, not a suspension
        assertEquals(NctType.NT, a.nct(A, 1)?.type)
        val sus = linesScore("A4:4 G4:4", "F4:4. E4:8", "C4:2", "F3:4 C3:4").analysis()
        assertEquals("4–3", sus.nct(A, 0)?.label)
        assertEquals(listOf("IV", "I"), sus.romans())
    }
}
