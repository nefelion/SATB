package com.grothgarvc.fourpartwriting.engrave

import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Analysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NoteRef(val voice: Voice, val index: Int)

/** Drawing primitives in staff-space units, y down. */
sealed interface Mark { val ref: NoteRef? }
data class GlyphMark(val c: Char, val x: Float, val y: Float, override val ref: NoteRef? = null, val scale: Float = 1f) : Mark
data class LineMark(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, override val ref: NoteRef? = null) : Mark
/** Parallelogram with top edge (x1,y1)-(x2,y2) and vertical thickness [h]. */
data class BeamMark(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val h: Float, override val ref: NoteRef? = null) : Mark
data class TextMark(val text: String, val x: Float, val y: Float, val size: Float, val style: TextStyle) : Mark {
    override val ref: NoteRef? get() = null
}
enum class TextStyle { ROMAN, SYMBOL, TEMPO, MEASURE_NO }

data class ChordLabel(val symbol: String, val roman: String)

/** Labels per slice onset; unrecognized sonorities show only "?" (details are in the issues list). */
fun chordLabels(a: Analysis): Map<Fraction, ChordLabel> = a.slices.indices.associate { i ->
    val c = a.chords[i]
    a.slices[i].time to ChordLabel(if (c.recognized) c.symbol else "", c.roman)
}

class ColumnBox(val time: Fraction, val x: Float)
class MeasureBox(val index: Int, val start: Fraction, val end: Fraction, val x0: Float, val x1: Float, val columns: List<ColumnBox>)
class SystemBox(val top: Float, val bottom: Float, val staffTop: FloatArray, val measures: List<MeasureBox>)

/** Tap target: staff, staff position, nearest column time, and whether the tap was after the last column. */
data class Hit(val staff: Int, val position: Int, val time: Fraction, val afterLast: Boolean)

class ScoreLayout(val width: Float, val height: Float, val marks: List<Mark>, val systems: List<SystemBox>) {

    fun hitTest(x: Float, y: Float): Hit? {
        if (systems.isEmpty()) return null
        val sys = systems.firstOrNull { y < it.bottom } ?: systems.last()
        val mid = (sys.staffTop[0] + 4 + sys.staffTop[1]) / 2
        val staff = if (y < mid) 0 else 1
        val pos = ((sys.staffTop[staff] + 4 - y) * 2).roundToInt()
        val m = sys.measures.firstOrNull { x < it.x1 } ?: sys.measures.last()
        if (m.columns.isEmpty()) return Hit(staff, pos, m.start, true)
        val last = m.columns.last()
        if (x > last.x + max(1.8f, (m.x1 - last.x) / 2)) return Hit(staff, pos, last.time, true)
        val col = m.columns.minBy { abs(x - (it.x + 0.6f)) }
        return Hit(staff, pos, col.time, false)
    }

    /** x and system of the column at [t], for the playback cursor. */
    fun locate(t: Fraction): Pair<SystemBox, Float>? {
        for (s in systems) for (m in s.measures) for (c in m.columns) if (c.time == t) return s to c.x
        return null
    }
}

object Engraver {
    private const val LEFT_MARGIN = 1.6f
    private const val RIGHT_MARGIN = 0.8f
    private const val EMPTY_MEASURE = 10f

    private class Ev(val voice: Voice, val index: Int, val onset: Fraction, val e: Event) {
        val end get() = onset + e.duration.value
    }

    private class Item(val ev: Ev, val staff: Int, val pos: Int?) {
        var xOff = 0f
        var acc: Int? = null
        var accX = 0f
        val headW get() = if (ev.e.duration.base == 1) Smufl.WHOLE_W else Smufl.HEAD_W
        val head get() = when (ev.e.duration.base) { 1 -> Smufl.HEAD_WHOLE; 2 -> Smufl.HEAD_HALF; else -> Smufl.HEAD_BLACK }
    }

