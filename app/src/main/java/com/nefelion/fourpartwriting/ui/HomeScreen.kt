package com.nefelion.fourpartwriting.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.Lessons
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlinx.coroutines.delay

/** How far a lesson is, for its badge. */
internal enum class Stage { DONE, NOW, AHEAD }

/** The lesson to go on with: the first not done, or the last when all are. */
private fun nextLesson(done: Set<Int>) = Lessons.ALL.indices.firstOrNull { it !in done } ?: Lessons.ALL.lastIndex

/** A lesson opens once the one before it is done, or when all are unlocked in Settings. */
internal fun locked(i: Int, done: Set<Int>, all: Boolean) = !all && i > 0 && i - 1 !in done

internal fun stageOf(i: Int, done: Set<Int>) = when { i in done -> Stage.DONE; i == nextLesson(done) -> Stage.NOW; else -> Stage.AHEAD }

/** The first two bars of [score] (after a pickup), for a preview. */
private fun opening(score: Score): Score {
    val end = score.bar(if (score.pickup != null) 2 else 1).end
    return score.copy(voices = Voice.entries.map { v -> score.voice(v).zip(score.onsets(v)).filter { it.second < end }.map { it.first } })
}

/** The cards rise in once per app start, not every time the menu comes back. */
private var introPlayed = false

/** The main menu: Lessons (the main way through the app) and Free Mode. Reads [st] only through derived values. */
@Composable
internal fun HomeScreen(st: State<UiState>, vm: EditorViewModel, onLessons: () -> Unit, onFree: () -> Unit) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    val play = remember { !introPlayed }
    LaunchedEffect(Unit) { introPlayed = true }
    val cs = MaterialTheme.colorScheme
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The score Free Mode opens: the open one if it is yours, else your last one (its file is current: it was saved on leaving it)
    val free by remember { derivedStateOf {
        val s = st.value
        (if (s.slot == null && s.study == null) s.score else s.scores.firstOrNull { it.first == s.freeName }?.second)?.let(::opening)
    } }

    @Composable fun LessonsCard(m: Modifier) = MenuCard("Lessons", "Learn to write for four voices.",
        cs.primaryContainer, m.entrance(1, play), onLessons) { BookArt() }
    @Composable fun FreeCard(m: Modifier) = MenuCard("Free Mode", "Write your own SATB. Check it against the rules whenever you like.",
        cs.surfaceVariant, m.entrance(2, play), { vm.openFree(); onFree() }) { free?.let { MiniScore(it, numerals = false) } }

    Surface(Modifier.fillMaxSize(), color = cs.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max = 900.dp).fillMaxWidth().entrance(0, play), verticalAlignment = Alignment.CenterVertically) {
                    AppLogo(36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("SATB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    for ((glyph, label, open) in listOf(Triple("ⓘ", "About", { about = true }), Triple("⚙", "Settings", { settings = true })))
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = open).semantics { contentDescription = label },
                            contentAlignment = Alignment.Center) { Text(glyph, fontSize = 22.sp, color = cs.onSurfaceVariant) }
                }
                Spacer(Modifier.height(16.dp))
                if (landscape) Row(Modifier.widthIn(max = 900.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LessonsCard(Modifier.weight(1f).fillMaxSize())
                    FreeCard(Modifier.weight(1f).fillMaxSize())
                } else Column(Modifier.widthIn(max = 560.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LessonsCard(Modifier.weight(1f).fillMaxWidth())
                    FreeCard(Modifier.weight(1f).fillMaxWidth())
                }
            }
            LocalSnack.current?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)) }
        }
    }
    if (settings) SettingsDialog(st.value, vm) { settings = false }
    if (about) AboutDialog { about = false }
}

/** A big menu card: a picture ([art]) on top, then [title] and [text]. It sinks a little while pressed. */
@Composable
private fun MenuCard(title: String, text: String, color: Color, modifier: Modifier, onClick: () -> Unit, art: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    // A tap opens it; a finger that slides away from where it went down cancels, even if it lets go on the card
    Surface(modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics { role = Role.Button; onClick(title) { click(); true } }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                pressed = true
                var moved = false
                while (true) {
                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; pressed = false }
                    if (!ch.pressed) { if (!moved) click(); break }
                }
                pressed = false
            }
        }, shape = RoundedCornerShape(28.dp), color = color, contentColor = cs.onSurface) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().alpha(0.6f), contentAlignment = Alignment.Center) { art() }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** An open book with staves and a few notes on its pages, for Lessons. Fits its box. */
