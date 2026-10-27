package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.theory.FiguredBass
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiguredBassTest {
    private val C = Key(0, Mode.MAJOR)
    private val Am = Key(0, Mode.MINOR)
    private fun names(bass: String, figs: List<String>, key: Key = C) =
        FiguredBass.realize(Pitch.parse(bass), figs, key).toSortedMap().values.joinToString(" ") { it.name }

    @Test fun abbreviationsFillIn() {
        assertEquals("E G", names("C3", emptyList()))
        assertEquals("E A", names("C3", listOf("6")))
        assertEquals("F A", names("C3", listOf("6", "4")))
        assertEquals("B D F", names("G2", listOf("7")))
        assertEquals("D F G", names("B2", listOf("6", "5")))
        assertEquals("F G B", names("D3", listOf("4", "3")))
        assertEquals("G B D", names("F3", listOf("2")))
        assertEquals("F G", names("C3", listOf("4")))
    }

    @Test fun accidentalsChangeTheNote() {
        // A lone sharp is the third: the leading tone of A minor over E
        assertEquals("G♯ B", names("E3", listOf("#"), Am))
        assertEquals("G B", names("E3", emptyList(), Am))
        assertEquals("A♭ C", names("F3", listOf("b3")))
        assertEquals("F B", names("D3", listOf("#6"), Key(-1, Mode.MINOR)))
        // Natural cancels the key signature: B♭ major, ♮3 over G is B♮
        assertEquals("B D", names("G2", listOf("n"), Key(-2, Mode.MAJOR)))
    }

    private fun score(vararg cols: String) = Score(voices = (0 until 4).map { v ->
        cols.map { c -> c.split(" ")[v].let { t -> Event(Fraction.of(1, 4), Pitch.parse(t.substringBefore('@')), figures = t.substringAfter('@', "").split(",").filter { it.isNotEmpty() }) } }
    })

    @Test fun checkFlagsWrongAndMissingNotes() {
        fun issues(sc: Score) = VoiceLeading.check(sc, setOf(Rule.FIGURES)).issues
        assertTrue(issues(score("A4 C4 E3 C3@6")).isEmpty())
        // G (in S and T) is a wrong note, and A is missing
        assertEquals(3, issues(score("G4 C4 G3 C3@6")).size)
        // The fifth may be left out; the seventh may not
        assertTrue(issues(score("B4 G4 B3 G2@7", "C5 E4 C4 C3")).any { it.message.contains("also asks for F") })
        assertTrue(issues(score("F4 B3 D4 G2@7", "E4 C4 C4 C3")).isEmpty())
    }

    @Test fun figuresSurviveTheFile() {
        val sc = score("A4 C4 E3 C3@6", "B4 F4 D4 G2@6,4,2")
        assertEquals(sc, ScoreCodec.decode(ScoreCodec.encode(sc)))
    }
}