    private class Col(val time: Fraction, val items: List<Item>) {
        var leftExt = 0f
        var rightExt = 0f
        var x = 0f
        fun headRight(staff: Int) = items.filter { it.staff == staff && it.pos != null }.maxOfOrNull { it.xOff + it.headW } ?: Smufl.HEAD_W
    }

    private class Meas(val index: Int, val start: Fraction, val end: Fraction, val cols: List<Col>, val evs: List<Ev>) {
        val adv = FloatArray(cols.size)
        var lead = 0f
        val minWidth get() = if (cols.isEmpty()) EMPTY_MEASURE else lead + adv.sum()
    }

    fun layout(score: Score, width: Float, labels: Map<Fraction, ChordLabel> = emptyMap()): ScoreLayout {
        val ml = score.time.measure
        val all = Voice.entries.flatMap { v ->
            var t = Fraction.ZERO
            score.voice(v).mapIndexed { i, e -> Ev(v, i, t, e).also { t += e.duration.value } }
        }
        val total = score.length
        val contentMeasures = ceil(total.toDouble() / ml.toDouble() - 1e-9).toInt()
        // Appending works by tapping right of the last column, so no extra empty bar is drawn.
        val measures = (0 until max(1, contentMeasures)).map { m ->
            val start = ml * Fraction.of(m.toLong(), 1)
            val end = start + ml
            val evs = all.filter { it.onset >= start && it.onset < end }
            buildMeasure(score, m, start, end, evs)
        }

        // System breaking
        val avail = width - LEFT_MARGIN - RIGHT_MARGIN
        val systemsM = mutableListOf<MutableList<Meas>>()
        for (m in measures) {
            val cur = systemsM.lastOrNull()
            val header = headerWidth(score, systemsM.size.let { if (cur == null) it else it - 1 } == 0)
            if (cur != null && header + cur.sumOf { it.minWidth.toDouble() } + m.minWidth <= avail) cur += m
            else systemsM += mutableListOf(m)
        }

        val marks = ArrayList<Mark>(1024)
        val boxes = mutableListOf<SystemBox>()
        var y = 0f
        for ((si, sm) in systemsM.withIndex()) {
            val first = si == 0
            val lastSystem = si == systemsM.lastIndex
            val header = headerWidth(score, first)
            val natural = sm.sumOf { it.minWidth.toDouble() }.toFloat()
            val advSum = sm.sumOf { it.adv.sum().toDouble() }.toFloat()
            val stretch = if (!lastSystem && advSum > 0) 1f + (avail - header - natural) / advSum else 1f

            // Vertical extents from note positions (with stems)
            var max0 = 8; var min0 = 0; var max1 = 8; var min1 = 0
            for (m in sm) for (c in m.cols) for (it in c.items) {
                val p = it.pos ?: continue
                val hi = p + if (it.ev.voice.stemUp && it.ev.e.duration.base > 1) 7 else 0
                val lo = p - if (!it.ev.voice.stemUp && it.ev.e.duration.base > 1) 7 else 0
                if (it.staff == 0) { max0 = max(max0, hi); min0 = min(min0, lo) } else { max1 = max(max1, hi); min1 = min(min1, lo) }
            }
            val hasLabels = labels.isNotEmpty()
            var above = max(3f, (max0 - 8) * 0.5f + 1f) + (if (hasLabels) 2.6f else 0f) + (if (first) 3f else 0f)
            if (!first) above += 1f
            val gap = max(6f, (0 - min0) * 0.5f + (max1 - 8) * 0.5f + 1.5f)
            val below = max(3f, (0 - min1) * 0.5f + 1f) + (if (hasLabels) 3.4f else 0f) + 1f
            val st = floatArrayOf(y + above, y + above + 4 + gap)
            fun sy(staff: Int, p: Int) = st[staff] + (8 - p) * 0.5f
            fun syf(staff: Int, p: Float) = st[staff] + (8 - p) * 0.5f

            val x0 = LEFT_MARGIN
            var x = x0 + header
            val mboxes = mutableListOf<MeasureBox>()
            for (m in sm) {
                val mx0 = x
                if (m.cols.isEmpty()) {
                    x += EMPTY_MEASURE * (if (stretch > 1f) stretch else 1f)
                } else {
                    var cx = mx0 + m.lead
                    for ((i, c) in m.cols.withIndex()) { c.x = cx; cx += m.adv[i] * stretch }
                    x = cx
                }
                mboxes += MeasureBox(m.index, m.start, m.end, mx0, x, m.cols.map { ColumnBox(it.time, it.x) })
            }
            val xEnd = x

            // Staff lines, brace, system barline
            for (s in 0..1) for (l in 0..4) marks += LineMark(x0, sy(s, l * 2), xEnd, sy(s, l * 2), Smufl.STAFF_LINE)
            val top = sy(0, 8); val bot = sy(1, 0)
            val braceScale = (bot - top) / Smufl.BRACE_H
            marks += GlyphMark(Smufl.BRACE, x0 - 0.35f - Smufl.BRACE_W * braceScale, bot, scale = braceScale)
            marks += LineMark(x0, top, x0, bot, Smufl.THIN_BAR)

            // Header: clef, key signature, time signature
            var hx = x0 + 0.8f
            for (s in 0..1) {
                val clef = score.clefs[s]
                marks += GlyphMark(Smufl.clefGlyph(clef), hx, sy(s, (clef.line - 1) * 2))
            }
            hx += Smufl.CLEF_W + 0.8f
            val f = score.key.fifths
            if (f != 0) {
                val alter = if (f > 0) 1 else -1
                for (s in 0..1) {
                    var kx = hx
                    for (p in score.clefs[s].keySigPositions(f)) {
                        marks += GlyphMark(Smufl.accidental(alter), kx, sy(s, p))
                        kx += Smufl.accidentalWidth(alter) + 0.12f
                    }
                }
                hx += keySigWidth(f) + 0.6f
            }
            if (first) {
                for (s in 0..1) timeSig(score.time, hx, ::sy, s, marks)
                // Tempo: quarter = N
                marks += GlyphMark(Smufl.MET_QUARTER, x0 + 0.8f, top - above + 2.3f, scale = 0.75f)
                marks += TextMark("= ${score.tempo}", x0 + 2.0f, top - above + 2.3f, 1.6f, TextStyle.TEMPO)
            } else {
                marks += TextMark("${sm.first().index + 1}", x0, top - 1.2f, 1.2f, TextStyle.MEASURE_NO)
            }

            // Measures
            val symRight = floatArrayOf(-99f, -99f)
            val romRight = floatArrayOf(-99f, -99f)
            for ((mi, m) in sm.withIndex()) {
                val box = mboxes[mi]
                val isFinal = lastSystem && mi == sm.lastIndex
                if (isFinal) {
                    val tx = box.x1 - Smufl.THICK_BAR / 2
                    marks += LineMark(tx, top, tx, bot, Smufl.THICK_BAR)
                    val nx = box.x1 - Smufl.THICK_BAR - Smufl.BAR_SEP - Smufl.THIN_BAR / 2
                    marks += LineMark(nx, top, nx, bot, Smufl.THIN_BAR)
                } else {
                    marks += LineMark(box.x1, top, box.x1, bot, Smufl.THIN_BAR)
                }
                drawMeasure(score, m, box, ::sy, ::syf, marks)
                for (c in m.cols) labels[c.time]?.let { lab ->
                    val cx = c.x + Smufl.HEAD_W / 2
                    // Labels that would collide with the previous one move to a second row.
                    fun row(text: String, size: Float, lastRight: FloatArray): Int {
                        val w = text.length * size * 0.5f
                        val r = if (cx - w / 2 < lastRight[0] + 0.3f) 1 else 0
                        lastRight[r] = cx + w / 2
                        return r
                    }
                    if (lab.symbol.isNotEmpty()) {
                        val r = row(lab.symbol, 1.3f, symRight)
                        marks += TextMark(lab.symbol, cx, top - max(1.6f, (max0 - 8) * 0.5f + 1.6f) - r * 1.4f, 1.3f, TextStyle.SYMBOL)
                    }
                    val r = row(lab.roman, 1.6f, romRight)
                    marks += TextMark(lab.roman, cx, bot + max(3.0f, (0 - min1) * 0.5f + 2.6f) + r * 2.0f, 1.6f, TextStyle.ROMAN)
                }
            }
            val sysBottom = st[1] + 4 + below
            boxes += SystemBox(y, sysBottom, st, mboxes)
            y = sysBottom
        }
        return ScoreLayout(width, y, marks, boxes)
    }

