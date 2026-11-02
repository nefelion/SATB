package com.nefelion.fourpartwriting.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nefelion.fourpartwriting.R
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import kotlinx.coroutines.launch

private class Page(val title: String, val body: String, val visual: @Composable () -> Unit)

/** Welcome plus a few swipeable pages on how the editor works; [atEnd] opens it on the last page. Shown once, then the theme choice. */
@Composable
fun Tutorial(atEnd: Boolean, onDone: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val voices = LocalScoreColors.current.voices
    val pages = listOf(
        Page("Welcome to SATB", "Write four-part harmony and get instant feedback.") { Logo() },
        Page("Write notes", "Hold the staff and slide to aim the note, then let go. After each note the next voice is picked for you: " +
            "soprano, alto, tenor, bass, then the next chord. To change a note later, tap it and drag.") { EntryDemo(voices, ink) },
        Page("Length and accidentals", "Press the button, slide onto a choice and let go.") { PickDemo(ink) },
        Page("Check and listen", "The check button checks your harmony, the play button plays it.") {
            Keys { Key { Ico(Icons.Rounded.Check, ink, 30.dp) }; Key { Ico(Icons.Rounded.PlayArrow, ink, 30.dp) } }
        },
    )
    val pager = rememberPagerState(if (atEnd) pages.lastIndex else 0) { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val page = pages[i]
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        page.visual()
                        Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(page.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (!last) TextButton(onClick = onDone) { Text("Skip") } }
                Dots(pages.size, pager.currentPage)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(if (last) "Get started" else "Next")
                    }
                }
            }
        }
    }
}

/** One dot per page, the [current] one filled with the primary color. */
@Composable
internal fun Dots(count: Int, current: Int) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    repeat(count) { i ->
        Box(Modifier.size(8.dp).clip(CircleShape).background(
            if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
    }
}

/** The app icon (background and foreground layers) as a rounded tile. */
@Composable
private fun Logo() = Box(Modifier.size(132.dp).clip(RoundedCornerShape(32.dp))) {
    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
    // Launchers show the middle 72 of the 108dp icon canvas; scale up to match
    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
}

private const val STEP_MS = 1100
/** Notes of a C major chord, S A T B: staff (0 treble, 1 bass) and staff spaces below its top line. */
private val CHORD = listOf(0 to 0.5f, 0 to 1.5f, 1 to 0.5f, 1 to 2.5f)

/**
 * A finger aims each note of a chord in turn, S A T B, sliding from above or below and letting go;
 * the voice buttons below light up as each voice comes.
 */
@Composable
private fun EntryDemo(voices: List<Color>, ink: Color) {
    val bravura = rememberBravura()
    val finger = MaterialTheme.colorScheme.primary
    // Four notes, then a pause with the chord done
    val t by rememberInfiniteTransition(label = "entry").animateFloat(0f, 5f,
        infiniteRepeatable(tween(STEP_MS * 5, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val current = t.toInt().coerceAtMost(4)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(220.dp, 128.dp)) {
            val sp = 9.dp.toPx()
            val tops = listOf(8.dp.toPx(), 8.dp.toPx() + 8 * sp)
            val x0 = 4.dp.toPx(); val x1 = size.width - 4.dp.toPx()
            for (top in tops) for (l in 0..4) drawLine(ink, Offset(x0, top + l * sp), Offset(x1, top + l * sp), 1.dp.toPx())
            drawLine(ink, Offset(x0, tops[0]), Offset(x0, tops[1] + 4 * sp), 1.5.dp.toPx())
            drawIntoCanvas { c ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = bravura; textSize = 4 * sp; color = ink.toArgb() }
                c.nativeCanvas.drawText(Smufl.G_CLEF.toString(), x0 + sp * 0.6f, tops[0] + 3 * sp, paint)
                c.nativeCanvas.drawText(Smufl.F_CLEF.toString(), x0 + sp * 0.6f, tops[1] + sp, paint)
            }
            val cx = size.width * 0.62f
            for (i in 0..3) {
                val (staff, y) = CHORD[i]
                val target = tops[staff] + y * sp
                val p = t - i
                if (p < 0f) continue
                // Slide in from above (upper voices) or below, ease out, let go at 0.6
                val from = target + if (i % 2 == 0) -3.5f * sp else 3.5f * sp
                val k = FastOutSlowInEasing.transform(((p - 0.1f) / 0.5f).coerceIn(0f, 1f))
                val y1 = if (i == current) from + (target - from) * k else target
                val held = i == current && p < 0.6f
                val fade = if (t > 4.7f) 1f - (t - 4.7f) / 0.3f else 1f
                val col = voices[i].copy(alpha = (if (held) 0.45f else 1f) * fade)
                rotate(-20f, Offset(cx, y1)) { drawOval(col, Offset(cx - sp * 0.65f, y1 - sp * 0.45f), Size(sp * 1.3f, sp * 0.9f)) }
                if (held && p > 0.05f) drawCircle(finger.copy(alpha = 0.3f), sp * 1.6f, Offset(cx + sp * 1.8f, y1 + sp * 1.2f))
            }
        }
        Keys { listOf("S", "A", "T", "B").forEachIndexed { i, v ->
            val on = i == current
            Key(if (on) voices[i] else MaterialTheme.colorScheme.surfaceVariant) {
                Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.surface else voices[i])
            }
        } }
    }
}

