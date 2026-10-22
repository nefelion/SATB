package com.grothgarvc.fourpartwriting.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grothgarvc.fourpartwriting.EditorViewModel
import com.grothgarvc.fourpartwriting.UiState
import com.grothgarvc.fourpartwriting.engrave.Smufl
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Mode
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Severity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val st by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(false) }
    LaunchedEffect(st.message) {
        st.message?.let { snack.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SATB", fontWeight = FontWeight.Bold)
                        Text("${st.score.key.name} · ${st.score.time.label} · ♩=${st.score.tempo}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = vm::check) { Text("Check") }
                    TextButton(onClick = vm::togglePlay) { Text(if (st.playing) "■ Stop" else "▶ Play") }
                    TextButton(onClick = { settings = true }) { Text("⚙", fontSize = 20.sp) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.White).verticalScroll(rememberScrollState())) {
                ScoreCanvas(st, vm::tap, vm::nudge)
            }
            st.analysis?.let { IssuesPanel(st, vm) }
            HorizontalDivider()
            Palette(st, vm)
        }
    }
    if (settings) SettingsSheet(st, vm) { settings = false }
}

@Composable
private fun IssuesPanel(st: UiState, vm: EditorViewModel) {
    val a = st.analysis ?: return
    val errors = a.issues.count { it.severity == Severity.ERROR }
    val warnings = a.issues.size - errors
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = 190.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (a.issues.isEmpty()) "No problems found ✓" else "$errors error${if (errors == 1) "" else "s"} · $warnings warning${if (warnings == 1) "" else "s"}",
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::closeAnalysis) { Text("Hide") }
            }
            LazyColumn {
                itemsIndexed(a.issues) { i, issue ->
                    val t = a.slices.getOrNull(issue.slice)?.time ?: Fraction.ZERO
                    val focused = st.focusedIssue == i
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { vm.focusIssue(i) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("●", color = if (issue.severity == Severity.ERROR) Color(0xFFD32F2F) else Color(0xFFE67700))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${where(t, st.score.time)} · ${issue.rule}", style = MaterialTheme.typography.labelMedium)
                            Text(issue.message, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun where(t: Fraction, time: TimeSig): String {
    val ml = time.measure
    val bar = (t / ml).let { it.num / it.den }
    val beat = ((t - ml * Fraction.of(bar, 1)) / Fraction.of(1, time.beatUnit.toLong())).toDouble() + 1
    val b = if (beat % 1.0 == 0.0) beat.toInt().toString() else "%.2f".format(beat).trimEnd('0')
    return "Bar ${bar + 1}, beat $b"
}

@Composable
private fun Chip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier.padding(2.dp).size(42.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Palette(st: UiState, vm: EditorViewModel) {
    val ink = MaterialTheme.colorScheme.onSurface
    val sel = st.selected?.let { st.score.voice(it.voice).getOrNull(it.index) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (v in Voice.entries) Chip(st.voice == v, { vm.selectVoice(v) }) {
                Text(v.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Chip(st.autoVoice, vm::toggleAutoVoice, Modifier.width(56.dp)) { Text("auto", fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
            Chip(false, { vm.nudge(1) }) { Text("▲") }
            Chip(false, { vm.nudge(-1) }) { Text("▼") }
            Chip(false, vm::undo) { Text("↶", fontSize = 20.sp) }
            Chip(false, vm::redo) { Text("↷", fontSize = 20.sp) }
            Chip(false, vm::delete) { Text("⌫", fontSize = 18.sp) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val activeBase = sel?.duration?.base ?: st.duration.base
            val activeDots = sel?.duration?.dots ?: st.duration.dots
            listOf(1, 2, 4, 8, 16, 32, 64).forEachIndexed { i, b ->
                Chip(activeBase == b, { vm.setDuration(b) }) { GlyphIcon(Smufl.MET_NOTES[i], 38, ink) }
            }
            Chip(activeDots > 0, vm::toggleDot) {
                Text(if (activeDots == 2) "··" else "·", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Chip(sel != null && sel.pitch == null, vm::rest) { GlyphIcon(Smufl.RESTS[2], 38, ink) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            val activeAlter = if (sel?.pitch != null) sel.pitch.alter.takeIf { it != st.score.key.sigAlter(sel.pitch.step) } else st.accidental
            for (a in listOf(-2, -1, 0, 1, 2)) Chip(activeAlter == a, { vm.setAccidental(a) }) {
                GlyphIcon(Smufl.accidental(a), 38, ink)
            }
        }
    }
}

// ---------- settings ----------

private fun keyLabel(f: Int, m: Mode) = Key(f, m).name + when {
    f > 0 -> "  ($f♯)"; f < 0 -> "  (${-f}♭)"; else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(st: UiState, vm: EditorViewModel, onDismiss: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importSf2) }
    val score = st.score
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Key", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (score.key.fifths > -7) vm.setKey(score.key.copy(fifths = score.key.fifths - 1)) }) { Text("♭") }
                Text(keyLabel(score.key.fifths, score.key.mode), Modifier.weight(1f).padding(horizontal = 8.dp))
                OutlinedButton(onClick = { if (score.key.fifths < 7) vm.setKey(score.key.copy(fifths = score.key.fifths + 1)) }) { Text("♯") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Mode.entries) FilterButton(score.key.mode == m, m.name.lowercase()) { vm.setKey(score.key.copy(mode = m)) }
            }

            Text("Time signature", style = MaterialTheme.typography.titleSmall)
            val t = score.time
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { if (t.beats > 1) vm.setTime(TimeSig(t.beats - 1, t.beatUnit)) }) { Text("−") }
                Text("${t.beats}", Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { if (t.beats < 16) vm.setTime(TimeSig(t.beats + 1, t.beatUnit)) }) { Text("+") }
                Text("/")
                for (u in listOf(2, 4, 8, 16)) FilterButton(t.beatUnit == u && t.symbol == TimeSig.Symbol.NUMERIC, "$u") { vm.setTime(TimeSig(t.beats, u)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterButton(t.symbol == TimeSig.Symbol.COMMON, "C (4/4)") { vm.setTime(TimeSig(4, 4, TimeSig.Symbol.COMMON)) }
                FilterButton(t.symbol == TimeSig.Symbol.CUT, "¢ (2/2)") { vm.setTime(TimeSig(2, 2, TimeSig.Symbol.CUT)) }
            }

            Text("Tempo: ♩ = ${score.tempo}", style = MaterialTheme.typography.titleSmall)
            Slider(value = score.tempo.toFloat(), onValueChange = { vm.setTempo(it.toInt()) }, valueRange = 30f..240f)

            Text("Clefs", style = MaterialTheme.typography.titleSmall)
            ClefPicker("Upper staff (S/A)", score.clefs[0]) { vm.setClef(0, it) }
            ClefPicker("Lower staff (T/B)", score.clefs[1]) { vm.setClef(1, it) }

            Text("Sound", style = MaterialTheme.typography.titleSmall)
            Text(if (st.soundLoaded) "Piano SoundFont loaded." else "No SoundFont loaded. Pick a piano .sf2 file.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Load .sf2 file…") }

            Text("Score", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.clearScore(); onDismiss() }) { Text("Clear") }
                OutlinedButton(onClick = { vm.loadExample(); onDismiss() }) { Text("Example") }
            }
            Text("Music font: Bravura © Steinberg Media Technologies, SIL Open Font License.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FilterButton(selected: Boolean, label: String, onClick: () -> Unit) {
    if (selected) FilledTonalButton(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun ClefPicker(label: String, clef: Clef, onPick: (Clef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(clef.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (c in Clef.entries) DropdownMenuItem(text = { Text(c.label) }, onClick = { onPick(c); open = false })
            }
        }
    }
}