    // ---------- horizontal ----------

    private fun keySigWidth(f: Int): Float {
        val alter = if (f > 0) 1 else -1
        return abs(f) * (Smufl.accidentalWidth(alter) + 0.12f)
    }

    private fun digits(n: Int) = n.toString().map { it - '0' }
    private fun numW(n: Int) = digits(n).sumOf { Smufl.DIGIT_W[it].toDouble() }.toFloat()

    private fun timeSigWidth(t: TimeSig) =
        if (t.symbol != TimeSig.Symbol.NUMERIC) 1.7f else max(numW(t.beats), numW(t.beatUnit))

    private fun headerWidth(score: Score, first: Boolean): Float {
        var w = 0.8f + Smufl.CLEF_W + 0.8f
        if (score.key.fifths != 0) w += keySigWidth(score.key.fifths) + 0.6f
        if (first) w += timeSigWidth(score.time) + 1.0f
        return w + 0.4f
    }

    private fun timeSig(t: TimeSig, x: Float, sy: (Int, Int) -> Float, s: Int, marks: MutableList<Mark>) {
        val w = timeSigWidth(t)
        when (t.symbol) {
            TimeSig.Symbol.COMMON -> marks += GlyphMark(Smufl.TIME_COMMON, x, sy(s, 4))
            TimeSig.Symbol.CUT -> marks += GlyphMark(Smufl.TIME_CUT, x, sy(s, 4))
            else -> for ((n, p) in listOf(t.beats to 6, t.beatUnit to 2)) {
                var dx = x + (w - numW(n)) / 2
                for (d in digits(n)) { marks += GlyphMark(Smufl.TIME_0 + d, dx, sy(s, p)); dx += Smufl.DIGIT_W[d] }
            }
        }
    }

