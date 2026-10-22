package com.grothgarvc.fourpartwriting.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.grothgarvc.fourpartwriting.R
import com.grothgarvc.fourpartwriting.UiState
import com.grothgarvc.fourpartwriting.engrave.BeamMark
import com.grothgarvc.fourpartwriting.engrave.chordLabels
import com.grothgarvc.fourpartwriting.engrave.Engraver
import com.grothgarvc.fourpartwriting.engrave.GlyphMark
import com.grothgarvc.fourpartwriting.engrave.Hit
import com.grothgarvc.fourpartwriting.engrave.LineMark
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.engrave.Smufl
import com.grothgarvc.fourpartwriting.engrave.TextMark
import com.grothgarvc.fourpartwriting.engrave.TextStyle
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Severity
import kotlin.math.hypot

private const val INK = 0xFF161616.toInt()
private const val SELECT = 0xFF1565C0.toInt()
private const val ERROR = 0xFFD32F2F.toInt()
private const val WARN = 0xFFE67700.toInt()
private const val PLAY = 0xFF2E7D32.toInt()

/** Bravura typeface, loaded once. */
@Composable
fun rememberBravura(): Typeface {
    val ctx = LocalContext.current
    return remember { ResourcesCompat.getFont(ctx, R.font.bravura)!! }
}

@Composable
fun ScoreCanvas(
    state: UiState,
    onTap: (Hit) -> Unit,
    onNudge: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bravura = rememberBravura()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spPx = with(density) { 7.dp.toPx() }
        val widthSp = constraints.maxWidth / spPx
        val analysis = state.analysis
        val labels = remember(analysis) { analysis?.let(::chordLabels) ?: emptyMap() }
        val layout = remember(state.score, widthSp, labels) { Engraver.layout(state.score, widthSp, labels) }

        // Highlight colours per note
        val colors = remember(analysis, state.focusedIssue, state.selected, state.playTime) {
            val m = HashMap<NoteRef, Int>()
            if (analysis != null) {
                fun refs(i: Int) = analysis.issues[i].notes.mapNotNull { (si, v) ->
                    analysis.slices.getOrNull(si)?.eventIndex?.get(v.ordinal)?.takeIf { it >= 0 }?.let { NoteRef(v, it) }
                }
                val shown = state.focusedIssue?.let { listOf(it) } ?: analysis.issues.indices.sortedBy { analysis.issues[it].severity == Severity.ERROR }
                for (i in shown) for (r in refs(i)) m[r] = if (analysis.issues[i].severity == Severity.ERROR) ERROR else WARN
            }
            state.playTime?.let { t ->
                state.score.slices().firstOrNull { it.time == t }?.let { sl ->
                    for (v in Voice.entries) sl.eventIndex[v.ordinal].takeIf { it >= 0 }?.let { m[NoteRef(v, it)] = PLAY }
                }
            }
            state.selected?.let { m[it] = SELECT }
            m
        }
        val selectedHead = remember(layout, state.selected) {
            state.selected?.let { sel ->
                layout.marks.firstOrNull { it is GlyphMark && it.ref == sel && it.c in charArrayOf(Smufl.HEAD_BLACK, Smufl.HEAD_HALF, Smufl.HEAD_WHOLE) }
                    ?.let { (it as GlyphMark).let { g -> Offset((g.x + 0.6f) * spPx, g.y * spPx) } }
            }
        }
        val paints = remember(bravura) { Paints(bravura) }
        val tap by rememberUpdatedState(onTap)
        val nudge by rememberUpdatedState(onNudge)
        val heightDp = with(density) { (layout.height * spPx).toDp() }

        Canvas(
            Modifier
                .size(maxWidth, heightDp)
                .pointerInput(layout) {
                    detectTapGestures { o -> layout.hitTest(o.x / spPx, o.y / spPx)?.let(tap) }
                }
                .pointerInput(selectedHead) {
                    // Drag the selected note up/down; any other drag scrolls the page.
                    val head = selectedHead ?: return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (hypot(down.position.x - head.x, down.position.y - head.y) > 3f * spPx) return@awaitEachGesture
                        var acc = 0f
                        val step = spPx / 2
                        drag(down.id) { ch ->
                            acc += ch.positionChange().y
                            ch.consume()
                            while (acc > step) { nudge(-1); acc -= step }
                            while (acc < -step) { nudge(1); acc += step }
                        }
                    }
                }
        ) {
            drawRect(androidx.compose.ui.graphics.Color.White)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                // Playback cursor
                state.playTime?.let { t ->
                    layout.locate(t)?.let { (sys, x) ->
                        paints.fill.color = 0x302E7D32
                        nc.drawRect((x - 0.5f) * spPx, (sys.staffTop[0] - 1.5f) * spPx, (x + 1.7f) * spPx, (sys.staffTop[1] + 5.5f) * spPx, paints.fill)
                    }
                }
                for (mk in layout.marks) {
                    val color = mk.ref?.let { colors[it] } ?: INK
                    when (mk) {
                        is LineMark -> {
                            paints.line.color = color
                            paints.line.strokeWidth = mk.width * spPx
                            nc.drawLine(mk.x1 * spPx, mk.y1 * spPx, mk.x2 * spPx, mk.y2 * spPx, paints.line)
                        }
                        is GlyphMark -> {
                            paints.glyph.color = color
                            paints.glyph.textSize = 4f * spPx * mk.scale
                            nc.drawText(mk.c.toString(), mk.x * spPx, mk.y * spPx, paints.glyph)
                        }
                        is BeamMark -> {
                            paints.fill.color = color
                            val p = paints.path
                            p.reset()
                            p.moveTo(mk.x1 * spPx, mk.y1 * spPx); p.lineTo(mk.x2 * spPx, mk.y2 * spPx)
                            p.lineTo(mk.x2 * spPx, (mk.y2 + mk.h) * spPx); p.lineTo(mk.x1 * spPx, (mk.y1 + mk.h) * spPx)
                            p.close()
                            nc.drawPath(p, paints.fill)
                        }
                        is TextMark -> drawText(nc, mk, spPx, paints)
                    }
                }
            }
        }
    }
}

