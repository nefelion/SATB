package com.nefelion.fourpartwriting.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nefelion.fourpartwriting.theory.Overtones
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.Verdict
import com.nefelion.fourpartwriting.theory.VoiceLeading
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlinx.coroutines.launch

/**
 * One lesson as swipeable steps with Back / Next below, like the welcome pages; [header] shows its number and title,
 * [onNext] opens the next lesson. It opens on the step it was left on. Choices stay on rotation.
 */
@Composable
internal fun LessonPage(i: Int, st: UiState, vm: EditorViewModel, header: @Composable RowScope.() -> Unit, onNext: () -> Unit, onDismiss: () -> Unit) {
    val l = Lessons.ALL[i]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = LocalScoreColors.current
    // Kept here, not in a step: the pager drops steps out of view
    var heard by rememberSaveable(i) { mutableIntStateOf(-1) }
    var step by rememberSaveable(i) { mutableIntStateOf(0) }
    var picks by rememberSaveable(i) { mutableStateOf(IntArray(l.tasks.size) { -1 }) }
    var info by rememberSaveable { mutableStateOf<Rule?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf(false) }
    val body = MaterialTheme.typography.bodyMedium
    val spots = l.rules.flatMap { r -> IN_BACH[r].orEmpty().map { r to it } }
    // Closing the lesson silences what it plays (the overtone card holds a tone until told otherwise); rotating does not
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(i) {
        onDispose {
            val activity = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            if (activity?.isChangingConfigurations != true) vm.stop()
        }
    }

    // One task: its passage with the note to pick, the options, and what the pick says
    val task: @Composable ColumnScope.(Int) -> Unit = { k ->
        val t = l.tasks[k]
        Text(t.prompt, style = body)
        val pick = t.options.getOrNull(picks[k])
        val verdictColor = when (pick?.verdict) { Verdict.BEST -> colors.ok; Verdict.OK -> colors.warnNote; Verdict.WRONG -> colors.errorNote; null -> Color.Unspecified }
        val target = NoteRef(t.voice, t.score.voice(t.voice).lastIndex)
        MiniScore(if (pick == null) t.open else t.with(pick.note), if (pick == null) emptyMap() else mapOf(target to verdictColor), frame = t.score)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            t.options.forEachIndexed { o, opt ->
                val choose = { picks = picks.copyOf().also { it[k] = o }; vm.playPassage(t.with(opt.note)) }
                val label: @Composable RowScope.() -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(opt.note.name)
                        t.role(opt.note)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (picks[k] == o) Button(onClick = choose, shape = SHAPE, modifier = Modifier.weight(1f), content = label)
                else OutlinedButton(onClick = choose, shape = SHAPE, modifier = Modifier.weight(1f), content = label)
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

    // The steps this lesson has, each a title and its content
    val steps = buildList<Pair<String, @Composable ColumnScope.() -> Unit>> {
        if (l.lab) {
            add("A note is many notes" to { NoteBuilder(st, vm) })
            add("Piano into guitar" to { TimbreMorph(st, vm) })
            Overtones.COMPARE.forEachIndexed { k, c -> add(c.title to {
                if (k == 0) Text("Four voices sing each chord: soprano, alto, tenor and bass, from high to low. " +
                    "In C3 or E5, the number is the octave; C4 is middle C.", style = MaterialTheme.typography.bodySmall, color = muted)
                SeriesCard(c, vm) { info = it }
                Text("Try it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                task(k)
            }) }
        }

        l.hear?.let { h -> add("Hear it" to {
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
        }) }

        l.why?.let { why -> add("Why" to {
            Text(why, style = body)
            Text("Checked from now on (tap for more):", style = MaterialTheme.typography.bodySmall, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in l.rules) OutlinedButton(onClick = { info = r }, shape = SHAPE) { Text(r.label) }
            }
        }) }

        l.watch?.let { w -> add("Watch it done" to {
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
        }) }

        // One step per task; a lab lesson puts each task under its card instead
        if (!l.lab) l.tasks.forEachIndexed { k, _ -> add((if (l.tasks.size > 1) "Try it (${k + 1} of ${l.tasks.size})" else "Try it") to { task(k) }) }

        if (spots.isNotEmpty()) add("In Bach's chorales" to {
            Text("Where Bach keeps or bends these rules. Tap to open.", style = body)
            for ((r, spot) in spots) Column(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.openStudy(spot.study, r, spot.bar, spot.breaks) }.padding(4.dp)) {
                Text("${r.label}: ${spot.study}, bar ${spot.bar} · ${if (spot.breaks) "breaks it" else "keeps it"}", style = MaterialTheme.typography.labelLarge,
                    color = if (spot.breaks) colors.warnText else colors.ok)
            }
        })

        add("Practice" to {
            Text("First, find this lesson's mistakes in a short passage.", style = body)
            FilledTonalButton(onClick = { vm.openLessonFind(i) }, shape = SHAPE) { Text("Find the mistakes") }
            Text(l.apply, style = body)
            Text("Solve one with no rule breaks to finish the lesson.",
                style = MaterialTheme.typography.bodySmall, color = muted)
            if (i in st.lessonsDone) Text("✓ Lesson done", fontWeight = FontWeight.Bold, color = colors.ok)
            // One exercise per lesson: your work on it is kept until you ask for a new one
            if (i in st.lessonSaved) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.openLessonExercise(i) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("Continue exercise") }
                OutlinedButton(onClick = { replacing = true }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("New exercise") }
            } else Button(onClick = { vm.openLessonExercise(i) }, shape = SHAPE) { Text("Start exercise") }
        })
    }

    val pager = rememberPagerState(remember(i) { vm.lessonStep(i).coerceIn(0, steps.lastIndex) }) { steps.size }
    LaunchedEffect(pager.currentPage) { vm.setLessonStep(i, pager.currentPage) }
    val scope = rememberCoroutineScope()
    val p = pager.currentPage
    val last = p == steps.lastIndex

    PageFrame("${i + 1}. ${l.title}", onDismiss, header) {
        // This page covers the editor's snackbar, so say here why Play is silent
        // Not in a lab lesson: its note card plays its own sound
        if (!st.canPlay && !l.lab) Row(Modifier.padding(horizontal = 16.dp).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Text(if (st.soundLoading) "Loading the sound…" else "No sound yet: load a SoundFont (.sf2, preferred) or pick the built-in basic piano to hear the examples.",
                Modifier.weight(1f), style = body, color = MaterialTheme.colorScheme.error)
            if (!st.soundLoading) TextButton({ settings = true }) { Text("Settings") }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { k ->
            val (title, content) = steps[k]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // A lab lesson starts with sound and needs no summary first
                if (k == 0 && !l.lab) Text(l.idea, Modifier.widthIn(max = 640.dp).fillMaxWidth(), style = body, color = muted)
                Group("${k + 1} · $title", content = content)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { if (p > 0) TextButton({ scope.launch { pager.animateScrollToPage(p - 1) } }) { Text("Back") } }
            Dots(steps.size, p)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                when {
                    !last -> Button({ scope.launch { pager.animateScrollToPage(p + 1) } }) { Text("Next") }
                    i < Lessons.ALL.lastIndex -> Button(onNext, enabled = !locked(i + 1, st.lessonsDone, st.allUnlocked)) { Text("Next lesson") }
                    else -> Button(onDismiss) { Text("Done") }
                }
            }
        }
    }
    // Back from a Bach example, the card is here again
    if (LocalOnTop.current) info?.let { r ->
        RuleInfoDialog(r, true, onToggle = null,
            onStudy = { vm.openStudy(it.study, r, it.bar, it.breaks) }) { info = null }
    }
    Overlays { if (settings) SettingsDialog(st, vm) { settings = false } }
    if (replacing) ReplaceLessonDialog(i, vm) { replacing = false }
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
 * A small static score: [score] drawn with [colors] on some notes and the roman numerals of [frame] (unless not [numerals])
 * (the whole passage, whose size the drawing keeps, so it does not jump while notes appear).
 */
