package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.engrave.BeamMark
import com.grothgarvc.fourpartwriting.engrave.Engraver
import com.grothgarvc.fourpartwriting.engrave.GlyphMark
import com.grothgarvc.fourpartwriting.engrave.LineMark
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.engrave.Smufl
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Severity
import com.grothgarvc.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun notes(vararg p: String, base: Int = 8) = p.map { Event(Duration(base), Pitch.parse(it)) }

    @Test fun exampleHasNoErrors() {
        val a = VoiceLeading.check(EXAMPLE)
        assertEquals(emptyList<String>(), a.issues.filter { it.severity == Severity.ERROR }.map { it.message })
        assertEquals(listOf("I", "IV", "V", "I", "vi", "ii⁶", "Cad⁶₄", "V⁷", "I"), a.chords.map { it.roman })
    }

    @Test fun stemDirectionsFollowVoices() {
        val s = Score(voices = listOf(notes("C5", base = 4), notes("A4", base = 4), notes("E3", base = 4), notes("C3", base = 4)))
        val l = Engraver.layout(s, 60f)
        fun stem(v: Voice) = l.marks.filterIsInstance<LineMark>().first { it.ref == NoteRef(v, 0) }
        assertTrue("soprano stem goes up", stem(Voice.SOPRANO).y2 < stem(Voice.SOPRANO).y1)
        assertTrue("alto stem goes down", stem(Voice.ALTO).y2 > stem(Voice.ALTO).y1)
        assertTrue("tenor stem goes up", stem(Voice.TENOR).y2 < stem(Voice.TENOR).y1)
        assertTrue("bass stem goes down", stem(Voice.BASS).y2 > stem(Voice.BASS).y1)
    }

    @Test fun accidentalStateWithinBar() {
        // F♯ F♯ F♮ F♯ in C major: sharp, nothing, natural, sharp
        val s = Score(voices = listOf(notes("F#4", "F#4", "F4", "F#4", base = 4), emptyList(), emptyList(), emptyList()))
        val acc = Engraver.layout(s, 80f).marks.filterIsInstance<GlyphMark>()
            .filter { it.ref?.voice == Voice.SOPRANO && it.c in charArrayOf(Smufl.SHARP, Smufl.NATURAL, Smufl.FLAT) }
            .map { it.ref!!.index to it.c }
        assertEquals(listOf(0 to Smufl.SHARP, 2 to Smufl.NATURAL, 3 to Smufl.SHARP), acc)
    }

    @Test fun beamGroupingFollowsMeter() {
        val eighths = notes("C5", "D5", "E5", "F5", "G5", "A5")
        fun beams(t: TimeSig) = Engraver.layout(Score(time = t, voices = listOf(eighths, emptyList(), emptyList(), emptyList())), 80f)
            .marks.filterIsInstance<BeamMark>().size
        assertEquals(3, beams(TimeSig(3, 4))) // three beamed pairs
        assertEquals(2, beams(TimeSig(6, 8))) // two groups of three
    }

    @Test fun barsDoNotOverflow() {
        assertTrue(fitsBars(notes("C4", "D4", base = 2), TimeSig(4, 4)))
        assertTrue(!fitsBars(notes("C4", base = 4) + notes("D4", base = 1), TimeSig(4, 4)))
    }
}