    private fun idealSpace(dt: Fraction) = max(2.4f, 3.4f * sqrt(dt.toDouble().toFloat() * 4f))

    private fun buildMeasure(score: Score, index: Int, start: Fraction, end: Fraction, evs: List<Ev>): Meas {
        val times = evs.map { it.onset }.distinct().sorted()
        val state = arrayOf(HashMap<Int, Int>(), HashMap<Int, Int>())
        val cols = times.map { t ->
            val items = evs.filter { it.onset == t }.map { ev ->
                val staff = ev.voice.staff
                Item(ev, staff, ev.e.pitch?.let { score.clefs[staff].position(it) })
            }
            val col = Col(t, items)
            for (s in 0..1) {
                val notes = items.filter { it.staff == s && it.pos != null }.sortedByDescending { it.pos!! }
                // Accidentals against key signature and earlier accidentals in this bar
                for (n in notes) {
                    val p = n.ev.e.pitch!!
                    val cur = state[s][p.diatonic] ?: score.key.sigAlter(p.step)
                    if (p.alter != cur) n.acc = p.alter
                    state[s][p.diatonic] = p.alter
                }
                // Seconds and unshareable unisons between the two voices: stem-up voice moves right
                val up = notes.firstOrNull { it.ev.voice.stemUp }
                val dn = notes.firstOrNull { !it.ev.voice.stemUp }
                if (up != null && dn != null && abs(up.pos!! - dn.pos!!) <= 1) {
                    val a = up.ev.e; val b = dn.ev.e
                    // Unisons share one notehead when the heads look the same (and dots match).
                    val share = a.pitch == b.pitch && a.duration.dots == b.duration.dots && up.head == dn.head
                    if (!share) up.xOff = dn.headW
                }
                // Stack accidentals in columns, top to bottom
                val columns = mutableListOf<MutableList<Item>>()
                for (n in notes.filter { it.acc != null }) {
                    val k = columns.indexOfFirst { c -> c.none { overlaps(it, n) } }
                    if (k >= 0) columns[k] += n else columns += mutableListOf(n)
                }
                var right = -0.2f + (notes.minOfOrNull { it.xOff } ?: 0f)
                for (c in columns) {
                    val w = c.maxOf { Smufl.accidentalWidth(it.acc!!) }
                    for (n in c) n.accX = right - Smufl.accidentalWidth(n.acc!!)
                    right -= w + 0.15f
                }
                col.leftExt = max(col.leftExt, -right - 0.15f)
            }
            var r = 0f
            for (it in items) {
                val d = it.ev.e.duration
                var w = if (it.pos == null) 1.7f else it.xOff + it.headW
                if (it.pos != null && d.flags > 0 && it.ev.voice.stemUp) w = max(w, it.xOff + it.headW + Smufl.FLAG_W)
                if (d.dots > 0) w += 0.3f + d.dots * 0.55f
                r = max(r, w)
            }
            col.rightExt = r
            col
        }
        val m = Meas(index, start, end, cols, evs)
        for ((i, c) in cols.withIndex()) {
            val next = cols.getOrNull(i + 1)
            val dt = (next?.time ?: end) - c.time
            val need = c.rightExt + (next?.leftExt ?: 0.6f) + 0.5f
            m.adv[i] = max(idealSpace(dt), need)
        }
        m.lead = 1.2f + (cols.firstOrNull()?.leftExt ?: 0f)
        return m
    }