@Composable
internal fun MiniScore(score: Score, colors: Map<NoteRef, Color> = emptyMap(), frame: Score = score, numerals: Boolean = true) {
    val sc = LocalScoreColors.current
    val bravura = rememberBravura()
    val paints = remember(bravura) { Paints(bravura) }
    val labels = remember(frame, numerals) {
        if (!numerals) return@remember emptyMap()
        val a = VoiceLeading.check(frame)
        if (a.points.isEmpty()) emptyMap() else chordLabels(a).mapValues { it.value.copy(symbol = "", alt = null) }
    }
    val marks = remember(score, labels) { plain(score, labels) }
    val box = remember(frame, labels) { bounds(plain(frame, labels)) }
    val (left, top, right, bottom) = box
    val w = right - left; val h = bottom - top
    val ink = sc.ink.toArgb(); val nct = sc.nct.toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Also fits a bounded height (a card on the main menu)
        val spDp = min(7.dp, maxWidth / w).let { if (constraints.hasBoundedHeight) min(it, maxHeight / h) else it }
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
    if (xs.isEmpty()) return listOf(0f, 0f, 1f, 1f) // nothing drawn
    return listOf(xs.min() - 0.5f, ys.min() - 0.5f, xs.max() + 0.5f, ys.max() + 0.5f)
}
