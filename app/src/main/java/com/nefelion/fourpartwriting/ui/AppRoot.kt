package com.nefelion.fourpartwriting.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.theory.Lessons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val HOME = "home"
private const val EDITOR = "editor"
private const val LESSONS = "lessons"
private const val FREE = "free"
/** A Bach chorale over the screen it was opened from; leaving it brings back the score that was open before. */
private const val STUDY = "study"
private fun lesson(i: Int) = "lesson:$i"

/** False on a screen with another one over it: its dialogs hide until it is back on top (see [Overlays]). */
internal val LocalOnTop = compositionLocalOf { true }

/** A screen's dialogs: hidden while another screen is over it, and back as they were (with their state) when it returns. */
@Composable
internal fun Overlays(content: @Composable () -> Unit) {
    val holder = rememberSaveableStateHolder()
    if (LocalOnTop.current) holder.SaveableStateProvider("overlays", content)
}

/** Lessons easing: quick start, long gentle settle. */
private val SETTLE = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The app's screens as a back stack: the main menu, the lessons and a lesson, Free Mode, the editor. A screen slides over the one below it;
 * the back gesture drags it away live (predictive back) and a lesson's badge flies between Home and its page.
 * Any score opening (a lesson's exercise, a Bach spot, a score from Free Mode) goes to the editor.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(vm: EditorViewModel) {
    val st = vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val message by remember { derivedStateOf { st.value.message } }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.clearMessage() } }

    // Kept on rotation, as plain strings
    var stack by rememberSaveable { mutableStateOf(listOf(HOME)) }
    fun push(s: String) { if (stack.last() != s) stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    // A score opened: to the editor (not on rotation, which keeps the tick)
    val tick by remember { derivedStateOf { st.value.openTick } }
    var seenTick by rememberSaveable { mutableIntStateOf(tick) }
    LaunchedEffect(tick) { if (tick != seenTick) { seenTick = tick; push(if (st.value.bachOpen) STUDY else EDITOR) } }

    val motion = remember { SeekableTransitionState(stack) }
    val transition = rememberTransition(motion, label = "screens")
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(stack) {
        val before = motion.currentState
        if (before != stack) motion.animateTo(stack)
        // A screen that was left (back) starts fresh next time
        for (k in before - stack.toSet()) holder.removeState(k)
        if (STUDY in before && STUDY !in stack) vm.leaveStudy()
    }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        val back = stack.dropLast(1)
        try {
            events.collect { motion.seekTo(it.progress, back) }
            stack = back
        } catch (e: CancellationException) {
            // Gesture let go of: the screen slides back into place
            scope.launch { motion.animateTo(motion.currentState) }
            throw e
        }
    }

    CompositionLocalProvider(LocalSnack provides snack) {
        SharedTransitionLayout {
            transition.AnimatedContent(
                transitionSpec = {
                    val forward = targetState.size >= initialState.size
                    val d = if (forward) 1 else -1
                    ((slideInHorizontally(tween(360, easing = SETTLE)) { d * it / 5 } + fadeIn(tween(240, 60))) togetherWith
                        (slideOutHorizontally(tween(360, easing = SETTLE)) { -d * it / 10 } + fadeOut(tween(180))))
                        .apply { targetContentZIndex = if (forward) 1f else -1f }
                },
                contentKey = { it.last() },
            ) { s ->
                val screen = s.last()
                val shared: @Composable (String) -> Modifier = { key ->
                    Modifier.sharedElement(rememberSharedContentState(key), this@AnimatedContent)
                }
                holder.SaveableStateProvider(screen) { CompositionLocalProvider(LocalOnTop provides (screen == stack.last())) {
                    when {
                        screen == HOME -> HomeScreen(st, vm, onLessons = { push(LESSONS) }, onFree = { push(FREE) })
                        screen == LESSONS -> LessonsScreen(st, shared, onLesson = { push(lesson(it)) }, onBack = ::pop)
                        screen == FREE -> ScoresPage(st.value, vm, screen = true, onOpen = { push(EDITOR) }, onDismiss = ::pop)
                        screen == EDITOR || screen == STUDY -> EditorScreen(vm, snack, study = screen == STUDY)
                        else -> {
                            val i = screen.substringAfter(':').toInt()
                            LessonPage(i, st.value, vm, header = {
                                LessonNode(i, stageOf(i, st.value.lessonsDone), shared("node$i"))
                                Spacer(Modifier.width(12.dp))
                                Text(Lessons.ALL[i].title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }, onNext = { stack = stack.dropLast(1) + lesson(i + 1) }, onDismiss = ::pop)
                        }
                    }
                } }
            }
        }
    }
}
