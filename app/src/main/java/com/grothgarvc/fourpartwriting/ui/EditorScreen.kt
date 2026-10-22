package com.grothgarvc.fourpartwriting.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.grothgarvc.fourpartwriting.EditorViewModel
import com.grothgarvc.fourpartwriting.UiState
import com.grothgarvc.fourpartwriting.engrave.Smufl
import com.grothgarvc.fourpartwriting.model.Arrangement as StaffArrangement
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Mode
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Rule
import com.grothgarvc.fourpartwriting.theory.Severity
import kotlin.math.roundToInt

private val BASES = listOf(1, 2, 4, 8, 16, 32, 64)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val st by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    var tempoDialog by remember { mutableStateOf(false) }
    // Score-wide actions (clear, example) run at once and offer Undo instead of asking first
    var undoable by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.message) {
        st.message?.let { snack.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(undoable) {
        val m = undoable ?: return@LaunchedEffect
        if (snack.showSnackbar(m, actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.undo()
        undoable = null
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        // The score runs edge to edge (under the camera cutout); only the controls keep clear of it
        val safe = WindowInsets.safeDrawing
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            // Issues list: a fifth of the screen at most, so the score stays the main thing in landscape
            val listMax = (maxHeight * 0.2f).coerceIn(60.dp, 200.dp)
            val dir = LocalLayoutDirection.current
            val density = LocalDensity.current
            val edges = safe.getLeft(density, dir).toFloat() to safe.getRight(density, dir).toFloat()
            Column(Modifier.fillMaxSize()) {
                if (!wide) Row(Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ActionButtons(st, vm, onSettings = { settings = true }, leading = true)
                    Gap()
                    ActionButtons(st, vm, onSettings = { settings = true }, leading = false, below = true)
                }
                if (!wide) HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()
                    .then(if (wide) Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Top)) else Modifier)) {
                    ScoreCanvas(
                        st, vm::tap, vm::nudge, vm::dragPitch,
                        onTempoTap = { tempoDialog = true },
                        savedViewport = vm.viewport, onViewport = vm::rememberViewport,
                        edges = edges,
                    )
                    if (st.reorder.isNotEmpty()) ReorderPrompt(st, vm, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
                st.analysis?.let { IssuesStrip(st, vm, safe.only(WindowInsetsSides.Horizontal), listMax, oneLine = wide) }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (wide) { ActionButtons(st, vm, onSettings = { settings = true }, leading = true); Gap() }
                    VoiceButtons(st, vm)
                    Gap()
                    NoteTools(st, vm)
                    if (wide) { Gap(); ActionButtons(st, vm, onSettings = { settings = true }, leading = false) }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(safe).padding(bottom = 64.dp))
        }
    }
    if (settings) SettingsDialog(st, vm, onUndoable = { undoable = it }) { settings = false }
    if (tempoDialog) Dialog(onDismissRequest = { tempoDialog = false }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Tempo", style = MaterialTheme.typography.titleLarge)
                TempoEditor(st.score.tempo, vm)
                FilledTonalButton(onClick = { tempoDialog = false }, shape = SHAPE, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

/** Quarter-note tempo: type it, step it, or slide it. One undo step per change. */
@Composable
private fun TempoEditor(tempo: Int, vm: EditorViewModel) {
    var text by remember(tempo) { mutableStateOf(tempo.toString()) }
    fun commit(v: Int) { vm.setTempo(v); vm.commitTempo() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlyphIcon(Smufl.MET_NOTES[2], 36, MaterialTheme.colorScheme.onSurface)
            Text("=", style = MaterialTheme.typography.titleMedium)
            // Steps of 5, snapping to the 5-grid (83 -> 80 / 85); any value can still be typed
            Small("−") { commit((tempo - 1) / 5 * 5) }
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { s ->
                    text = s.filter { it.isDigit() }.take(3)
                    text.toIntOrNull()?.takeIf { it in 20..300 }?.let(::commit)
                },
                singleLine = true,
                shape = SHAPE,
                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.width(84.dp),
            )
            Small("+") { commit((tempo / 5 + 1) * 5) }
        }
        Slider(value = tempo.toFloat(), onValueChange = { vm.setTempo((it / 5).roundToInt() * 5) }, onValueChangeFinished = vm::commitTempo,
            valueRange = 30f..240f, steps = (240 - 30) / 5 - 1, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable private fun Gap() = Spacer(Modifier.width(6.dp))

/** Floating card: chords whose notes look entered in the wrong voices. */
@Composable
private fun ReorderPrompt(st: UiState, vm: EditorViewModel, modifier: Modifier) {
    val n = st.reorder.size
    val bar = (st.reorder.first() / st.score.time.measure).let { it.num / it.den + 1 }
    Surface(modifier, shape = RoundedCornerShape(14.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (n == 1) "Voices out of order in bar $bar." else "Voices out of order in $n chords (from bar $bar).",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = vm::keepOrder) { Text("Dismiss") }
            FilledTonalButton(onClick = vm::reorderVoices) { Text("Reorder S A T B") }
        }
    }
}

/** Shared corner shape for buttons, toggles and fields. */
private val SHAPE = RoundedCornerShape(12.dp)

/**
 * A square-ish toolbar button that grows to share the row. With [hold], holding it shows a drop
 * target ([hold].label) above it, or below when [below]; sliding onto it and letting go runs [hold].onDrop.
 * A small dot marks buttons that can be held; [menu] adds a ▾ corner mark for buttons that open a menu.
 */
@Composable
private fun RowScope.Tool(
    selected: Boolean = false,
    onClick: () -> Unit,
    label: String,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    hold: HoldTarget? = null,
    menu: Boolean = false,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val drop by rememberUpdatedState(hold?.onDrop)
    val below = hold?.below == true
    val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Box(
        Modifier.weight(1f).height(46.dp).padding(2.dp).clip(SHAPE)
            .background(if (selected) color else Color.Transparent)
            .semantics { contentDescription = label }
            .then(if (hold == null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier.semantics {
                role = Role.Button
                onClick(label) { click(); true }
                onLongClick(hold.label) { drop?.invoke(); true }
            }.pointerInput(below) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var released = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation().also { released = true }
                    }
                    if (released) { if (up != null) { up.consume(); click() }; return@awaitEachGesture }
                    shown = true
                    val reach = 14.dp.toPx()
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        over = if (below) ch.position.y > size.height + reach else ch.position.y < -reach
                        ch.consume()
                        if (!ch.pressed) break
                    }
                    if (over) drop?.invoke()
                    shown = false; over = false
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (hold != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.7f) else hint))
        if (menu) Text("▾", fontSize = 10.sp, color = hint, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 1.dp))
        if (shown && hold != null) {
            val gap = with(LocalDensity.current) { 52.dp.roundToPx() }
            Popup(alignment = if (below) Alignment.BottomCenter else Alignment.TopCenter, offset = IntOffset(0, if (below) gap else -gap)) {
                Surface(
                    shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp,
                    color = if (over) MaterialTheme.colorScheme.errorContainer.takeIf { hold.danger } ?: MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(hold.label, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** Two related toolbar buttons sharing one rounded tray. */
@Composable
private fun RowScope.Pill(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.weight(2f).height(46.dp).padding(2.dp).clip(SHAPE).background(MaterialTheme.colorScheme.surfaceVariant), content = content)

/** What a held toolbar button offers: slide onto [label] and let go to run [onDrop]. */
private class HoldTarget(val label: String, val below: Boolean, val danger: Boolean = false, val onDrop: () -> Unit)

@Composable
private fun RowScope.ActionButtons(st: UiState, vm: EditorViewModel, onSettings: () -> Unit, leading: Boolean, below: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    if (leading) {
        Tool(st.playing, vm::togglePlay, if (st.playing) "Stop" else "Play") { Text(if (st.playing) "■" else "▶", fontSize = 20.sp, color = ink) }
        Tool(st.analysis != null, vm::check, "Check") { Text("✓", fontSize = 22.sp, color = ink, fontWeight = FontWeight.Bold) }
        Pill {
            Tool(onClick = { vm.step(-1) }, label = "Previous note") { Text("‹", fontSize = 30.sp, color = ink) }
            Tool(onClick = { vm.step(1) }, label = "Next note") { Text("›", fontSize = 30.sp, color = ink) }
        }
    } else {
        Pill {
            Tool(onClick = vm::undo, label = "Undo") { Text("↶", fontSize = 22.sp, color = ink) }
            Tool(onClick = vm::redo, label = "Redo") { Text("↷", fontSize = 22.sp, color = ink) }
        }
        val bar = st.selected?.let { r -> st.score.onsets(r.voice).getOrNull(r.index) }?.let { (it / st.score.time.measure).let { f -> f.num / f.den + 1 } }
        Tool(onClick = vm::delete, label = "Delete", hold = HoldTarget(if (bar != null) "🗑  Delete bar $bar" else "🗑  Select a note first", below, danger = true, onDrop = vm::deleteMeasure)) {
            Text("⌫", fontSize = 20.sp, color = ink)
        }
        Tool(onClick = onSettings, label = "Settings") { Text("⚙", fontSize = 20.sp, color = ink) }
    }
}

@Composable
private fun RowScope.VoiceButtons(st: UiState, vm: EditorViewModel) {
    for (v in Voice.entries) {
        val col = VOICE_COLORS[v.ordinal]
        Tool(st.shownVoice == v, { vm.selectVoice(v) }, "Voice ${v.label}", color = col, hold = HoldTarget("🔒  ${v.label} only", below = false) { vm.lockVoice(v) }) {
            Text(v.label + if (st.locked == v) "🔒" else "", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (st.shownVoice == v) Color.White else col)
        }
    }
}

@Composable
private fun RowScope.NoteTools(st: UiState, vm: EditorViewModel) {
    val ink = MaterialTheme.colorScheme.onSurface
    val d = st.shownDuration
    val idx = BASES.indexOf(d.base)
    var durOpen by remember { mutableStateOf(false) }
    var accOpen by remember { mutableStateOf(false) }
    val sel = st.selectedEvent

    Box(Modifier.weight(1f)) {
        Row { Tool(onClick = { durOpen = true }, label = "Note length", menu = true) { GlyphIcon(Smufl.MET_NOTES[idx], 40, ink) } }
        DropdownMenu(durOpen, { durOpen = false }, shape = SHAPE) {
            Row {
                BASES.forEachIndexed { i, b ->
                    Box(Modifier.size(48.dp).clip(SHAPE)
                        .background(if (b == d.base) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { vm.setDuration(b); durOpen = false }, contentAlignment = Alignment.Center) {
                        GlyphIcon(Smufl.MET_NOTES[i], 42, ink)
                    }
                }
            }
        }
    }
    Tool(d.dots > 0, vm::toggleDot, "Dot") { Text(if (d.dots == 2) "··" else "·", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink) }
    Tool(sel != null && sel.pitch == null, vm::rest, "Rest") { GlyphIcon(Smufl.RESTS[idx], 40, ink) }
    val p = sel?.pitch
    val shownAlter = if (p != null) p.alter.takeIf { it != st.score.key.sigAlter(p.step) } else st.accidental
    Box(Modifier.weight(1f)) {
        Row {
            Tool(shownAlter != null, { accOpen = true }, "Accidental", menu = true) {
                GlyphIcon(Smufl.accidental(shownAlter ?: 0), 40, if (shownAlter == null) ink.copy(alpha = 0.5f) else ink)
            }
        }
        DropdownMenu(accOpen, { accOpen = false }, shape = SHAPE) {
            Row {
                for (a in listOf(-2, -1, 0, 1, 2)) Box(Modifier.size(48.dp).clip(SHAPE)
                    .background(if (a == shownAlter) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { vm.setAccidental(a); accOpen = false }, contentAlignment = Alignment.Center) {
                    GlyphIcon(Smufl.accidental(a), 42, ink)
                }
            }
        }
    }
    // "⁀" sits high in the font; nudge it to the button's middle
    Tool(onClick = vm::tie, label = "Tie") { Text("⁀", fontSize = 30.sp, color = ink, modifier = Modifier.offset(y = 7.dp)) }
}

@Composable
private fun IssuesStrip(st: UiState, vm: EditorViewModel, insets: WindowInsets, maxList: Dp, oneLine: Boolean) {
    val a = st.analysis ?: return
    // Landscape has little height: start with just the summary line
    var open by remember { mutableStateOf(!oneLine) }
    val errors = a.issues.count { it.severity == Severity.ERROR }
    val warnings = a.issues.size - errors
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(insets)) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(44.dp).clickable { open = !open }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.issues.isEmpty()) Text("No problems found ✓", style = MaterialTheme.typography.titleSmall, color = OK_GREEN)
                else {
                    if (errors > 0) Badge("$errors error${if (errors == 1) "" else "s"}", ERROR_RED)
                    if (warnings > 0) Badge("$warnings warning${if (warnings == 1) "" else "s"}", WARN_AMBER)
                    Text(if (open) "▾" else "▴", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(44.dp).clickable(onClick = vm::closeAnalysis).semantics { contentDescription = "Close check" },
                    contentAlignment = Alignment.Center) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (open && a.issues.isNotEmpty()) LazyColumn(Modifier.heightIn(max = maxList)) {
                itemsIndexed(a.issues) { i, issue ->
                    val t = a.slices.getOrNull(issue.slice)?.time ?: Fraction.ZERO
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .background(if (st.focusedIssue == i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { vm.focusIssue(i) },
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (issue.severity == Severity.ERROR) ERROR_RED else WARN_AMBER))
                        val head = "${where(t, st.score.time)} · ${issue.rule.label}"
                        val pad = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        if (oneLine) Row(pad, verticalAlignment = Alignment.CenterVertically) {
                            Text(head, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(10.dp))
                            Text(issue.message, style = MaterialTheme.typography.bodySmall)
                        } else Column(pad) {
                            Text(head, style = MaterialTheme.typography.labelLarge)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private val ERROR_RED = Color(0xFFC62828)
private val WARN_AMBER = Color(0xFFB85C00)
private val OK_GREEN = Color(0xFF2E7D32)

/** Small tinted count label, e.g. "2 warnings". */
@Composable
private fun Badge(text: String, color: Color) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))

private fun where(t: Fraction, time: TimeSig): String {
    val ml = time.measure
    val bar = (t / ml).let { it.num / it.den }
    val beat = ((t - ml * Fraction.of(bar, 1)) / Fraction.of(1, time.beatUnit.toLong())).toDouble() + 1
    val b = if (beat % 1.0 == 0.0) beat.toInt().toString() else "%.2f".format(beat).trimEnd('0')
    return "Bar ${bar + 1}, beat $b"
}

// ---------- settings ----------

private fun keyLabel(k: Key) = k.name + when {
    k.fifths > 0 -> "  ·  ${k.fifths}♯"; k.fifths < 0 -> "  ·  ${-k.fifths}♭"; else -> ""
}

@Composable
private fun SettingsDialog(st: UiState, vm: EditorViewModel, onUndoable: (String) -> Unit, onDismiss: () -> Unit) {
    val sf2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSf2) }
    val xmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.recordare.musicxml+xml")) { uri ->
        uri?.let(vm::exportMusicXml)
    }
    val score = st.score
    var rulesOpen by remember { mutableStateOf(false) }
    if (rulesOpen) RulesDialog(st, vm) { rulesOpen = false }
    FullScreenPage("Score settings", onDismiss) {
        item {
            Group("Notation") {
                Label("Key")
                val k = score.key
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("♭", glyph = Smufl.FLAT, enabled = k.fifths > -7) { vm.setKey(k.copy(fifths = k.fifths - 1)) }
                    Text(keyLabel(k), Modifier.weight(1f), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    Small("♯", glyph = Smufl.SHARP, enabled = k.fifths < 7) { vm.setKey(k.copy(fifths = k.fifths + 1)) }
                    Segmented(listOf("maj", "min"), if (k.mode == Mode.MAJOR) 0 else 1) { vm.setKey(k.copy(mode = Mode.entries[it])) }
                }
                Label("Time signature")
                val t = score.time
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Small("−", enabled = t.beats > 1) { vm.setTime(TimeSig(t.beats - 1, t.beatUnit)) }
                    Text("${t.beats}", Modifier.width(28.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium)
                    Small("+", enabled = t.beats < 16) { vm.setTime(TimeSig(t.beats + 1, t.beatUnit)) }
                    Text("/", style = MaterialTheme.typography.titleMedium)
                    Segmented(listOf("2", "4", "8", "16"), listOf(2, 4, 8, 16).indexOf(t.beatUnit)) { vm.setTime(TimeSig(t.beats, listOf(2, 4, 8, 16)[it])) }
                }
                Label("Staves")
                // "/" = staff break; SAT/B is keyboard style
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in StaffArrangement.entries) Choice(score.arrangement == a, a.label) { vm.setArrangement(a) }
                }
                val names = score.arrangement.label.split("/")
                for (i in score.clefs.indices) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Staff ${i + 1} (${names[i]})", Modifier.weight(1f))
                    Picker(score.clefs[i].label, Clef.entries, { it.label }) { vm.setClef(i, it) }
                }
            }
        }
        item {
            Group("Playback") {
                Label("Tempo")
                TempoEditor(score.tempo, vm)
                Label("Sound")
                Text(if (st.soundLoaded) "Piano SoundFont loaded." else "No SoundFont loaded. Pick a piano .sf2 file.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { sf2Picker.launch(arrayOf("*/*")) }, shape = SHAPE) { Text("Load .sf2 file…") }
            }
        }
        item {
            Group("Writing") {
                SwitchRow("After each note, move to the next voice (S→A→T→B)", st.autoVoice, vm::toggleAutoVoice)
                Row(Modifier.fillMaxWidth().clip(SHAPE).clickable { rulesOpen = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rules to check", Modifier.weight(1f))
                    Text("${st.rules.size} of ${Rule.entries.size} on  ›", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Group("Score") {
                OutlinedButton(onClick = { xmlSaver.launch("satb.musicxml") }, shape = SHAPE) { Text("Export MusicXML…") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.loadExample(); onDismiss(); onUndoable("Example loaded") }, shape = SHAPE) { Text("Load example") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.clearScore(); onDismiss(); onUndoable("Score cleared") }, shape = SHAPE,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear score") }
                }
            }
        }
        item {
            Text("Music font: Bravura © Steinberg Media Technologies, SIL Open Font License.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 4.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun RulesDialog(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    FullScreenPage("Rules to check", onDismiss) {
        item {
            Group(null) { for (r in Rule.entries) key(r.name) { SwitchRow(r.label, r in st.rules) { vm.toggleRule(r) } } }
        }
    }
}

/** Full-screen page with a back arrow, drawn under the status bar like the editor. */
@Composable
private fun FullScreenPage(title: String, onDismiss: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog has its own window: give it dark status bar icons on the light page too
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = true }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clickable(onClick = onDismiss).semantics { contentDescription = "Back" },
                        contentAlignment = Alignment.Center) { Text("←", fontSize = 22.sp) }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
        }
    }
}

/** A titled card holding one group of settings. */
@Composable
private fun Group(title: String?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Readable width on tablets and in landscape
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Square stepper button; [glyph] draws a music symbol (e.g. ♭) in the Bravura font instead of [label]. */
@Composable
private fun Small(label: String, glyph: Char? = null, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, shape = SHAPE, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        if (glyph != null) GlyphIcon(glyph, 46, MaterialTheme.colorScheme.onSurface) else Text(label, fontSize = 20.sp)
    }

/** Compact connected toggle buttons. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(SHAPE).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.height(48.dp).width(48.dp).padding(3.dp).clip(RoundedCornerShape(9.dp))
                .background(if (i == selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(SHAPE).clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) FilledTonalButton(onClick = onClick, shape = SHAPE) { Text(label, fontWeight = FontWeight.Bold) }
    else OutlinedButton(onClick = onClick, shape = SHAPE) { Text(label) }
}

@Composable
private fun <T> Picker(current: String, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = SHAPE) { Text("$current  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = SHAPE) {
            for (o in options) DropdownMenuItem(text = { Text(label(o)) }, onClick = { onPick(o); open = false })
        }
    }
}
