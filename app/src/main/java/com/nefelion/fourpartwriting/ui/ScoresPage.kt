package com.nefelion.fourpartwriting.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.ScoreLibrary
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.Rule
import androidx.compose.runtime.key
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Exercises
import com.nefelion.fourpartwriting.theory.Lessons
import com.nefelion.fourpartwriting.model.ScoreCodec
import androidx.compose.runtime.saveable.Saver

/** Saved scores: open, start a new one, edit, set its rules, rename, copy, delete, save to or add from a .satb file. */
@Composable
internal fun ScoresPage(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshScores() }
    // Dialogs and menus stay open on rotation
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var exercise by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var editingScore by rememberSaveable { mutableStateOf<String?>(null) }
    var rulesFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicating by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    // Score whose file format is being picked, and the picked one
    var formatFor by rememberSaveable { mutableStateOf<String?>(null) }
    var asXml by rememberSaveable { mutableStateOf(false) }
    var studying by rememberSaveable { mutableStateOf(false) }
    var learning by rememberSaveable { mutableStateOf(false) }
    // Generic type: providers keep an unknown .satb or .musicxml extension only then, so a duplicate becomes "satb (1).musicxml"
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = exporting
        exporting = null
        if (uri != null && name != null) vm.exportScoreFile(name, uri, asXml)
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importScoreFile(uri); onDismiss() }
    }
    // Name, details and ⋮ menu; tapping opens the score (the open one just goes back to it)
    val row: @Composable (String, Score?, Boolean) -> Unit = { name, sc, open ->
        Row(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.openScore(name); onDismiss() }.padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (open) FontWeight.Bold else FontWeight.Normal)
                Text(sc?.let(::summary) ?: "Could not read this file", style = MaterialTheme.typography.bodySmall,
                    color = if (open) LocalContentColor.current.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(48.dp).clip(SHAPE).clickable { menuFor = name }.semantics { contentDescription = "Options for $name" },
                contentAlignment = Alignment.Center) {
                Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                DropdownMenu(expanded = menuFor == name, onDismissRequest = { menuFor = null }, shape = SHAPE) {
                    KeepRefreshRate()
                    @Composable fun item(label: String, danger: Boolean = false, action: () -> Unit) = DropdownMenuItem(
                        text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                        onClick = { menuFor = null; action() })
                    // The open study score is read-only and not in the library: it can only be copied
                    val study = open && st.study != null
                    if (!study) item("Edit") { editingScore = name }
                    if (!study) item("Rules") { rulesFor = name }
                    if (!study) item("Rename") { renaming = name }
                    item("Duplicate") { duplicating = name }
                    item("Save to file…") { formatFor = name }
                    if (!study) HorizontalDivider()
                    if (!study) item("Delete", danger = true) { deleting = name }
                }
            }
        }
    }
    FullScreenPage("Scores", onDismiss) {
        item {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { naming = true }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("New score") }
                OutlinedButton(onClick = { opener.launch(arrayOf("*/*")) }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("Open file…") }
            }
        }
        item {
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { exercise = true }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("New exercise") }
                OutlinedButton(onClick = { studying = true }, shape = SHAPE, modifier = Modifier.weight(1f)) { Text("Bach chorales") }
            }
        }
        item {
            FilledTonalButton(onClick = { learning = true }, shape = SHAPE, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("Lessons" + if (st.lessonsDone.isEmpty()) "" else " (${st.lessonsDone.size} of ${Lessons.ALL.size} done)")
            }
        }
        // (a study score is not in the library, so a score of yours with the same name is listed)
        val other = st.scores.filter { st.study != null || it.first != st.scoreName }
        item {
            // The open score's file may lag its last edit by the autosave delay, so its details come from the editor
            val cs = MaterialTheme.colorScheme
            Group("Open", BorderStroke(2.dp, cs.primary), cs.primaryContainer, cs.onPrimaryContainer) { row(st.scoreName, st.score, true) }
        }
        if (other.isNotEmpty()) item {
            Group("Other scores") {
                other.forEachIndexed { i, (name, sc) ->
                    if (i > 0) HorizontalDivider()
                    row(name, sc, false)
                }
            }
        }
    }
    if (exercise) ExerciseDialog(Voice.SOPRANO in st.score.given, onDismiss = { exercise = false }) { level, soprano -> exercise = false; vm.newExercise(level, soprano); onDismiss() }
    if (learning) LessonsPage(st, vm) { learning = false }
    if (studying) StudyPage(st, vm, onDismiss = { studying = false }) { studying = false; onDismiss() }
    if (naming) ScoreDialog("New score", "", Score(), askName = true, action = "Create", onDismiss = { naming = false }) { name, settings ->
        naming = false; vm.newScore(name, settings); onDismiss()
    }
    editingScore?.let { name ->
        // The open score's file may lag its last edit, so its settings come from the editor
        val sc = if (name == st.scoreName) st.score else st.scores.firstOrNull { it.first == name }?.second
        if (sc == null) editingScore = null
        else ScoreDialog("Edit $name", name, sc, askName = false, action = "Save", onDismiss = { editingScore = null }) { _, settings ->
            editingScore = null; vm.editScore(name, settings)
        }
    }
    rulesFor?.let { name ->
        val sc = if (name == st.scoreName) st.score else st.scores.firstOrNull { it.first == name }?.second
        if (sc == null) rulesFor = null else ScoreRulesPage(name, sc, st, vm) { rulesFor = null }
    }
    renaming?.let { old -> NameDialog("Rename score", old, "Rename", onDismiss = { renaming = null }) { vm.renameScore(old, it); renaming = null } }
    duplicating?.let { from ->
        NameDialog("Duplicate score", vm.copyName(from), "Duplicate", onDismiss = { duplicating = null }) { vm.duplicateScore(from, it); duplicating = null }
    }
    formatFor?.let { name ->
        val pick = { xml: Boolean -> formatFor = null; asXml = xml; exporting = name; saver.launch(name + if (xml) ".musicxml" else ScoreLibrary.EXT) }
        AlertDialog(
            onDismissRequest = { formatFor = null },
            title = { Text("Save $name as") },
            text = {
                KeepRefreshRate()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((xml, label, about) in listOf(
                        Triple(false, "SATB score", "Opens in this app with everything kept"),
                        Triple(true, "MusicXML", "For MuseScore, Sibelius, Finale and other notation apps"),
                    )) OutlinedButton(onClick = { pick(xml) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { formatFor = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { KeepRefreshRate(); Text("The score will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm.deleteScore(name) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** The bundled Bach chorales: tap one to open it read-only ([onOpen] then closes the pages). */
@Composable
private fun StudyPage(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit, onOpen: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadStudies() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FullScreenPage("Bach chorales", onDismiss) {
        item {
            Text("Four-part chorales by J. S. Bach to listen to, check and learn from. They open read-only: duplicate one in Scores to change it.",
                Modifier.widthIn(max = 640.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        item {
            Group(null) {
                if (st.studies.isEmpty()) Text("Loading…", color = muted)
                st.studies.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().clip(SHAPE).clickable { vm.openStudy(s.id); onOpen() }.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Text(s.title.substringBefore(" (BWV"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (s.id == st.study) FontWeight.Bold else FontWeight.Normal)
                        Text("${s.id} · ${summary(s.score)}", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Text("Bach chorales from the BCFB dataset by Yaolong Ju et al., CC BY 4.0, based on the Neue Bach-Ausgabe. Converted for SATB: the continuo part is left out and old modal key signatures are written as modern ones.",
                Modifier.widthIn(max = 640.dp), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

/** Rules checked in score [name] only; rules off in Settings are off everywhere, so they are not listed. */
@Composable
private fun ScoreRulesPage(name: String, sc: Score, st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    var info by rememberSaveable { mutableStateOf<Rule?>(null) }
    val locked = sc.lesson != null
    info?.let { r -> RuleInfoDialog(r, locked || r.name !in sc.rulesOff, onToggle = if (locked) null else ({ vm.toggleScoreRule(name, r) }),
        onStudy = { vm.openStudy(it.study, r, it.bar, it.breaks, it.note) }) { info = null } }
    FullScreenPage("Rules · $name", onDismiss) {
        item {
            Group("Checked in this score") {
                // A lesson's score is checked with the rules taught so far, whatever Settings say
                val rules = sc.lesson?.let(Lessons::rulesUpTo) ?: st.rules
                for (r in Rule.entries.filter { it in rules }) key(r.name) {
                    RuleRow(r, locked || r.name !in sc.rulesOff, onInfo = { info = r }, locked = locked) { vm.toggleScoreRule(name, r) }
                }
                val note = if (sc.lesson != null) "A lesson's score checks the rules taught up to that lesson. They can't be switched off."
                    else if (st.rules.size < Rule.entries.size) "Rules switched off in Settings are not checked in any score." else null
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** "D major · 3/4 · 12 bars". */
private fun summary(sc: Score): String {
    val bars = if (sc.length == Fraction.ZERO) 0L else sc.bars(sc.length).size.toLong()
    return "${sc.key.name} · ${sc.time.beats}/${sc.time.beatUnit} · " + when (bars) { 0L -> "empty"; 1L -> "1 bar"; else -> "$bars bars" }
}

/**
 * A score's name and settings: with [askName], first the name, then a summary of key, time signature, tempo and staves;
 * tapping a row opens a small window with just that setting. Used for New score, Edit and taps on the score's header.
 * [onDone] gets the name and a note-less score holding the settings.
 */
@Composable
internal fun ScoreDialog(label: String, startName: String, start: Score, askName: Boolean, action: String,
                         onDismiss: () -> Unit, onDone: (String, Score) -> Unit) {
    val steps = if (askName) 2 else 1
    var step by rememberSaveable { mutableIntStateOf(if (askName) 0 else 1) }
    // Caret starts at the end of the name
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(startName, TextRange(startName.length))) }
    val name = field.text
    // The choices as a note-less score; kept as score-file text so they survive rotation
    var draft by rememberSaveable(stateSaver = Saver({ ScoreCodec.encode(it) }, { ScoreCodec.decode(it) })) {
        mutableStateOf(start.copy(voices = List(4) { emptyList() }, rulesOff = emptySet(), ignored = emptySet()))
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    fun next() {
        if (name.isBlank()) return
        if (step == 1) onDone(name, draft) else step++
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // New/Edit show the name being set; opened from the score, only the small label
        title = {
            Column {
                Text(if (steps > 1) "$label · ${step + 1} of 2" else label, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (askName) Text(if (step == 0) "Name" else name.trim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            KeepRefreshRate()
            if (step == 0) {
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(field, { field = if (it.text.length > 60) it.copy(it.text.take(60)) else it }, Modifier.focusRequester(focus), singleLine = true, shape = SHAPE,
                    placeholder = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { next() }))
            } else Column(Modifier.verticalScroll(rememberScrollState())) {
                // An exercise's figures are read in its key and its cadence is placed in its meter
                val fixed = draft.given.isNotEmpty()
                SettingRow("Key", draft.key.name, enabled = !fixed) { editing = "Key" }
                HorizontalDivider()
                SettingRow("Time signature", "${draft.time.beats}/${draft.time.beatUnit}", enabled = !fixed) { editing = "Time signature" }
                HorizontalDivider()
                SettingRow("Pickup bar", draft.pickup?.let { pickupLabel(it, draft.time) } ?: "None", enabled = !fixed) { editing = "Pickup bar" }
                if (fixed) Text("Key and time signature are fixed by the exercise.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                HorizontalDivider()
                SettingRow("Tempo", "= ${draft.tempo}", Smufl.beatMark(draft.time)) { editing = "Tempo" }
                HorizontalDivider()
                SettingRow("Staves", draft.arrangement.label) { editing = "Staves" }
            }
        },
        confirmButton = { TextButton(onClick = ::next, enabled = name.isNotBlank()) { Text(if (step == 1) action else "Next") } },
        dismissButton = {
            val back = askName && step == 1
            TextButton(onClick = { if (back) step-- else onDismiss() }) { Text(if (back) "Back" else "Cancel") }
        },
    )
    editing?.let { what ->
        Dialog(onDismissRequest = { editing = null }) {
            KeepRefreshRate()
            // Looks like the other dialogs; a tap anywhere on it outside the tempo field ends typing (caret and keyboard go away)
            Surface(Modifier.clearFocusOnTap(), shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(what, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        when (what) {
                            "Key" -> KeyPicker(draft.key) { draft = draft.copy(key = it) }
                            // A pickup must stay shorter than a full bar
                            "Time signature" -> TimeSigPicker(draft.time) { draft = draft.copy(time = it, pickup = draft.pickup?.takeIf { p -> p < it.measure }) }
                            "Pickup bar" -> PickupPicker(draft.pickup, draft.time) { draft = draft.copy(pickup = it) }
                            "Tempo" -> TempoEditor(draft.tempo, draft.time, { draft = draft.copy(tempo = it.coerceIn(20, 300)) }, {})
                            else -> StavesPicker(draft, { draft = draft.withArrangement(it) }) { i, c ->
                                draft = draft.copy(clefs = draft.clefs.toMutableList().also { it[i] = c })
                            }
                        }
                    }
                    TextButton(onClick = { editing = null }, Modifier.align(Alignment.End)) { Text("Done") }
                }
            }
        }
    }
}

/** One setting in the new-score summary: name on the left, value and › on the right; tap to change it. */
@Composable
private fun SettingRow(label: String, value: String, glyph: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(Modifier.fillMaxWidth().clip(SHAPE).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else dim)
        glyph?.let { GlyphIcon(it, 28, MaterialTheme.colorScheme.primary) }
        Text(if (enabled) "$value  ›" else value, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.primary else dim)
    }
}

@Composable
private fun NameDialog(title: String, start: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    // Caret starts at the end of the name
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeepRefreshRate()
            OutlinedTextField(field, { field = if (it.text.length > 60) it.copy(it.text.take(60)) else it }, Modifier.focusRequester(focus), singleLine = true, shape = SHAPE,
                placeholder = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onDone(text) }))
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks the kind (figured bass or given soprano, [onPick]'s soprano; starts as [sopranoFirst]) and level of a new generated exercise. */
@Composable
internal fun ExerciseDialog(sopranoFirst: Boolean, onDismiss: () -> Unit, onPick: (Exercises.Level, Boolean) -> Unit) {
    var soprano by rememberSaveable { mutableStateOf(sopranoFirst) }
    AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New exercise") },
    text = {
        KeepRefreshRate()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Figured bass", "Given soprano").forEachIndexed { i, label ->
                    SegmentedButton(selected = soprano == (i == 1), onClick = { soprano = i == 1 }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Text(if (soprano) "Choose the chords and write alto, tenor and bass under the given soprano, then press ✓ to check."
                else "Write soprano, alto and tenor over the given figured bass, then press ✓ to check.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((level, about) in listOf(
                Exercises.Level.EASY to "Triads in root position and first inversion, major keys",
                Exercises.Level.MEDIUM to "Adds seventh chords, the cadential ⁶₄ and minor keys",
                Exercises.Level.HARD to "Adds secondary dominants, longer phrases",
            )) OutlinedButton(onClick = { onPick(level, soprano) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(level.label, style = MaterialTheme.typography.titleSmall)
                    Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    },
    confirmButton = {},
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
