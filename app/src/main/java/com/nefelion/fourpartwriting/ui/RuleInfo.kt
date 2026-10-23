package com.nefelion.fourpartwriting.ui

import android.graphics.Paint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import com.nefelion.fourpartwriting.engrave.BeamMark
import com.nefelion.fourpartwriting.engrave.ChordLabel
import com.nefelion.fourpartwriting.engrave.Engraver
import com.nefelion.fourpartwriting.engrave.GlyphMark
import com.nefelion.fourpartwriting.engrave.HeadBox
import com.nefelion.fourpartwriting.engrave.LineMark
import com.nefelion.fourpartwriting.engrave.Mark
import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.engrave.ScoreLayout
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.engrave.TextMark
import com.nefelion.fourpartwriting.engrave.TieMark
import com.nefelion.fourpartwriting.engrave.chordLabels
import com.nefelion.fourpartwriting.model.Clef
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.RuleExample
import com.nefelion.fourpartwriting.theory.RuleExamples
import com.nefelion.fourpartwriting.theory.VoiceLeading
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import com.nefelion.fourpartwriting.model.Arrangement as Staves

/** Rules whose marked pairs of notes are an interval worth naming ("P5"). */
private val INTERVAL_RULES = setOf(Rule.PARALLEL_FIFTHS, Rule.PARALLEL_OCTAVES, Rule.DIRECT, Rule.UNEQUAL_FIFTHS, Rule.SPACING)

/** Glyphs that belong to a notehead and travel with it: the head, its accidental and dots. */
private val ATTACHED = setOf(Smufl.HEAD_BLACK, Smufl.HEAD_HALF, Smufl.HEAD_WHOLE, Smufl.DOT) + (-2..2).map(Smufl::accidental)

private fun Mark.attached() = this is GlyphMark && ref != null && c in ATTACHED
private fun Mark.ledger() = this is LineMark && ref == null && width == Smufl.LEDGER
/** Staff lines and barlines. */
private fun Mark.frame() = this is LineMark && ref == null && width != Smufl.LEDGER

private const val STRETCH = 1.8f

/** Everything the demo draws, worked out once per rule. Coordinates in staff spaces, first staff top at y = 0. */
private class Demo(val rule: Rule, ex: RuleExample) {
    val show = ex.show
    private val badA = VoiceLeading.check(ex.bad, setOf(rule))
    private val goodA = VoiceLeading.check(ex.good, setOf(rule))
    val message = badA.issues.firstOrNull()?.message.orEmpty()

    // One staff (treble or bass, whichever needs fewer ledger lines) when the shown notes fit within three ledger lines
    private val staves: Staves
    private val clefs: List<Clef>
    init {
        val ps = listOf(ex.bad, ex.good).flatMap { s -> show.flatMap { v -> s.voice(v).mapNotNull { it.pitch } } }
        fun ledgers(c: Clef) = ps.maxOfOrNull { p -> c.position(p).let { max(it - 8, -it) } } ?: 0
        val best = listOf(Clef.TREBLE, Clef.BASS).minBy(::ledgers)
        if (ledgers(best) <= 6) { staves = Staves.ONE; clefs = listOf(best) } else { staves = Staves.CLOSED; clefs = listOf(Clef.TREBLE, Clef.BASS) }
    }

    private val rawBad = engrave(ex.bad, badA, ex.roman)
    private val rawGood = engrave(ex.good, goodA, ex.roman)

    private fun engrave(s: Score, a: Analysis, roman: Boolean): ScoreLayout {
        val labels = if (roman) chordLabels(a).mapValues { ChordLabel("", it.value.roman) } else emptyMap()
        return Engraver.layout(s.copy(arrangement = staves, clefs = clefs), labels, show = show)
    }

    /**
     * Where each column (by time) goes, the same in both versions so only the changed notes move
     * (an accidental would otherwise push one version's chord right), and spread out so arrows fit between chords.
     */
    private val target: Map<Fraction, Float> = run {
        val cols = listOf(rawBad, rawGood).map { l -> l.measures.flatMap { it.columns }.map { it.time to it.x } }
        val out = HashMap<Fraction, Float>()
        for (t in cols.flatten().map { it.first }.distinct().sorted()) out[t] = cols.mapNotNull { cs ->
            val i = cs.indexOfFirst { it.first == t }
            when {
                i < 0 -> null
                i == 0 -> cs[0].second
                else -> out.getValue(cs[i - 1].first) + (cs[i].second - cs[i - 1].second) * STRETCH
            }
        }.max()
        out
    }

