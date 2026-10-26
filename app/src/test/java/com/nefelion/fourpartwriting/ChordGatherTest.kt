package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.audio.ChordGather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChordGatherTest {
    @Test fun chordIsDoneWhenAllKeysAreReleased() {
        val g = ChordGather()
        assertNull(g.key(48, true))
        assertNull(g.key(64, true))
        assertNull(g.key(48, false)) // bass let go early, upper keys still held
        assertNull(g.key(67, true))
        assertNull(g.key(72, true))
        assertNull(g.key(64, false))
        assertNull(g.key(67, false))
        assertEquals(listOf(48, 64, 67, 72), g.key(72, false))
    }

    @Test fun singleKeysAndStrayReleases() {
        val g = ChordGather()
        assertNull(g.key(60, false))
        assertNull(g.key(60, true))
        assertEquals(listOf(60), g.key(60, false))
        assertNull(g.key(62, true))
        assertNull(g.key(62, true)) // repeated press
        assertEquals(listOf(62), g.key(62, false))
    }
}
