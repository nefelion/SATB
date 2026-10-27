package com.nefelion.fourpartwriting.ui

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
import androidx.compose.material3.MaterialTheme
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.nefelion.fourpartwriting.R
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.engrave.BeamMark
import com.nefelion.fourpartwriting.engrave.Engraver
import com.nefelion.fourpartwriting.engrave.GlyphMark
import com.nefelion.fourpartwriting.engrave.Hit
import com.nefelion.fourpartwriting.engrave.LineMark
import com.nefelion.fourpartwriting.engrave.Mark
import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.engrave.TextMark
import com.nefelion.fourpartwriting.engrave.TextStyle
import com.nefelion.fourpartwriting.engrave.TieMark
import com.nefelion.fourpartwriting.engrave.chordLabels
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Severity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min



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
    /** Finger down at a hit (null: the touch became a drag or pinch, or hit the tempo mark). */
    onPress: (Hit?) -> Unit,
    onNudge: (Int) -> Unit,
    onDrag: (Boolean?) -> Unit,
    onTempoTap: () -> Unit,
    /** The caret stands on the selected note (while naming its chord or figures) instead of after the voice. */
    caretAtSelection: Boolean = false,
    savedViewport: Pair<Float, Float>?,
    onViewport: (Float, Float) -> Unit,
    /** Left/right px hidden by a camera cutout or side bars: the score can scroll clear of them. */
    edges: Pair<Float, Float> = 0f to 0f,
    modifier: Modifier = Modifier,
) {
    val bravura = rememberBravura()
    val density = LocalDensity.current
    val paper = MaterialTheme.colorScheme.surface
    val sc = LocalScoreColors.current
    val ink = sc.ink.toArgb()
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
        // Everything that captures sp/offX/offY must restart with this key, or it keeps the old (dead) state
        val viewKey = state.score.arrangement to landscape
        var sp by remember(viewKey) {
            val saved = savedViewport?.takeIf { !usedSaved[0] }
            mutableFloatStateOf(saved?.first?.coerceIn(minSp, maxSp)
                // Fit the height, but keep ~40 staff spaces across so a tall portrait view still shows a bar or two
                ?: min(viewH * 0.92f / layout.height, viewW / 40f).coerceIn(with(density) { 6.dp.toPx() }, with(density) { 19.dp.toPx() }))
        }
        var offX by remember(viewKey) {
            mutableFloatStateOf(savedViewport?.takeIf { !usedSaved[0] }?.second ?: edges.first).also { usedSaved[0] = true }
        }
        var offY by remember(viewKey) { mutableFloatStateOf(0f) }
        val currentLayout by rememberUpdatedState(layout)
        val vw by rememberUpdatedState(viewW)
        val vh by rememberUpdatedState(viewH)
        val edge by rememberUpdatedState(edges)
        fun clampOffsets() {
            val l = currentLayout
            val (el, er) = edge
            val cw = l.width * sp; val ch = l.height * sp
            offX = if (cw <= vw - el - er) el else offX.coerceIn(vw - er - cw, el)
            offY = if (ch <= vh) (vh - ch) / 2 else offY.coerceIn(vh - ch, 0f)
        }
        SideEffect { clampOffsets() }
        LaunchedEffect(viewKey) { snapshotFlow { sp to offX }.collect { (a, b) -> onViewport(a, b) } }

        // Highlight colors per note
        // Focused issue: its flagged heads (one per issue note; a tied note picks the head sounding at that slice), by voice
        val flagged = remember(layout, analysis, state.focusedIssue) {
            val issue = state.focusedIssue?.let { analysis?.issues?.getOrNull(it) } ?: return@remember emptyMap()
            issue.notes.mapNotNull { (si, v) ->
                val sl = analysis!!.slices.getOrNull(si) ?: return@mapNotNull null
                val ref = sl.eventIndex[v.ordinal].takeIf { it >= 0 }?.let { NoteRef(v, it) } ?: return@mapNotNull null
                layout.heads.filter { it.ref == ref }.sortedBy { it.x }.let { hs -> hs.lastOrNull { it.at <= sl.time } ?: hs.firstOrNull() }
            }.distinct().groupBy { it.ref.voice }.mapValues { (_, hs) -> hs.sortedBy { it.x } }
        }

        val colors = remember(analysis, flagged, state.selected, state.playTime, state.score, sc, paper) {
            val play = sc.play.toArgb()
            val m = HashMap<NoteRef, Int>()
            // Rests that only fill out a bar after a voice ends (not written): light, but opaque so nothing shows through
            val filler = lerp(paper, sc.ink, 0.45f).toArgb()
            for (v in Voice.entries) m[NoteRef(v, state.score.voice(v).size)] = filler
            // An exercise's given voices are gray
            val gray = lerp(paper, sc.ink, 0.5f).toArgb()
            for (v in state.score.given) for (i in state.score.voice(v).indices) m[NoteRef(v, i)] = gray
            if (analysis != null && flagged.isNotEmpty()) {
                // Check with an issue picked: its notes stay plain ink (rings mark them), the rest fades toward paper, opaque so staff lines don't show through
                val keep = flagged.values.flatten().map { it.ref }.toSet()
                val faded = lerp(paper, sc.ink, sc.otherInk).toArgb()
                for (v in Voice.entries) for (i in state.score.voice(v).indices) NoteRef(v, i).let { if (it !in keep) m[it] = faded }
            }
            state.playTime?.let { t ->
                for (v in Voice.entries) state.score.eventAt(v, t).takeIf { it >= 0 }?.let { m[NoteRef(v, it)] = play }
            }
            state.selected?.let { m[it] = sc.voices[it.voice.ordinal].toArgb() }
            m
        }

        // A selected tied note: full color only around the tapped head, its other heads lighter
        val headRange = remember(layout, state.selected, state.selectedTime) {
            val sel = state.selected ?: return@remember null
            val hs = layout.heads.filter { it.ref == sel }.sortedBy { it.x }
            val t = state.selectedTime ?: return@remember null
            if (hs.size < 2) return@remember null
            val k = hs.indexOfLast { it.at <= t }.coerceAtLeast(0)
            val lo = if (k > 0) (hs[k - 1].x + hs[k].x) / 2 else Float.NEGATIVE_INFINITY
            val hi = if (k < hs.lastIndex) (hs[k].x + hs[k + 1].x) / 2 else Float.POSITIVE_INFINITY
            lo..hi
        }

        // Scroll to the note being worked on, but only when asked (edit, selection jump, issue, playback)
        // Over the dragged note while dragging; otherwise where the next note of the voice goes
        val dragged = state.selected?.takeIf { state.dragging }?.let { r -> state.score.onsets(r.voice).getOrNull(r.index) }
        val caretX = (if (caretAtSelection) state.selectedTime?.let(layout::xFor) else null)
            ?: layout.xFor(dragged ?: state.score.length(state.shownVoice))
        suspend fun follow(x: Float) {
            val px = x * sp + offX
            val margin = vw * 0.18f
            if (px < margin || px > vw - margin) animate(offX, vw * 0.4f - x * sp) { v, _ -> offX = v; clampOffsets() }
        }
        val firstTick = remember { intArrayOf(state.followTick) }
        LaunchedEffect(state.followTick) {
            if (state.followTick == firstTick[0]) return@LaunchedEffect
            follow(state.selected?.let { sel -> (currentLayout.heads + currentLayout.rests).firstOrNull { it.ref == sel }?.x } ?: caretX)
        }
        LaunchedEffect(state.focusedIssue) {
            val t = state.focusedIssue?.let { i -> analysis?.issues?.getOrNull(i)?.let { analysis.slices.getOrNull(it.slice)?.time } }
            t?.let { currentLayout.locate(it) }?.let { follow(it) }
        }
        // When Check opens (labels added, less height), zoom out just enough to show the whole system;
        // when it closes, go back to the zoom and position from before (sp, offX, offY; sp < 0 = none saved)
        val beforeCheck = remember(viewKey) { floatArrayOf(-1f, 0f, 0f) }
        LaunchedEffect(analysis != null, vh, viewKey) {
            if (analysis == null) return@LaunchedEffect
            if (beforeCheck[0] < 0) { beforeCheck[0] = sp; beforeCheck[1] = offX; beforeCheck[2] = offY }
            val fit = vh * 0.96f / currentLayout.height
            if (sp > fit) animate(sp, fit.coerceAtLeast(minSp)) { v, _ -> sp = v; clampOffsets() }
        }
        // Not keyed on vh: the issues strip closing changes the height and must not cancel this
        LaunchedEffect(analysis == null, viewKey) {
            if (analysis != null || beforeCheck[0] < 0) return@LaunchedEffect
            val (s0, x0, y0) = beforeCheck.copyOf().also { beforeCheck[0] = -1f }
            val s1 = sp; val x1 = offX; val y1 = offY
            animate(0f, 1f) { t, _ ->
                sp = s1 + (s0 - s1) * t; offX = x1 + (x0 - x1) * t; offY = y1 + (y0 - y1) * t
                clampOffsets()
            }
        }
        LaunchedEffect(state.playTime) { state.playTime?.let { currentLayout.locate(it) }?.let { follow(it) } }

        val paints = remember(bravura) { Paints(bravura) }
        val tap by rememberUpdatedState(onTap)
        val press by rememberUpdatedState(onPress)
        val nudge by rememberUpdatedState(onNudge)
        val drag by rememberUpdatedState(onDrag)
        val tempoTap by rememberUpdatedState(onTempoTap)
        val hasSelection by rememberUpdatedState(state.selected != null)

        Canvas(
            Modifier
                .size(maxWidth, maxHeight)
                .pointerInput(viewKey) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var mode = 0 // 0 = undecided (maybe tap), 1 = pitch drag, 2 = pan, 3 = pinch
                        val dx = (down.position.x - offX) / sp; val dy = (down.position.y - offY) / sp
                        val tb = currentLayout.tempoBox
                        val hb = currentLayout.headerBox
                        // Tempo, clef, key or time signature: opens the score settings
                        val onTempo = (dx in tb[0]..tb[2] && dy in tb[1]..tb[3]) || (dx in hb[0]..hb[2] && dy in hb[1]..hb[3])
                        val downHit = if (onTempo) null else currentLayout.hitTest(dx, dy)
                        press(downHit)
                        var moved = Offset.Zero
                        var acc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (mode == 0) {
                                    if (downHit == null) tempoTap() else tap(downHit)
                                }
                                if (mode == 1) drag(true)
                                break
                            }
                            if (pressed.size >= 2) {
                                // A second finger always means pinch/pan; undo a pitch drag it interrupted
                                if (mode == 1) drag(false)
                                if (mode == 0) press(null)
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
                                press(null)
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
            drawRect(paper)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(offX, offY)
                // Playback cursor
                state.playTime?.let { t ->
                    layout.locate(t)?.let { x ->
                        paints.fill.color = sc.play.copy(alpha = 0.19f).toArgb()
                        nc.drawRect((x - 0.5f) * sp, (layout.noteTop - 1.5f) * sp, (x + 1.7f) * sp, (layout.noteBottom + 1.5f) * sp, paints.fill)
                    }
                }
                // Insertion caret for the active voice (none in movement mode)
                if (!state.playing && !state.moving) {
                    val col = sc.voices[state.shownVoice.ordinal]
                    paints.fill.color = col.copy(alpha = sc.caretAlpha).toArgb()
                    // From above the highest note to below the lowest, so the voice letter never sits under a note
                    val cTop = min(layout.top - 2f, layout.noteTop - 1f)
                    nc.drawRect((caretX - 0.3f) * sp, cTop * sp, (caretX + 1.5f) * sp, max(layout.bottom + 2f, layout.noteBottom + 1f) * sp, paints.fill)
                    paints.text.color = col.toArgb()
                    paints.text.textSize = 1.4f * sp
                    paints.text.typeface = paints.bold
                    paints.text.textAlign = Paint.Align.CENTER
                    nc.drawText(state.shownVoice.label, (caretX + 0.6f) * sp, (cTop - 0.4f) * sp, paints.text)
                }
                // The note a tap here would add, while the finger is down
                // The note a tap here would add, while the finger is down; hint notes until the next edit
                val ghosts = listOfNotNull(state.ghost) + (state.hint?.takeIf { it.score == state.score }?.notes ?: emptyList())
                for (g in ghosts) {
                    paints.glyph.color = sc.voices[(g.voice ?: state.shownVoice).ordinal].copy(alpha = 0.55f).toArgb()
                    paints.glyph.textSize = 4f * sp
                    val gy = layout.staffTop[g.staff] + 4 - g.pos / 2f
                    val gx = layout.xFor(g.at)
                    nc.drawText(Smufl.HEAD_BLACK.toString(), gx * sp, gy * sp, paints.glyph)
                    g.alter?.let { a -> nc.drawText(Smufl.accidental(a).toString(), (gx - Smufl.accidentalWidth(a) - 0.25f) * sp, gy * sp, paints.glyph) }
                    paints.line.color = paints.glyph.color
                    paints.line.strokeWidth = Smufl.LEDGER * sp
                    // Ledger lines above or below the staff
                    val top = layout.staffTop[g.staff]
                    var p = if (g.pos > 8) 10 else -2
                    while ((g.pos > 8 && p <= g.pos) || (g.pos < 0 && p >= g.pos)) {
                        val ly = (top + 4 - p / 2f) * sp
                        nc.drawLine((gx - Smufl.LEDGER_EXT) * sp, ly, (gx + Smufl.HEAD_W + Smufl.LEDGER_EXT) * sp, ly, paints.line)
                        p += if (g.pos > 8) 2 else -2
                    }
                }
                val visL = -offX / sp - 6f
                val visR = (-offX + vw) / sp + 6f
                paints.ink = ink; paints.nct = sc.nct.toArgb()
                val focused = state.focusedIssue?.let { analysis?.issues?.getOrNull(it) }
                if (focused != null && flagged.isNotEmpty()) {
                    // Soft halo behind each flagged head and a band joining each voice's heads (parallels read as parallel bands).
                    // Drawn opaque into one layer, then blended once, so overlaps don't darken.
                    nc.saveLayerAlpha(-offX, -offY, -offX + vw, -offY + vh, 0x50) // bounds = the visible view
                    val pr = paints.ring
                    pr.color = (if (focused.severity == Severity.ERROR) sc.errorNote else sc.warnNote).toArgb()
                    for (hs in flagged.values) {
                        pr.style = Paint.Style.FILL
                        for (h in hs) nc.drawOval((h.x - 1.05f) * sp, (h.y - 0.85f) * sp, (h.x + 1.05f) * sp, (h.y + 0.85f) * sp, pr)
                        pr.style = Paint.Style.STROKE
                        pr.strokeWidth = 0.6f * sp
                        for ((h1, h2) in hs.zipWithNext()) nc.drawLine(h1.x * sp, h1.y * sp, h2.x * sp, h2.y * sp, pr)
                    }
                    nc.restore()
                }
                drawMarks(nc, layout.marks, sp, paints, visL, visR) { mk ->
                    val color = mk.ref?.let { colors[it] }
                    when {
                        color == null -> null
                        headRange != null && mk.ref == state.selected && mk.x !in headRange -> (color and 0xFFFFFF) or (0x59 shl 24)
                        else -> color
                    }
                }
                analysis?.let { a ->
                    if (focused == null) {
                        // Nothing picked: a small tick above the system where each problem is, errors drawn last
                        // Above the chord names (SYMBOL text, baseline y) so they never overlap
                        val y = layout.marks.filter { it is TextMark && it.style == TextStyle.SYMBOL }
                            .minOfOrNull { (it as TextMark).let { t -> t.y - t.size } } ?.minus(0.4f)
                            ?: (min(layout.top, layout.noteTop) - 1f)
                        for (issue in a.issues.sortedBy { it.severity == Severity.ERROR }) {
                            val x = a.slices.getOrNull(issue.slice)?.time?.let { layout.locate(it) } ?: continue
                            val cx = x + Smufl.HEAD_W / 2
                            val pt = paints.tick
                            pt.color = (if (issue.severity == Severity.ERROR) sc.errorNote else sc.warnNote).toArgb()
                            pt.strokeWidth = 0.25f * sp // round-joined stroke = rounded corners
                            val p = paints.path
                            p.reset()
                            p.moveTo((cx - 0.45f) * sp, (y - 0.85f) * sp); p.lineTo((cx + 0.45f) * sp, (y - 0.85f) * sp); p.lineTo(cx * sp, (y - 0.15f) * sp)
                            p.close()
                            nc.drawPath(p, pt)
                        }
                    }
                }
                nc.restore()
            }
        }
    }
}