    val badL: ScoreLayout
    val goodL: ScoreLayout
    init {
        val a = align(rawBad); val b = align(rawGood)
        // One staff length for both
        fun staffLine(m: Mark) = m is LineMark && m.frame() && m.y1 == m.y2
        val lines = (a.marks + b.marks).filter(::staffLine).map { it as LineMark }
        val x1 = lines.minOf { it.x1 }; val x2 = lines.maxOf { it.x2 }
        fun fix(l: ScoreLayout) = ScoreLayout(l.width, l.height, l.marks.map { if (staffLine(it)) (it as LineMark).copy(x1 = x1, x2 = x2) else it },
            l.staffTop, l.measures, l.heads, l.contentEnd)
        badL = fix(a); goodL = fix(b)
    }

    /**
     * Moves every column of [l] to its [target] place; marks of a note go with its head (accidentals and stems stay
     * attached). The first staff's top goes to y = 0 (the engraver puts it lower when notes reach higher).
     */
    private fun align(l: ScoreLayout): ScoreLayout {
        val cols = l.measures.flatMap { it.columns }.sortedBy { it.x }
        fun dx(x: Float): Float {
            val c = cols.lastOrNull { it.x <= x + 0.01f } ?: cols.firstOrNull() ?: return 0f
            return target.getValue(c.time) - c.x
        }
        val y0 = l.staffTop[0]
        val headX = l.heads.associate { it.ref to it.x }
        val marks = l.marks.map { m ->
            val r = m.ref?.let(headX::get)
            when {
                r != null -> shift(m, dx(r), -y0)
                m is LineMark && m.ledger() -> shift(m, dx((m.x1 + m.x2) / 2), -y0) // its middle is the head centre
                m is LineMark -> m.copy(x1 = m.x1 + dx(m.x1), x2 = m.x2 + dx(m.x2), y1 = m.y1 - y0, y2 = m.y2 - y0)
                else -> shift(m, dx(m.x), -y0)
            }
        }
        val heads = l.heads.map { HeadBox(it.ref, it.x + dx(it.x), it.y - y0, it.staff, it.pos, it.at) }
        val st = FloatArray(l.staffTop.size) { l.staffTop[it] - y0 }
        return ScoreLayout(l.width + dx(l.width), l.height, marks, st, l.measures, heads, l.contentEnd)
    }

    /** Head centre of a note. */
    fun head(l: ScoreLayout, r: NoteRef) = l.heads.firstOrNull { it.ref == r }?.let { it.x to it.y }

    private fun ref(a: Analysis, slice: Int, v: Voice) =
        a.slices.getOrNull(slice)?.eventIndex?.get(v.ordinal)?.takeIf { it >= 0 && v in show }?.let { NoteRef(v, it) }

    /** The offending notes; an issue that names none (a missing third, a wrong progression) marks its whole chord. */
    val flagged: Set<NoteRef> = badA.issues.flatMap { i ->
        if (i.notes.isEmpty()) show.mapNotNull { v -> ref(badA, i.slice, v) } else i.notes.mapNotNull { (s, v) -> ref(badA, s, v) }
    }.toSet()

    /** Same voice, two notes: the motion. */
    class Arrow(val from: NoteRef, val to: NoteRef)
    /** Same moment, two voices: the interval between them, with its name if it matters. */
    /** [right]: on the right of the chord, because arrows arrive at it from the left. */
    class Bracket(val hi: NoteRef, val lo: NoteRef, val name: String?, val right: Boolean)

