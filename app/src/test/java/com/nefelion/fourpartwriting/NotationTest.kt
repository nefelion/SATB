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

    /**
     * Written values of a voice of notes with these lengths ("r" before a length = rest), one "|" between
     * events; triplet values marked "t" (e.g. "t8" = triplet eighth), ties shown as spaces.
     */
    private fun w3(vararg ev: String, t: TimeSig = c44): String {
        val events = ev.map { tok ->
            val rest = tok.startsWith("r")
            val (n, d) = tok.removePrefix("r").split("/").map { it.toLong() }
            com.nefelion.fourpartwriting.model.Event(f(n, d), if (rest) null else com.nefelion.fourpartwriting.model.Pitch.parse("C4"))
        }
        return Notation.splitVoice(events, t).joinToString(" | ") { segs ->
            segs.joinToString(" ") { s -> (if (s.tuplet) "t" else "") + s.dur.base + ".".repeat(s.dur.dots) }
        }
    }

    @Test fun tripletEighths() {
        assertEquals("t8 | t8 | t8 | 4", w3("1/12", "1/12", "1/12", "1/4"))
        assertEquals("t4 | t8", w3("1/6", "1/12"))           // quarter + eighth
        assertEquals("t8 | t8 | t8", w3("1/12", "r1/12", "1/12"))
        assertEquals("1 | t8 | t8 | t8", w3("1/1", "1/12", "1/12", "1/12")) // second bar
    }

    @Test fun quarterTripletsSpanTwoBeats() {
        // the user-reported bug: the middle one was two tied triplet eighths
        assertEquals("t4 | t4 | t4 | 2", w3("1/6", "1/6", "1/6", "1/2"))
        assertEquals("4 | t4 | t4 | t4 | 4", w3("1/4", "1/6", "1/6", "1/6", "1/4")) // from beat 2
        assertEquals("t4 | t4 | t4", w3("1/6", "1/6", "1/6", t = TimeSig(2, 2)))
    }

    @Test fun halfTripletsSpanTheBar() {
        assertEquals("t2 | t2 | t2", w3("1/3", "1/3", "1/3"))
    }

    @Test fun tripletsAfterAnOffbeatStart() {
        // after a plain eighth: the group runs from the "and" of 1, no ties
        assertEquals("8 | t8 | t8 | t8", w3("1/8", "1/12", "1/12", "1/12"))
    }

    @Test fun unfinishedGroupStaysInItsGroup() {
        // two triplet eighths written so far: the voice ends inside the group
        val segs = Notation.splitVoice(listOf(1, 1).map { com.nefelion.fourpartwriting.model.Event(f(1, 12), null) }, c44)
        assertEquals(f(0, 1), segs[1].single().group?.start)
        assertEquals(listOf(com.nefelion.fourpartwriting.engrave.TupletGroup(f(0, 1), f(1, 4))), Notation.groups(listOf(f(1, 12), f(1, 12)), c44))
    }

    @Test fun tripletTiedAcrossTheBeat() {
        // a quarter tied to the first triplet eighth of beat 2
        assertEquals("4 t8 | t8 | t8", w3("1/3", "1/12", "1/12"))
        // the sounding lengths add up
        val segs = Notation.splitVoice(listOf(com.nefelion.fourpartwriting.model.Event(f(1, 6), null),
            com.nefelion.fourpartwriting.model.Event(f(1, 2), com.nefelion.fourpartwriting.model.Pitch.parse("C4"))), c44)[1]
        assertEquals(f(1, 2), segs.fold(Fraction.ZERO) { a, s -> a + s.length })
        assertEquals(true, segs.zipWithNext().all { (a, b) -> a.onset + a.length == b.onset })
    }

    @Test fun sixteenthTriplets() {
        assertEquals("t16 | t16 | t16 | 8", w3("1/24", "1/24", "1/24", "1/8"))
    }

    @Test fun plainValuesAreNotTriplets() {
        assertEquals("4 | 2", w3("1/4", "1/2"))
    }

    /** [k] notes of [n]:[m] tuplet with written value 1/[unit], then a plain quarter: written values and group labels. */
    private fun tagged(k: Int, n: Int, m: Int, unit: Long): Pair<String, List<String>> {
        val tp = com.nefelion.fourpartwriting.model.Tuplet(n, m, f(1, unit))
        val c4 = com.nefelion.fourpartwriting.model.Pitch.parse("C4")
        val events = List(k) { com.nefelion.fourpartwriting.model.Event(f(m.toLong(), unit * n), c4, tp) } +
            com.nefelion.fourpartwriting.model.Event(f(1, 4), c4)
        val segs = Notation.splitVoice(events, c44)
        return segs.joinToString(" | ") { s -> s.joinToString(" ") { (if (it.tuplet) "t" else "") + it.dur.base } } to
            segs.flatten().mapNotNull { it.group?.label }.distinct()
    }

    @Test fun anyTupletIsWrittenAsPicked() {
        assertEquals("t16 | t16 | t16 | t16 | t16 | 4" to listOf("5"), tagged(5, 5, 4, 16))
        // Same sounding length as 5:4 sixteenths, but kept as eighths
        assertEquals("t8 | t8 | t8 | t8 | t8 | 4" to listOf("5:2"), tagged(5, 5, 2, 8))
        // A group of 5 eighths' time, not a plain value
        assertEquals("t8 | t8 | t8 | 8 8" to listOf("3:5"), tagged(3, 3, 5, 8)) // the quarter after it crosses beat 4
    }

    @Test fun tupletWithAPlainTimeInsideIsOneGroup() {
        // 6:4 sixteenths: the 4th note starts on the plain eighth, still one group
        val (w, labels) = tagged(6, 6, 4, 16)
        assertEquals("t16 | t16 | t16 | t16 | t16 | t16 | 4", w)
        assertEquals(listOf("6"), labels)
        assertEquals(1, Notation.tupletGroups(List(6) { com.nefelion.fourpartwriting.model.Event(f(1, 24), null,
            com.nefelion.fourpartwriting.model.Tuplet(6, 4, f(1, 16))) }, c44).size)
    }
}
