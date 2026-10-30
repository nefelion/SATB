package com.nefelion.fourpartwriting.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.graphics.withTranslation
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.engrave.BeamMark
import com.nefelion.fourpartwriting.engrave.ChordLabel
import com.nefelion.fourpartwriting.engrave.Engraver
import com.nefelion.fourpartwriting.engrave.GlyphMark
import com.nefelion.fourpartwriting.engrave.LineMark
import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.engrave.TextMark
import com.nefelion.fourpartwriting.engrave.TieMark
import com.nefelion.fourpartwriting.engrave.chordLabels
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.IN_BACH
import com.nefelion.fourpartwriting.theory.Lesson
import com.nefelion.fourpartwriting.theory.Lessons
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.Verdict
import com.nefelion.fourpartwriting.theory.VoiceLeading
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors

/** The guided lessons, in order; a ✓ marks one whose exercise was solved. Any lesson can be opened. */
@Composable
internal fun LessonsPage(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FullScreenPage("Lessons", onDismiss) {
        item {
            Text("Short lessons on how voices move: hear it, see it, try it.",
                Modifier.widthIn(max = 640.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        item {
            Group(null) {
                Lessons.ALL.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clip(SHAPE).clickable { open = i }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${l.title}", fontWeight = FontWeight.Bold)
                            Text(l.idea, style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        if (i in st.lessonsDone) Text("✓", Modifier.padding(start = 8.dp), color = LocalScoreColors.current.ok,
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        item {
            Text("More lessons coming.",
                Modifier.widthIn(max = 640.dp), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
    open?.let { i -> LessonPage(i, st, vm, onNext = { open = i + 1 }) { open = null } }
}

/** One lesson's seven steps on a page; [onNext] opens the next lesson. Choices stay on rotation. */
@Composable
private fun LessonPage(i: Int, st: UiState, vm: EditorViewModel, onNext: () -> Unit, onDismiss: () -> Unit) {
    val l = Lessons.ALL[i]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = LocalScoreColors.current
    // Keyed by lesson, so the next lesson starts fresh
    var heard by rememberSaveable(i) { mutableIntStateOf(-1) }
    var step by rememberSaveable(i) { mutableIntStateOf(0) }
    var picks by rememberSaveable(i) { mutableStateOf(IntArray(l.tasks.size) { -1 }) }
    var info by rememberSaveable { mutableStateOf<Rule?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val body = MaterialTheme.typography.bodyMedium
    val spots = l.rules.flatMap { r -> IN_BACH[r].orEmpty().map { r to it } }
    // Steps are numbered by what this lesson has
    val sections = listOfNotNull("A note is many notes".takeIf { l.lab }, "What it means".takeIf { l.lab }, "Hear it".takeIf { l.hear != null },
        "Why".takeIf { l.why != null }, "Watch it done".takeIf { l.watch != null }, "Try it", "Find it yourself",
        "In Bach's chorales".takeIf { spots.isNotEmpty() }, "Use it")
    fun section(name: String) = "${sections.indexOf(name) + 1} · $name"
    // Closing the lesson silences what it plays (the overtone card holds a tone until told otherwise); rotating does not
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(i) {
        onDispose {
            val activity = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            if (activity?.isChangingConfigurations != true) vm.stop()
        }
    }

    FullScreenPage("${i + 1}. ${l.title}", onDismiss) {
        // A lab lesson starts with sound and needs no summary first
        if (!l.lab) item { Text(l.idea, Modifier.widthIn(max = 640.dp).fillMaxWidth(), style = body, color = muted) }
        // This page covers the editor's snackbar, so say here why Play is silent
        if (!st.canPlay) item {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The note card synthesizes its own sound; everything else needs the chosen instrument
                Text(when { st.soundLoading -> "Loading the sound…"; l.lab -> "Step 1 plays its own sound. Load a SoundFont (.sf2) or pick the built-in piano for the rest."
                    else -> "No sound yet: load a SoundFont (.sf2) or pick the built-in piano to hear the examples." },
                    Modifier.weight(1f), style = body, color = if (l.lab) muted else MaterialTheme.colorScheme.error)
                if (!st.soundLoading) TextButton({ settings = true }) { Text("Settings") }
            }
        }

        if (l.lab) {
            item { Group(section("A note is many notes")) { NoteBuilder(st, vm) } }
            item { Group(section("What it means")) { SeriesRules(vm) { info = it } } }
        }

        l.hear?.let { h -> item {
            Group(section("Hear it")) {
                Text(h.question, style = body)
                val a = if (h.goodFirst) h.good else h.bad
                val b = if (h.goodFirst) h.bad else h.good
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.playPassage(a) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Play A") }
                    OutlinedButton(onClick = { vm.playPassage(b) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Play B") }
                }
                Progress(st, a, b)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in 0..1) FilledTonalButton(onClick = { heard = k }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text(if (k == 0) "A" else "B") }
                }
                if (heard >= 0) {
                    val right = (heard == 0) == h.goodFirst
                    Text(if (right) "Yes." else "Listen again: it's the other one.", fontWeight = FontWeight.Bold, color = if (right) colors.ok else colors.warnText)
                    Text(h.answer, style = body)
                    Text("A", style = MaterialTheme.typography.labelLarge); MiniScore(a)
                    Text("B", style = MaterialTheme.typography.labelLarge); MiniScore(b)
                }
            }
        } }

        l.why?.let { why -> item {
            Group(section("Why")) {
                Text(why, style = body)
                Text("Checked from now on (tap for more):", style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in l.rules) OutlinedButton(onClick = { info = r }, shape = SHAPE) { Text(r.label) }
                }
            }
        } }

        l.watch?.let { w -> item {
            Group(section("Watch it done")) {
                val (counts, caption) = w.steps[step]
                val now = shown(w.score, counts)
                val before = if (step > 0) shown(w.score, w.steps[step - 1].first) else Score(voices = List(4) { emptyList() })
                // The notes this step adds, in the "right" color
                val added = Voice.entries.flatMap { v -> (before.voice(v).size until now.voice(v).size).map { NoteRef(v, it) } }
                MiniScore(now, added.associateWith { colors.ok }, frame = w.score)
                Text("Step ${step + 1} of ${w.steps.size}", style = MaterialTheme.typography.labelLarge, color = muted)
                Text(caption, style = body, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step-- }, enabled = step > 0, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("Back") }
                    OutlinedButton(onClick = { vm.playPassage(now) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Play") }
                    FilledTonalButton(onClick = { step++ }, enabled = step < w.steps.lastIndex, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("Next") }
                }
                Progress(st, now)
            }
        } }

        item {
            Group(section("Try it")) {
                l.tasks.forEachIndexed { k, t ->
                    if (k > 0) HorizontalDivider()
                    Text("${k + 1}. ${t.prompt}", style = body)
                    val pick = t.options.getOrNull(picks[k])
                    val verdictColor = when (pick?.verdict) { Verdict.BEST -> colors.ok; Verdict.OK -> colors.warnNote; Verdict.WRONG -> colors.errorNote; null -> Color.Unspecified }
                    val target = NoteRef(t.voice, t.score.voice(t.voice).lastIndex)
                    MiniScore(if (pick == null) t.open else t.with(pick.note), if (pick == null) emptyMap() else mapOf(target to verdictColor), frame = t.score)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.options.forEachIndexed { o, opt ->
                            val choose = { picks = picks.copyOf().also { it[k] = o }; vm.playPassage(t.with(opt.note)) }
                            if (picks[k] == o) Button(onClick = choose, shape = SHAPE, modifier = Modifier.weight(1f)) { Text(opt.note.name) }
                            else OutlinedButton(onClick = choose, shape = SHAPE, modifier = Modifier.weight(1f)) { Text(opt.note.name) }
                        }
                    }
                    Progress(st, t.score, *t.options.map { t.with(it.note) }.toTypedArray())
                    if (pick != null) {
                        Text(when (pick.verdict) { Verdict.BEST -> "Best"; Verdict.OK -> "Works, but not the best"; Verdict.WRONG -> "Not this one" },
                            fontWeight = FontWeight.Bold, color = when (pick.verdict) { Verdict.BEST -> colors.ok; Verdict.OK -> colors.warnText; Verdict.WRONG -> colors.errorText })
                        Text(pick.text, style = body)
                        if (pick.verdict != Verdict.BEST) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.playPassage(t.with(pick.note)) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Yours") }
                            OutlinedButton(onClick = { vm.playPassage(t.score) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ The best") }
                        }
                    }
                }
            }
        }

        item {
            Group(section("Find it yourself")) {
                Text("Spot this lesson's mistakes in a short passage.", style = body)
                FilledTonalButton(onClick = { vm.openLessonFind(i) }, shape = SHAPE) { Text("Open the passage") }
            }
        }

        if (spots.isNotEmpty()) item {
            Group(section("In Bach's chorales")) {
                Text("Where Bach keeps or bends these rules. Tap to open.", style = body)
                for ((r, spot) in spots) Column(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.openStudy(spot.study, r, spot.bar, spot.breaks, spot.note) }.padding(4.dp)) {
                    Text("${r.label}: ${spot.study}, bar ${spot.bar} · ${if (spot.breaks) "breaks it" else "keeps it"}", style = MaterialTheme.typography.labelLarge,
                        color = if (spot.breaks) colors.warnText else colors.ok)
                }
            }
        }

        item {
            Group(section("Use it")) {
                Text(l.apply, style = body)
                Text("Solve one with no rule breaks to finish the lesson.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                if (i in st.lessonsDone) Text("✓ Lesson done", fontWeight = FontWeight.Bold, color = colors.ok)
                FilledTonalButton(onClick = { vm.newLessonExercise(i) }, shape = SHAPE) { Text("New exercise") }
            }
        }

        if (i < Lessons.ALL.lastIndex) item {
            Button(onClick = onNext, shape = SHAPE) { Text("Next lesson: ${Lessons.ALL[i + 1].title}") }
        }
    }
    info?.let { r ->
        RuleInfoDialog(r, true, onToggle = null,
            onStudy = { info = null; vm.openStudy(it.study, r, it.bar, it.breaks, it.note) }) { info = null }
    }
    if (settings) SettingsDialog(st, vm) { settings = false }
}

/** A bar that fills while one of [scores] plays; invisible otherwise, so the page doesn't jump. */
@Composable
internal fun Progress(st: UiState, vararg scores: Score) {
    val p = st.passage?.takeIf { it.first in scores }?.second
    LinearProgressIndicator(progress = { p ?: 0f }, modifier = Modifier.fillMaxWidth().alpha(if (p == null) 0f else 1f))
}

/** [score] with only the first n notes of each voice, n from the "SATB" digits [counts]. */
private fun shown(score: Score, counts: String) = score.copy(voices = Voice.entries.map { score.voice(it).take(counts[it.ordinal].digitToInt()) })

/**
 * A small static score: [score] drawn with [colors] on some notes and the roman numerals of [frame]
 * (the whole passage, whose size the drawing keeps, so it does not jump while notes appear).
 */
@Composable
internal fun MiniScore(score: Score, colors: Map<NoteRef, Color> = emptyMap(), frame: Score = score) {
    val sc = LocalScoreColors.current
    val bravura = rememberBravura()
    val paints = remember(bravura) { Paints(bravura) }
    val labels = remember(frame) {
        val a = VoiceLeading.check(frame)
        if (a.points.isEmpty()) emptyMap() else chordLabels(a).mapValues { ChordLabel("", it.value.roman) }
    }
    val marks = remember(score, labels) { plain(score, labels) }
    val box = remember(frame, labels) { bounds(plain(frame, labels)) }
    val (left, top, right, bottom) = box
    val w = right - left; val h = bottom - top
    val ink = sc.ink.toArgb(); val nct = sc.nct.toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val spDp = min(7.dp, maxWidth / w)
        Canvas(Modifier.size(spDp * w, spDp * h)) {
            val sp = size.width / w
            paints.ink = ink; paints.nct = nct
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.withTranslation(-left * sp, -top * sp) { drawMarks(nc, marks, sp, paints) { m -> m.ref?.let(colors::get)?.toArgb() } }
            }
        }
    }
}

/** The marks of [score] without its tempo mark and the rests that fill out voices not written yet. */
private fun plain(score: Score, labels: Map<com.nefelion.fourpartwriting.model.Fraction, ChordLabel>): List<com.nefelion.fourpartwriting.engrave.Mark> {
    val l = Engraver.layout(score, labels)
    val (x0, y0, x1, y1) = l.tempoBox.toList()
    return l.marks.filter { m ->
        val r = m.ref
        val tempo = (m is TextMark && m.style == com.nefelion.fourpartwriting.engrave.TextStyle.TEMPO) ||
            (m is GlyphMark && r == null && m.x in x0..x1 && m.y in y0..y1)
        !tempo && (r == null || r.index < score.voice(r.voice).size)
    }
}

/** Left, top, right, bottom of [marks], in staff spaces. */
private fun bounds(marks: List<com.nefelion.fourpartwriting.engrave.Mark>): List<Float> {
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    for (m in marks) when (m) {
        is LineMark -> { xs += m.x1; xs += m.x2; ys += m.y1; ys += m.y2 }
        is GlyphMark -> { xs += m.x; xs += m.x + 1.2f; ys += m.y - 1f; ys += m.y + 1f }
        is TextMark -> { xs += m.x - m.text.length * m.size * 0.3f; xs += m.x + m.text.length * m.size * 0.3f; ys += m.y - m.size; ys += m.y + 0.5f }
        is BeamMark -> { xs += m.x1; xs += m.x2; ys += m.y1; ys += m.y2 + m.h }
        is TieMark -> { xs += m.x1; xs += m.x2; ys += m.y - 1f; ys += m.y + 1f }
    }
    return listOf(xs.min() - 0.5f, ys.min() - 0.5f, xs.max() + 0.5f, ys.max() + 0.5f)
}
