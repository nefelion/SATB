package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.export.Midi
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiTest {
    @Test
    fun soprano_quarter_then_half() {
        // C5 quarter, quarter rest, D5 half
        val s = Score(voices = listOf(listOf(Event(Fraction.of(1, 4), Pitch(0, 0, 5)), Event(Fraction.of(1, 4), null), Event(Fraction.of(1, 2), Pitch(1, 0, 5)))) + List(3) { emptyList() })
        val b = Midi.export(s)
        assertEquals("MThd", String(b, 0, 4))
        assertEquals(5, b[11].toInt()) // tempo track + 4 voices
        // First voice track: after "MTrk" + length + the name event (delta 0, FF 03 07 "Soprano")
        val t = String(b, Charsets.ISO_8859_1).indexOf("MTrk", 14 + 8 + 4)
        val ev = b.copyOfRange(t + 8 + 4 + 7, t + 8 + 4 + 7 + 18).map { it.toInt() and 0xFF }
        // on C5 at 0; off after 480 ticks (83 60); on D5 after another 480; off after 960 (87 40)
        assertEquals(listOf(0, 0x90, 72, 90, 0x83, 0x60, 0x80, 72, 0, 0x83, 0x60, 0x90, 74, 90, 0x87, 0x40, 0x80, 74), ev)
    }
}