    private fun overlaps(a: Item, b: Item): Boolean {
        val ya = a.pos!! * 0.5f; val yb = b.pos!! * 0.5f
        val aTop = ya + Smufl.accidentalTop(a.acc!!); val aBot = ya - Smufl.accidentalBottom(a.acc!!)
        val bTop = yb + Smufl.accidentalTop(b.acc!!); val bBot = yb - Smufl.accidentalBottom(b.acc!!)
        return aBot < bTop - 0.2f && bBot < aTop - 0.2f
    }

    // ---------- drawing a measure ----------

    private fun drawMeasure(
        score: Score, m: Meas, box: MeasureBox,
        sy: (Int, Int) -> Float, syf: (Int, Float) -> Float, marks: MutableList<Mark>,
    ) {
        val ml = m.end - m.start
        val heads = HashMap<Ev, Pair<Float, Float>>() // ev -> head x, head y
        for (c in m.cols) {
            for (s in 0..1) {
                val items = c.items.filter { it.staff == s }
                val dotX = c.x + c.headRight(s) + 0.3f
                val restPair = items.filter { it.pos == null }
                val mergedRest = restPair.size == 2 && restPair[0].ev.e.duration == restPair[1].ev.e.duration
                for (it in items) {
                    val ev = it.ev
                    val d = ev.e.duration
                    val ref = NoteRef(ev.voice, ev.index)
                    if (it.pos == null) {
                        if (mergedRest && !ev.voice.stemUp) continue
                        val ri = Integer.numberOfTrailingZeros(d.base)
                        val fullBar = d.value == ml && m.evs.count { e -> e.voice == ev.voice } == 1
                        val hangs = ri == 0 || fullBar // whole rests hang from a line
                        // Two voices share the staff: upper-voice rests go up, lower-voice rests go down.
                        val pos = when {
                            mergedRest || twoVoiceRestIsAlone(m, ev) -> if (hangs) 6 else 4
                            ev.voice.stemUp -> 8
                            else -> if (hangs) 2 else 0
                        }
                        val glyph = if (fullBar) Smufl.REST_WHOLE else Smufl.RESTS[ri]
                        val rx = if (fullBar) (box.x0 + box.x1) / 2 - 0.56f else c.x
                        marks += GlyphMark(glyph, rx, sy(s, pos), ref)
                        if (!fullBar) for (k in 0 until d.dots) marks += GlyphMark(Smufl.DOT, rx + 1.4f + 0.3f + k * 0.55f, sy(s, pos + 1), ref)
                        continue
                    }
                    val p = it.pos
                    val hx = c.x + it.xOff
                    val hy = sy(s, p)
                    heads[ev] = hx to hy
                    // ledger lines
                    var lp = -2
                    while (lp >= p) { marks += LineMark(hx - Smufl.LEDGER_EXT, sy(s, lp), hx + it.headW + Smufl.LEDGER_EXT, sy(s, lp), Smufl.LEDGER); lp -= 2 }
                    lp = 10
                    while (lp <= p) { marks += LineMark(hx - Smufl.LEDGER_EXT, sy(s, lp), hx + it.headW + Smufl.LEDGER_EXT, sy(s, lp), Smufl.LEDGER); lp += 2 }
                    marks += GlyphMark(it.head, hx, hy, ref)
                    it.acc?.let { a -> marks += GlyphMark(Smufl.accidental(a), c.x + it.accX, hy, ref) }
                    if (d.dots > 0) {
                        val dp = if (p % 2 == 0) p + (if (ev.voice.stemUp) 1 else -1) else p
                        for (k in 0 until d.dots) marks += GlyphMark(Smufl.DOT, dotX + k * 0.55f, sy(s, dp), ref)
                    }
                }
            }
        }
        // Stems, flags, beams per voice
        for (v in Voice.entries) {
            val notes = m.evs.filter { it.voice == v }.sortedBy { it.onset }
            val groups = beamGroups(notes, m.start, score.time)
            val beamed = groups.flatten().toSet()
            for (ev in notes) {
                if (ev.e.pitch == null || ev.e.duration.base == 1 || ev in beamed) continue
                val (hx, hy) = heads[ev] ?: continue
                stemAndFlag(v, ev, hx, hy, syf(v.staff, 4f), marks)
            }
            for (g in groups) beam(v, g, heads, syf(v.staff, 4f), marks)
        }
    }

