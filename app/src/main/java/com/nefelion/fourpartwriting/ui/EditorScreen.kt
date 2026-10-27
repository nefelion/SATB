package com.nefelion.fourpartwriting.ui

import androidx.compose.material3.AlertDialog
import com.nefelion.fourpartwriting.R
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.luminance
import com.nefelion.fourpartwriting.ui.theme.LocalScoreColors
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.engrave.tupletLabel
import com.nefelion.fourpartwriting.model.Arrangement as StaffArrangement
import com.nefelion.fourpartwriting.model.Clef
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.ChordText
import com.nefelion.fourpartwriting.theory.FiguredBass
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import com.nefelion.fourpartwriting.theory.Severity
import kotlin.math.roundToInt

private val BASES = listOf(1, 2, 4, 8, 16, 32, 64)

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val st by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var settings by rememberSaveable { mutableStateOf(false) }
    var tempoDialog by rememberSaveable { mutableStateOf(false) }
    // Score-wide actions offer Undo; Clear also asks first
    var undoable by remember { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var scores by rememberSaveable { mutableStateOf(false) }
    var figures by rememberSaveable { mutableStateOf(false) }
    var exercise by rememberSaveable { mutableStateOf(false) }
    var chords by rememberSaveable { mutableStateOf(false) }
    var bassX by remember { mutableFloatStateOf(-1f) }
    // The figures pocket belongs to the bass: leaving the bass voice closes it
    LaunchedEffect(st.shownVoice) { if (st.shownVoice != Voice.BASS) figures = false }
    // Generic type: providers keep an unknown .musicxml extension only then, so a duplicate becomes "satb (1).musicxml"
    val xmlSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(vm::exportMusicXml)
    }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    val more: @Composable RowScope.() -> Unit = {
        MoreButton(moreOpen, { moreOpen = it }) { item ->
            item("Scores", false) { scores = true }
            HorizontalDivider()
            if (st.score.given.isNotEmpty()) {
                item("Show a solution", false) { vm.showSolution() }
                item("New exercise", false) { exercise = true }
                HorizontalDivider()
            }
            item("Hint", false) { vm.hint() }
            if (st.selected != null) item("Fix this chord", false) { vm.fixChord() }
            // Again while open: closes it, like Done
            item("Specify chord", false) { if (chords) chords = false else if (vm.startChords()) { chords = true; figures = false } }
            item("Export MusicXML", false) { xmlSaver.launch("${st.scoreName}.musicxml") }
            item("Clear score", true) { confirmClear = true }
            HorizontalDivider()
            item("Settings", false) { settings = true }
            item("About", false) { about = true }
        }
    }
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
                    ActionButtons(st, vm, more = more, leading = true)
                    Gap()
                    ActionButtons(st, vm, more = more, leading = false, below = true)
                }
                if (!wide) HorizontalDivider()
                // Clip: zoomed-in music must stay under the toolbars, not paint over them
                Box(Modifier.weight(1f).fillMaxWidth()
                    .then(if (wide) Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Top)) else Modifier).clipToBounds()) {
                    val haptic = LocalHapticFeedback.current
                    ScoreCanvas(
                        st, { if (vm.tap(it)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, vm::press, vm::nudge, vm::dragPitch,
                        caretAtSelection = chords || figures,
                        onTempoTap = { tempoDialog = true },
                        savedViewport = vm.viewport, onViewport = vm::rememberViewport,
                        edges = edges,
                    )
                    if (st.reorder.isNotEmpty()) ReorderPrompt(st, vm, Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
                st.analysis?.let { IssuesStrip(st, vm, safe.only(WindowInsetsSides.Horizontal), listMax, oneLine = wide, onUndoable = { undoable = it }) }
                if (figures) { HorizontalDivider(); FiguresPanel(st, vm, safe.only(WindowInsetsSides.Horizontal)) { figures = false } }
                if (chords) { HorizontalDivider(); ChordPanel(st, vm, safe.only(WindowInsetsSides.Horizontal)) { chords = false } }
                HorizontalDivider()
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (wide) { ActionButtons(st, vm, more = more, leading = true); Gap() }
                        VoiceButtons(st, vm) { bassX = it }
                        Gap()
                        NoteTools(st, vm)
                        if (wide) { Gap(); ActionButtons(st, vm, more = more, leading = false) }
                    }
                    // A pocket above the B button: bass figures (not in exercises, whose bass is fixed)
                    if (st.shownVoice == Voice.BASS && !st.moving && Voice.BASS !in st.score.given && !figures && bassX >= 0)
                        FiguresTab(Modifier.align(Alignment.TopStart), bassX) { if (vm.startFigures()) { figures = true; chords = false } }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(safe).padding(bottom = 64.dp))
        }
    }
    if (settings) SettingsDialog(st, vm) { settings = false }
    if (about) AboutDialog { about = false }
    if (exercise) ExerciseDialog(Voice.SOPRANO in st.score.given, onDismiss = { exercise = false }) { level, soprano -> exercise = false; vm.newExercise(level, soprano) }
    if (scores) ScoresPage(st, vm) { scores = false }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear score?") },
        text = { KeepRefreshRate(); Text("All notes will be removed.") },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; vm.clearScore(); undoable = "Score cleared" },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    // Tapping the tempo, clef, key or time signature: the score's settings, applied as one undo step
    if (tempoDialog) ScoreDialog("Score settings", st.scoreName, st.score, askName = false, action = "Save",
        onDismiss = { tempoDialog = false }) { _, settings -> tempoDialog = false; vm.editScore(st.scoreName, settings) }
}

