package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.theory.Overtones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the overtone lesson shows, checked against the model it draws and plays. */
class OvertonesTest {

    @Test fun seriesOfC4() {
        val c4 = Pitch.parse("C4").midi
        assertEquals(listOf("C4", "C5", "G5", "C6", "E6", "G6", "B♭6", "C7"), (1..8).map { Overtones.name(c4, it) })
        assertEquals(listOf(7, 11, 13, 14), (1..16).filter { Overtones.outOfTune(it) })
    }

    /** The measured A4 says what the captions say. */
    @Test fun measuredPiano() {
        val p = Overtones.A4_PIANO
        assertEquals((1..p.size).toList(), p.map { it.n })
        // A stiff string runs sharp up high, high partials fade faster, and the 9th sits near the hammer's still point
        assertTrue(p[9].hz > 10 * p[0].hz * 1.005)
        assertTrue(p[9].level(0.5) / p[9].amp < p[0].level(0.5) / p[0].amp)
        assertTrue(p[8].amp < p[7].amp && p[8].amp < p[9].amp)
        // Partial 1 is the strongest: the drawings put it at the top
        assertTrue(p.drop(1).all { it.amp < p[0].amp })
    }
}
