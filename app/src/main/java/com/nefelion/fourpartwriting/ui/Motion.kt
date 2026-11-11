package com.nefelion.fourpartwriting.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Press feedback: quick to sink, a small bounce on release. */
internal val PRESS = spring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)

/** Sinks to [to] while [source] is pressed and springs back. Draw-only. */
@Composable
internal fun Modifier.pressScale(source: InteractionSource, to: Float = 0.94f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) to else 1f, PRESS, label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

private val SIZE = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntSize.VisibilityThreshold)
private val FADE = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)

/** A panel in a column opening and closing. */
internal val GROW = expandVertically(SIZE) + fadeIn(FADE)
internal val SHRINK = shrinkVertically(SIZE) + fadeOut(FADE)

/** A card over the score popping in and out. */
internal val POP_IN = scaleIn(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium), 0.9f) + fadeIn(FADE)
internal val POP_OUT = scaleOut(FADE, 0.9f) + fadeOut(FADE)

/** [v], or while it is null its last non-null value: content that animates away keeps showing what it had. */
@Composable
internal fun <T : Any> kept(v: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (v != null) last[0] = v
    @Suppress("UNCHECKED_CAST")
    return v ?: last[0] as T?
}

/** Content sliding sideways: settles fast, no bounce. */
internal val SLIDE = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, IntOffset.VisibilityThreshold)

private const val BURST_MS = 1100
private const val HEADS = 18

/**
 * Note heads in the voice colors flying up from the bottom center and falling away, with a success haptic,
 * each time [tick] goes up (never again on rotation). Draw-only; takes no touches.
 */
@Composable
internal fun Burst(tick: Int, modifier: Modifier) {
    var seen by rememberSaveable { mutableIntStateOf(tick) }
    val t = remember { Animatable(1f) }
    val haptic = haptics()
    LaunchedEffect(tick) {
        if (tick == seen) return@LaunchedEffect
        seen = tick
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        t.snapTo(0f)
        t.animateTo(1f, tween(BURST_MS, easing = LinearEasing))
    }
    val running by remember { derivedStateOf { t.value < 1f } }
    if (!running) return
    val colors = LocalScoreColors.current.let { it.voices + it.ok }
    // Per head: angle, speed, spin, color; fixed for one burst
    val heads = remember(seen) {
        val r = Random(seen)
        List(HEADS) { floatArrayOf(-PI.toFloat() * (0.15f + 0.7f * r.nextFloat()), 0.55f + 0.5f * r.nextFloat(), r.nextFloat() * 2 - 1, r.nextInt(colors.size).toFloat()) }
    }
    Canvas(modifier) {
        val p = t.value
        val reach = size.height.coerceAtMost(size.width) * 1.1f
        val head = Size(22.dp.toPx(), 15.dp.toPx())
        val fade = 1 - p * p * p
        val pop = (p * 8).coerceAtMost(1f)
        for (h in heads) {
            val v = h[1] * reach
            val x = size.width / 2 + cos(h[0]) * v * p
            // Thrown up, then gravity pulls it back down
            val y = size.height + sin(h[0]) * v * p * 1.6f + reach * 0.9f * p * p
            withTransform({ translate(x, y); rotate(-20f + h[2] * 140f * p, Offset.Zero); scale(pop, pop, Offset.Zero) }) {
                drawOval(colors[h[3].toInt()].copy(alpha = fade), Offset(-head.width / 2, -head.height / 2), head)
            }
        }
    }
}

/** A light tick each time [pager] settles on another page. */
@Composable
internal fun PageTicks(pager: PagerState) {
    val haptic = haptics()
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) } }
}

/** The Vibration setting. Provided at the root; unlike [LocalHapticFeedback], it reaches into dialogs, which get their own. */
val LocalVibration = staticCompositionLocalOf { true }

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

/** Haptic feedback that stays silent when Vibration is off in Settings; use instead of [LocalHapticFeedback]. */
@Composable
fun haptics(): HapticFeedback = if (LocalVibration.current) LocalHapticFeedback.current else NoHaptics
