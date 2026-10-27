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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Welcome plus a few swipeable pages on how the editor works. Shown once; Settings can show it again. */
@Composable
fun Tutorial(onDone: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val voices = LocalScoreColors.current.voices
    val pages = listOf(
        Page("Welcome to SATB", "Write four-part harmony and get instant feedback.") { Logo() },
        Page("Write notes", "Pick a voice and tap the staff, or hold and slide to aim the note, then let go. To change a note later, tap it and drag.") {
            Keys { listOf("S", "A", "T", "B").forEachIndexed { i, v -> Key { Text(v, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = voices[i]) } } }
        },
        Page("Length and accidentals", "Press, slide and let go to pick one.") {
            Keys {
                Key { GlyphIcon(Smufl.MET_NOTES[3], 40, ink) }
                Key { GlyphIcon(Smufl.SHARP, 40, ink) }
            }
        },
        Page("Check and listen", "✓ checks your harmony, ▶ plays it.") {
            Keys { Key { Text("✓", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink) }; Key { Text("▶", fontSize = 22.sp, color = ink) } }
        },
    )
    val pager = rememberPagerState { pages.size }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(pages.size) { i ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                        Text(if (last) "Start writing" else "Next")
                    }
                }
            }
        }
    }
}

/** The app icon (background and foreground layers) as a rounded tile. */
@Composable
private fun Logo() = Box(Modifier.size(132.dp).clip(RoundedCornerShape(32.dp))) {
    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
    // Launchers show the middle 72 of the 108dp icon canvas; scale up to match
    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
}

@Composable
private fun Keys(content: @Composable () -> Unit) =
    Row(Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }

/** A toolbar-like button picture. */
@Composable
private fun Key(content: @Composable () -> Unit) =
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) { content() }