    val arrows: List<Arrow>
    val brackets: List<Bracket>
    init {
        val a = LinkedHashSet<Pair<NoteRef, NoteRef>>()
        val b = LinkedHashSet<Triple<NoteRef, NoteRef, String?>>()
        for (issue in badA.issues) {
            val ns = issue.notes.distinct().filter { it.second in show }
            for (v in ns.map { it.second }.distinct()) {
                val rs = ns.filter { it.second == v }.sortedBy { it.first }.mapNotNull { ref(badA, it.first, v) }.distinct()
                a += rs.zipWithNext()
            }
            for (s in ns.map { it.first }.distinct()) {
                val vs = ns.filter { it.first == s }.map { it.second }.sortedBy { it.ordinal }
                if (vs.size != 2) continue
                val hi = ref(badA, s, vs[0]) ?: continue
                val lo = ref(badA, s, vs[1]) ?: continue
                val name = if (rule in INTERVAL_RULES) {
                    val p = ex.bad.voice(hi.voice)[hi.index].pitch; val q = ex.bad.voice(lo.voice)[lo.index].pitch
                    if (p != null && q != null) Interval.between(q, p).name else null
                } else null
                b += Triple(hi, lo, name)
            }
        }
        arrows = a.filter { (f, t) -> head(badL, f)?.let { p -> head(badL, t)?.let { q -> hypot(q.first - p.first, q.second - p.second) > 1.5f } } == true }
            .map { (f, t) -> Arrow(f, t) }
        brackets = b.map { (hi, lo, name) -> Bracket(hi, lo, name, arrows.any { it.to == hi || it.to == lo }) }
    }

    /** Left edge of a bracket: clear of the heads and accidentals of both notes. */
    fun bracketX(br: Bracket) =
        if (br.right) listOf(br.hi, br.lo).maxOf { r -> head(badL, r)?.first ?: 0f } + 1.2f // clear of up stems
        else badL.marks.filter { it.attached() && (it.ref == br.hi || it.ref == br.lo) }.minOf { it.x } - 0.6f

    /** Same rhythm in both versions (shown voices): heads glide from the wrong place to the right one. Else crossfade. */
    val glide: Boolean = show.all { v ->
        ex.bad.voice(v).map { it.length to (it.pitch == null) } == ex.good.voice(v).map { it.length to (it.pitch == null) }
    }
    val changed: Set<NoteRef> = show.flatMap { v ->
        ex.good.voice(v).indices.filter { i -> ex.bad.voice(v).getOrNull(i)?.pitch != ex.good.voice(v)[i].pitch }.map { NoteRef(v, it) }
    }.toSet()

    /** Bounds of everything drawn, both versions and the arrows, brackets and labels. */
    val left: Float; val right: Float; val top: Float; val bottom: Float
    init {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (m in badL.marks + goodL.marks) when (m) {
            is LineMark -> { xs += m.x1; xs += m.x2; ys += m.y1; ys += m.y2 }
            is GlyphMark -> { xs += m.x; xs += m.x + 1.2f; ys += m.y - 1f; ys += m.y + 1f }
            is TextMark -> { xs += m.x - m.text.length * m.size * 0.3f; xs += m.x + m.text.length * m.size * 0.3f; ys += m.y - m.size; ys += m.y + 0.5f }
            is BeamMark -> { xs += m.x1; xs += m.x2; ys += m.y1; ys += m.y2 + m.h }
            is TieMark -> { xs += m.x1; xs += m.x2; ys += m.y - 1f; ys += m.y + 1f }
        }
        for (br in brackets) {
            val x = bracketX(br)
            val label = (br.name?.length ?: 0) * 0.8f
            xs += if (br.right) x + 0.3f + label else x - 0.3f - label
        }
        left = xs.min(); right = xs.max(); top = ys.min(); bottom = ys.max()
    }
}

