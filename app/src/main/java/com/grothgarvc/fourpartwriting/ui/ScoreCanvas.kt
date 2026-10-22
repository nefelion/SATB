package com.grothgarvc.fourpartwriting.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.grothgarvc.fourpartwriting.R
import com.grothgarvc.fourpartwriting.UiState
import com.grothgarvc.fourpartwriting.engrave.BeamMark
import com.grothgarvc.fourpartwriting.engrave.Engraver
import com.grothgarvc.fourpartwriting.engrave.GlyphMark
import com.grothgarvc.fourpartwriting.engrave.Hit
import com.grothgarvc.fourpartwriting.engrave.LineMark
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.engrave.TextMark
import com.grothgarvc.fourpartwriting.engrave.TextStyle
import com.grothgarvc.fourpartwriting.engrave.TieMark
import com.grothgarvc.fourpartwriting.engrave.chordLabels
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Severity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val INK = 0xFF161616.toInt()
private const val SELECT = 0xFF1565C0.toInt()
private const val ERROR = 0xFFD32F2F.toInt()
private const val WARN = 0xFFE67700.toInt()
private const val PLAY = 0xFF2E7D32.toInt()

/** Voice colours, used on the voice buttons and the insertion caret. */
val VOICE_COLORS = listOf(Color(0xFFC62828), Color(0xFFEF6C00), Color(0xFF2E7D32), Color(0xFF1565C0))

/** Bravura typeface, loaded once. */
@Composable
fun rememberBravura(): Typeface {
    val ctx = LocalContext.current
    return remember { ResourcesCompat.getFont(ctx, R.font.bravura)!! }
}

/**
 * The whole score on one line, zoomed so the staves fill the height.
 * One finger: tap = select/add/deselect; vertical drag with a note selected = change its pitch;
 * any other drag = move the view. Two fingers: pinch to zoom and move.
 */
