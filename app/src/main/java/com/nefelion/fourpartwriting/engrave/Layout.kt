package com.nefelion.fourpartwriting.engrave

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NoteRef(val voice: Voice, val index: Int)

/** Staff positions a note may be entered or moved to: up to 4 ledger lines below and above the staff. */
val NOTE_POSITIONS = -8..16

/** Drawing primitives in staff-space units, y down. */
sealed interface Mark { val ref: NoteRef?; val x: Float }
data class GlyphMark(val c: Char, override val x: Float, val y: Float, override val ref: NoteRef? = null, val scale: Float = 1f) : Mark
data class LineMark(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float, override val ref: NoteRef? = null) : Mark {
    override val x get() = min(x1, x2)
}
/** Parallelogram with top edge (x1,y1)-(x2,y2) and vertical thickness [h]. */
data class BeamMark(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val h: Float, override val ref: NoteRef? = null) : Mark {
    override val x get() = x1
}
/** Tie from x1 to x2 at height y; [above] = curves upward. */
data class TieMark(val x1: Float, val x2: Float, val y: Float, val above: Boolean, override val ref: NoteRef? = null) : Mark {
    override val x get() = x1
}
data class TextMark(val text: String, override val x: Float, val y: Float, val size: Float, val style: TextStyle) : Mark {
    override val ref: NoteRef? get() = null
}
enum class TextStyle { ROMAN, SYMBOL, TEMPO, MEASURE_NO, NCT }

data class ChordLabel(val symbol: String, val roman: String)

/** Labels per slice onset; unrecognized sonorities show only "?" (details are in the issues list). */
fun chordLabels(a: Analysis): Map<Fraction, ChordLabel> = a.points.associate { i ->
    val c = a.chords[i]
    a.slices[i].time to ChordLabel(if (c.recognized) c.symbol else "", c.roman)
}

class ColumnBox(val time: Fraction, val x: Float)
class MeasureBox(val index: Int, val start: Fraction, val end: Fraction, val x0: Float, val x1: Float, val columns: List<ColumnBox>)
/** [at]: onset of this head's written segment (a tied note has one head per segment). */
class HeadBox(val ref: NoteRef, val x: Float, val y: Float, val staff: Int, val pos: Int, val at: Fraction)

/**
 * Tap target: staff, staff position, nearest column time, and whether the tap was after the last column.
 * [exact]: a notehead on the tapped line/space; [near]: a notehead close to the tap.
 * [exactAt] / [nearAt]: onset of that head (which head of a tied note was tapped).
 */
data class Hit(
    val staff: Int, val position: Int, val time: Fraction, val afterLast: Boolean,
    val exact: NoteRef? = null, val near: NoteRef? = null,
    val exactAt: Fraction? = null, val nearAt: Fraction? = null,
)

/** One continuous system: all bars on one line. */
class ScoreLayout(
    val width: Float,
    val height: Float,
    val marks: List<Mark>,
    val staffTop: FloatArray,
    val measures: List<MeasureBox>,
    val heads: List<HeadBox>,
    val contentEnd: Fraction,
    /** Tempo mark bounds (x0, y0, x1, y1), tappable. */
    val tempoBox: FloatArray = FloatArray(4),
    /** Highest / lowest point of any note or stem (staff-space y), for cursors that must clear them. */
    val noteTop: Float = staffTop.first(),
    val noteBottom: Float = staffTop.last() + 4,
) {
    val top get() = staffTop.first()
    val bottom get() = staffTop.last() + 4

    fun hitTest(x: Float, y: Float): Hit {
        val staff = staffTop.indices.minBy { abs(y - (staffTop[it] + 2)) }
        val pos = ((staffTop[staff] + 4 - y) * 2).roundToInt()
        val close = heads.filter { abs(it.x - x) < 1.5f }
        val exactH = close.firstOrNull { it.staff == staff && it.pos == pos }
        val nearH = close.filter { abs(it.y - y) < 1.3f && abs(it.x - x) < 1.2f }
            .minByOrNull { (it.x - x) * (it.x - x) + 2 * (it.y - y) * (it.y - y) }
        fun hit(t: Fraction, after: Boolean) = Hit(staff, pos, t, after, exactH?.ref, nearH?.ref, exactH?.at, nearH?.at)
        val m = measures.firstOrNull { x < it.x1 } ?: return hit(contentEnd, true)
        if (m.columns.isEmpty()) return hit(m.start, true)
        val last = m.columns.last()
        if (x > last.x + max(1.8f, (m.x1 - last.x) / 2)) return hit(last.time, true)
        val col = m.columns.minBy { abs(x - (it.x + 0.6f)) }
        return hit(col.time, false)
    }

    /** x of the column at [t], or where a note starting at [t] would go. */
    fun xFor(t: Fraction): Float {
        for (m in measures) {
            if (t < m.start || t > m.end) continue
            m.columns.firstOrNull { it.time == t }?.let { return it.x }
            if (t == m.end) continue
            val before = m.columns.lastOrNull { it.time < t }
            val after = m.columns.firstOrNull { it.time > t }
            if (before == null && after == null) return m.x0 + 1.5f
            val (t0, x0) = before?.let { it.time to it.x } ?: (m.start to m.x0 + 1.2f)
            val (t1, x1) = after?.let { it.time to it.x } ?: (m.end to m.x1 - 1.0f)
            return x0 + ((t - t0) / (t1 - t0)).toDouble().toFloat() * (x1 - x0)
        }
        return (measures.lastOrNull()?.x1 ?: 0f) + 1.5f
    }

    fun locate(t: Fraction): Float? = measures.firstNotNullOfOrNull { m -> m.columns.firstOrNull { it.time == t }?.x }
}