    /** True when the other voice of the staff has nothing at all in this bar (draw rest centred). */
    private fun twoVoiceRestIsAlone(m: Meas, ev: Ev): Boolean {
        val other = Voice.entries.first { it.staff == ev.voice.staff && it != ev.voice }
        return m.evs.none { it.voice == other }
    }

    private fun stemLength(flags: Int) = 3.5f + when (flags) { 3 -> 0.75f; 4 -> 1.5f; else -> 0f }

    private fun stemAndFlag(v: Voice, ev: Ev, hx: Float, hy: Float, middleY: Float, marks: MutableList<Mark>) {
        val ref = NoteRef(v, ev.index)
        val flags = ev.e.duration.flags
        val len = stemLength(flags)
        if (v.stemUp) {
            val sx = hx + Smufl.HEAD_W - Smufl.STEM / 2
            val tip = min(hy - len, middleY)
            marks += LineMark(sx, hy - Smufl.STEM_ANCHOR_Y, sx, tip, Smufl.STEM, ref)
            if (flags > 0) marks += GlyphMark(Smufl.FLAG_UP[flags - 1], sx - Smufl.STEM / 2, tip + Smufl.FLAG_UP_Y[flags - 1], ref)
        } else {
            val sx = hx + Smufl.STEM / 2
            val tip = max(hy + len, middleY)
            marks += LineMark(sx, hy + Smufl.STEM_ANCHOR_Y, sx, tip, Smufl.STEM, ref)
            if (flags > 0) marks += GlyphMark(Smufl.FLAG_DOWN[flags - 1], sx - Smufl.STEM / 2, tip + Smufl.FLAG_DOWN_Y[flags - 1], ref)
        }
    }