/**
 * Draws engraved [marks] at [sp] px per staff space. [colorOf] gives a mark's color, or null for plain ink.
 * Two passes: plain marks, then colored ones on top (a selected unison stays visible).
 * Marks outside [visL]..[visR] (staff spaces) are skipped.
 */
internal fun drawMarks(
    nc: android.graphics.Canvas, marks: List<Mark>, sp: Float, paints: Paints,
    visL: Float = Float.NEGATIVE_INFINITY, visR: Float = Float.POSITIVE_INFINITY, colorOf: (Mark) -> Int?,
) {
    for (pass in 0..1) for (mk in marks) {
        val color = colorOf(mk)
        if ((pass == 0) != (color == null)) continue
        if (mk.x > visR) continue
        if (mk is LineMark && max(mk.x1, mk.x2) < visL) continue
        if (mk is TieMark && mk.x2 < visL) continue
        if (mk !is LineMark && mk !is TieMark && mk.x < visL - 6f) continue
        val markInk = color ?: paints.ink
        when (mk) {
            is LineMark -> {
                paints.line.color = markInk
                paints.line.strokeWidth = mk.width * sp
                nc.drawLine(mk.x1 * sp, mk.y1 * sp, mk.x2 * sp, mk.y2 * sp, paints.line)
            }
            is GlyphMark -> {
                paints.glyph.color = markInk
                paints.glyph.textSize = 4f * sp * mk.scale
                nc.drawText(mk.c.toString(), mk.x * sp, mk.y * sp, paints.glyph)
            }
            is BeamMark -> {
                paints.fill.color = markInk
                val p = paints.path
                p.reset()
                p.moveTo(mk.x1 * sp, mk.y1 * sp); p.lineTo(mk.x2 * sp, mk.y2 * sp)
                p.lineTo(mk.x2 * sp, (mk.y2 + mk.h) * sp); p.lineTo(mk.x1 * sp, (mk.y1 + mk.h) * sp)
                p.close()
                nc.drawPath(p, paints.fill)
            }
            is TieMark -> {
                paints.fill.color = markInk
                nc.drawPath(tiePath(mk, sp, paints.path), paints.fill)
            }
            is TextMark -> drawText(nc, mk, sp, paints)
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

internal class Paints(bravura: Typeface) {
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND }
    val path = Path()
    val text = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Plain text and non-chord-tone label colors for the current theme, set before each draw. */
    var ink = 0
    var nct = 0
    val roman: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    val italic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
}

private const val SUPERS = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺"
private const val SUBS = "₀₁₂₃₄₅₆₇₈₉"

private fun drawText(nc: android.graphics.Canvas, t: TextMark, sp: Float, p: Paints) {
    val paint = p.text
    paint.color = p.ink
    paint.textSize = t.size * sp
    when (t.style) {
        TextStyle.TEMPO -> { paint.typeface = p.bold; paint.textAlign = Paint.Align.LEFT; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.MEASURE_NO -> { paint.typeface = p.italic; paint.textAlign = Paint.Align.LEFT; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.SYMBOL -> { paint.typeface = p.bold; paint.textAlign = Paint.Align.CENTER; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.NCT -> { paint.typeface = p.italic; paint.textAlign = Paint.Align.LEFT; paint.color = p.nct; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.ROMAN -> drawRoman(nc, t, sp, p)
        TextStyle.NAME -> { paint.typeface = p.roman; paint.textAlign = Paint.Align.CENTER; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
        TextStyle.FIGURE -> { paint.typeface = p.roman; paint.textAlign = Paint.Align.CENTER; nc.drawText(t.text, t.x * sp, t.y * sp, paint) }
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

/** A single SMuFL glyph centered in a box, for toolbar buttons. */
@Composable
fun GlyphIcon(c: Char, sizeDp: Int, color: Color, modifier: Modifier = Modifier) = GlyphIcon(c.toString(), sizeDp, color, modifier)

/** [str]: one glyph, or a few drawn as one (a dotted note), centered. */
@Composable
fun GlyphIcon(str: String, sizeDp: Int, color: Color, modifier: Modifier = Modifier) {
    val bravura = rememberBravura()
    val paint = remember(bravura) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura } }
    val bounds = remember { Rect() }
    Canvas(modifier.size(sizeDp.dp)) {
        paint.textSize = size.height * 0.62f
        paint.color = color.toArgb()
        paint.getTextBounds(str, 0, str.length, bounds)
        drawIntoCanvas {
            it.nativeCanvas.drawText(str, size.width / 2 - bounds.exactCenterX(), size.height / 2 - bounds.exactCenterY(), paint)
        }
    }
}