object Engraver {
    private const val LEFT_MARGIN = 2.0f
    private const val TAIL = 14f // room after the last bar for appending
    private const val EMPTY_MEASURE = 12f

    /** One written segment of an event. */
    private class Item(val voice: Voice, val index: Int, val seg: Seg, val segIndex: Int, val tieNext: Boolean, val staff: Int, val pos: Int?) {
        var dir = 1          // stem direction: 1 up, -1 down
        var xOff = 0f        // head offset within its column (seconds, voice collisions)
        var acc: Int? = null
        var accX = 0f
        val ref get() = NoteRef(voice, index)
        val d get() = seg.dur
        val headW get() = if (d.base == 1) Smufl.WHOLE_W else Smufl.HEAD_W
        val head get() = when (d.base) { 1 -> Smufl.HEAD_WHOLE; 2 -> Smufl.HEAD_HALF; else -> Smufl.HEAD_BLACK }
        val isNote get() = pos != null
    }

    /** Notes of one staff, column and stem direction with the same written value: one chord. */
    private class StemUnit(val staff: Int, val dir: Int, val items: List<Item>) {
        var shift = 0f
        val flags get() = items.first().d.flags
        val hasStem get() = items.first().d.base > 1
    }

    private class Col(val time: Fraction, val items: List<Item>) {
        var leftExt = 0f
        var rightExt = 0f
        var x = 0f
        val units = mutableListOf<StemUnit>()
        fun headRight(staff: Int) = items.filter { it.staff == staff && it.isNote }.maxOfOrNull { it.xOff + it.headW } ?: Smufl.HEAD_W
    }

    private class Meas(val index: Int, val start: Fraction, val end: Fraction, val cols: List<Col>, val items: List<Item>) {
        val adv = FloatArray(cols.size)
        var lead = 0f
        val width get() = if (cols.isEmpty()) EMPTY_MEASURE else lead + adv.sum()
    }

