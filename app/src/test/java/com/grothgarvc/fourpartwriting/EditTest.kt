package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.model.Edit
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.Voice
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
}
