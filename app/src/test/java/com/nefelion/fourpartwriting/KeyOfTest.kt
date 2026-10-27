package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.PitchClass
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyOfTest {
    private fun of(step: Int, alter: Int, mode: Mode) = Key.of(PitchClass(step, alter), mode)

    @Test fun everyKeyRoundTrips() {
        for (f in -7..7) for (m in Mode.entries) {
            val k = Key(f, m)
            assertEquals(k, Key.of(k.tonic, m))
        }
    }

    @Test fun lettersAndRespelling() {
        assertEquals(Key(0, Mode.MINOR).copy(fifths = -3), of(0, 0, Mode.MINOR)) // C minor: 3 flats
        assertEquals(Key(-3, Mode.MAJOR), of(1, 1, Mode.MAJOR)) // D♯ major → E♭ major
        assertEquals(Key(7, Mode.MINOR), of(5, 1, Mode.MINOR)) // A♯ minor: 7 sharps
        assertEquals(Key(4, Mode.MAJOR), of(3, -1, Mode.MAJOR)) // F♭ major → E major
        // Every letter with ♭, ♮ or ♯ gives a key in both modes
        for (s in 0..6) for (a in -1..1) for (m in Mode.entries) assertEquals(true, of(s, a, m) != null)
    }

    @Test fun relative() {
        assertEquals(Key(0, Mode.MINOR), Key(0, Mode.MAJOR).relative)
    }
}