private fun phase(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

/**
 * Floating card explaining [rule]: its description and a looping animation of a wrong passage being corrected.
 * Arrows show the motion, brackets the interval, and the offending notes glide to their right place.
 */
@Composable
fun RuleInfoDialog(
    rule: Rule, enabled: Boolean, onToggle: () -> Unit,
    onIgnoreHere: (() -> Unit)? = null, onIgnoreInScore: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val demo = remember(rule) { RuleExamples.ALL[rule]?.let { Demo(rule, it) } }
    Dialog(onDismissRequest = onDismiss) {
        KeepRefreshRate()
        Surface(Modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rule.label, style = MaterialTheme.typography.titleLarge)
                Text(rule.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (demo != null) DemoCanvas(demo)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Check this rule", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { onToggle() })
                }
                // Opened from an issue in check mode
                if (onIgnoreHere != null || onIgnoreInScore != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onIgnoreHere?.let { OutlinedButton(onClick = it, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ignore here", maxLines = 1) } }
                    onIgnoreInScore?.let { OutlinedButton(onClick = it, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ignore in this score", maxLines = 1) } }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

private const val LOOP_MS = 5200
// Timeline (fraction of the loop): fade in, marks draw in, marks fade, notes glide, right version, fade out
private const val FADE_IN = 0.06f
private const val GLIDE_A = 0.42f
private const val GLIDE_B = 0.6f
private const val FADE_OUT = 0.94f

@Composable
private fun DemoCanvas(d: Demo) {
    val sc = LocalScoreColors.current
    val bravura = rememberBravura()
    val paints = remember(bravura) { Paints(bravura) }
    val t by rememberInfiniteTransition(label = "demo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val right by remember { derivedStateOf { t >= GLIDE_B } }
    val ink = sc.ink.toArgb(); val err = sc.errorNote.toArgb(); val ok = sc.ok.toArgb()
    val ease = FastOutSlowInEasing
    val w = d.right - d.left
    val h = d.bottom - d.top

    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // At most 8 dp per staff space, so a one-note example stays a normal size
        val spDp = min(8.dp, maxWidth / w)
        Canvas(Modifier.size(spDp * w, spDp * h)) {
            val sp = size.width / w
            paints.ink = ink; paints.nct = sc.nct.toArgb()
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(-d.left * sp, -d.top * sp)
                val alpha = phase(t, 0f, FADE_IN) * (1f - phase(t, FADE_OUT, 1f))
                nc.saveLayerAlpha(null, (alpha * 255).toInt())
                val k = ease.transform(phase(t, GLIDE_A, GLIDE_B))
                fun badColor(m: Mark) = if (m.ref in d.flagged) err else null
                fun goodColor(m: Mark) = if (m.ref in d.changed) ok else null
                when {
                    k == 0f -> drawMarks(nc, d.badL.marks, sp, paints, colorOf = ::badColor)
                    k == 1f -> drawMarks(nc, d.goodL.marks, sp, paints, colorOf = ::goodColor)
                    d.glide -> glideFrame(nc, d, k, sp, paints, ink, err, ok)
                    else -> {
                        layer(nc, 1 - k) { drawMarks(nc, d.badL.marks, sp, paints, colorOf = ::badColor) }
                        layer(nc, k) { drawMarks(nc, d.goodL.marks, sp, paints, colorOf = ::goodColor) }
                    }
                }
                // Arrows and brackets on the wrong version, gone once the notes move
                val draw = phase(t, 0.08f, 0.28f)
                val markAlpha = draw * (1f - phase(t, 0.36f, 0.46f))
                if (markAlpha > 0f) overlay(nc, d, draw, (err and 0xFFFFFF) or ((markAlpha * 255).toInt() shl 24), sp, paints)
                nc.restore()
                nc.restore()
            }
        }
    }
    Text(if (right) "✓ Right" else "✗ Wrong", fontWeight = FontWeight.Bold, color = if (right) sc.ok else sc.errorText,
        style = MaterialTheme.typography.labelLarge)
    // Always takes two lines so the card does not jump between phases
    Text(if (right) " " else d.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2, maxLines = 2, modifier = Modifier.heightIn(min = 32.dp))
}

private inline fun layer(nc: android.graphics.Canvas, alpha: Float, block: () -> Unit) {
    nc.saveLayerAlpha(null, (alpha * 255).toInt())
    block()
    nc.restore()
}

/**
 * Between the two versions: staff and barlines of the right version; every head (with accidental and dots) on its way
 * from its wrong to its right place, with ledger lines where it is now. Stems, flags and beams travel with their notes
 * (paired by note and order); what has no partner, and the text, crossfades.
 */
private fun glideFrame(nc: android.graphics.Canvas, d: Demo, k: Float, sp: Float, p: Paints, ink: Int, err: Int, ok: Int) {
    drawMarks(nc, d.goodL.marks.filter { it.frame() }, sp, p) { null }
    fun rest(l: ScoreLayout) = l.marks.filter { !it.frame() && !it.ledger() && !it.attached() }
    fun byNote(l: ScoreLayout) = rest(l).filter { it.ref != null }.groupBy { it.ref to it::class }
    val badBy = byNote(d.badL); val goodBy = byNote(d.goodL)
    val travelling = ArrayList<Mark>(); val pairedGood = HashSet<Mark>(); val fadeOut = ArrayList<Mark>()
    for ((key, ms) in badBy) for ((i, m) in ms.withIndex()) {
        val g = goodBy[key]?.getOrNull(i)
        val t = g?.let { lerp(m, it, k) }
        if (t != null) { travelling += t; pairedGood += g } else fadeOut += m
    }
    fun color(m: Mark): Int? {
        val from = if (m.ref in d.flagged) err else ink
        return when (m.ref) { in d.changed -> blend(from, ok, k); in d.flagged -> blend(err, ink, k); else -> null }
    }
    // Numerals (in order, when both versions have the same number) slide to their new place while they crossfade
    val badText = rest(d.badL).filterIsInstance<TextMark>(); val goodText = rest(d.goodL).filterIsInstance<TextMark>()
    val slide = badText.size == goodText.size
    fun at(t: TextMark, i: Int) = if (slide) t.copy(x = mix(badText[i].x, goodText[i].x, k), y = mix(badText[i].y, goodText[i].y, k)) else t
    layer(nc, 1 - k) { drawMarks(nc, fadeOut + badText.mapIndexed { i, t -> at(t, i) }, sp, p) { m -> if (m.ref in d.flagged) err else null } }
    layer(nc, k) {
        drawMarks(nc, rest(d.goodL).filter { it !in pairedGood && it !is TextMark } + goodText.mapIndexed { i, t -> at(t, i) }, sp, p) { m ->
            if (m.ref in d.changed) ok else null
        }
    }
    drawMarks(nc, travelling, sp, p, colorOf = ::color)
    val now = HashMap<NoteRef, Pair<Float, Float>>()
    val moved = d.badL.marks.filter { it.attached() }.map { m ->
        val r = m.ref!!
        val a = d.head(d.badL, r); val b = d.head(d.goodL, r)
        if (a == null || b == null) m else {
            now[r] = (a.first + (b.first - a.first) * k) to (a.second + (b.second - a.second) * k)
            shift(m, (b.first - a.first) * k, (b.second - a.second) * k)
        }
    }
    drawMarks(nc, moved, sp, p, colorOf = ::color)
    // Ledger lines of each head at its current height
    p.line.color = ink; p.line.strokeWidth = Smufl.LEDGER * sp
    for (hb in d.badL.heads) {
        val (cx, y) = now[hb.ref] ?: continue
        val glyph = d.badL.marks.firstOrNull { it.ref == hb.ref && (it as? GlyphMark)?.c in HEADS }
        val half = glyph?.let { hb.x - it.x } ?: (Smufl.HEAD_W / 2)
        val st = d.badL.staffTop[hb.staff]
        val x1 = (cx - half - Smufl.LEDGER_EXT) * sp; val x2 = (cx + half + Smufl.LEDGER_EXT) * sp
        var ly = st - 1f
        while (ly >= y - 0.01f) { nc.drawLine(x1, ly * sp, x2, ly * sp, p.line); ly -= 1f }
        ly = st + 5f
        while (ly <= y + 0.01f) { nc.drawLine(x1, ly * sp, x2, ly * sp, p.line); ly += 1f }
    }
}

private val HEADS = setOf(Smufl.HEAD_BLACK, Smufl.HEAD_HALF, Smufl.HEAD_WHOLE)

/** Arrows (motion) and brackets (interval) on the wrong version, growing with [draw]. */
private fun overlay(nc: android.graphics.Canvas, d: Demo, draw: Float, col: Int, sp: Float, p: Paints) {
    p.line.color = col; p.fill.color = col
    p.line.strokeWidth = 0.28f * sp
    p.line.strokeCap = Paint.Cap.ROUND
    for (ar in d.arrows) {
        val (x1, y1) = d.head(d.badL, ar.from) ?: continue
        val (x2, y2) = d.head(d.badL, ar.to) ?: continue
        // Head centre to head centre, starting and stopping a little outside each (elliptic) head
        val ang = atan2(y2 - y1, x2 - x1)
        val c = cos(ang); val s = sin(ang)
        val gap = 0.35f + 1f / sqrt((c / 0.65f) * (c / 0.65f) + (s / 0.5f) * (s / 0.5f))
        val sx = x1 + gap * c; val sy = y1 + gap * s
        val ex = x2 - gap * c; val ey = y2 - gap * s
        val px = sx + (ex - sx) * draw; val py = sy + (ey - sy) * draw
        // The shaft stops inside the arrowhead so its round cap does not poke past the tip
        val back = if (draw > 0.9f) 0.5f else 0f
        nc.drawLine(sx * sp, sy * sp, (px - back * c) * sp, (py - back * s) * sp, p.line)
        if (draw > 0.9f) arrowHead(nc, px * sp, py * sp, ang, 0.8f * sp, p)
    }
    for (br in d.brackets) {
        val (_, yh) = d.head(d.badL, br.hi) ?: continue
        val (_, yl) = d.head(d.badL, br.lo) ?: continue
        val x = d.bracketX(br)
        val y2 = yh + (yl - yh) * draw
        nc.drawLine(x * sp, yh * sp, x * sp, y2 * sp, p.line)
        val tick = if (br.right) -0.4f else 0.4f // ticks point at the notes
        nc.drawLine(x * sp, yh * sp, (x + tick) * sp, yh * sp, p.line)
        if (draw >= 1f) {
            nc.drawLine(x * sp, yl * sp, (x + tick) * sp, yl * sp, p.line)
            if (br.name != null) {
                p.text.color = col; p.text.typeface = p.bold
                p.text.textSize = 1.3f * sp; p.text.textAlign = if (br.right) Paint.Align.LEFT else Paint.Align.RIGHT
                nc.drawText(br.name, (x + if (br.right) 0.3f else -0.3f) * sp, ((yh + yl) / 2 + 0.45f) * sp, p.text)
            }
        }
    }
    p.line.strokeCap = Paint.Cap.BUTT
}

private fun arrowHead(nc: android.graphics.Canvas, x: Float, y: Float, ang: Float, len: Float, p: Paints) {
    val path = p.path
    path.reset()
    path.moveTo(x, y)
    path.lineTo(x - len * cos(ang - 0.45f), y - len * sin(ang - 0.45f))
    path.lineTo(x - len * cos(ang + 0.45f), y - len * sin(ang + 0.45f))
    path.close()
    nc.drawPath(path, p.fill)
}

private fun blend(a: Int, b: Int, k: Float): Int = Color(a).let { ca ->
    val cb = Color(b)
    Color(ca.red + (cb.red - ca.red) * k, ca.green + (cb.green - ca.green) * k, ca.blue + (cb.blue - ca.blue) * k, ca.alpha).toArgb()
}

private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

/** [a] on its way to [b], or null when they are not the same kind of mark. */
private fun lerp(a: Mark, b: Mark, k: Float): Mark? = when {
    // A stem that changes direction keeps its length: it rides on its head (the y1 end) and flips halfway
    a is LineMark && b is LineMark && (a.y2 < a.y1) != (b.y2 < b.y1) -> {
        val x = mix(a.x1, b.x1, k); val y = mix(a.y1, b.y1, k)
        val s = if (k < 0.5f) a else b
        s.copy(x1 = x, y1 = y, x2 = s.x2 - s.x1 + x, y2 = s.y2 - s.y1 + y)
    }
    a is LineMark && b is LineMark -> a.copy(x1 = mix(a.x1, b.x1, k), y1 = mix(a.y1, b.y1, k), x2 = mix(a.x2, b.x2, k), y2 = mix(a.y2, b.y2, k))
    a is GlyphMark && b is GlyphMark && a.c == b.c -> a.copy(x = mix(a.x, b.x, k), y = mix(a.y, b.y, k))
    a is BeamMark && b is BeamMark -> a.copy(x1 = mix(a.x1, b.x1, k), y1 = mix(a.y1, b.y1, k), x2 = mix(a.x2, b.x2, k), y2 = mix(a.y2, b.y2, k))
    a is TieMark && b is TieMark && a.above == b.above -> a.copy(x1 = mix(a.x1, b.x1, k), x2 = mix(a.x2, b.x2, k), y = mix(a.y, b.y, k))
    else -> null
}

private fun shift(m: Mark, dx: Float, dy: Float): Mark = when (m) {
    is GlyphMark -> m.copy(x = m.x + dx, y = m.y + dy)
    is LineMark -> m.copy(x1 = m.x1 + dx, x2 = m.x2 + dx, y1 = m.y1 + dy, y2 = m.y2 + dy)
    is BeamMark -> m.copy(x1 = m.x1 + dx, x2 = m.x2 + dx, y1 = m.y1 + dy, y2 = m.y2 + dy)
    is TieMark -> m.copy(x1 = m.x1 + dx, x2 = m.x2 + dx, y = m.y + dy)
    is TextMark -> m.copy(x = m.x + dx, y = m.y + dy)
}
