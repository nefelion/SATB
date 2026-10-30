package com.nefelion.fourpartwriting.ui

import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.audio.Tones
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Overtones
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.roundToInt

private val PIANO = Overtones.A4_PIANO
/** The drawings put partial 1 at the top. */
private val TOP = PIANO[0].amp

/** Step 0 is silent; steps 1 to 8 strike partials 1 to k; step 9 strikes all of them; [BEEP] strikes 440 Hz again just before;
 * [START] is step 1 opening a build. */
private const val STEPS = 9
private const val BEEP = 10
private const val START = 11
private const val TAG = "note"

/** Seconds between strikes while building, and how long the last one rings. Exact as Floats: the drawing's clock is one. */
private const val GAP = 1.0
private const val RING = 3.5

/** Staff positions (diatonic, E4 = 30) the first 8 partials are written at: 3 and 4 under 8va, 5 to 8 under 15ma. */
private val WRITTEN = listOf(33, 40, 37, 40, 35, 37, 39, 40)

/** Names of partials 2 to 8, listed as they join while building. */
private val NAMES = listOf("A5", "E6", "A6", "C♯7", "E7", "G7 (a little flat)", "A7")

/** What step [k] says; the steps of a build share one caption that lists the partials so far. */
private fun caption(k: Int) = when (k) {
    0 -> "A4 is tuned to 440 Hz. Tap to hear just that frequency."
    1 -> "If the note were only that one frequency, it would sound like this: a thin beep. But instruments don't sound like that."
    STEPS -> "And the real A, played on the piano."
    BEEP -> "Once more, just 440 Hz."
    else -> "A real note has more inside. Listen as more frequencies join 440 Hz, one by one" +
        (if (k == START) "." else ": " + NAMES.take(k - 1).joinToString(", ") + ".")
}

/** Seconds before the first 440 Hz, so the spectrum has appeared first. */
private const val LEAD = 1.5

/**
 * What the note card plays and draws: the hammer strikes ([time] in seconds, step), each with the partials of its step,
 * damping the strike before; the last rings until [length]. Sound, staff and spectrum all read it.
 */
private class Plan(val strikes: List<Pair<Double, Int>>, val length: Double) {
    private fun last(e: Double) = strikes.lastOrNull { it.first <= e }
    fun step(e: Double) = last(e)?.second ?: 0

    /** Whether partial [i] sounds at [e] seconds. */
    fun sounds(i: Int, e: Double) = i < count(step(e))

    /** Strength of partial [i] at [e] seconds: struck at the last strike, fading as on the piano. */
    fun level(i: Int, e: Double) = last(e)?.let { (t, k) -> if (i < count(k)) PIANO[i].level(e - t) else 0.0 } ?: 0.0

    fun tones() = strikes.withIndex().flatMap { (j, s) ->
        val end = strikes.getOrNull(j + 1)?.first ?: length
        PIANO.take(count(s.second)).map { Tones.Tone(it, s.first, end) }
    }

    companion object {
        /** Partials struck in step [k]. */
        fun count(k: Int) = when (k) { STEPS -> PIANO.size; BEEP, START -> 1; else -> k }

        /** Step [k] struck once (none for 0). */
        fun at(k: Int, after: Double = 0.0) = Plan(if (k == 0) emptyList() else listOf(after to k), after + RING)

        /** Steps 1 to 8, one strike each, then 440 Hz alone and the whole note, each let ring a little longer. */
        fun build() = (1..8).map { (it - 1) * GAP to if (it == 1) START else it }
            .let { s -> s.last().first.let { t -> s + listOf(t + 1.5 to BEEP, t + 3.0 to STEPS) } }
            .let { Plan(it, it.last().first + RING) }
    }
}

/** Whole-note chords, one per bar, from voicings "S A T B". */
private fun chords(vararg voicings: String) = Score(voices = voicings.map(Overtones::parse).let { cs ->
    List(4) { v -> cs.map { Event(Fraction.of(1, 1), it[v]) } }
})

/** 0 at −40 dB or below, 1 at full strength. */
private fun loudness(level: Double) = ((20 * log10(level.coerceAtLeast(1e-6)) + 40) / 40).coerceIn(0.0, 1.0).toFloat()

/**
 * A4 built up from one partial to a piano, struck again with each partial added: staff, spectrum and sound move together.
 * Opens on A alone on the staff; the first tap strikes 440 Hz and shows the spectrum; once it has died away, the next
 * builds the whole note from 440 Hz up. Built, either can be struck again.
 */