@Composable
fun ScoreCanvas(
    state: UiState,
    onTap: (Hit) -> Unit,
    onNudge: (Int) -> Unit,
    onDrag: (Boolean?) -> Unit,
    onTempoTap: () -> Unit,
    savedViewport: Pair<Float, Float>?,
    onViewport: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bravura = rememberBravura()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val landscape = viewW > viewH
        val analysis = state.analysis
        val labels = remember(analysis) { analysis?.let(::chordLabels) ?: emptyMap() }
        val nctLabels = remember(analysis) { analysis?.ncts?.mapValues { it.value.label } ?: emptyMap() }
        val layout = remember(state.score, labels, nctLabels) { Engraver.layout(state.score, labels, nctLabels) }

        val minSp = with(density) { 4.dp.toPx() }
        val maxSp = with(density) { 30.dp.toPx() }
        // Zoom/offset survive panels opening (view height changes); they reset to "fit" only for a new
        // arrangement or orientation. The saved viewport is used once, on start.
        val usedSaved = remember { booleanArrayOf(false) }
        var sp by remember(state.score.arrangement, landscape) {
            val saved = savedViewport?.takeIf { !usedSaved[0] }
            mutableFloatStateOf(saved?.first?.coerceIn(minSp, maxSp)
                ?: (viewH * 0.92f / layout.height).coerceIn(with(density) { 6.dp.toPx() }, with(density) { 19.dp.toPx() }))
        }
        var offX by remember(state.score.arrangement, landscape) {
            mutableFloatStateOf(savedViewport?.takeIf { !usedSaved[0] }?.second ?: 0f).also { usedSaved[0] = true }
        }
        var offY by remember(state.score.arrangement, landscape) { mutableFloatStateOf(0f) }
        val currentLayout by rememberUpdatedState(layout)
        val vw by rememberUpdatedState(viewW)
        val vh by rememberUpdatedState(viewH)
        fun clampOffsets() {
            val l = currentLayout
            val cw = l.width * sp; val ch = l.height * sp
            offX = if (cw <= vw) 0f else offX.coerceIn(vw - cw, 0f)
            offY = if (ch <= vh) (vh - ch) / 2 else offY.coerceIn(vh - ch, 0f)
        }
        SideEffect { clampOffsets() }
        LaunchedEffect(Unit) { snapshotFlow { sp to offX }.collect { (a, b) -> onViewport(a, b) } }

        // Highlight colours per note
        val colors = remember(analysis, state.focusedIssue, state.selected, state.playTime, state.score) {
            val m = HashMap<NoteRef, Int>()
            if (analysis != null) {
                fun refs(i: Int) = analysis.issues[i].notes.mapNotNull { (si, v) ->
                    analysis.slices.getOrNull(si)?.eventIndex?.get(v.ordinal)?.takeIf { it >= 0 }?.let { NoteRef(v, it) }
                }
                val shown = state.focusedIssue?.let { listOf(it) } ?: analysis.issues.indices.sortedBy { analysis.issues[it].severity == Severity.ERROR }
                for (i in shown) for (r in refs(i)) m[r] = if (analysis.issues[i].severity == Severity.ERROR) ERROR else WARN
            }
            state.playTime?.let { t ->
                for (v in Voice.entries) state.score.eventAt(v, t).takeIf { it >= 0 }?.let { m[NoteRef(v, it)] = PLAY }
            }
            state.selected?.let { m[it] = SELECT }
            m
        }

        // Scroll to the note being worked on, but only when asked (edit, selection jump, issue, playback)
        val caretX = layout.xFor(state.score.length(state.voice))
        suspend fun follow(x: Float) {
            val px = x * sp + offX
            val margin = vw * 0.18f
            if (px < margin || px > vw - margin) animate(offX, vw * 0.4f - x * sp) { v, _ -> offX = v; clampOffsets() }
        }
        val firstTick = remember { intArrayOf(state.followTick) }
        LaunchedEffect(state.followTick) {
            if (state.followTick == firstTick[0]) return@LaunchedEffect
            follow(state.selected?.let { sel -> currentLayout.heads.firstOrNull { it.ref == sel }?.x } ?: caretX)
        }
        LaunchedEffect(state.focusedIssue) {
            val t = state.focusedIssue?.let { i -> analysis?.issues?.getOrNull(i)?.let { analysis.slices.getOrNull(it.slice)?.time } }
            t?.let { currentLayout.locate(it) }?.let { follow(it) }
        }
        // When Check opens (labels added, less height), zoom out just enough to show the whole system
        LaunchedEffect(analysis != null, vh) {
            val fit = vh * 0.96f / currentLayout.height
            if (analysis != null && sp > fit) animate(sp, fit.coerceAtLeast(minSp)) { v, _ -> sp = v; clampOffsets() }
        }
        LaunchedEffect(state.playTime) { state.playTime?.let { currentLayout.locate(it) }?.let { follow(it) } }

        val paints = remember(bravura) { Paints(bravura) }
        val tap by rememberUpdatedState(onTap)
        val nudge by rememberUpdatedState(onNudge)
        val drag by rememberUpdatedState(onDrag)
        val tempoTap by rememberUpdatedState(onTempoTap)
        val hasSelection by rememberUpdatedState(state.selected != null)

        Canvas(
            Modifier
                .size(maxWidth, maxHeight)
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 = undecided (maybe tap), 1 = pitch drag, 2 = pan, 3 = pinch
                        var moved = Offset.Zero
                        var acc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (mode == 0) {
                                    val x = (down.position.x - offX) / sp; val y = (down.position.y - offY) / sp
                                    val tb = currentLayout.tempoBox
                                    if (x in tb[0]..tb[2] && y in tb[1]..tb[3]) tempoTap()
                                    else tap(currentLayout.hitTest(x, y))
                                }
                                if (mode == 1) drag(true)
                                break
                            }
                            if (pressed.size >= 2) {
                                // A second finger always means pinch/pan; undo a pitch drag it interrupted
                                if (mode == 1) drag(false)
                                mode = 3
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val c = ev.calculateCentroid()
                                val newSp = (sp * zoom).coerceIn(minSp, maxSp)
                                val k = newSp / sp
                                offX = c.x - (c.x - offX) * k + pan.x
                                offY = c.y - (c.y - offY) * k + pan.y
                                sp = newSp
                                clampOffsets()
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = pressed.first()
                            if (mode == 3) { ch.consume(); continue } // one finger left after a pinch
                            val d = ch.positionChange()
                            moved += d
                            if (mode == 0) {
                                // Decide once the finger has clearly moved, and not in the first moments
                                // (a second finger may still be on its way).
                                if (moved.getDistance() < slop || ch.uptimeMillis - down.uptimeMillis < 120) continue
                                mode = if (hasSelection && abs(moved.y) > abs(moved.x) * 1.3f) 1 else 2
                                if (mode == 1) { drag(null); acc = moved.y } else { offX += moved.x; offY += moved.y }
                            } else if (mode == 1) acc += d.y
                            else { offX += d.x; offY += d.y }
                            if (mode == 1) {
                                val step = sp / 2
                                while (acc > step) { nudge(-1); acc -= step }
                                while (acc < -step) { nudge(1); acc += step }
                            } else clampOffsets()
                            ch.consume()
                        }
                    }
                }
        ) {
            drawRect(Color.White)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(offX, offY)
                // Playback cursor
                state.playTime?.let { t ->
                    layout.locate(t)?.let { x ->
                        paints.fill.color = 0x302E7D32
                        nc.drawRect((x - 0.5f) * sp, (layout.top - 1.5f) * sp, (x + 1.7f) * sp, (layout.bottom + 1.5f) * sp, paints.fill)
                    }
                }
                // Insertion caret for the active voice
                if (!state.playing) {
                    val col = VOICE_COLORS[state.voice.ordinal]
                    paints.fill.color = col.copy(alpha = 0.22f).toArgb()
                    nc.drawRect((caretX - 0.3f) * sp, (layout.top - 2f) * sp, (caretX + 1.5f) * sp, (layout.bottom + 2f) * sp, paints.fill)
                    paints.text.color = col.toArgb()
                    paints.text.textSize = 1.4f * sp
                    paints.text.typeface = paints.bold
                    paints.text.textAlign = Paint.Align.CENTER
                    nc.drawText(state.voice.label, (caretX + 0.6f) * sp, (layout.top - 2.4f) * sp, paints.text)
                }
                val visL = -offX / sp - 6f
                val visR = (-offX + vw) / sp + 6f
                // Two passes: plain marks, then coloured ones on top (a selected unison stays visible)
                for (pass in 0..1) for (mk in layout.marks) {
                    val color = mk.ref?.let { colors[it] }
                    if ((pass == 0) != (color == null)) continue
                    if (mk.x > visR) continue
                    if (mk is LineMark && max(mk.x1, mk.x2) < visL) continue
                    if (mk is TieMark && mk.x2 < visL) continue
                    if (mk !is LineMark && mk !is TieMark && mk.x < visL - 6f) continue
                    val ink = color ?: INK
                    when (mk) {
                        is LineMark -> {
                            paints.line.color = ink
                            paints.line.strokeWidth = mk.width * sp
                            nc.drawLine(mk.x1 * sp, mk.y1 * sp, mk.x2 * sp, mk.y2 * sp, paints.line)
                        }
                        is GlyphMark -> {
                            paints.glyph.color = ink
                            paints.glyph.textSize = 4f * sp * mk.scale
                            nc.drawText(mk.c.toString(), mk.x * sp, mk.y * sp, paints.glyph)
                        }
                        is BeamMark -> {
                            paints.fill.color = ink
                            val p = paints.path
                            p.reset()
                            p.moveTo(mk.x1 * sp, mk.y1 * sp); p.lineTo(mk.x2 * sp, mk.y2 * sp)
                            p.lineTo(mk.x2 * sp, (mk.y2 + mk.h) * sp); p.lineTo(mk.x1 * sp, (mk.y1 + mk.h) * sp)
                            p.close()
                            nc.drawPath(p, paints.fill)
                        }
                        is TieMark -> {
                            paints.fill.color = ink
                            nc.drawPath(tiePath(mk, sp, paints.path), paints.fill)
                        }
                        is TextMark -> drawText(nc, mk, sp, paints)
                    }
                }
                nc.restore()
            }
        }
    }
}

