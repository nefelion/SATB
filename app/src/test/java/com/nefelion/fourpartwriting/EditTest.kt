package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Edit
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import org.junit.Assert.assertEquals
import org.junit.Test

class EditTest {
    private val S = Voice.SOPRANO
    private val q = Fraction.of(1, 4)
    private val h = Fraction.of(1, 2)
    private val e8 = Fraction.of(1, 8)

    private fun s(vararg ev: String): Score = Score().withVoice(S, ev.map { tok ->
        val (p, d) = tok.split(":")
        Event(Fraction.of(1, d.toLong()), if (p == "r") null else Pitch.parse(p))
    })

    /** Voice as "C4:1/4 r:1/2 ..." for compact asserts. */
    private fun str(sc: Score) = sc.voice(S).joinToString(" ") { e ->
        (e.pitch?.name ?: "r") + ":" + e.length.num + "/" + e.length.den
    }

    @Test fun appendCrossingTheBarIsOneEvent() {
        val sc = Edit.append(s("C4:2", "D4:4"), S, h, Pitch.parse("E4"))
        assertEquals("C4:1/2 D4:1/4 E4:1/2", str(sc)) // written later as tied quarters
    }

    @Test fun appendLaterFillsTheGapWithARest() {
        // 3 quarters in 4/4, then a quarter on beat 2 of bar 2: rest from beat 4 of bar 1
        val sc = Edit.append(s("C4:4", "D4:4", "E4:4"), S, q, Pitch.parse("F4"), Fraction.of(5, 4))
        assertEquals("C4:1/4 D4:1/4 E4:1/4 r:1/2 F4:1/4", str(sc))
        // a trailing rest merges with the gap; "at" before the end is ignored
        assertEquals("C4:1/4 r:3/4 F4:1/4", str(Edit.append(s("C4:4", "r:4"), S, q, Pitch.parse("F4"), Fraction.of(1, 1))))
        assertEquals("C4:1/4 F4:1/4", str(Edit.append(s("C4:4"), S, q, Pitch.parse("F4"), Fraction.ZERO)))
    }

    /** Chords "S A T B" of quarter notes. */
    private fun chords(vararg c: String) = Score(voices = Voice.entries.map { v ->
        c.map { Event(q, it.split(" ")[v.ordinal].let { p -> if (p == "r") null else Pitch.parse(p) }) }
    })

    @Test fun misorderedChordsAreFoundAndSorted() {
        // chord 2 was entered bottom-up; chord 3 is a unison (fine)
        val sc = chords("E4 C4 G3 C3", "C3 G3 C4 E4", "C4 C4 G3 C3")
        assertEquals(listOf(q), Edit.misorderedChords(sc))
        val fixed = Edit.sortChords(sc, listOf(q))
        assertEquals(chords("E4 C4 G3 C3", "E4 C4 G3 C3", "C4 C4 G3 C3"), fixed)
        assertEquals(emptyList<Fraction>(), Edit.misorderedChords(fixed))
        // a rest, or different note lengths, is not a block chord: never flagged
        assertEquals(emptyList<Fraction>(), Edit.misorderedChords(chords("C3 G3 r E4")))
        val uneven = chords("C3 G3 C4 E4").let { Edit.setLength(it, Voice.BASS, 0, h) }
        assertEquals(emptyList<Fraction>(), Edit.misorderedChords(uneven))
    }

    @Test fun deleteMeasureClosesTheGap() {
        // bar 1: C D E F, bar 2: G (whole), bar 3: A B (halves)
        val sc = s("C4:4", "D4:4", "E4:4", "F4:4", "G4:1", "A4:2", "B4:2")
        assertEquals("C4:1/4 D4:1/4 E4:1/4 F4:1/4 A4:1/2 B4:1/2", str(Edit.deleteMeasure(sc, 1)))
        // C4 held from bar 1 into bar 2: deleting bar 1 keeps its part in bar 2
        val held = Score().withVoice(S, listOf(Event(Fraction.of(3, 2), Pitch.parse("C4")), Event(q, Pitch.parse("D4"))))
        assertEquals("C4:1/2 D4:1/4", str(Edit.deleteMeasure(held, 0)))
    }

    @Test fun emptyLastBarsAreDropped() {
        // bars 2 and 3 only have rests
        assertEquals("C4:1/1", str(Edit.dropEmptyLastBars(s("C4:1", "r:1", "r:2", "r:2"))))
        // the rest finishing bar 1 stays; bar 2 goes
        assertEquals("C4:1/2 r:1/2", str(Edit.dropEmptyLastBars(s("C4:2", "r:2", "r:1"))))
    }

    @Test fun notesFillARest() {
        // half rest + half note: a half note fills the rest; or two quarters, one after another
        val sc = s("r:2", "G4:2")
        assertEquals("C4:1/2 G4:1/2", str(Edit.fillRest(sc, S, Fraction.ZERO, h, Pitch.parse("C4"))!!.first))
        val (one, i) = Edit.fillRest(sc, S, Fraction.ZERO, q, Pitch.parse("C4"))!!
        assertEquals("C4:1/4 r:1/4 G4:1/2" to 0, str(one) to i)
        val (two, j) = Edit.fillRest(one, S, q, q, Pitch.parse("D4"))!!
        assertEquals("C4:1/4 D4:1/4 G4:1/2" to 1, str(two) to j)
        // longer than what is left of the rest: shortened, G4 does not move
        assertEquals("r:1/4 C4:1/4 G4:1/2", str(Edit.fillRest(sc, S, q, h, Pitch.parse("C4"))!!.first))
        // not in a rest
        assertEquals(null, Edit.fillRest(sc, S, h, q, Pitch.parse("C4")))
    }