@Composable
internal fun NoteBuilder(st: UiState, vm: EditorViewModel) {
    var shown by rememberSaveable { mutableIntStateOf(0) }
    var built by rememberSaveable { mutableStateOf(false) }
    var plan by remember { mutableStateOf(Plan.at(shown)) }
    // Seconds into the plan, as heard from the speaker
    var e by remember { mutableFloatStateOf(0f) }
    // From 0: the clock still holds where the last plan settled
    fun play(p: Plan) { plan = p; e = 0f; vm.playTones(TAG, p.tones(), p.length) }
    val playing = st.tone == TAG
    LaunchedEffect(playing) {
        if (playing) while (true) withFrameNanos { e = Tones.elapsed().toFloat() }
        else {
            shown = plan.step(e.toDouble())
            if (shown == STEPS) built = true
            // Settles back to the last strike's first moment
            plan.strikes.lastOrNull()?.let { (t, _) -> animate(e, t.toFloat(), animationSpec = tween(600)) { v, _ -> e = v } }
        }
    }
    val k by remember(playing) { derivedStateOf { if (playing) plan.step(e.toDouble()) else shown } }

    // Before anything plays, the written A alone
    val staff = @Composable { SeriesStaff { i -> if (k == 0) (if (i == 0) 1f to 1f else 0f to 0f) else (if (plan.sounds(i, e.toDouble())) 1f else 0f) to loudness(plan.level(i, e.toDouble()) / TOP) } }
    val spectrum = @Composable { AnimatedVisibility(k > 0 || playing) { Spectrum { i -> plan.level(i, e.toDouble()) / TOP } } }
    // The old caption fades out before the new one fades in; a build's steps share one, growing in place
    val text = @Composable {
        AnimatedContent(if (k in 2..8) START else k, transitionSpec = { fadeIn(tween(150, delayMillis = 120)) togetherWith fadeOut(tween(120)) }, label = "caption") {
            Text(caption(if (it == START) k else it), style = MaterialTheme.typography.bodyLarge)
        }
    }
    val buttons = @Composable { Box {
    if (built) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ play(Plan.at(BEEP)) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ 440 Hz") }
        Button({ play(Plan.at(STEPS)) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("▶ Piano A") }
    }
    // Gone while it sounds: building waits until the first 440 Hz has died away
    else AnimatedVisibility(!playing) {
        Button({ play(if (k == 0) Plan.at(1, LEAD) else Plan.build()) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) {
            Text(if (k == 0) "▶ Play 440 Hz" else "▶ Build the real note")
        }
    }
    } }

    // Landscape: staff and caption beside the spectrum and buttons, so all of it fits on the screen
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { staff(); text() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { spectrum(); buttons() }
        }
    else { staff(); spectrum(); text(); buttons() }
}

/**
 * A4 and its partials 2 to 8 on a treble staff, each as strong as it sounds; [state] gives (presence, loudness) of partial i.
 * A partial not heard yet is not drawn, nor its 8va or 15ma.
 */
@Composable
private fun SeriesStaff(state: (Int) -> Pair<Float, Float>) {
    val bravura = rememberBravura()
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val glyph = remember(bravura) { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura } }
    val italic = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spDp = min(9.dp, maxWidth / 38)
        Canvas(Modifier.fillMaxWidth().height(spDp * 11)) {
            val s = spDp.toPx()
            fun y(d: Int) = (46 - d) * s / 2
            val line = ink.copy(alpha = 0.7f)
            for (d in 30..38 step 2) drawLine(line, Offset(0.3f * s, y(d)), Offset(size.width - 0.3f * s, y(d)), 0.12f * s)
            val x0 = 4.4f * s
            val slot = (size.width - x0 - 0.5f * s) / 8
            val headW = Smufl.WHOLE_W * s
            fun hx(i: Int) = x0 + i * slot + (slot - headW) / 2
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                glyph.textSize = 4f * s
                glyph.color = ink.toArgb()
                nc.drawText(Smufl.G_CLEF.toString(), 0.7f * s, y(32), glyph)
                italic.textSize = 1.5f * s
                // 8va over partials 3 and 4, 15ma over 5 to 8: they sound one or two octaves above where they are written
                for ((first, last, mark) in listOf(Triple(2, 3, "8va"), Triple(4, 7, "15ma"))) {
                    val shown = state(first).first
                    if (shown <= 0f) continue
                    val col = line.copy(alpha = line.alpha * shown)
                    val yb = y(44)
                    italic.color = col.toArgb()
                    nc.drawText(mark, hx(first), yb, italic)
                    val end = hx(last) + headW
                    drawLine(col, Offset(hx(first) + italic.measureText(mark) + 0.3f * s, yb - 0.4f * s), Offset(end, yb - 0.4f * s), 0.1f * s,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5f * s, 0.4f * s)))
                    drawLine(col, Offset(end, yb - 0.4f * s), Offset(end, yb + 0.6f * s), 0.1f * s)
                }
                for (i in 0 until 8) {
                    val (presence, loud) = state(i)
                    if (presence <= 0f) continue
                    // As strong as the partial sounds right now
                    val alpha = presence * (0.15f + 0.85f * loud)
                    val d = WRITTEN[i]
                    val x = hx(i)
                    val ledgers = (if (d <= 28) (28 downTo d) else if (d >= 40) (40..d) else IntRange.EMPTY).filter { it % 2 == 0 }
                    for (l in ledgers) drawLine(ink.copy(alpha = alpha), Offset(x - 0.4f * s, y(l)), Offset(x + headW + 0.4f * s, y(l)), 0.16f * s)
                    glyph.color = accent.copy(alpha = alpha).toArgb()
                    if (i == 4) nc.drawText(Smufl.SHARP.toString(), x - 1.3f * s, y(d), glyph)
                    nc.drawText(Smufl.HEAD_WHOLE.toString(), x, y(d), glyph)
                }
            }
        }
    }
}