@Composable
private fun BookArt() {
    val cs = MaterialTheme.colorScheme
    val paper = cs.surface; val edge = cs.primary; val ink = cs.onSurface
    Canvas(Modifier.fillMaxSize()) {
        val w = minOf(size.width * 0.8f, size.height * 1.5f)
        val h = w / 1.5f
        val cx = size.width / 2; val top = (size.height - h) / 2 + h * 0.06f; val bottom = top + h * 0.82f
        val dip = h * 0.1f
        // Page edges sag toward the spine: y at distance u (0 spine .. 1 outer edge) from it
        fun sag(y: Float, u: Float) = y + dip * (1 - u) * (1 - u)
        val stroke = Stroke(w * 0.018f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        for (side in listOf(-1f, 1f)) {
            fun x(u: Float) = cx + side * u * w / 2
            val page = Path().apply {
                moveTo(x(0f), sag(top, 0f))
                for (k in 1..20) { val u = k / 20f; lineTo(x(u), sag(top, u)) }
                for (k in 20 downTo 0) { val u = k / 20f; lineTo(x(u), sag(bottom, u)) }
                close()
            }
            drawPath(page, paper)
            drawPath(page, edge, style = stroke)
            // Two staves per page, and a few noteheads on them
            val gap = h * 0.032f
            for (staff in 0..1) {
                val y0 = top + h * (0.14f + staff * 0.34f)
                for (l in 0..4) {
                    val line = Path().apply {
                        moveTo(x(0.14f), sag(y0 + l * gap, 0.14f))
                        for (k in 1..10) { val u = 0.14f + 0.72f * k / 10; lineTo(x(u), sag(y0 + l * gap, u)) }
                    }
                    drawPath(line, ink.copy(alpha = 0.35f), style = Stroke(w * 0.005f))
                }
                for ((n, u) in listOf(0.26f, 0.42f, 0.58f, 0.74f).withIndex()) {
                    val step = ((n + staff + if (side > 0) 1 else 0) % 3) + 1
                    val cy = sag(y0 + gap * (4 - step), u)
                    drawOval(ink.copy(alpha = 0.7f), Offset(x(u) - gap * 0.65f, cy - gap * 0.45f), Size(gap * 1.3f, gap * 0.9f))
                }
            }
        }
        // The spine
        drawLine(edge, Offset(cx, sag(top, 0f)), Offset(cx, sag(bottom, 0f)), stroke.width, StrokeCap.Round)
    }
}

private var lockedToast: android.widget.Toast? = null

/** The lessons in order, a card each; the next one stands out. [shared] ties a badge to its lesson's page. */
@Composable
internal fun LessonsScreen(st: State<UiState>, shared: @Composable (String) -> Modifier, onLesson: (Int) -> Unit, onBack: () -> Unit) {
    val done by remember { derivedStateOf { st.value.lessonsDone } }
    val all by remember { derivedStateOf { st.value.allUnlocked } }
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Page("Lessons", onBack) {
        for ((i, l) in Lessons.ALL.withIndex()) item(key = "lesson$i") {
            val stage = stageOf(i, done)
            val now = stage == Stage.NOW
            val shut = locked(i, done, all)
            Surface(onClick = { if (shut) {
                // Fast taps replace the toast instead of queuing behind it
                lockedToast?.cancel()
                lockedToast = android.widget.Toast.makeText(ctx, "Finish the previous lessons first", android.widget.Toast.LENGTH_SHORT).apply { show() }
            } else onLesson(i) }, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().alpha(if (shut) 0.5f else 1f),
                shape = RoundedCornerShape(20.dp),
                color = if (now) cs.primaryContainer else cs.surfaceVariant, contentColor = cs.onSurface) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    LessonNode(i, stage, shared("node$i"))
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(l.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(l.idea, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!shut) Text("›", fontSize = 26.sp, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Fades and lifts [i]'s element into place on the first menu of the app start, a little after the one before. Draw-only. */
@Composable
private fun Modifier.entrance(i: Int, play: Boolean): Modifier {
    val a = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (a.value < 1f) { delay(70L * i); a.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    }
    return graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 24.dp.toPx() }
}

/** Lesson [i]'s round badge: ✓ when done, filled with a soft pulse when it is next, an outline ahead. The pulse is draw-only. */
@Composable
internal fun LessonNode(i: Int, stage: Stage, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pulse = if (stage == Stage.NOW) rememberInfiniteTransition(label = "pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse") else null
    val ring = cs.primary
    Box(modifier.size(40.dp).drawBehind {
        val t = pulse?.value ?: return@drawBehind
        drawCircle(ring.copy(alpha = 0.3f * (1 - t)), radius = size.minDimension / 2 * (1f + 0.45f * t))
    }.then(when (stage) {
        Stage.DONE -> Modifier.clip(CircleShape).background(LocalScoreColors.current.ok)
        Stage.NOW -> Modifier.clip(CircleShape).background(cs.primary)
        Stage.AHEAD -> Modifier.clip(CircleShape).border(BorderStroke(1.5.dp, cs.outline), CircleShape)
    }), contentAlignment = Alignment.Center) {
        Text(if (stage == Stage.DONE) "✓" else "${i + 1}", fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = if (stage == Stage.AHEAD) cs.onSurfaceVariant else cs.surface)
    }
}