private const val PICK_MS = 2600
private const val CELL = 44
/** Each button's choices (glyphs), where the finger starts and which one it picks. */
private val PICKS = listOf(
    Triple((0..3).map { Smufl.MET_NOTES[it] }, 2, 1),
    Triple(listOf(-1, 0, 1).map(Smufl::accidental), 1, 2),
)

/**
 * The note-length button, then the accidental button: the finger presses, the choices open above it,
 * the finger slides onto one and lets go, and the button shows the pick.
 */
@Composable
private fun PickDemo(ink: Color) {
    val t by rememberInfiniteTransition(label = "pick").animateFloat(0f, 2f,
        infiniteRepeatable(tween(PICK_MS * 2, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val tool = t.toInt().coerceAtMost(1)
    val p = t - tool
    val (cells, from, to) = PICKS[tool]
    val open = p in 0.15f..0.8f
    val hover = from + (to - from) * FastOutSlowInEasing.transform(((p - 0.3f) / 0.35f).coerceIn(0f, 1f))
    val pocket = MaterialTheme.colorScheme.surfaceVariant
    val pick = MaterialTheme.colorScheme.primary
    val finger = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    // Two 56dp keys 10dp apart, centered in 260dp; the pocket sits centered over the pressed key
    val keyX = listOf(69, 135)
    Box(Modifier.size(260.dp, 140.dp)) {
        val pocketW = cells.size * CELL + 8
        val px = (keyX[tool] + 28 - pocketW / 2).coerceIn(0, 260 - pocketW)
        if (open) Row(Modifier.offset(px.dp, 6.dp).clip(RoundedCornerShape(12.dp)).background(pocket).padding(4.dp)) {
            cells.forEachIndexed { i, c ->
                Box(Modifier.size(CELL.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (i == hover.roundToInt()) pick.copy(alpha = 0.25f) else Color.Transparent), contentAlignment = Alignment.Center) {
                    GlyphIcon(c, 40, ink)
                }
            }
        }
        for (k in 0..1) {
            val (cs, f, d) = PICKS[k]
            // Before its turn a button shows its start; after letting go, the pick
            val shown = if (k < tool || (k == tool && p > 0.8f)) d else f
            Box(Modifier.offset(keyX[k].dp, 76.dp).size(56.dp).clip(RoundedCornerShape(12.dp))
                .background(if (k == tool && p > 0.05f && p < 0.8f) pick.copy(alpha = 0.18f) else pocket), contentAlignment = Alignment.Center) {
                GlyphIcon(cs[shown], 40, ink)
            }
        }
        // The finger: on the button, then on the hovered choice
        if (p > 0.05f && p < 0.85f) {
            val onPocket = ((p - 0.15f) / 0.15f).coerceIn(0f, 1f)
            val fx = keyX[tool] + 28 + ((px + 4 + CELL * hover + CELL / 2) - (keyX[tool] + 28)) * onPocket
            val fy = 104 + (6 + 4 + CELL / 2 - 104) * onPocket
            Box(Modifier.offset((fx - 18).dp, (fy - 18 + 14).dp).size(36.dp).clip(CircleShape).background(finger))
        }
    }
}

@Composable
private fun Keys(content: @Composable () -> Unit) =
    Row(Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }

/** A toolbar-like button picture. */
@Composable
private fun Key(color: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable () -> Unit) =
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(color),
        contentAlignment = Alignment.Center) { content() }
