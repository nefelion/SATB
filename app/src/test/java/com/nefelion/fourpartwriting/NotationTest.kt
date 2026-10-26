package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.Notation
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Test

class NotationTest {
    private fun f(n: Long, d: Long) = Fraction.of(n, d)

    /** Written values as "base[.]" strings, e.g. "4 8" = quarter tied to eighth. */
    private fun written(onset: Fraction, len: Fraction, t: TimeSig, rest: Boolean = false) =
        Notation.split(onset, len, t, rest).joinToString(" ") { s ->
            if (s.fullBar) "bar" else s.dur.base.toString() + ".".repeat(s.dur.dots)
        }

    private val c44 = TimeSig(4, 4)

    @Test fun simpleValuesStayWhole() {
        assertEquals("4", written(f(0, 1), f(1, 4), c44))
        assertEquals("2.", written(f(0, 1), f(3, 4), c44))
        assertEquals("1", written(f(0, 1), f(1, 1), c44))
        assertEquals("4.", written(f(0, 1), f(3, 8), c44)) // dotted quarter on beat 1
    }

    @Test fun halfOnBeatTwoIsNotSplit() {
        assertEquals("2", written(f(1, 4), f(1, 2), c44)) // quarter–half–quarter syncopation
        assertEquals("2.", written(f(1, 4), f(3, 4), c44))
        assertEquals("2", written(f(1, 4), f(1, 2), TimeSig(3, 4)))
    }

    @Test fun offBeatNotesShowTheBeat() {
        assertEquals("8 4 8", written(f(1, 8), f(1, 2), c44)) // half on "and" of 1
        assertEquals("4 8", written(f(1, 4), f(3, 8), c44))   // dotted quarter on beat 2 shows beat 3
        assertEquals("8 8", written(f(2, 8), f(2, 8), TimeSig(6, 8))) // quarter on 3rd eighth of 6/8
        assertEquals("4", written(f(1, 8), f(2, 8), TimeSig(6, 8)))   // quarter on 2nd eighth is fine
    }

    @Test fun oddLengths() {
        assertEquals("2 8", written(f(0, 1), f(5, 8), c44))
        assertEquals("4 16", written(f(0, 1), f(5, 16), c44))
    }

    @Test fun barlinesAlwaysSplit() {
        assertEquals("2 2", written(f(1, 2), f(1, 1), c44))
        assertEquals("4 1", written(f(3, 4), f(5, 4), c44))
        assertEquals(listOf(f(3, 4), f(1, 1)), Notation.split(f(3, 4), f(5, 4), c44, false).map { it.onset })
    }

    @Test fun restsShowBeatsAndHalfBar() {
        assertEquals("bar", written(f(0, 1), f(1, 1), c44, rest = true))
        assertEquals("bar", written(f(0, 1), f(3, 4), TimeSig(3, 4), rest = true))
        assertEquals("2", written(f(0, 1), f(1, 2), c44, rest = true))
        assertEquals("4 4", written(f(1, 4), f(1, 2), c44, rest = true))
        assertEquals("8 16", written(f(0, 1), f(3, 16), c44, rest = true))
        assertEquals("4.", written(f(0, 1), f(3, 8), TimeSig(6, 8), rest = true))
    }

    @Test fun cutTime() {
        val cut = TimeSig(2, 2, TimeSig.Symbol.CUT)
        assertEquals("1", written(f(0, 1), f(1, 1), cut))
        assertEquals("4 4", written(f(1, 4), f(1, 2), cut)) // off the half-note beat: split at beat 2
    }

    /** Like [written], triplet values marked "t" (e.g. "t8" = triplet eighth). */
    private fun w3(onset: Fraction, len: Fraction, t: TimeSig = c44, rest: Boolean = false) =
        Notation.split(onset, len, t, rest).joinToString(" ") { s ->
            (if (s.tuplet) "t" else "") + s.dur.base + ".".repeat(s.dur.dots)
        }

    @Test fun tripletEighths() {
        assertEquals("t8", w3(f(0, 1), f(1, 12)))
        assertEquals("t8", w3(f(1, 12), f(1, 12)))
        assertEquals("t8", w3(f(1, 6), f(1, 12)))
        assertEquals("t4", w3(f(0, 1), f(1, 6)))      // quarter + eighth triplet
        assertEquals("t8", w3(f(1, 6), f(1, 12), rest = true))
        assertEquals("t8", w3(f(5, 4) + f(1, 12), f(1, 12)))  // second bar
    }

    @Test fun tripletTiedAcrossTheBeat() {
        // last triplet eighth of beat 1 tied into a quarter on beat 2
        assertEquals("t8 4", w3(f(1, 6), f(1, 12) + f(1, 4)))
        // a quarter starting on beat 1 tied to the first triplet eighth of beat 2
        assertEquals("4 t8", w3(f(0, 1), f(1, 4) + f(1, 12)))
        // the sounding lengths add up
        val segs = Notation.split(f(1, 6), f(1, 2), c44, false)
        assertEquals(f(1, 2), segs.fold(Fraction.ZERO) { a, s -> a + s.length })
        assertEquals(segs.zipWithNext().all { (a, b) -> a.onset + a.length == b.onset }, true)
    }

    @Test fun quarterTripletsInCutTime() {
        assertEquals("t4", w3(f(0, 1), f(1, 6), TimeSig(2, 2)))
        assertEquals("t4", w3(f(1, 6), f(1, 6), TimeSig(2, 2)))
        assertEquals("t4", w3(f(1, 3), f(1, 6), TimeSig(2, 2)))
    }

    @Test fun sixteenthTriplets() {
        assertEquals("t16", w3(f(0, 1), f(1, 24)))
        assertEquals("t16", w3(f(1, 24), f(1, 24)))
        assertEquals("t16", w3(f(1, 12), f(1, 24)))
        assertEquals("8", w3(f(1, 8), f(1, 8)))  // plain eighth after them
    }

    @Test fun plainValuesAreNotTriplets() {
        assertEquals("4", w3(f(0, 1), f(1, 4)))
        assertEquals("2", w3(f(1, 4), f(1, 2)))
    }
}
