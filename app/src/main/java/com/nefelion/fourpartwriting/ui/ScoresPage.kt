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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nefelion.fourpartwriting.EditorViewModel
import com.nefelion.fourpartwriting.ScoreLibrary
import com.nefelion.fourpartwriting.UiState
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import androidx.compose.runtime.saveable.Saver

/** Saved scores: open, start a new one, rename, copy, delete, save to or add from a .satb file. */
@Composable
internal fun ScoresPage(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshScores() }
    // Dialogs and menus stay open on rotation
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var editingScore by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    // Generic type, as for MusicXML: providers then keep the .satb extension
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val name = exporting
        exporting = null
        if (uri != null && name != null) vm.exportScoreFile(name, uri)
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
                    item("Edit") { editingScore = name }
                    item("Rename") { renaming = name }
                    item("Duplicate") { vm.duplicateScore(name) }
                    item("Save to file…") { exporting = name; saver.launch("$name${ScoreLibrary.EXT}") }
                    HorizontalDivider()
                    item("Delete", danger = true) { deleting = name }
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
        val other = st.scores.filter { it.first != st.scoreName }
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
    if (naming) ScoreDialog("New score", "", Score(), askName = true, action = "Create", onDismiss = { naming = false }) { name, settings ->
        naming = false; vm.newScore(name, settings); onDismiss()
    }
    editingScore?.let { name ->
        // The open score's file may lag its last edit, so its settings come from the editor
        val sc = if (name == st.scoreName) st.score else st.scores.firstOrNull { it.first == name }?.second
        if (sc == null) editingScore = null
        else ScoreDialog("Edit score", name, sc, askName = true, action = "Save", onDismiss = { editingScore = null }) { newName, settings ->
            editingScore = null; vm.editScore(name, newName, settings)
        }
    }
    renaming?.let { old -> NameDialog("Rename score", old, "Rename", onDismiss = { renaming = null }) { vm.renameScore(old, it); renaming = null } }
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

/** "D major · 3/4 · 12 bars". */
private fun summary(sc: Score): String {
    val m = sc.length / sc.time.measure
    val bars = (m.num + m.den - 1) / m.den
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
    var name by rememberSaveable { mutableStateOf(startName) }
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
                OutlinedTextField(name, { name = it.take(60) }, Modifier.focusRequester(focus), singleLine = true, shape = SHAPE,
                    placeholder = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { next() }))
            } else Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingRow("Key", draft.key.name) { editing = "Key" }
                HorizontalDivider()
                SettingRow("Time signature", "${draft.time.beats}/${draft.time.beatUnit}") { editing = "Time signature" }
                HorizontalDivider()
                SettingRow("Tempo", "♩ = ${draft.tempo}") { editing = "Tempo" }
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
                            "Time signature" -> TimeSigPicker(draft.time) { draft = draft.copy(time = it) }
                            "Tempo" -> TempoEditor(draft.tempo, { draft = draft.copy(tempo = it.coerceIn(20, 300)) }, {})
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
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(SHAPE).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$value  ›", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NameDialog(title: String, start: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(start) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeepRefreshRate()
            OutlinedTextField(text, { text = it.take(60) }, Modifier.focusRequester(focus), singleLine = true, shape = SHAPE,
                placeholder = { Text("Name") }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onDone(text) }))
        },
        confirmButton = { TextButton(onClick = { onDone(text) }, enabled = text.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