/** An analyzer view: [level] of each piano partial as a peak, on a log frequency axis in dB, like an EQ. */
@Composable
private fun Spectrum(level: (Int) -> Double) {
    val a = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tm = rememberTextMeasurer()
    val logs = remember { PIANO.map { log10(it.hz) } }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val h = size.height - 18.dp.toPx()
        val lo = log10(200.0); val hi = log10(10_000.0)
        fun x(lf: Double) = ((lf - lo) / (hi - lo) * size.width).toFloat()
        fun y(db: Double) = (-db.coerceIn(-60.0, 0.0) / 60 * h).toFloat()
        for (db in listOf(-20.0, -40.0)) drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
        for ((f, label) in listOf(200 to "200", 500 to "500", 1000 to "1k", 2000 to "2k", 5000 to "5k", 10000 to "10k Hz")) {
            val xx = x(log10(f.toDouble()))
            drawLine(grid, Offset(xx, 0f), Offset(xx, h), 1f)
            val l = tm.measure(label, TextStyle(fontSize = 10.sp, color = muted))
            drawText(l, topLeft = Offset((xx - l.size.width / 2f).coerceIn(0f, size.width - l.size.width), size.height - l.size.height))
        }
        val v = DoubleArray(PIANO.size) { level(it) }
        val line = Path(); val fill = Path().apply { moveTo(0f, h) }
        // One point per pixel: the peaks are narrower than coarser steps
        val n = size.width.toInt()
        for (i in 0..n) {
            val lf = lo + (hi - lo) * i / n
            var sum = 1e-6
            for (j in v.indices) if (v[j] > 1e-6) { val d = (lf - logs[j]) / 0.009; if (d < 4 && d > -4) sum += v[j] * exp(-d * d) }
            val xx = size.width * i / n
            val yy = y(20 * log10(sum))
            if (i == 0) line.moveTo(xx, yy) else line.lineTo(xx, yy)
            fill.lineTo(xx, yy)
        }
        fill.lineTo(size.width, h); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(a.copy(alpha = 0.45f), a.copy(alpha = 0.04f)), 0f, h))
        drawPath(line, a, style = Stroke(1.5.dp.toPx()))
        for (j in 0 until 8) if (v[j] > 1e-3) drawCircle(a, 3.5.dp.toPx(), Offset(x(logs[j]), y(20 * log10(v[j]))))
    }
}

/** One voicing habit the series explains: a good and a bad C major chord side by side, the note they differ in colored. */
@Composable
internal fun SeriesCard(c: Overtones.Compare, vm: EditorViewModel, onRule: (Rule) -> Unit) {
    Text(c.head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(c.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val differ = remember(c) { Overtones.parse(c.good).zip(Overtones.parse(c.bad)).withIndex().filter { it.value.first != it.value.second }.map { it.index } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Side(c.good, true, c.rule, differ, vm, Modifier.weight(1f))
        Side(c.bad, false, c.rule, differ, vm, Modifier.weight(1f))
    }
    OutlinedButton({ vm.playPassage(chords(c.good, c.bad)) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) { Text("▶ Both: ✓, then ✗") }
    TextButton({ onRule(c.rule) }) { Text("${c.rule.label} rule ›") }
}

/** One voicing card: verdict, chord with the [differ] voices colored, what tells the two apart, play. */
@Composable
private fun Side(voicing: String, good: Boolean, rule: Rule, differ: List<Int>, vm: EditorViewModel, modifier: Modifier) {
    val sc = LocalScoreColors.current
    val notes = remember(voicing) { Overtones.parse(voicing) }
    val tint = if (good) sc.ok else sc.errorNote
    val fact = remember(voicing, rule) {
        if (rule == Rule.DOUBLING) "Third: ${(Overtones.shares(notes, listOf("C", "E", "G").map { Pitch.parse(it + "4").pc })[1] * 100).roundToInt()}% of the sound"
        // Gaps between neighboring voices, from the bass up
        else "Gaps: " + notes.reversed().zipWithNext { lo, up -> ordinal(Interval.between(lo, up).number) }.joinToString(" · ")
    }
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.5.dp, tint.copy(alpha = 0.6f))) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (good) "✓ Like this" else "✗ Not this", style = MaterialTheme.typography.labelLarge, color = if (good) sc.ok else sc.errorText)
            MiniScore(remember(voicing) { chords(voicing) }, differ.associate { NoteRef(Voice.entries[it], 0) to tint })
            Text(fact, style = MaterialTheme.typography.bodySmall, minLines = 2)
            OutlinedButton({ vm.playPassage(chords(voicing)) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) { Text("▶ Play") }
        }
    }
}

private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }
