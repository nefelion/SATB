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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Text("Short lessons on how voices move. Each one lets you hear the idea, see it done, try it, find it in a passage, meet it in Bach and use it in an exercise.",
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
            Text("More lessons will follow: independent voices, notes that pull, the bass line and cadences.",
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
    val body = MaterialTheme.typography.bodyMedium

    FullScreenPage("${i + 1}. ${l.title}", onDismiss) {
        item { Text(l.idea, Modifier.widthIn(max = 640.dp).fillMaxWidth(), style = body, color = muted) }

        item {
            Group("1 · Hear it") {
                Text(l.hear.question, style = body)
                val a = if (l.hear.goodFirst) l.hear.good else l.hear.bad
                val b = if (l.hear.goodFirst) l.hear.bad else l.hear.good
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.playPassage(a) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Play A") }
                    OutlinedButton(onClick = { vm.playPassage(b) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Play B") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in 0..1) FilledTonalButton(onClick = { heard = k }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text(if (k == 0) "A" else "B") }
                }
                if (heard >= 0) {
                    val right = (heard == 0) == l.hear.goodFirst
                    Text(if (right) "Yes." else "Listen again: it's the other one.", fontWeight = FontWeight.Bold, color = if (right) colors.ok else colors.warnText)
                    Text(l.hear.answer, style = body)
                    Text("A", style = MaterialTheme.typography.labelLarge); MiniScore(a)
                    Text("B", style = MaterialTheme.typography.labelLarge); MiniScore(b)
                }
            }
        }

        item {
            Group("2 · Why") {
                Text(l.why, style = body)
                Text("Rules that follow from it (tap one for its card):", style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in l.rules) OutlinedButton(onClick = { info = r }, shape = SHAPE) { Text(r.label) }
                }
            }
        }

        item {
            Group("3 · Watch it done") {
                val w = l.watch
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
            }
        }

        item {
            Group("4 · Try it") {
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
            Group("5 · Find it yourself") {
                Text("A short passage with mistakes of this lesson opens in the editor. Tap the notes you think are wrong, then Reveal to see what you found.", style = body)
                FilledTonalButton(onClick = { vm.openLessonFind(i) }, shape = SHAPE) { Text("Open the passage") }
            }
        }

        val spots = l.rules.flatMap { r -> IN_BACH[r].orEmpty().map { r to it } }
        if (spots.isNotEmpty()) item {
            Group("6 · In Bach's chorales") {
                Text("Real music follows these rules, and sometimes bends them for a reason. Tap a spot to open it at that bar.", style = body)
                for ((r, spot) in spots) Column(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.openStudy(spot.study, r, spot.bar, spot.breaks, spot.note) }.padding(4.dp)) {
                    Text("${r.label}: ${spot.study}, bar ${spot.bar} · ${if (spot.breaks) "breaks it" else "keeps it"}", style = MaterialTheme.typography.labelLarge,
                        color = if (spot.breaks) colors.warnText else colors.ok)
                    Text(spot.note, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }

        item {
            Group("7 · Use it") {
                Text(l.apply, style = body)
                Text("The check uses the rules of this lesson and the ones before. Solve one exercise with no rule breaks to finish the lesson.",
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
        RuleInfoDialog(r, r in st.rules, onToggle = { vm.toggleRule(r) },
            onStudy = { info = null; vm.openStudy(it.study, r, it.bar, it.breaks, it.note) }) { info = null }
    }
}

/** [score] with only the first n notes of each voice, n from the "SATB" digits [counts]. */
private fun shown(score: Score, counts: String) = score.copy(voices = Voice.entries.map { score.voice(it).take(counts[it.ordinal].digitToInt()) })

/**
 * A small static score: [score] drawn with [colors] on some notes and the roman numerals of [frame]
 * (the whole passage, whose size the drawing keeps, so it does not jump while notes appear).
 */
@Composable
private fun MiniScore(score: Score, colors: Map<NoteRef, Color> = emptyMap(), frame: Score = score) {
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
