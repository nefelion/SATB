package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.model.Arrangement
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Mode
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.ScoreCodec
import com.grothgarvc.fourpartwriting.model.TimeSig
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
    }

    @Test fun garbageIsRejected() {
        assertNull(ScoreCodec.decode(""))
        assertNull(ScoreCodec.decode("v1\nkey x"))
        assertNull(ScoreCodec.decode(ScoreCodec.encode(EXAMPLE).replace("CLOSED", "NOPE")))
    }
}
