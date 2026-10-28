package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.Notation
import com.nefelion.fourpartwriting.model.Change
import com.nefelion.fourpartwriting.model.Edit
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarMapTest {
    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val G = Key(1, Mode.MAJOR)

    @Test fun pickupIsBarZero() {
        val s = Score(pickup = f(1, 4))
        val b0 = s.barAt(Fraction.ZERO)
        assertEquals(listOf(0, Fraction.ZERO, f(1, 4), f(-3, 4)), listOf(b0.index, b0.start, b0.end, b0.downbeat))
        val b1 = s.barAt(f(1, 4))
        assertEquals(listOf(1, f(1, 4), f(5, 4)), listOf(b1.index, b1.start, b1.end))
        assertEquals(0, s.barNumber(Fraction.ZERO))
        assertEquals(2, s.barNumber(f(5, 4)))
        // The pickup's beat counts from the downbeat: its quarter is beat 4
        assertEquals(f(3, 4), s.inBar(Fraction.ZERO))
    }

    @Test fun timeAndKeyChangeFromABar() {
        val s = Score(changes = mapOf(2 to Change(key = G, time = TimeSig(3, 4))))
        val b2 = s.barAt(f(2, 1))
        assertEquals(listOf(2, f(2, 1), f(11, 4)), listOf(b2.index, b2.start, b2.end))
        val b4 = s.barAt(f(7, 2))
        assertEquals(listOf(4, f(7, 2), f(17, 4)), listOf(b4.index, b4.start, b4.end))
        assertEquals(Key(0, Mode.MAJOR), s.keyAt(f(15, 8)))
        assertEquals(G, s.keyAt(f(2, 1)))
        assertEquals(G, s.keyOfBar(9))
    }

    @Test fun noteIsSplitAtTheEndOfAPickup() {
        val s = Score(pickup = f(1, 4))
        val segs = Notation.split(Fraction.ZERO, f(1, 2), s, false)
        assertEquals(listOf(f(1, 4), f(1, 4)), segs.map { it.length })
        // Off the downbeat of bar 1, a half on beat 2 is split like any other bar
        assertEquals(1, Notation.split(f(1, 4), f(1, 2), s, false).size)
    }

    @Test fun pickupAndChangesRoundTrip() {
        val s = Score(pickup = f(3, 8), time = TimeSig(6, 8), changes = mapOf(3 to Change(key = G), 5 to Change(time = TimeSig(2, 4))))
        assertEquals(s, ScoreCodec.decode(ScoreCodec.encode(s)))
    }

    @Test fun deletingABarMovesLaterChanges() {
        val notes = chordsScore(KC, "C5:1 E4:1 G3:1 C3:1", "C5:1 E4:1 G3:1 C3:1", "C5:1 E4:1 G3:1 C3:1", "C5:1 E4:1 G3:1 C3:1")
        val s = notes.copy(changes = mapOf(3 to Change(key = G)))
        assertEquals(mapOf(2 to Change(key = G)), Edit.deleteMeasure(s, 1).changes)
        // The bar of a change goes: the change moves to the bar that takes its place
        assertEquals(mapOf(3 to Change(key = G)), Edit.deleteMeasure(s, 3).changes)
        // Deleting the first bar: a change at bar 1 becomes the score's own key
        val first = Edit.deleteMeasure(notes.copy(changes = mapOf(1 to Change(key = G))), 0)
        assertEquals(G, first.key)
        assertEquals(emptyMap<Int, Change>(), first.changes)
        assertNull(Edit.deleteMeasure(notes.copy(pickup = f(1, 4)), 0).pickup)
    }

    @Test fun modulationIsReadInTheNewKeyWithAPivot() {
        val s = chordsScore(KC, "E5 G4 C4 C3", "F5 A4 C4 F3", "D5 G4 B3 G2", "E5 G4 C4 C3", "D5 A4 F#4 D3", "D5 G4 B3 G2", "D5 G4 B3 G2", "D5 G4 B3 G2")
            .copy(changes = mapOf(1 to Change(key = G)))
        val r = s.analysis().romans()
        assertEquals("I = IV", r[3])
        assertEquals("V", r[4])
        assertEquals("I", r[5])
    }
}