    /** Consecutive flagged notes inside one beat (dotted beat in compound meter). */
    private fun beamGroups(notes: List<Ev>, start: Fraction, time: TimeSig): List<List<Ev>> {
        val beat = time.beatLength
        val out = mutableListOf<List<Ev>>()
        var cur = mutableListOf<Ev>()
        var curBeat = -1L
        fun flush() { if (cur.size >= 2) out += cur; cur = mutableListOf() }
        for (ev in notes) {
            val rel = ev.onset - start
            val b = (rel / beat).let { it.num / it.den }
            val endsInBeat = ((ev.end - start) / beat).let { it <= Fraction.of(b + 1, 1) }
            if (ev.e.pitch == null || ev.e.duration.flags == 0 || !endsInBeat) { flush(); curBeat = -1; continue }
            if (b != curBeat) flush()
            curBeat = b
            cur += ev
        }
        flush()
        return out
    }

    private fun beam(v: Voice, g: List<Ev>, heads: Map<Ev, Pair<Float, Float>>, middleY: Float, marks: MutableList<Mark>) {
        val pts = g.map { heads.getValue(it) }
        val up = v.stemUp
        val sx = pts.map { (hx, _) -> if (up) hx + Smufl.HEAD_W - Smufl.STEM / 2 else hx + Smufl.STEM / 2 }
        val ys = pts.map { it.second }
        val maxFlags = g.maxOf { it.e.duration.flags }
        val len = 3.5f + 0.75f * (maxFlags - 1)
        val dxAll = (sx.last() - sx.first()).coerceAtLeast(0.01f)
        val inner = ys.subList(1, ys.size - 1)
        val concave = if (up) inner.any { it < min(ys.first(), ys.last()) } else inner.any { it > max(ys.first(), ys.last()) }
        val dy = if (concave) 0f else ((ys.last() - ys.first()) / 2f).coerceIn(-1f, 1f)
        val k = dy / dxAll
        var y0 = if (up) ys.indices.minOf { ys[it] - len - k * (sx[it] - sx.first()) }
        else ys.indices.maxOf { ys[it] + len - k * (sx[it] - sx.first()) }
        // Beams for notes far outside the staff reach the middle line
        if (up && y0 + k * dxAll / 2 > middleY) y0 = middleY - k * dxAll / 2
        if (!up && y0 + k * dxAll / 2 < middleY) y0 = middleY - k * dxAll / 2
        fun by(x: Float) = y0 + k * (x - sx.first())
        for (i in g.indices) {
            val ref = NoteRef(v, g[i].index)
            val anchor = if (up) ys[i] - Smufl.STEM_ANCHOR_Y else ys[i] + Smufl.STEM_ANCHOR_Y
            marks += LineMark(sx[i], anchor, sx[i], by(sx[i]), Smufl.STEM, ref)
        }
        val ref0 = NoteRef(v, g.first().index)
        for (level in 1..maxFlags) {
            val off = (level - 1) * (Smufl.BEAM + Smufl.BEAM_GAP)
            fun seg(xa: Float, xb: Float) {
                val ya = if (up) by(xa) + off else by(xa) - Smufl.BEAM - off
                val yb = if (up) by(xb) + off else by(xb) - Smufl.BEAM - off
                marks += BeamMark(xa, ya, xb, yb, Smufl.BEAM, ref0)
            }
            val half = Smufl.STEM / 2
            var i = 0
            while (i < g.size) {
                if (g[i].e.duration.flags < level) { i++; continue }
                var j = i
                while (j + 1 < g.size && g[j + 1].e.duration.flags >= level) j++
                // A lone beamlet points left when its note completes a subdivision (e.g. dotted 8th + 16th).
                val grid = Fraction.of(1, 1L shl (level + 1))
                val offGrid = ((g[i].onset - Fraction.ZERO) / grid).den != 1L
                when {
                    j > i -> seg(sx[i] - half, sx[j] + half)
                    i == g.lastIndex || (i > 0 && offGrid) -> seg(sx[i] - 1.1f, sx[i] + half)
                    else -> seg(sx[i] - half, sx[i] + 1.1f)
                }
                i = j + 1
            }
        }
    }
}
