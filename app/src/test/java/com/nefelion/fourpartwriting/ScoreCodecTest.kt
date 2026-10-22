package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Arrangement
import com.nefelion.fourpartwriting.model.Clef
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreCodecTest {
    @Test fun roundTrip() {
        assertEquals(EXAMPLE, ScoreCodec.decode(ScoreCodec.encode(EXAMPLE)))
        val odd = Score(
            key = Key(-6, Mode.MINOR), time = TimeSig(6, 8), tempo = 132, arrangement = Arrangement.OPEN,
            clefs = listOf(Clef.SOPRANO, Clef.ALTO, Clef.TENOR, Clef.BASS_8VB),
            voices = listOf(
                listOf(Event(Fraction.of(5, 8), Pitch.parse("Bbb4")), Event(Fraction.of(1, 8), null)),
                listOf(Event(Fraction.of(3, 16), Pitch.parse("Fx4"))),
                emptyList(),
                listOf(Event(Fraction.of(7, 4), Pitch.parse("C#2"))),
            ),
        )
        assertEquals(odd, ScoreCodec.decode(ScoreCodec.encode(odd)))
        val kb = EXAMPLE.withArrangement(Arrangement.KEYBOARD)
        assertEquals(kb, ScoreCodec.decode(ScoreCodec.encode(kb)))
        // Older files name a preset
        assertEquals(EXAMPLE, ScoreCodec.decode(ScoreCodec.encode(EXAMPLE).replace("arr 0 0 1 1", "arr CLOSED")))
    }

    @Test fun historyRoundTrip() {
        val a = EXAMPLE; val b = EXAMPLE.copy(tempo = 120); val c = EXAMPLE.withArrangement(Arrangement.OPEN)
        assertEquals(listOf(a, b) to listOf(c), ScoreCodec.decodeHistory(ScoreCodec.encodeHistory(listOf(a, b), listOf(c))))
        assertEquals(emptyList<Score>() to emptyList<Score>(), ScoreCodec.decodeHistory(""))
    }

    @Test fun arrangements() {
        assertEquals(8, Arrangement.entries.distinct().size)
        val kb = Arrangement.KEYBOARD
        assertEquals("SAT/B", kb.label)
        assertEquals(listOf(1, -1, -1, 0), kb.stems)
        assertEquals(listOf(Clef.TREBLE, Clef.BASS), kb.defaultClefs)
        assertEquals(listOf(Clef.TREBLE, Clef.TREBLE_8VB, Clef.BASS), Arrangement.THREE.defaultClefs)
        assertEquals(listOf(1, 1, -1, -1), Arrangement.ONE.stems)
    }

    @Test fun garbageIsRejected() {
        assertNull(ScoreCodec.decode(""))
        assertNull(ScoreCodec.decode("v1\nkey x"))
        assertNull(ScoreCodec.decode(ScoreCodec.encode(EXAMPLE).replace("arr 0 0 1 1", "arr 0 2 1 1")))
    }
}
