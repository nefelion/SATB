package com.nefelion.fourpartwriting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nefelion.fourpartwriting.engrave.Smufl
import com.nefelion.fourpartwriting.model.Arrangement as StaffArrangement
import com.nefelion.fourpartwriting.model.Clef
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.TimeSig

/**
 * Key: pick the tonic letter, its ♭/♮/♯ and major/minor (C → C minor);
 * or step around the circle of fifths with ♭/♯, keeping the mode; or switch to the relative key (C major ↔ A minor).
 */
@Composable
internal fun KeyPicker(key: Key, onKey: (Key) -> Unit) {
    val t = key.tonic
    // A letter alone means the natural; a tonic beyond 7 accidentals is respelled (D♯ major → E♭ major)
    fun pick(step: Int, alter: Int, mode: Mode) { Key.of(PitchClass(step, alter), mode)?.let(onKey) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segmented("CDEFGAB".map { it.toString() }, t.step, itemWidth = 40.dp) { pick(it, 0, key.mode) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(listOf(Smufl.FLAT, Smufl.NATURAL, Smufl.SHARP).map { it.toString() }, t.alter + 1, itemWidth = 44.dp, glyphs = true) {
                pick(t.step, it - 1, key.mode)
            }
            Segmented(listOf("major", "minor"), key.mode.ordinal, itemWidth = 72.dp) { pick(t.step, t.alter, Mode.entries[it]) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("Fewer sharps", glyph = Smufl.FLAT, enabled = key.fifths > -7) { onKey(key.copy(fifths = key.fifths - 1)) }
            Text(keyLabel(key), Modifier.weight(1f), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Small("More sharps", glyph = Smufl.SHARP, enabled = key.fifths < 7) { onKey(key.copy(fifths = key.fifths + 1)) }
        }
        OutlinedButton(onClick = { onKey(key.relative) }, shape = SHAPE, modifier = Modifier.fillMaxWidth()) {
            Text("Relative key: ${key.relative.name}")
        }
    }
}

/** Stacked like a time signature: beats with −/+ on top, the beat unit (2/4/8/16) below. Fits a narrow dialog. */
@Composable
internal fun TimeSigPicker(t: TimeSig, onTime: (TimeSig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("−", enabled = t.beats > 1) { onTime(TimeSig(t.beats - 1, t.beatUnit)) }
            Text("${t.beats}", Modifier.width(32.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
            Small("+", enabled = t.beats < 16) { onTime(TimeSig(t.beats + 1, t.beatUnit)) }
            Text("beats", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(listOf("2", "4", "8", "16"), listOf(2, 4, 8, 16).indexOf(t.beatUnit)) { onTime(TimeSig(t.beats, listOf(2, 4, 8, 16)[it])) }
            Text("beat unit", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Staff count, then which SATB split for that count ("/" = staff break; SAT/B is keyboard style), then each staff's clef. */
@Composable
internal fun StavesPicker(score: Score, onArrangement: (StaffArrangement) -> Unit, onClef: (Int, Clef) -> Unit) {
    val arr = score.arrangement
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The split gets its own line: beside the count it would be squeezed in a narrow dialog
        Segmented(listOf("1", "2", "3", "4"), arr.staves - 1) { i ->
            if (i + 1 != arr.staves) onArrangement(listOf(StaffArrangement.ONE, StaffArrangement.CLOSED, StaffArrangement.THREE, StaffArrangement.OPEN)[i])
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Voices per staff", Modifier.weight(1f))
            Picker(arr.label, StaffArrangement.entries.filter { it.staves == arr.staves }, { it.label }, onArrangement)
        }
        val names = arr.label.split("/")
        for (i in score.clefs.indices) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Staff ${i + 1} (${names[i]})", Modifier.weight(1f))
            Picker(score.clefs[i].label, Clef.entries, { it.label }) { onClef(i, it) }
        }
    }
}
