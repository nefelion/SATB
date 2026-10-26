package com.nefelion.fourpartwriting.engrave

import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.TimeSig

/**
 * One written note/rest value. Note segments tie to the next segment of the same event.
 * [group]: its tuplet group ([dur] is n/m of the sounding [length]); null = plain.
 */
data class Seg(val onset: Fraction, val length: Fraction, val dur: Duration, val fullBar: Boolean = false, val group: TupletGroup? = null) {
    val tuplet get() = group != null
}

/** A tuplet group: [n] in the time of [m], from [start] until [end]. */
data class TupletGroup(val start: Fraction, val end: Fraction, val n: Int = 3, val m: Int = 2) {
    val label get() = tupletLabel(n, m)
}

/** Number shown over a tuplet: "5" when [m] is the usual power of two below [n] (3:2, 5:4, 7:4), else "5:2". */
fun tupletLabel(n: Int, m: Int) = if (n > 1 && m == Integer.highestOneBit(n - 1)) "$n" else "$n:$m"

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

    /** On the plain (power-of-two) grid. */
    fun binary(a: Fraction) = a.den and (a.den - 1) == 0L

    private val SIZES = (0..6).map { Fraction.of(1, 64L shr it) } // 1/64 .. 1 whole

    /**
     * Triplet groups of a voice, from the times its events start and end. Times off the plain grid that
     * follow each other (no plain time between them) are one run, and belong to one group: the longest
     * plain value that holds them all on its thirds and fits between the plain times around the run, within
     * the bar. It starts on a multiple of its own length (a long note before it is cut there), else at the
     * plain time just before the run (e.g. triplets after an eighth).
     */
    fun groups(lengths: List<Fraction>, time: TimeSig): List<TupletGroup> {
        val ml = time.measure
        val out = mutableListOf<TupletGroup>()
        var t = Fraction.ZERO
        var b0 = Fraction.ZERO
        val run = mutableListOf<Fraction>()
        fun barStart(x: Fraction) = ml * Fraction.of((x / ml).let { it.num / it.den }, 1)
        /** [b1]: the plain time ending the run (null: the voice ends inside it). */
        fun flush(b1: Fraction?) {
            if (run.isEmpty()) return
            val bs = barStart(b0)
            var from = b0
            var rest: List<Fraction> = run.toList()
            run.clear()
            // Usually one group holds the whole run; if not, the longest leading part that fits takes one, and so on
            while (rest.isNotEmpty()) {
                val found = (rest.size downTo 1).firstNotNullOfOrNull { n ->
                    val pts = rest.take(n)
                    val limit = minOf(rest.getOrNull(n) ?: b1 ?: (bs + ml), b1 ?: (bs + ml), bs + ml)
                    fun fits(start: Fraction, g: Fraction) = start >= from && start + g <= limit &&
                        pts.all { it < start + g && ((it - start) / (g / Fraction.of(3, 1))).den == 1L }
                    SIZES.reversed().firstNotNullOfOrNull { g ->
                        val aligned = bs + g * Fraction.of(((pts.first() - bs) / g).let { it.num / it.den }, 1)
                        listOf(aligned, from).firstOrNull { fits(it, g) }?.let { TupletGroup(it, it + g) to n }
                    }
                }
                // shortcut: a time on no triplet grid (other tuplets) gets no group and is written approximately
                if (found == null) { rest = rest.drop(1); continue }
                out += found.first
                from = found.first.end
                rest = rest.drop(found.second)
            }
        }
        for (len in lengths) {
            t += len
            when {
                binary(t) -> { flush(t); b0 = t }
                // A new bar starts a new run
                run.isNotEmpty() && barStart(t) != barStart(run.first()) -> { flush(barStart(t)); b0 = barStart(t); run += t }
                run.isEmpty() && barStart(t) != barStart(b0) -> { b0 = barStart(t); run += t }
                else -> run += t
            }
        }
        flush(null)
        return out
    }

    /**
     * Tuplet groups of a voice. An event with a tuplet that is not inside a group starts one ([Tuplet.span]
     * long); events without one (older files) still get their triplet groups read from the lengths.
     */
    fun tupletGroups(events: List<Event>, time: TimeSig): List<TupletGroup> {
        val tagged = mutableListOf<TupletGroup>()
        var t = Fraction.ZERO
        for (e in events) {
            val tp = e.tuplet
            if (tp != null && tagged.lastOrNull().let { it == null || t >= it.end }) tagged += TupletGroup(t, t + tp.span, tp.n, tp.m)
            t += e.length
        }
        val read = groups(events.map { it.length }, time).filter { g -> tagged.none { it.start < g.end && g.start < it.end } }
        return (tagged + read).sortedBy { it.start }
    }

    /** Written segments of every event of a voice (tuplet groups worked out from the whole voice). */
    fun splitVoice(events: List<Event>, time: TimeSig): List<List<Seg>> {
        val gs = tupletGroups(events, time)
        var t = Fraction.ZERO
        return events.map { e -> split(t, e.length, time, e.pitch == null, gs).also { t += e.length } }
    }

    /** Written values of a length inside an [n]:[m] tuplet (n/m of it); a length without one is tied from the largest values. */
    private fun tupletValues(len: Fraction, n: Int, m: Int): List<Pair<Fraction, Duration>> {
        val out = mutableListOf<Pair<Fraction, Duration>>()
        var w = len * Fraction.of(n.toLong(), m.toLong())
        var t = Fraction.ZERO
        while (w > Fraction.ZERO) {
            val d = representable(w) ?: listOf(1, 2, 4, 8, 16, 32, 64).map { Duration(it) }.firstOrNull { it.value <= w } ?: Duration(64)
            out += t to d
            val real = d.value * Fraction.of(m.toLong(), n.toLong())
            t += real; w -= d.value
        }
        return out
    }

    private class Ctx(val time: TimeSig) {
        val bar = time.measure
        val beat = time.beatLength
        val half: Fraction? = if (bar / beat == Fraction.of(4, 1)) bar / Fraction.of(2, 1) else null
        fun crossesHalf(p: Fraction, e: Fraction) = half != null && p < half && e > half
        fun sub(g: Fraction) = if (g == beat && time.isCompound) g / Fraction.of(3, 1) else g / Fraction.of(2, 1)
    }

    /** [groups]: the voice's tuplet groups ([tupletGroups] of its events); parts inside one are written as tuplets. */
    fun split(onset: Fraction, length: Fraction, time: TimeSig, isRest: Boolean, groups: List<TupletGroup> = emptyList()): List<Seg> {
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
            else {
                // Parts inside a tuplet group are written as tuplets, the parts between them as usual
                var a = t
                while (a < pieceEnd) {
                    val g = groups.firstOrNull { a >= it.start && a < it.end }
                    if (g != null) {
                        val z = minOf(pieceEnd, g.end)
                        for ((s, d) in tupletValues(z - a, g.n, g.m)) out += Seg(a + s, d.value * Fraction.of(g.m.toLong(), g.n.toLong()), d, group = g)
                        a = z
                    } else {
                        val z = groups.filter { it.start > a }.minOfOrNull { it.start }?.let { minOf(it, pieceEnd) } ?: pieceEnd
                        for ((s, d) in if (isRest) rest(a - barStart, z - barStart, ctx.beat, ctx) else note(a - barStart, z - barStart, ctx.beat, ctx, true))
                            out += Seg(barStart + s, d.value, d)
                        a = z
                    }
                }
            }
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