private class Paints(bravura: Typeface) {
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val path = Path()
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
    val roman: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val italic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
}

private const val SUPERS = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺"
private const val SUBS = "₀₁₂₃₄₅₆₇₈₉"

private fun drawText(nc: android.graphics.Canvas, t: TextMark, sp: Float, p: Paints) {
    val paint = p.text
    paint.color = INK
    paint.textSize = t.size * sp
    when (t.style) {
        TextStyle.TEMPO -> { paint.typeface = p.bold; paint.textAlign = Paint.Align.LEFT; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.MEASURE_NO -> { paint.typeface = p.italic; paint.textAlign = Paint.Align.LEFT; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.SYMBOL -> { paint.typeface = p.bold; paint.textAlign = Paint.Align.CENTER; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.ROMAN -> drawRoman(nc, t, sp, p)
    }
}

/** Roman numeral with figured-bass digits stacked (e.g. V⁶₅/V). */
private fun drawRoman(nc: android.graphics.Canvas, t: TextMark, sp: Float, p: Paints) {
    val big = p.text
    big.typeface = p.roman
    big.textAlign = Paint.Align.LEFT
    val size = t.size * sp
    val small = size * 0.62f
    // Split into runs: plain text, or a figure (superscript run + optional subscript run)
    data class Run(val text: String, val top: String = "", val bottom: String = "", val fig: Boolean = false)
    val runs = mutableListOf<Run>()
    var i = 0
    val s = t.text
    while (i < s.length) {
        if (s[i] in SUPERS || s[i] in SUBS) {
            var top = ""; var bot = ""
            while (i < s.length && s[i] in SUPERS) { top += SUPERS.indexOf(s[i]).let { if (it == 10) '+' else '0' + it }; i++ }
            while (i < s.length && s[i] in SUBS) { bot += '0' + SUBS.indexOf(s[i]); i++ }
            runs += Run("", top, bot, true)
        } else {
            val st = i
            while (i < s.length && s[i] !in SUPERS && s[i] !in SUBS) i++
            runs += Run(s.substring(st, i))
        }
    }
    fun width(r: Run): Float {
        if (!r.fig) { big.textSize = size; return big.measureText(r.text) }
        big.textSize = small
        return maxOf(big.measureText(r.top), big.measureText(r.bottom)) + 0.1f * sp
    }
    val total = runs.sumOf { width(it).toDouble() }.toFloat()
    var x = t.x * sp - total / 2
    val y = t.y * sp
    for (r in runs) {
        val w = width(r)
        if (!r.fig) { big.textSize = size; nc.drawText(r.text, x, y, big) }
        else {
            big.textSize = small
            if (r.bottom.isEmpty()) nc.drawText(r.top, x, y - size * 0.45f, big)
            else { nc.drawText(r.top, x, y - size * 0.55f, big); nc.drawText(r.bottom, x, y + size * 0.05f, big) }
        }
        x += w
    }
}

/** A single SMuFL glyph centred in a box, for palette buttons. */
@Composable
fun GlyphIcon(c: Char, sizeDp: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val bravura = rememberBravura()
    val paint = remember(bravura) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura } }
    val bounds = remember { Rect() }
    Canvas(modifier.size(sizeDp.dp).height(sizeDp.dp)) {
        paint.textSize = size.height * 0.62f
        paint.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        val str = c.toString()
        paint.getTextBounds(str, 0, 1, bounds)
        drawIntoCanvas {
            it.nativeCanvas.drawText(str, size.width / 2 - bounds.exactCenterX(), size.height / 2 - bounds.exactCenterY(), paint)
        }
    }
}
