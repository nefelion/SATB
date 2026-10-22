package com.grothgarvc.fourpartwriting.engrave

import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.TimeSig

/** One written note/rest value. Note segments tie to the next segment of the same event. */
data class Seg(val onset: Fraction, val length: Fraction, val dur: Duration, val fullBar: Boolean = false)

/**
 * Splits a sounding length into written values following common-practice rules:
 * always at barlines; never hiding the half-bar of a 4-beat bar (except an on-beat note that
 * also ends on a beat, e.g. quarter–half–quarter); off-beat notes never cross a beat;
 * rests are aligned to their own value and never span the half-bar.
 */
object Notation {
    fun representable(len: Fraction): Duration? {
        for (base in intArrayOf(1, 2, 4, 8, 16, 32, 64)) for (dots in 0..2) {
            val d = Duration(base, dots)
            if (d.value == len) return d
        }
        return null
    }

    private fun multiple(a: Fraction, g: Fraction) = (a / g).den == 1L

    private class Ctx(val time: TimeSig) {
        val bar = time.measure
        val beat = time.beatLength
        val half: Fraction? = if (bar / beat == Fraction.of(4, 1)) bar / Fraction.of(2, 1) else null
        fun crossesHalf(p: Fraction, e: Fraction) = half != null && p < half && e > half
        fun sub(g: Fraction) = if (g == beat && time.isCompound) g / Fraction.of(3, 1) else g / Fraction.of(2, 1)
    }

    fun split(onset: Fraction, length: Fraction, time: TimeSig, isRest: Boolean): List<Seg> {
        val out = mutableListOf<Seg>()
        val ml = time.measure
        var t = onset
        val end = onset + length
        while (t < end) {
            val bar = (t / ml).let { it.num / it.den }
            val barStart = ml * Fraction.of(bar, 1)
            val pieceEnd = minOf(end, barStart + ml)
            val ctx = Ctx(time)
            val p = t - barStart; val e = pieceEnd - barStart
            if (isRest && p == Fraction.ZERO && e == ml)
                out += Seg(t, ml, representable(ml) ?: Duration(1), fullBar = true)
            else for ((s, d) in if (isRest) rest(p, e, ctx.beat, ctx) else note(p, e, ctx.beat, ctx, true))
                out += Seg(barStart + s, d.value, d)
            t = pieceEnd
        }
        return out
    }

    private fun tooSmall(g: Fraction) = g < Fraction.of(1, 256)

    /** Positions are relative to the bar start. Returns (start, value) pairs. */
    private fun note(p: Fraction, e: Fraction, g: Fraction, c: Ctx, top: Boolean): List<Pair<Fraction, Duration>> {
        if (p >= e) return emptyList()
        if (tooSmall(g)) return greedy(p, e)
        if (multiple(p, g)) {
            val d = representable(e - p)
            if (d != null && (!c.crossesHalf(p, e) || (top && multiple(e, g)))) return listOf(p to d)
            if (c.crossesHalf(p, e)) return note(p, c.half!!, g, c, top) + note(c.half, e, g, c, top)
            var k = ((e - p) / g).let { it.num / it.den }
            while (k > 0 && representable(g * Fraction.of(k, 1)) == null) k--
            if (k > 0) {
                val len = g * Fraction.of(k, 1)
                return listOf(p to representable(len)!!) + note(p + len, e, g, c, top)
            }
            return note(p, e, c.sub(g), c, false)
        }
        val nb = g * Fraction.of(((p / g).let { it.num / it.den }) + 1, 1)
        if (e > nb) return note(p, nb, g, c, top) + note(nb, e, g, c, top)
        return note(p, e, c.sub(g), c, false)
    }

    private fun rest(p: Fraction, e: Fraction, g: Fraction, c: Ctx): List<Pair<Fraction, Duration>> {
        if (p >= e) return emptyList()
        if (tooSmall(g)) return greedy(p, e)
        if (multiple(p, g)) {
            val d = representable(e - p)
            val undotted = d?.let { Fraction.of(1, it.base.toLong()) }
            val ok = d != null && !c.crossesHalf(p, e) &&
                (d.value <= g || multiple(p, undotted!!)) &&
                (d.dots == 0 || (c.time.isCompound && multiple(d.value, c.beat)))
            if (ok) return listOf(p to d!!)
            if (c.crossesHalf(p, e)) return rest(p, c.half!!, g, c) + rest(c.half, e, g, c)
            if (e - p >= g) representable(g)?.let { return listOf(p to it) + rest(p + g, e, g, c) }
            return rest(p, e, c.sub(g), c)
        }
        val nb = g * Fraction.of(((p / g).let { it.num / it.den }) + 1, 1)
        if (e > nb) return rest(p, nb, g, c) + rest(nb, e, g, c)
        return rest(p, e, c.sub(g), c)
    }

    /** Fallback for lengths off the 1/256 grid: largest values first. */
    private fun greedy(p: Fraction, e: Fraction): List<Pair<Fraction, Duration>> {
        val out = mutableListOf<Pair<Fraction, Duration>>()
        var t = p
        while (t < e) {
            val d = listOf(1, 2, 4, 8, 16, 32, 64).map { Duration(it) }.firstOrNull { it.value <= e - t } ?: Duration(64)
            out += t to d
            t += d.value
        }
        return out
    }
}