    @Test fun lengtheningTakesTimeFromFollowingNotes() {
        val sc = s("C4:4", "D4:4", "E4:4", "F4:4")
        assertEquals("C4:1/2 E4:1/4 F4:1/4", str(Edit.setLength(sc, S, 0, h)))
        assertEquals("C4:5/8 E4:1/8 F4:1/4", str(Edit.setLength(sc, S, 0, Fraction.of(5, 8))))
        assertEquals("C4:1/1", str(Edit.setLength(sc, S, 0, Fraction.of(1, 1))))
        // past the end of the voice: just longer
        assertEquals("C4:1/4 D4:1/4 E4:1/4 F4:1/1", str(Edit.setLength(sc, S, 3, Fraction.of(1, 1))))
    }

    @Test fun shorteningLeavesARestSoLaterNotesKeepTheirTime() {
        val sc = s("C4:2", "D4:2")
        assertEquals("C4:1/4 r:1/4 D4:1/2", str(Edit.setLength(sc, S, 0, q)))
        assertEquals("C4:1/2 D4:1/8", str(Edit.setLength(sc, S, 1, e8))) // last note: no trailing rest
    }

    @Test fun deleteRules() {
        val sc = s("C4:4", "D4:4", "E4:4")
        assertEquals("C4:1/4 D4:1/4", str(Edit.delete(sc, S, 2)))       // last: removed
        assertEquals("C4:1/4 r:1/4 E4:1/4", str(Edit.delete(sc, S, 1))) // middle note: rest
        assertEquals("r:1/4 D4:1/4 E4:1/4", str(Edit.delete(sc, S, 0)))
        val withRest = Edit.delete(sc, S, 1)
        assertEquals("C4:1/4 E4:1/4", str(Edit.delete(withRest, S, 1)))  // middle rest: gap closes
    }

    @Test fun adjacentRestsMerge() {
        val sc = s("C4:4", "r:4", "D4:4", "E4:4")
        assertEquals("C4:1/4 r:1/2 E4:1/4", str(Edit.delete(sc, S, 2)))
    }

    @Test fun tie() {
        assertEquals("C4:1/2 E4:1/4", str(Edit.tie(s("C4:4", "C4:4", "E4:4"), S, 0)))
        assertEquals("C4:1/2", str(Edit.tie(s("C4:4", "D4:4"), S, 0))) // takes the first pitch
        assertEquals("C4:1/4", str(Edit.tie(s("C4:4"), S, 0)))          // nothing to tie to
        assertEquals("r:1/4 C4:1/4", str(Edit.tie(s("r:4", "C4:4"), S, 0)))
    }

    @Test fun pitchAndRest() {
        val sc = s("C4:4", "D4:4")
        assertEquals("C4:1/4 G4:1/4", str(Edit.setPitch(sc, S, 1, Pitch.parse("G4"))))
        assertEquals("C4:1/4 r:1/4", str(Edit.setPitch(sc, S, 1, null)))
    }

    @Test fun restPartDeletesOneHeadOfATiedNote() {
        // C4 whole note from beat 3 of bar 1 to beat 3 of bar 2 (two tied halves), then D4
        val sc = s("r:2", "C4:1", "D4:4")
        val bar2 = Fraction.of(1, 1)
        assertEquals("r:1/2 C4:1/2 r:1/2 D4:1/4", str(Edit.restPart(sc, S, 1, bar2, bar2 + h)))
        assertEquals("r:1/1 C4:1/2 D4:1/4", str(Edit.restPart(sc, S, 1, h, bar2)))
        // last event: the tail is cut off, not left as a rest
        assertEquals("r:1/2 C4:1/2", str(Edit.restPart(s("r:2", "C4:1"), S, 1, bar2, bar2 + h)))
    }

    @Test fun untieSplitsTheNote() {
        val sc = s("r:2", "C4:1", "D4:4")
        assertEquals("r:1/2 C4:1/2 C4:1/2 D4:1/4", str(Edit.untie(sc, S, 1, Fraction.of(1, 1))))
    }

    @Test fun followLengthCopiesTheNearestVoiceAboveThatStartsThere() {
        val sc = s("C4:2", "D4:8").withVoice(Voice.BASS, listOf(Event(q, Pitch.parse("C3"))))
        // Alto at 0: soprano (above) wins over bass
        assertEquals(h, Edit.follow(sc, Voice.ALTO, Fraction.ZERO)?.length)
        // Alto at 1/2: the soprano's eighth starts there
        assertEquals(e8, Edit.follow(sc, Voice.ALTO, h)?.length)
        // Soprano at 0: nothing above, so the bass below
        assertEquals(q, Edit.follow(sc, S, Fraction.ZERO)?.length)
        // No event starts at 1/4 in another voice
        assertEquals(null, Edit.follow(sc, Voice.ALTO, q)?.length)
    }

    @Test fun chordVoicesAreTheEarliestEndingTopToBottom() {
        val empty = Score()
        assertEquals(Voice.entries, Edit.chordVoices(empty, 4))
        assertEquals(listOf(S, Voice.ALTO), Edit.chordVoices(empty, 2))
        // Soprano and bass already have a note: a two-note chord goes to alto and tenor
        val sb = empty.withVoice(S, listOf(Event(q, Pitch.parse("C5")))).withVoice(Voice.BASS, listOf(Event(q, Pitch.parse("C3"))))
        assertEquals(listOf(Voice.ALTO, Voice.TENOR), Edit.chordVoices(sb, 2))
        assertEquals(Voice.entries, Edit.chordVoices(sb, 6))
    }
}