    /**
     * [show]: draw only these voices, bare (no clef, key, time, tempo, bar numbers or final bar), for small examples.
     * Null: the full editor score.
     */
    fun layout(
        score: Score, labels: Map<Fraction, ChordLabel> = emptyMap(), nctLabels: Map<NoteRef, String> = emptyMap(),
        show: Set<Voice>? = null,
    ): ScoreLayout {
        val ml = score.time.measure
        val n = score.arrangement.staves
        val bare = show != null
        val shown = show ?: Voice.entries.toSet()
        val stems = score.arrangement.stems(shown)

        // Written segments of every event
        val all = mutableListOf<Item>()
        for (v in Voice.entries) {
            if (v !in shown) continue
            var t = Fraction.ZERO
            for ((i, e) in score.voice(v).withIndex()) {
                val segs = Notation.split(t, e.length, score.time, e.pitch == null)
                val staff = score.staffOf(v)
                for ((k, s) in segs.withIndex())
                    all += Item(v, i, s, k, e.pitch != null && k < segs.lastIndex, staff, e.pitch?.let { score.clefs[staff].position(it) })
                t += e.length
            }
        }
        val total = score.length
        val barCount = max(1, ceil(total.toDouble() / ml.toDouble() - 1e-9).toInt())
        // Where a voice has no notes up to the end of the last bar, show rests (display only:
        // their index is past the voice's last event, so they cannot be selected or edited).
        // An empty score is one bar of rests.
        val barEnd = ml * Fraction.of(barCount.toLong(), 1)
        if (!bare) for (v in Voice.entries) {
            val t = score.length(v)
            if (t >= barEnd) continue
            val staff = score.staffOf(v)
            for ((k, s) in Notation.split(t, barEnd - t, score.time, true).withIndex())
                all += Item(v, score.voice(v).size, s, k, false, staff, null)
        }
        val measures = (0 until barCount).map { m ->
            val start = ml * Fraction.of(m.toLong(), 1)
            val end = start + ml
            buildMeasure(score, stems, m, start, end, all.filter { it.seg.onset >= start && it.seg.onset < end })
        }

        // Vertical extents per staff (note positions incl. stems)
        val maxP = IntArray(n) { 8 }; val minP = IntArray(n) { 0 }
        for (it in all) {
            val p = it.pos ?: continue
            val stem = if (it.d.base > 1) 7 else 0
            maxP[it.staff] = max(maxP[it.staff], p + if (it.dir > 0) stem else 0)
            minP[it.staff] = min(minP[it.staff], p - if (it.dir < 0) stem else 0)
        }
        val hasLabels = labels.isNotEmpty()
        val st = FloatArray(n)
        var y = 3.2f + (if (hasLabels) 2.8f else 0f) + max(0f, (maxP[0] - 8) * 0.5f)
        for (s in 0 until n) {
            if (s > 0) y += max(5f, (0 - minP[s - 1]) * 0.5f + (maxP[s] - 8) * 0.5f + 1.5f)
            st[s] = y
            y += 4f
        }
        val height = y + max(2.5f, (0 - minP[n - 1]) * 0.5f + 1f) + (if (hasLabels) 3.6f else 0f)
        fun sy(staff: Int, p: Int) = st[staff] + (8 - p) * 0.5f
        fun syf(staff: Int, p: Float) = st[staff] + (8 - p) * 0.5f

        val marks = ArrayList<Mark>(2048)
        val heads = mutableListOf<HeadBox>()
        val x0 = LEFT_MARGIN
        val header = if (!bare) headerWidth(score) else if (n > 1) 0.8f + Smufl.CLEF_W + 0.8f else 0f
        var x = x0 + header
        val boxes = mutableListOf<MeasureBox>()
        for (m in measures) {
            val mx0 = x
            var cx = mx0 + m.lead
            for ((i, c) in m.cols.withIndex()) { c.x = cx; cx += m.adv[i] }
            x = mx0 + m.width
            boxes += MeasureBox(m.index, m.start, m.end, mx0, x, m.cols.map { ColumnBox(it.time, it.x) })
        }
        val xEnd = x

        val top = sy(0, 8); val bot = sy(n - 1, 0)
        for (s in 0 until n) for (l in 0..4) marks += LineMark(x0, sy(s, l * 2), xEnd, sy(s, l * 2), Smufl.STAFF_LINE)
        // Two staves (piano style) get a brace, more (choir style) a bracket
        if (!bare && n == 2) {
            val braceScale = (bot - top) / Smufl.BRACE_H
            marks += GlyphMark(Smufl.BRACE, x0 - 0.35f - Smufl.BRACE_W * braceScale, bot, scale = braceScale)
        } else if (!bare && n > 2) {
            val bx = x0 - 1.0f
            marks += LineMark(bx + 0.25f, top - 0.5f, bx + 0.25f, bot + 0.5f, Smufl.THICK_BAR)
            marks += GlyphMark(Smufl.BRACKET_TOP, bx, top - 0.5f)
            marks += GlyphMark(Smufl.BRACKET_BOTTOM, bx, bot + 0.5f)
        }
        if (n > 1 && !bare) marks += LineMark(x0, top, x0, bot, Smufl.THIN_BAR)

        // Header: clef, key signature, time signature, tempo
        var hx = x0 + 0.8f
        val tempoY = top - max(2.2f, (maxP[0] - 8) * 0.5f + 1.2f) - (if (hasLabels) 2.8f else 0f)
        val tx = x0 + if (n > 2) 1.6f else 0.8f // clear the bracket hook
        // Bare: clefs only on two staves, where the notes cannot be read without them
        if (!bare || n > 1) for (s in 0 until n) marks += GlyphMark(Smufl.clefGlyph(score.clefs[s]), hx, sy(s, (score.clefs[s].line - 1) * 2))
        hx += Smufl.CLEF_W + 0.8f
        if (!bare) {
            val f = score.key.fifths
            if (f != 0) {
                val alter = if (f > 0) 1 else -1
                for (s in 0 until n) {
                    var kx = hx
                    for (p in score.clefs[s].keySigPositions(f)) {
                        marks += GlyphMark(Smufl.accidental(alter), kx, sy(s, p))
                        kx += Smufl.accidentalWidth(alter) + 0.12f
                    }
                }
                hx += keySigWidth(f) + 0.6f
            }
            for (s in 0 until n) timeSig(score.time, hx, ::sy, s, marks)
            marks += GlyphMark(Smufl.MET_QUARTER, tx, tempoY, scale = 0.75f)
            marks += TextMark("= ${score.tempo}", tx + 1.2f, tempoY, 1.6f, TextStyle.TEMPO)
        }

        // Measures
        val symRight = floatArrayOf(-99f, -99f)
        val romRight = floatArrayOf(-99f, -99f)
        val headAt = HashMap<Triple<Voice, Int, Int>, Pair<Item, Pair<Float, Float>>>()
        val labelTop = top - max(2.4f, (maxP[0] - 8) * 0.5f + 1.6f)
        val labelBot = bot + max(3.0f, (0 - minP[n - 1]) * 0.5f + 2.6f)
        for ((mi, m) in measures.withIndex()) {
            val box = boxes[mi]
            val isFinal = mi == measures.lastIndex && total > Fraction.ZERO && (total / ml).den == 1L
            if (bare) { if (mi < measures.lastIndex) marks += LineMark(box.x1, top, box.x1, bot, Smufl.THIN_BAR) }
            else if (isFinal) {
                val tx = box.x1 - Smufl.THICK_BAR / 2
                marks += LineMark(tx, top, tx, bot, Smufl.THICK_BAR)
                val nx = box.x1 - Smufl.THICK_BAR - Smufl.BAR_SEP - Smufl.THIN_BAR / 2
                marks += LineMark(nx, top, nx, bot, Smufl.THIN_BAR)
            } else marks += LineMark(box.x1, top, box.x1, bot, Smufl.THIN_BAR)
            if (mi > 0 && !bare) marks += TextMark("${mi + 1}", box.x0 + 0.2f, top - 0.6f, 1.0f, TextStyle.MEASURE_NO)
            drawMeasure(score, shown, m, box, ::sy, ::syf, marks, heads, headAt)
            for (c in m.cols) labels[c.time]?.let { lab ->
                val cx = c.x + Smufl.HEAD_W / 2
                // Labels that would collide with the previous one move to a second row.
                fun row(text: String, size: Float, last: FloatArray): Int {
                    val w = text.length * size * 0.5f
                    val r = if (cx - w / 2 < last[0] + 0.3f) 1 else 0
                    last[r] = cx + w / 2
                    return r
                }
                if (lab.symbol.isNotEmpty()) {
                    val r = row(lab.symbol, 1.3f, symRight)
                    marks += TextMark(lab.symbol, cx, labelTop - r * 1.4f, 1.3f, TextStyle.SYMBOL)
                }
                val r = row(lab.roman, 1.6f, romRight)
                marks += TextMark(lab.roman, cx, labelBot + r * 2.0f, 1.6f, TextStyle.ROMAN)
            }
        }
        drawTies(score, shown, headAt, marks)
        // Non-chord-tone labels, next to the first written head of the note
        val labelled = HashSet<NoteRef>()
        for (h in heads) {
            val lab = nctLabels[h.ref] ?: continue
            if (!labelled.add(h.ref)) continue
            marks += TextMark(lab, h.x + 1.1f, h.y - 0.9f, 1.0f, TextStyle.NCT)
        }
        val tempoBox = floatArrayOf(tx - 0.5f, tempoY - 3.2f, tx + 6.5f, tempoY + 0.8f)
        return ScoreLayout(xEnd + TAIL, height, marks, st, boxes, heads, total, tempoBox,
            noteTop = min(top, syf(0, maxP[0].toFloat())), noteBottom = max(bot, syf(n - 1, minP[n - 1].toFloat())))
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

    private fun headerWidth(score: Score): Float {
        var w = 0.8f + Smufl.CLEF_W + 0.8f
        if (score.key.fifths != 0) w += keySigWidth(score.key.fifths) + 0.6f
        return w + timeSigWidth(score.time) + 1.4f
    }

    private fun timeSig(t: TimeSig, x: Float, sy: (Int, Int) -> Float, s: Int, marks: MutableList<Mark>) {
        val w = timeSigWidth(t)
        when (t.symbol) {
            TimeSig.Symbol.COMMON -> marks += GlyphMark(Smufl.TIME_COMMON, x, sy(s, 4))
            TimeSig.Symbol.CUT -> marks += GlyphMark(Smufl.TIME_CUT, x, sy(s, 4))
            else -> for ((num, p) in listOf(t.beats to 6, t.beatUnit to 2)) {
                var dx = x + (w - numW(num)) / 2
                for (d in digits(num)) { marks += GlyphMark(Smufl.TIME_0 + d, dx, sy(s, p)); dx += Smufl.DIGIT_W[d] }
            }
        }
    }

    private fun idealSpace(dt: Fraction) = max(2.4f, 3.4f * sqrt(dt.toDouble().toFloat() * 4f))

    /** Stem direction of [voice] for a note at staff position [pos] (or a beam group's mean). */
    private fun dirFor(stems: List<Int>, voice: Voice, pos: Float): Int {
        val d = stems[voice.ordinal]
        return if (d != 0) d else if (pos >= 4f) -1 else 1
    }

    private fun buildMeasure(score: Score, stems: List<Int>, index: Int, start: Fraction, end: Fraction, items: List<Item>): Meas {
        // Stem directions: fixed per voice, or per beam group / note for a voice alone on its staff
        for (v in Voice.entries) {
            val notes = items.filter { it.voice == v && it.isNote }.sortedBy { it.seg.onset }
            val groups = beamGroups(notes, start, score.time)
            val grouped = HashSet<Item>()
            for (g in groups) {
                val dir = dirFor(stems, v, g.map { it.pos!!.toFloat() }.average().toFloat())
                g.forEach { it.dir = dir; grouped += it }
            }
            for (it in notes) if (it !in grouped) it.dir = dirFor(stems, v, it.pos!!.toFloat())
        }
        for (it in items) if (!it.isNote) it.dir = stems[it.voice.ordinal].let { d -> if (d == 0) 1 else d }

        val times = items.map { it.seg.onset }.distinct().sorted()
        val state = HashMap<Pair<Int, Int>, Int>() // (staff, diatonic) -> alter shown in this bar
        val cols = times.map { t ->
            val colItems = items.filter { it.seg.onset == t }
            val col = Col(t, colItems)
            for (s in colItems.map { it.staff }.distinct()) {
                val notes = colItems.filter { it.staff == s && it.isNote }.sortedByDescending { it.pos!! }
                // Accidentals against key signature and earlier accidentals in this bar.
                // A tied continuation shows none and does not change the bar's state.
                for (nIt in notes) {
                    if (nIt.segIndex > 0) continue
                    val p = score.voice(nIt.voice)[nIt.index].pitch!!
                    val cur = state[s to p.diatonic] ?: score.key.sigAlter(p.step)
                    if (p.alter != cur) nIt.acc = p.alter
                    state[s to p.diatonic] = p.alter
                }
                // Chord units: same stem direction and written value
                val units = notes.groupBy { it.dir to it.d }.map { (k, v) -> StemUnit(s, k.first, v) }
                for (u in units) {
                    // Seconds inside a chord: up stem puts the upper note right, down stem the lower note left
                    val ordered = if (u.dir > 0) u.items.sortedBy { it.pos!! } else u.items.sortedByDescending { it.pos!! }
                    var prev: Item? = null
                    for (it in ordered) {
                        if (prev != null && abs(it.pos!! - prev.pos!!) == 1 && prev.xOff == 0f)
                            it.xOff = (Smufl.HEAD_W - Smufl.STEM) * u.dir
                        prev = it
                    }
                }
                // Opposite-stem units a second apart (or unisons that cannot share a head): up unit moves right
                val ups = units.filter { it.dir > 0 }; val downs = units.filter { it.dir < 0 }
                for (u in ups) for (dn in downs) {
                    val clash = u.items.any { a -> dn.items.any { b ->
                        abs(a.pos!! - b.pos!!) == 1 || (a.pos == b.pos && !(a.head == b.head && a.d.dots == b.d.dots &&
                            score.voice(a.voice)[a.index].pitch == score.voice(b.voice)[b.index].pitch))
                    } }
                    if (clash) {
                        val w = dn.items.maxOf { it.headW }
                        if (u.shift < w) { u.shift = w; u.items.forEach { it.xOff += w } }
                    }
                }
                col.units += units
                // Down-stem chords with a left-shifted second need room on the left
                val leftMost = notes.minOfOrNull { it.xOff } ?: 0f
                // Stack accidentals in columns, top to bottom
                val columns = mutableListOf<MutableList<Item>>()
                for (nIt in notes.filter { it.acc != null }) {
                    val k = columns.indexOfFirst { c -> c.none { overlaps(it, nIt) } }
                    if (k >= 0) columns[k] += nIt else columns += mutableListOf(nIt)
                }
                var right = -0.2f + min(0f, leftMost)
                for (c in columns) {
                    val w = c.maxOf { Smufl.accidentalWidth(it.acc!!) }
                    for (nIt in c) nIt.accX = right - Smufl.accidentalWidth(nIt.acc!!)
                    right -= w + 0.15f
                }
                col.leftExt = max(col.leftExt, -right - 0.15f)
            }
            var r = 0f
            for (it in colItems) {
                val d = it.d
                var w = if (!it.isNote) 1.7f else it.xOff + it.headW
                if (it.isNote && d.flags > 0 && it.dir > 0) w = max(w, it.xOff + it.headW + Smufl.FLAG_W)
                if (d.dots > 0) w += 0.3f + d.dots * 0.55f
                if (it.tieNext) w += 0.8f
                r = max(r, w)
            }
            col.rightExt = r
            col
        }
        val m = Meas(index, start, end, cols, items)
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
        score: Score, shown: Set<Voice>, m: Meas, box: MeasureBox,
        sy: (Int, Int) -> Float, syf: (Int, Float) -> Float, marks: MutableList<Mark>,
        heads: MutableList<HeadBox>, headAt: MutableMap<Triple<Voice, Int, Int>, Pair<Item, Pair<Float, Float>>>,
    ) {
        for (c in m.cols) {
            for (s in c.items.map { it.staff }.distinct()) {
                val items = c.items.filter { it.staff == s }
                val dotX = c.x + c.headRight(s) + 0.3f
                drawRests(score, shown, m, box, c, s, items.filter { !it.isNote }, sy, marks)
                for (it in items.filter { it.isNote }) {
                    val p = it.pos!!
                    val hx = c.x + it.xOff
                    val hy = sy(s, p)
                    val ref = it.ref
                    heads += HeadBox(ref, hx + it.headW / 2, hy, s, p, it.seg.onset)
                    headAt[Triple(it.voice, it.index, it.segIndex)] = it to (hx to hy)
                    var lp = -2
                    while (lp >= p) { marks += LineMark(hx - Smufl.LEDGER_EXT, sy(s, lp), hx + it.headW + Smufl.LEDGER_EXT, sy(s, lp), Smufl.LEDGER); lp -= 2 }
                    lp = 10
                    while (lp <= p) { marks += LineMark(hx - Smufl.LEDGER_EXT, sy(s, lp), hx + it.headW + Smufl.LEDGER_EXT, sy(s, lp), Smufl.LEDGER); lp += 2 }
                    marks += GlyphMark(it.head, hx, hy, ref)
                    it.acc?.let { a -> marks += GlyphMark(Smufl.accidental(a), c.x + it.accX, hy, ref) }
                    if (it.d.dots > 0) {
                        val dp = if (p % 2 == 0) p + it.dir else p
                        for (k in 0 until it.d.dots) marks += GlyphMark(Smufl.DOT, dotX + k * 0.55f, sy(s, dp), ref)
                    }
                }
            }
        }
        // Stems, flags and beams per chord unit
        val unitOf = HashMap<Item, StemUnit>()
        for (c in m.cols) for (u in c.units) for (it in u.items) unitOf[it] = u
        val colX = HashMap<StemUnit, Float>()
        for (c in m.cols) for (u in c.units) colX[u] = c.x
        val beamedUnits = HashSet<StemUnit>()
        val drawnGroups = HashSet<List<StemUnit>>()
        for (v in Voice.entries) {
            val notes = m.items.filter { it.voice == v && it.isNote }.sortedBy { it.seg.onset }
            for (g in beamGroups(notes, m.start, score.time)) {
                val units = g.map { unitOf.getValue(it) }
                beamedUnits += units
                if (drawnGroups.add(units)) beam(units, colX, sy, syf(units.first().staff, 4f), marks)
            }
        }
        for (c in m.cols) for (u in c.units) {
            if (!u.hasStem || u in beamedUnits) continue
            stemAndFlag(u, c.x, sy, syf(u.staff, 4f), marks)
        }
    }

    private fun drawRests(
        score: Score, shown: Set<Voice>, m: Meas, box: MeasureBox, c: Col, s: Int, rests: List<Item>,
        sy: (Int, Int) -> Float, marks: MutableList<Mark>,
    ) {
        if (rests.isEmpty()) return
        val on = score.voicesOn(s).filter { it in shown }
        // Every voice of the staff rests here with the same value: draw one rest, centred
        val groups = rests.groupBy { it.dir to it.d }
        val allSame = rests.size == on.size && rests.map { it.d to it.seg.fullBar }.distinct().size == 1
        for ((key, g) in groups) {
            val it = g.first()
            if (allSame && it !== rests.first()) continue
            val d = it.d
            val ri = Integer.numberOfTrailingZeros(d.base)
            val hangs = ri == 0 || it.seg.fullBar
            val others = on.filter { v -> v !in g.map { r -> r.voice } }
            val alone = allSame || others.none { v -> m.items.any { o -> o.voice == v } }
            // Notes of the other stem direction at this point: keep the rest clear of them (≥ 2 spaces)
            val otherPos = c.items.filter { o -> o.staff == s && o.isNote && o.dir != key.first }.mapNotNull { o -> o.pos }
            var pos = when {
                alone -> if (hangs) 6 else 4
                key.first > 0 -> max(8, (otherPos.maxOrNull() ?: 0) + 4)
                else -> min(if (hangs) 2 else 0, (otherPos.minOrNull() ?: 8) - 4)
            }
            if (!alone && (hangs || ri == 1) && pos % 2 != 0) pos += key.first // whole/half rests sit on a line
            val glyph = if (it.seg.fullBar) Smufl.REST_WHOLE else Smufl.RESTS[ri]
            val rx = if (it.seg.fullBar) (box.x0 + box.x1) / 2 - 0.56f else c.x
            for (r in (if (allSame) rests else g)) marks += GlyphMark(glyph, rx, sy(s, pos), r.ref)
            if (!it.seg.fullBar) for (k in 0 until d.dots) marks += GlyphMark(Smufl.DOT, rx + 1.4f + 0.3f + k * 0.55f, sy(s, pos + 1), it.ref)
        }
    }

    private fun stemLength(flags: Int) = 3.5f + when (flags) { 3 -> 0.75f; 4 -> 1.5f; else -> 0f }

    private fun stemX(u: StemUnit, cx: Float) = cx + u.shift + if (u.dir > 0) Smufl.HEAD_W - Smufl.STEM / 2 else Smufl.STEM / 2

    private fun stemAndFlag(u: StemUnit, cx: Float, sy: (Int, Int) -> Float, middleY: Float, marks: MutableList<Mark>) {
        val ref = u.items.first().ref
        val flags = u.flags
        val len = stemLength(flags)
        val ys = u.items.map { sy(u.staff, it.pos!!) }
        val sx = stemX(u, cx)
        if (u.dir > 0) {
            val tip = min(ys.min() - len, middleY)
            marks += LineMark(sx, ys.max() - Smufl.STEM_ANCHOR_Y, sx, tip, Smufl.STEM, ref)
            if (flags > 0) marks += GlyphMark(Smufl.FLAG_UP[flags - 1], sx - Smufl.STEM / 2, tip + Smufl.FLAG_UP_Y[flags - 1], ref)
        } else {
            val tip = max(ys.max() + len, middleY)
            marks += LineMark(sx, ys.min() + Smufl.STEM_ANCHOR_Y, sx, tip, Smufl.STEM, ref)
            if (flags > 0) marks += GlyphMark(Smufl.FLAG_DOWN[flags - 1], sx - Smufl.STEM / 2, tip + Smufl.FLAG_DOWN_Y[flags - 1], ref)
        }
    }

    /** Consecutive flagged notes inside one beat (dotted beat in compound meter). */
    private fun beamGroups(notes: List<Item>, start: Fraction, time: TimeSig): List<List<Item>> {
        val beat = time.beatLength
        val out = mutableListOf<List<Item>>()
        var cur = mutableListOf<Item>()
        var curBeat = -1L
        fun flush() { if (cur.size >= 2) out += cur; cur = mutableListOf() }
        for (it in notes) {
            val rel = it.seg.onset - start
            val b = (rel / beat).let { f -> f.num / f.den }
            val endsInBeat = ((rel + it.seg.length) / beat) <= Fraction.of(b + 1, 1)
            if (it.d.flags == 0 || !endsInBeat) { flush(); curBeat = -1; continue }
            // A gap (rest) breaks the beam
            if (b != curBeat || cur.lastOrNull()?.let { l -> l.seg.onset + l.seg.length != it.seg.onset } == true) flush()
            curBeat = b
            cur += it
        }
        flush()
        return out
    }

    private fun beam(units: List<StemUnit>, colX: Map<StemUnit, Float>, sy: (Int, Int) -> Float, middleY: Float, marks: MutableList<Mark>) {
        val up = units.first().dir > 0
        val sx = units.map { stemX(it, colX.getValue(it)) }
        // The head nearest the beam sets the stem length; stems start at the far head
        val near = units.map { u -> u.items.map { sy(u.staff, it.pos!!) }.let { if (up) it.min() else it.max() } }
        val far = units.map { u -> u.items.map { sy(u.staff, it.pos!!) }.let { if (up) it.max() else it.min() } }
        val maxFlags = units.maxOf { it.flags }
        val len = 3.5f + 0.75f * (maxFlags - 1)
        val dxAll = (sx.last() - sx.first()).coerceAtLeast(0.01f)
        val inner = near.subList(1, near.size - 1)
        val concave = if (up) inner.any { it < min(near.first(), near.last()) } else inner.any { it > max(near.first(), near.last()) }
        val dy = if (concave) 0f else ((near.last() - near.first()) / 2f).coerceIn(-1f, 1f)
        val k = dy / dxAll
        var y0 = if (up) near.indices.minOf { near[it] - len - k * (sx[it] - sx.first()) }
        else near.indices.maxOf { near[it] + len - k * (sx[it] - sx.first()) }
        // Beams of notes far outside the staff reach the middle line
        if (up && y0 + k * dxAll / 2 > middleY) y0 = middleY - k * dxAll / 2
        if (!up && y0 + k * dxAll / 2 < middleY) y0 = middleY - k * dxAll / 2
        fun by(x: Float) = y0 + k * (x - sx.first())
        for (i in units.indices) {
            val ref = units[i].items.first().ref
            val anchor = if (up) far[i] - Smufl.STEM_ANCHOR_Y else far[i] + Smufl.STEM_ANCHOR_Y
            marks += LineMark(sx[i], anchor, sx[i], by(sx[i]), Smufl.STEM, ref)
        }
        val ref0 = units.first().items.first().ref
        val onsets = units.map { it.items.first().seg.onset }
        for (level in 1..maxFlags) {
            val off = (level - 1) * (Smufl.BEAM + Smufl.BEAM_GAP)
            fun seg(xa: Float, xb: Float) {
                val ya = if (up) by(xa) + off else by(xa) - Smufl.BEAM - off
                val yb = if (up) by(xb) + off else by(xb) - Smufl.BEAM - off
                marks += BeamMark(xa, ya, xb, yb, Smufl.BEAM, ref0)
            }
            val half = Smufl.STEM / 2
            var i = 0
            while (i < units.size) {
                if (units[i].flags < level) { i++; continue }
                var j = i
                while (j + 1 < units.size && units[j + 1].flags >= level) j++
                // A lone beamlet points left when its note completes a subdivision (e.g. dotted 8th + 16th).
                val grid = Fraction.of(1, 1L shl (level + 1))
                val offGrid = (onsets[i] / grid).den != 1L
                when {
                    j > i -> seg(sx[i] - half, sx[j] + half)
                    i == units.lastIndex || (i > 0 && offGrid) -> seg(sx[i] - 1.1f, sx[i] + half)
                    else -> seg(sx[i] - half, sx[i] + 1.1f)
                }
                i = j + 1
            }
        }
    }

    // ---------- ties ----------

    private fun drawTies(score: Score, shown: Set<Voice>, headAt: Map<Triple<Voice, Int, Int>, Pair<Item, Pair<Float, Float>>>, marks: MutableList<Mark>) {
        for ((key, v) in headAt) {
            val (it, xy) = v
            if (!it.tieNext) continue
            val next = headAt[Triple(key.first, key.second, key.third + 1)] ?: continue
            val (hx, hy) = xy
            val (nx, _) = next.second
            val multi = score.voicesOn(it.staff).count { v -> v in shown } > 1
            // Shared staff: ties go outward from the voice; alone: opposite the stem.
            val above = if (multi) it.dir > 0 else it.dir < 0
            val y = hy + if (above) -0.55f else 0.55f
            marks += TieMark(hx + it.headW + 0.12f, nx - 0.12f, y, above, it.ref)
        }
    }
}