/** Lens-shaped tie: thin ends (0.1 sp), thicker middle (0.22 sp). */
private fun tiePath(t: TieMark, sp: Float, p: Path): Path {
    val w = t.x2 - t.x1
    val s = if (t.above) -1f else 1f
    val h = min(0.25f + 0.06f * w, 0.9f)
    val th = 0.22f
    p.reset()
    p.moveTo(t.x1 * sp, t.y * sp)
    p.cubicTo((t.x1 + w * 0.25f) * sp, (t.y + s * h * 1.33f) * sp, (t.x2 - w * 0.25f) * sp, (t.y + s * h * 1.33f) * sp, t.x2 * sp, t.y * sp)
    p.cubicTo((t.x2 - w * 0.25f) * sp, (t.y + s * (h - th) * 1.33f) * sp, (t.x1 + w * 0.25f) * sp, (t.y + s * (h - th) * 1.33f) * sp, t.x1 * sp, t.y * sp)
    p.close()
    return p
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
        TextStyle.NCT -> { paint.typeface = p.italic; paint.textAlign = Paint.Align.LEFT; paint.color = 0xFF5E35B1.toInt(); nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
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

/** A single SMuFL glyph centred in a box, for toolbar buttons. */
@Composable
fun GlyphIcon(c: Char, sizeDp: Int, color: Color, modifier: Modifier = Modifier) {
    val bravura = rememberBravura()
    val paint = remember(bravura) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura } }
    val bounds = remember { Rect() }
    Canvas(modifier.size(sizeDp.dp)) {
        paint.textSize = size.height * 0.62f
        paint.color = color.toArgb()
        val str = c.toString()
        paint.getTextBounds(str, 0, 1, bounds)
        drawIntoCanvas {
            it.nativeCanvas.drawText(str, size.width / 2 - bounds.exactCenterX(), size.height / 2 - bounds.exactCenterY(), paint)
        }
    }
}
