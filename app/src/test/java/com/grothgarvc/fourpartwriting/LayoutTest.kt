package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.engrave.BeamMark
import com.grothgarvc.fourpartwriting.engrave.Engraver
import com.grothgarvc.fourpartwriting.engrave.GlyphMark
import com.grothgarvc.fourpartwriting.engrave.LineMark
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.engrave.Smufl
import com.grothgarvc.fourpartwriting.engrave.TieMark
import com.grothgarvc.fourpartwriting.model.Arrangement
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Severity
import com.grothgarvc.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun notes(vararg p: String, base: Int = 8) = p.map { Event(Duration(base), Pitch.parse(it)) }
    private fun only(v: List<Event>) = listOf(v, emptyList(), emptyList(), emptyList())

    @Test fun exampleHasNoErrors() {
        val a = VoiceLeading.check(EXAMPLE)
        assertEquals(emptyList<String>(), a.issues.filter { it.severity == Severity.ERROR }.map { it.message })
        assertEquals(listOf("I", "IV", "V", "I", "vi", "ii⁶", "Cad⁶₄", "V⁷", "I"), a.chords.map { it.roman })
    }

    private fun stem(l: com.grothgarvc.fourpartwriting.engrave.ScoreLayout, v: Voice) =
        l.marks.filterIsInstance<LineMark>().first { it.ref == NoteRef(v, 0) }
    private fun up(m: LineMark) = m.y2 < m.y1

    @Test fun stemDirectionsFollowArrangement() {
        val voices = listOf(notes("C5", base = 4), notes("A4", base = 4), notes("E3", base = 4), notes("C3", base = 4))
        val closed = Engraver.layout(Score(voices = voices))
        assertTrue(up(stem(closed, Voice.SOPRANO))); assertTrue(!up(stem(closed, Voice.ALTO)))
        assertTrue(up(stem(closed, Voice.TENOR))); assertTrue(!up(stem(closed, Voice.BASS)))
        // Open score: each voice alone, stem by position (C5 is above the middle line: down)
        val open = Engraver.layout(Score(voices = voices).withArrangement(Arrangement.OPEN))
        assertTrue(!up(stem(open, Voice.SOPRANO)))
        assertEquals(4, open.staffTop.size)
    }

    @Test fun oneStaffChordsShareAStem() {
        val voices = listOf(notes("E5", base = 4), notes("C5", base = 4), notes("G4", base = 4), notes("C4", base = 4))
        val l = Engraver.layout(Score(voices = voices).withArrangement(Arrangement.ONE))
        // S+A form one up-stem chord, T+B one down-stem chord: 2 stems
        assertEquals(2, l.marks.filterIsInstance<LineMark>().count { it.ref != null && it.x1 == it.x2 })
    }

    @Test fun accidentalStateWithinBar() {
        // F♯ F♯ F♮ F♯ in C major: sharp, nothing, natural, sharp
        val s = Score(voices = only(notes("F#4", "F#4", "F4", "F#4", base = 4)))
        val acc = Engraver.layout(s).marks.filterIsInstance<GlyphMark>()
            .filter { it.ref?.voice == Voice.SOPRANO && it.c in charArrayOf(Smufl.SHARP, Smufl.NATURAL, Smufl.FLAT) }
            .map { it.ref!!.index to it.c }
        assertEquals(listOf(0 to Smufl.SHARP, 2 to Smufl.NATURAL, 3 to Smufl.SHARP), acc)
    }

    @Test fun beamGroupingFollowsMeter() {
        val eighths = notes("C5", "D5", "E5", "F5", "G5", "A5")
        fun beams(t: TimeSig) = Engraver.layout(Score(time = t, voices = only(eighths))).marks.filterIsInstance<BeamMark>().size
        assertEquals(3, beams(TimeSig(3, 4))) // three beamed pairs
        assertEquals(2, beams(TimeSig(6, 8))) // two groups of three
    }

    @Test fun noteAcrossBarlineIsTied() {
        val s = Score(voices = only(listOf(Event(Duration(2, 1), Pitch.parse("C5")), Event(Duration(2), Pitch.parse("D5")))))
        val l = Engraver.layout(s)
        assertEquals(1, l.marks.filterIsInstance<TieMark>().size)
        // both written halves of the tied D5 can be tapped and select the same event
        assertEquals(2, l.heads.count { it.ref == NoteRef(Voice.SOPRANO, 1) })
    }

    @Test fun tapSelectsNearbyNote() {
        val l = Engraver.layout(EXAMPLE)
        val h = l.heads.first { it.ref == NoteRef(Voice.SOPRANO, 1) }
        val hit = l.hitTest(h.x + 0.3f, h.y + 0.2f)
        assertEquals(NoteRef(Voice.SOPRANO, 1), hit.exact)
        assertNotNull(hit.near)
        assertTrue(!hit.afterLast)
        // far right of the music: append
        assertTrue(l.hitTest(l.width - 2f, h.y).afterLast)
        assertEquals(Fraction.of(3, 1), l.hitTest(l.width - 2f, h.y).time)
    }
}