/** Tempo in beats of [time]: type it, step it, or slide it. [onChange] runs live; [onCommit] ends one change (one undo step in the editor). */
@Composable
internal fun TempoEditor(tempo: Int, time: TimeSig, onChange: (Int) -> Unit, onCommit: () -> Unit) {
    var text by remember(tempo) { mutableStateOf(tempo.toString()) }
    fun commit(v: Int) { onChange(v); onCommit() }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlyphIcon(Smufl.beatMark(time), 36, MaterialTheme.colorScheme.onSurface)
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                // Leaving the field drops an unfinished or out-of-range number and shows the tempo in use
                modifier = Modifier.width(84.dp).onFocusChanged { if (!it.isFocused) text = tempo.toString() },
            )
            Small("+") { commit((tempo / 5 + 1) * 5) }
        }
        Slider(value = tempo.toFloat(), onValueChange = { onChange((it / 5).roundToInt() * 5) }, onValueChangeFinished = onCommit,
            valueRange = 30f..240f, steps = (240 - 30) / 5 - 1, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable private fun Gap() = Spacer(Modifier.width(6.dp))

/** Tapping empty space ends text editing: the caret and keyboard go away. */
@Composable
internal fun Modifier.clearFocusOnTap(): Modifier {
    val focus = LocalFocusManager.current
    return pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
}

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

/** Tab sticking up from the bottom bar, centered on [centerX] (root px), that opens the figures panel. */
@Composable
private fun FiguresTab(modifier: Modifier, centerX: Float, onOpen: () -> Unit) {
    val col = LocalScoreColors.current.voices[Voice.BASS.ordinal]
    val w = 104.dp; val h = 30.dp
    Box(modifier
        .offset { IntOffset((centerX - w.toPx() / 2).roundToInt().coerceAtLeast(0), -h.roundToPx()) }
        .size(w, h).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(col)
        .clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = "Bass figures" },
        contentAlignment = Alignment.Center) {
        Text("Figures ▴", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** Figures for the selected bass note: common stacks, or built line by line (an accidental, then a number). Each key is one undo step; holding ⌫ clears. */
@Composable
private fun FiguresPanel(st: UiState, vm: EditorViewModel, insets: WindowInsets, onClose: () -> Unit) {
    val e = st.selected?.takeIf { it.voice == Voice.BASS }?.let { st.score.voice(Voice.BASS).getOrNull(it.index) }?.takeIf { it.pitch != null }
    val lines = e?.figures.orEmpty()
    val ink = MaterialTheme.colorScheme.onSurface
    fun key(k: Char) {
        val l = lines.toMutableList()
        // A digit after a lone accidental completes that line ("#" then "6" = "#6")
        if (k.isDigit() && l.lastOrNull()?.let { it.length == 1 && it[0] in "#bn" } == true) l[l.lastIndex] += k
        else if (l.size < 4) l += "$k"
        vm.setFigures(l)
    }
    @Composable fun Stack(f: List<String>, size: Int = 15) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (l in f) Text(FiguredBass.display(l), fontSize = size.sp, lineHeight = (size + 1).sp, color = ink, fontFamily = FontFamily.Serif)
    }
    Column(Modifier.fillMaxWidth().windowInsetsPadding(insets).padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(onClick = { vm.stepBass(-1) }, label = "Previous bass note") { Text("‹", fontSize = 30.sp, color = ink) }
            if (e == null) Text("Select a bass note", Modifier.weight(6f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else for (f in listOf(listOf("6"), listOf("6", "4"), listOf("7"), listOf("6", "5"), listOf("4", "3"), listOf("4", "2")))
                Tool(lines == f, { vm.setFigures(f) }, "Figures " + f.joinToString(" ")) { Stack(f) }
            Tool(onClick = { vm.stepBass(1) }, label = "Next bass note") { Text("›", fontSize = 30.sp, color = ink) }
            TextButton(onClick = onClose) { Text("Done") }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            for (k in "#bn2345679") Tool(onClick = { key(k) }, label = if (k.isDigit()) "$k" else FiguredBass.display("$k")) {
                if (k.isDigit()) Text("$k", fontSize = 19.sp, color = ink, fontFamily = FontFamily.Serif)
                else GlyphIcon(Smufl.accidental(when (k) { '#' -> 1; 'b' -> -1; else -> 0 }), 40, ink)
            }
            Tool(onClick = {
                val l = lines.toMutableList()
                if (l.isNotEmpty()) { l[l.lastIndex] = l.last().dropLast(1); if (l.last().isEmpty()) l.removeAt(l.lastIndex) }
                vm.setFigures(l)
            }, label = "Delete figure", long = { vm.setFigures(emptyList()) }) { Text("⌫", fontSize = 18.sp, color = ink) }
        }
    }
}

/** Chord name for the selected note: a common chord of the key in one tap, or any name typed (applied on Enter, ‹ › or Done). */
@Composable
private fun ChordPanel(st: UiState, vm: EditorViewModel, insets: WindowInsets, onClose: () -> Unit) {
    val e = st.selectedEvent?.takeIf { it.pitch != null }
    val ink = MaterialTheme.colorScheme.onSurface
    var text by rememberSaveable(st.selected, e?.chord) { mutableStateOf(e?.chord.orEmpty()) }
    val bad = text.isNotBlank() && ChordText.parse(text, st.score.key) == null
    fun apply() { if (e != null && text.trim() != e.chord.orEmpty()) vm.setChord(text) }
    // However the panel closes (Done, the menu), a typed name is kept
    val last by rememberUpdatedState(::apply)
    DisposableEffect(Unit) { onDispose { last() } }
    val common = if (st.score.key.mode == Mode.MAJOR) listOf("I", "ii", "IV", "V", "V7", "vi") else listOf("i", "iv", "V", "V7", "VI", "ii°6")
    Column(Modifier.fillMaxWidth().windowInsetsPadding(insets).padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(onClick = { apply(); vm.step(-1) }, label = "Previous note") { Text("‹", fontSize = 30.sp, color = ink) }
            if (e == null) Text("Select a note", Modifier.weight(6f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else for (c in common) Tool(e.chord == c, { text = c; vm.setChord(c) }, "Chord $c") {
                Text(c, fontSize = 17.sp, color = ink, fontFamily = FontFamily.Serif)
            }
            Tool(onClick = { apply(); vm.step(1) }, label = "Next note") { Text("›", fontSize = 30.sp, color = ink) }
            TextButton(onClick = { apply(); onClose() }) { Text("Done") }
        }
        androidx.compose.material3.OutlinedTextField(
            value = text, onValueChange = { text = it.take(16) }, singleLine = true, shape = SHAPE, isError = bad, enabled = e != null,
            placeholder = { Text("Any chord: C, Am7, G7/B, V65, V7/V") },
            supportingText = if (bad) ({ Text("Not a chord name") }) else null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { apply() }),
            trailingIcon = { if (e?.chord != null) TextButton(onClick = { text = ""; vm.setChord("") }) { Text("Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** Shared corner shape for buttons, toggles and fields. */
internal val SHAPE = RoundedCornerShape(12.dp)

/**
 * A square-ish toolbar button that grows to share the row. With [hold], holding it shows a drop
 * target ([hold].label) above it, or below when [below]; sliding onto it and letting go runs [hold].onDrop.
 * The target shows as soon as the finger is down; a quick release in place is a tap, while a long
 * hold released in place, or sliding elsewhere, cancels. A small dot marks buttons with a target; [menu] adds a ▾ corner mark.
 * [press] replaces all of this with a custom gesture; it gets the down event and a local-to-screen mapper.
 */
@Composable
private fun RowScope.Tool(
    selected: Boolean = false,
    onClick: () -> Unit,
    label: String,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
    hold: HoldTarget? = null,
    menu: Boolean = false,
    press: (suspend AwaitPointerEventScope.(PointerInputChange, (Offset) -> Offset) -> Unit)? = null,
    /** Plain long-press action (no drop target), with a haptic tick. */
    long: (() -> Unit)? = null,
    place: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var shown by remember { mutableStateOf(false) }
    var over by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val drop by rememberUpdatedState(hold?.onDrop)
    val onPress by rememberUpdatedState(press)
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val below = hold?.below == true
    val hint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Box(
        Modifier.weight(1f).then(place).height(46.dp).padding(2.dp).clip(SHAPE)
            .background(if (selected) color else Color.Transparent)
            .semantics { contentDescription = label }
            // Buttons at the screen edge would otherwise wait for Android's back-swipe check before a press arrives
            .then(if (press != null || hold != null) Modifier.systemGestureExclusion() else Modifier)
            .then(if (press != null) Modifier.semantics { role = Role.Button; onClick(label) { click(); true } }
                .onGloballyPositioned { coords[0] = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val f = onPress ?: return@awaitEachGesture
                        f(down) { p -> coords[0]?.takeIf { it.isAttached }?.localToScreen(p) ?: p }
                    }
                }
            else if (long != null) Modifier.combinedClickable(role = Role.Button, onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress); long()
            }, onClick = onClick)
            else if (hold == null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier.semantics {
                role = Role.Button
                onClick(label) { click(); true }
                onLongClick(hold.label) { drop?.invoke(); true }
            }.pointerInput(below) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    shown = true
                    val reach = 14.dp.toPx()
                    var moved = false
                    var lifted = false
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        over = if (below) ch.position.y > size.height + reach else ch.position.y < -reach
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        ch.consume()
                        if (!ch.pressed) { lifted = true; break }
                    }
                    val quick = currentEvent.changes.firstOrNull()?.let { it.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis } == true
                    if (over) drop?.invoke() else if (lifted && !moved && quick) click()
                    shown = false; over = false
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (hold != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.7f) else hint))
        if (menu) Text("▾", fontSize = 10.sp, color = hint, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 1.dp))
        if (shown && hold != null) {
            val gap = with(LocalDensity.current) { 52.dp.roundToPx() }
            Popup(alignment = if (below) Alignment.BottomCenter else Alignment.TopCenter, offset = IntOffset(0, if (below) gap else -gap)) {
                KeepRefreshRate()
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

/** Places a popup just above its anchor ([gap] px), centered on it and kept inside the window. */
private class AboveAnchor(val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(
            (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0),
        )
}

/** What a held toolbar button offers: slide onto [label] and let go to run [onDrop]. */
private class HoldTarget(val label: String, val below: Boolean, val danger: Boolean = false, val onDrop: () -> Unit)

@Composable
private fun RowScope.ActionButtons(st: UiState, vm: EditorViewModel, more: @Composable RowScope.() -> Unit, leading: Boolean, below: Boolean = false) {
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
        val bar = st.selectedTime?.let { (it / st.score.time.measure).let { f -> f.num / f.den + 1 } }
        Tool(onClick = vm::delete, label = "Delete", hold = HoldTarget(if (bar != null) "🗑  Delete bar $bar" else "🗑  Select a note first", below, danger = true, onDrop = vm::deleteMeasure)) {
            Text("⌫", fontSize = 20.sp, color = ink)
        }
        more()
    }
}

/** ⋮ overflow menu. [items] adds entries with item(label, danger, action); picking one closes the menu. Open state is hoisted so it survives rotation. */
@Composable
private fun RowScope.MoreButton(open: Boolean, onOpen: (Boolean) -> Unit, items: @Composable (item: @Composable (String, Boolean, () -> Unit) -> Unit) -> Unit) {
    Tool(onClick = { onOpen(true) }, label = "More options") {
        Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }, shape = SHAPE) {
            KeepRefreshRate()
            items { label, danger, action ->
                DropdownMenuItem(text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    onClick = { onOpen(false); action() })
            }
        }
    }
}

/** App logo (the launcher icon's two layers, cut to a circle), name, version, license and a link to the source. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    val uri = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Dialog(onDismissRequest = onDismiss) {
        KeepRefreshRate()
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Adaptive icon layers are 108dp with the visible part in the middle 72dp: draw them 1.5× and clip
                Box(Modifier.size(96.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    for (res in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground))
                        Image(painterResource(res), null, Modifier.requiredSize(144.dp))
                }
                Text("SATB", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = muted)
                Text("Free and open-source software (GPLv3)", style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center)
                FilledTonalButton(onClick = { runCatching { uri.openUri("https://github.com/nefelion/FourPartWriting") } }, shape = SHAPE) {
                    Text("Source code on GitHub")
                }
                Text("Music font: Bravura © Steinberg Media Technologies, SIL Open Font License.",
                    style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RowScope.VoiceButtons(st: UiState, vm: EditorViewModel, onBassX: (Float) -> Unit = {}) {
    for (v in Voice.entries) {
        val col = LocalScoreColors.current.voices[v.ordinal]
        val on = st.shownVoice == v && !st.moving
        Tool(on, { vm.selectVoice(v) }, "Voice ${v.label}", color = col, long = { vm.lockVoice(v) },
            place = if (v == Voice.BASS) Modifier.onGloballyPositioned { onBassX(it.boundsInRoot().center.x) } else Modifier) {
            Text(v.label + if (st.locked == v) "🔒" else "", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (on) MaterialTheme.colorScheme.surface else col)
        }
    }
}

@Composable
private fun RowScope.NoteTools(st: UiState, vm: EditorViewModel) {
    val ink = MaterialTheme.colorScheme.onSurface
    val d = st.shownDuration
    val idx = BASES.indexOf(d.base)
    val sel = st.selectedEvent
    val tup = st.shownTuplet
    val tupLabel = tup?.let { (n, m) -> tupletLabel(n, m) }
    var tupletDialog by rememberSaveable { mutableStateOf(false) }
    if (tupletDialog) TupletDialog(tup ?: (3 to 2), onPick = { n, m -> tupletDialog = false; vm.setTuplet(n, m) }) { tupletDialog = false }
    // Last cell: triplet on/off; hold it for any tuplet
    PickTool("Note length", selected = false, count = BASES.size + 1, current = idx, onPick = { if (it == BASES.size) vm.toggleTriplet() else vm.setDuration(BASES[it]) },
        holdItem = BASES.size, onHold = { tupletDialog = true },
        icon = {
            Box {
                GlyphIcon(Smufl.MET_NOTES[idx], 40, ink)
                if (tupLabel != null) Text(tupLabel, fontSize = if (tupLabel.length > 2) 9.sp else 13.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                    color = ink, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp))
            }
        }) {
        if (it == BASES.size) (tupLabel ?: "3").let { l ->
            Text(l, fontSize = when { l.length == 1 -> 24.sp; l.length <= 3 -> 18.sp; else -> 13.sp }, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                maxLines = 1, softWrap = false, color = if (tup != null) MaterialTheme.colorScheme.primary else ink)
        }
        else GlyphIcon(Smufl.MET_NOTES[it], 42, ink)
    }
    Tool(d.dots > 0, vm::toggleDot, "Dot") { Text(if (d.dots == 2) "··" else "·", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink) }
    Tool(sel != null && sel.pitch == null, vm::rest, "Rest") { GlyphIcon(Smufl.RESTS[idx], 40, ink) }
    val p = sel?.pitch
    val shownAlter = if (p != null) p.alter.takeIf { it != st.score.key.sigAlter(p.step) } else st.accidental
    PickTool("Accidental", selected = shownAlter != null, count = ALTERS.size, current = ALTERS.indexOf(shownAlter), onPick = { vm.setAccidental(ALTERS[it]) },
        icon = { GlyphIcon(Smufl.accidental(shownAlter ?: 0), 40, if (shownAlter == null) ink.copy(alpha = 0.5f) else ink) }) {
        GlyphIcon(Smufl.accidental(ALTERS[it]), 42, ink)
    }
    // "⁀" sits high in the font; nudge it to the button's middle
    Tool(onClick = vm::tie, label = "Tie") { Text("⁀", fontSize = 30.sp, color = ink, modifier = Modifier.offset(y = 7.dp)) }
}

private val ALTERS = listOf(-2, -1, 0, 1, 2)

/** Picks any tuplet: [start] fills the fields. */
@Composable
private fun TupletDialog(start: Pair<Int, Int>, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var n by rememberSaveable { mutableStateOf(start.first.toString()) }
    var m by rememberSaveable { mutableStateOf(start.second.toString()) }
    val nv = n.toIntOrNull()?.takeIf { it >= 1 }
    val mv = m.toIntOrNull()?.takeIf { it >= 1 }
    @Composable fun Field(text: String, onChange: (String) -> Unit) = androidx.compose.material3.OutlinedTextField(
        value = text, onValueChange = { s -> onChange(s.filter { it.isDigit() }.take(2)) }, singleLine = true, shape = SHAPE,
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = Modifier.width(72.dp))
    Dialog(onDismissRequest = onDismiss) {
        KeepRefreshRate()
        Surface(Modifier.clearFocusOnTap(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Tuplet", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(n) { n = it }
                    Text("in the time of")
                    Field(m) { m = it }
                }
                Text("Notes of the chosen length: e.g. 5 in the time of 4 sixteenths.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    FilledTonalButton(onClick = { onPick(nv!!, mv!!) }, enabled = nv != null && mv != null, shape = SHAPE) { Text("Set") }
                }
            }
        }
    }
}

/**
 * Toolbar button with a pocket of [count] choices drawn by [item]. Pressing opens the pocket at once:
 * slide onto a choice and let go to pick it. A tap leaves it open for a normal pick; sliding off cancels.
 * Holding choice [holdItem] (in the open pocket, or resting on it while sliding) calls [onHold] instead.
 */
@Composable
private fun RowScope.PickTool(
    label: String, selected: Boolean, count: Int, current: Int, onPick: (Int) -> Unit,
    holdItem: Int = -1, onHold: () -> Unit = {},
    icon: @Composable () -> Unit, item: @Composable (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var open by rememberSaveable { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(-1) }
    fun hold() { haptic.performHapticFeedback(HapticFeedbackType.LongPress); open = false; onHold() }
    val items = remember(count) { arrayOfNulls<LayoutCoordinates>(count) }
    fun at(p: Offset) = items.indexOfFirst { c ->
        c != null && c.isAttached && Rect(c.localToScreen(Offset.Zero), c.localToScreen(Offset(c.size.width.toFloat(), c.size.height.toFloat()))).contains(p)
    }
    Box(Modifier.weight(1f)) {
        Row {
            Tool(selected, { open = true }, label, menu = true, press = { down, toScreen ->
                open = true
                var moved = false
                var lifted = false
                var held = false
                // Time the finger came onto the current choice
                var since = down.uptimeMillis
                var now = since
                while (true) {
                    val left = if (holdItem >= 0 && hover == holdItem) since + viewConfiguration.longPressTimeoutMillis - now else -1L
                    val ev = if (left < 0) awaitPointerEvent() else withTimeoutOrNull(maxOf(1L, left)) { awaitPointerEvent() }
                    if (ev == null) { held = true; break }
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    now = ch.uptimeMillis
                    if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    val h = at(toScreen(ch.position))
                    if (h != hover) { hover = h; since = now }
                    ch.consume()
                    if (!ch.pressed) { lifted = true; break }
                }
                val pick = hover
                hover = -1
                if (held) hold()
                else if (lifted && pick >= 0) { onPick(pick); open = false }
                else if (moved || !lifted) open = false
            }, content = icon)
        }
        // A plain popup right above the button: a menu keeps a wide gap, too far for slide-to-pick
        if (open) Popup(popupPositionProvider = AboveAnchor(with(LocalDensity.current) { 4.dp.roundToPx() }),
            onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            KeepRefreshRate()
            Surface(shape = SHAPE, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.padding(4.dp)) {
                    for (i in 0 until count) Box(Modifier.size(48.dp).onGloballyPositioned { items[i] = it }.clip(SHAPE)
                        .background(when {
                            i == hover -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                            i == current -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                        })
                        .combinedClickable(onLongClick = if (i == holdItem) ::hold else null) { onPick(i); open = false }, contentAlignment = Alignment.Center) { item(i) }
                }
            }
        }
    }
}

@Composable
private fun IssuesStrip(st: UiState, vm: EditorViewModel, insets: WindowInsets, maxList: Dp, oneLine: Boolean, onUndoable: (String) -> Unit) {
    val a = st.analysis ?: return
    // Landscape has little height: start with just the summary line
    var open by rememberSaveable { mutableStateOf(!oneLine) }
    val errors = a.issues.count { it.severity == Severity.ERROR }
    val warnings = a.issues.size - errors
    val sc = LocalScoreColors.current
    // The issue's ignore key ("RULE@time|message"): saveable, and it still names the rule once the issue is gone
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    info?.let { key ->
        val r = Rule.valueOf(key.substringBefore('@'))
        val i = a.issues.indexOfFirst { VoiceLeading.ignoreKey(it, a.slices) == key }
        RuleInfoDialog(r, r in st.rules, onToggle = { vm.toggleRule(r) },
            onIgnoreHere = if (i < 0) null else ({ info = null; vm.ignoreIssue(i); onUndoable("Issue ignored") }),
            onIgnoreInScore = { info = null; vm.ignoreRuleInScore(r); onUndoable("${r.label} ignored in this score") },
        ) { info = null }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(insets)) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(44.dp).clickable { open = !open }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.issues.isEmpty()) Text("No problems found ✓", style = MaterialTheme.typography.titleSmall, color = sc.ok)
                else {
                    if (errors > 0) Badge("$errors error${if (errors == 1) "" else "s"}", sc.errorText)
                    if (warnings > 0) Badge("$warnings warning${if (warnings == 1) "" else "s"}", sc.warnText)
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
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (issue.severity == Severity.ERROR) sc.errorText else sc.warnText))
                        val head = "${where(t, st.score.time)} · ${issue.rule.label}"
                        val pad = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)
                        if (oneLine) Row(pad, verticalAlignment = Alignment.CenterVertically) {
                            Text(head, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(10.dp))
                            Text(issue.message, style = MaterialTheme.typography.bodySmall)
                        } else Column(pad) {
                            Text(head, style = MaterialTheme.typography.labelLarge)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.width(44.dp).fillMaxHeight().clickable { info = VoiceLeading.ignoreKey(issue, a.slices) }.semantics { contentDescription = "About ${issue.rule.label}" },
                            contentAlignment = Alignment.Center) { Text("ⓘ", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}


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

internal fun keyLabel(k: Key) = k.name + when {
    k.fifths > 0 -> "  ·  ${k.fifths}♯"; k.fifths < 0 -> "  ·  ${-k.fifths}♭"; else -> ""
}

@Composable
private fun SettingsDialog(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    val sf2Picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSf2) }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    if (rulesOpen) RulesDialog(st, vm) { rulesOpen = false }
    FullScreenPage("Settings", onDismiss) {
        item {
            Group("Appearance") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Theme", Modifier.weight(1f))
                    Segmented(listOf("Light", "Dark"), if (st.dark) 1 else 0, itemWidth = 84.dp) { vm.setDark(it == 1) }
                }
            }
        }
        item {
            Group("Playback") {
                Label("Sound")
                Text(if (st.soundLoading) "Loading…" else if (st.soundLoaded) "Loaded: ${st.soundName ?: "SoundFont"}" else "No SoundFont loaded. Pick any .sf2 file (piano, choir, strings…).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { sf2Picker.launch(arrayOf("*/*")) }, shape = SHAPE) { Text("Load .sf2 file…") }
            }
        }
        item { MidiGroup(st, vm) }
        item {
            Group("Rules") {
                Row(Modifier.fillMaxWidth().clip(SHAPE).clickable { rulesOpen = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rules to check", Modifier.weight(1f))
                    Text("${st.rules.size} of ${Rule.entries.size} on  ›", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** MIDI keyboards: what is connected, and a search for Bluetooth ones (USB ones connect by themselves). */
@Composable
private fun MidiGroup(st: UiState, vm: EditorViewModel) {
    var denied by rememberSaveable { mutableStateOf(false) }
    val perms = if (android.os.Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        denied = !r.values.all { it }
        if (!denied) vm.startMidiScan()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Group("MIDI keyboard") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (st.midiDevices.isEmpty()) "None connected" else "Connected: ${st.midiDevices.joinToString()}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted)
            FilledTonalButton(onClick = { ask.launch(perms) }, enabled = !st.midiScanning, shape = SHAPE) {
                Text(if (st.midiScanning) "Searching…" else "Find Bluetooth")
            }
        }
        if (denied) Text("Allow Bluetooth access to find keyboards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        st.midiNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        st.midiFound.forEachIndexed { i, name ->
            Row(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.connectMidi(i) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f))
                Text("Connect", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!st.midiScanning && st.midiFound.isEmpty() && st.midiDevices.isEmpty())
            Text("USB keyboards connect by themselves. For Bluetooth, switch on its MIDI mode and search (no pairing needed).",
                style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun RulesDialog(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    var info by rememberSaveable { mutableStateOf<Rule?>(null) }
    info?.let { r -> RuleInfoDialog(r, r in st.rules, onToggle = { vm.toggleRule(r) }) { info = null } }
    FullScreenPage("Rules to check", onDismiss) {
        item {
            Group("Most important first") { for (r in Rule.entries) key(r.name) { RuleRow(r, r in st.rules, onInfo = { info = r }) { vm.toggleRule(r) } } }
        }
    }
}

/** Rule name and one-line description, an ⓘ button for the animated example, and its switch. */
@Composable
internal fun RuleRow(r: Rule, checked: Boolean, onInfo: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(SHAPE).clickable(onClick = onToggle).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.label)
            Text(r.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onInfo).semantics { contentDescription = "About ${r.label}" },
            contentAlignment = Alignment.Center) { Text("ⓘ", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary) }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Call inside a Dialog or Popup: its window does not inherit the refresh rate MainActivity asked for. */
@Composable
internal fun KeepRefreshRate() {
    val view = LocalView.current
    SideEffect {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull() ?: return@SideEffect
        val modeId = activity.window.attributes.preferredDisplayModeId
        if (modeId == 0) return@SideEffect
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            if (dialogWindow.attributes.preferredDisplayModeId != modeId) dialogWindow.attributes = dialogWindow.attributes.apply { preferredDisplayModeId = modeId }
        } else {
            // A Popup is a bare view added to the WindowManager; its params object is the one the popup keeps updating
            val root = view.rootView
            val lp = root.layoutParams as? android.view.WindowManager.LayoutParams ?: return@SideEffect
            if (lp.preferredDisplayModeId != modeId && root.isAttachedToWindow) {
                lp.preferredDisplayModeId = modeId
                activity.windowManager.updateViewLayout(root, lp)
            }
        }
    }
}

/** Full-screen page with a back arrow, drawn under the status bar like the editor. */
@Composable
internal fun FullScreenPage(title: String, onDismiss: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        KeepRefreshRate()
        // The dialog has its own window: status bar icons that suit the page, and full screen in landscape like the editor
        val view = LocalView.current
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !dark
                    if (landscape) {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }
        Surface(Modifier.fillMaxSize().clearFocusOnTap(), color = MaterialTheme.colorScheme.surface) {
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
internal fun Group(title: String?, border: androidx.compose.foundation.BorderStroke? = null,
                   color: Color = MaterialTheme.colorScheme.surfaceVariant, contentColor: Color = MaterialTheme.colorScheme.onSurface, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Readable width on tablets and in landscape
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = color,
            contentColor = contentColor, border = border) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Square stepper button; [glyph] draws a music symbol (e.g. ♭) in the Bravura font instead of [label]. */
@Composable
internal fun Small(label: String, glyph: Char? = null, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, shape = SHAPE, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        if (glyph != null) GlyphIcon(glyph, 46, MaterialTheme.colorScheme.onSurface) else Text(label, fontSize = 20.sp)
    }

/** Compact connected toggle buttons; with [glyphs], each option is one SMuFL music symbol drawn in the music font. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, itemWidth: Dp = 48.dp, glyphs: Boolean = false, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(SHAPE).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.height(48.dp).width(itemWidth).padding(3.dp).clip(RoundedCornerShape(9.dp))
                .background(if (i == selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                val ink = if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                if (glyphs) GlyphIcon(o[0], 40, ink)
                else Text(o, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal, color = ink)
            }
        }
    }
}

@Composable
internal fun <T> Picker(current: String, options: List<T>, label: (T) -> String, onPick: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, shape = SHAPE) { Text("$current  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = SHAPE) {
            for (o in options) DropdownMenuItem(text = { Text(label(o)) }, onClick = { onPick(o); open = false })
        }
    }
}
