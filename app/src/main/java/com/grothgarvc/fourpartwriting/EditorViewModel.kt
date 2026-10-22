package com.grothgarvc.fourpartwriting

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.engrave.Hit
import com.grothgarvc.fourpartwriting.theory.Analysis
import com.grothgarvc.fourpartwriting.theory.VoiceLeading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val score: Score = EXAMPLE,
    val voice: Voice = Voice.SOPRANO,
    val autoVoice: Boolean = true,
    val duration: Duration = Duration(4),
    /** Accidental armed for the next placed note (null = follow key signature). */
    val accidental: Int? = null,
    val selected: NoteRef? = null,
    val analysis: Analysis? = null,
    val focusedIssue: Int? = null,
    val playing: Boolean = false,
    /** Onset of the slice under the playback cursor. */
    val playTime: Fraction? = null,
    val soundLoaded: Boolean = false,
    val message: String? = null,
    val undo: List<Score> = emptyList(),
    val redo: List<Score> = emptyList(),
)

private fun chorale(vararg chords: String): List<List<Event>> {
    val parsed = chords.map { it.split(" ") }
    return Voice.entries.map { v ->
        parsed.map { c ->
            val (p, d) = c[v.ordinal].split(":").let { it[0] to (it.getOrNull(1) ?: "4") }
            val dur = Duration(d.trimEnd('.').toInt(), d.count { it == '.' })
            Event(dur, if (p == "r") null else Pitch.parse(p))
        }
    }
}

/** A short phrase in C major (I IV V I | vi ii6 Cad6/4 V7 | I) so the first screen shows music. */
val EXAMPLE = Score(
    voices = chorale(
        "E4 C4 G3 C3", "F4 C4 A3 F3", "D4 B3 G3 G2", "E4 C4 G3 C3",
        "E4 C4 A3 A2", "F4 D4 A3 F2", "E4 C4 G3 G2", "D4 B3 F3 G2",
        "C4:1 C4:1 E3:1 C3:1",
    ),
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val sf2 = File(app.filesDir, "piano.sf2")
    private var playJob: Job? = null

    init {
        if (sf2.exists()) viewModelScope.launch(Dispatchers.IO) {
            val ok = Synth.load(sf2.path)
            _state.update { it.copy(soundLoaded = ok) }
        }
    }

    private val s get() = _state.value
    private fun msg(m: String) = _state.update { it.copy(message = m) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Applies a score edit with undo; re-runs the check if results are shown. */
    private fun edit(new: Score, selected: NoteRef? = s.selected, voice: Voice = s.voice) {
        if (new == s.score) { _state.update { it.copy(selected = selected, voice = voice) }; return }
        _state.update {
            it.copy(
                score = new, selected = selected, voice = voice,
                undo = (it.undo + it.score).takeLast(200), redo = emptyList(),
                analysis = it.analysis?.let { VoiceLeading.check(new) }, focusedIssue = null,
            )
        }
    }

    // ---------- input ----------

    fun tap(hit: Hit) {
        val score = s.score
        val v = s.voice
        val events = score.voice(v)
        val onsets = score.onsets(v)
        val end = score.length(v)
        val ml = score.time.measure
        val base = score.clefs[hit.staff].pitchAt(hit.position)
        val pitch = Pitch(base.step, s.accidental ?: score.key.sigAlter(base.step), base.octave)
        val idx = onsets.indexOf(hit.time)

        fun measureOf(t: Fraction) = (t / ml).let { it.num / it.den }
        val append = hit.time >= end || (hit.afterLast && (measureOf(hit.time) == measureOf(end) ||
            (measureOf(hit.time) == measureOf(end) - 1 && (end / ml).den == 1L)))

        when {
            append -> {
                if (s.duration.value > roomAfter(end, ml)) { msg("Not enough room in this bar for that note value."); return }
                val ref = NoteRef(v, events.size)
                val next = if (s.autoVoice) Voice.entries[(v.ordinal + 1) % 4] else v
                edit(score.withVoice(v, events + Event(s.duration, pitch)), ref, next)
                _state.update { it.copy(accidental = null) }
                preview(pitch)
            }
            idx >= 0 && !hit.afterLast -> {
                val old = events[idx]
                edit(score.withVoice(v, events.toMutableList().also { it[idx] = old.copy(pitch = pitch) }), NoteRef(v, idx))
                _state.update { it.copy(accidental = null) }
                preview(pitch)
            }
            else -> {
                val i = onsets.indexOfLast { it <= hit.time }
                if (i >= 0) _state.update { it.copy(selected = NoteRef(v, i)) }
            }
        }
    }

    fun selectVoice(v: Voice) = _state.update { it.copy(voice = v, selected = null) }
    fun toggleAutoVoice() = _state.update { it.copy(autoVoice = !it.autoVoice) }
    fun select(ref: NoteRef?) = _state.update { it.copy(selected = ref, voice = ref?.voice ?: it.voice) }

    private fun selectedEvent(): Pair<NoteRef, Event>? =
        s.selected?.let { r -> s.score.voice(r.voice).getOrNull(r.index)?.let { r to it } }

    private fun replaceSelected(f: (Event) -> Event): Boolean {
        val (r, e) = selectedEvent() ?: return false
        val list = s.score.voice(r.voice).toMutableList().also { it[r.index] = f(e) }
        if (!fitsBars(list, s.score.time)) { msg("That does not fit in the bar."); return true }
        edit(s.score.withVoice(r.voice, list))
        return true
    }

    fun setDuration(base: Int) {
        _state.update { it.copy(duration = Duration(base, it.duration.dots)) }
        replaceSelected { it.copy(duration = Duration(base, it.duration.dots)) }
    }

    /** Cycles 0 → 1 → 2 dots on the selected note, or on the palette value. */
    fun toggleDot() {
        fun next(d: Duration) = Duration(d.base, (d.dots + 1) % 3)
        if (!replaceSelected { it.copy(duration = next(it.duration)) })
            _state.update { it.copy(duration = next(it.duration)) }
    }

    private fun roomAfter(end: Fraction, ml: Fraction): Fraction {
        val bar = (end / ml).let { it.num / it.den }
        return ml - (end - ml * Fraction.of(bar, 1))
    }

    fun setAccidental(alter: Int) {
        val sel = selectedEvent()
        if (sel != null && sel.second.pitch != null) {
            val p = sel.second.pitch!!
            val newAlter = if (p.alter == alter) s.score.key.sigAlter(p.step) else alter
            replaceSelected { it.copy(pitch = p.copy(alter = newAlter)) }
            preview(p.copy(alter = newAlter))
        } else _state.update { it.copy(accidental = if (it.accidental == alter) null else alter) }
    }

    /** Moves the selected note by diatonic steps, spelling it by the key signature. */
    fun nudge(steps: Int) {
        val (_, e) = selectedEvent() ?: return
        val p = e.pitch ?: return
        val q = p.shiftDiatonic(steps).let { it.copy(alter = s.score.key.sigAlter(it.step)) }
        replaceSelected { it.copy(pitch = q) }
        preview(q)
    }

    fun rest() {
        if (replaceSelected { it.copy(pitch = null) }) return
        val v = s.voice
        val score = s.score
        if (s.duration.value > roomAfter(score.length(v), score.time.measure)) { msg("Not enough room in this bar for that rest."); return }
        val next = if (s.autoVoice) Voice.entries[(v.ordinal + 1) % 4] else v
        edit(score.withVoice(v, score.voice(v) + Event(s.duration, null)), NoteRef(v, score.voice(v).size), next)
    }

    fun delete() {
        val sel = selectedEvent()
        val score = s.score
        if (sel == null) {
            val v = s.voice
            val list = score.voice(v)
            if (list.isNotEmpty()) edit(score.withVoice(v, list.dropLast(1)), null)
            return
        }
        val (r, e) = sel
        val list = score.voice(r.voice)
        when {
            r.index == list.lastIndex -> edit(score.withVoice(r.voice, list.dropLast(1)), null)
            e.pitch != null -> edit(score.withVoice(r.voice, list.toMutableList().also { it[r.index] = e.copy(pitch = null) }))
            else -> msg("Only the last note of a voice can be removed; others become rests.")
        }
    }

    fun undo() = _state.update {
        val prev = it.undo.lastOrNull() ?: return@update it
        it.copy(score = prev, undo = it.undo.dropLast(1), redo = it.redo + it.score, selected = null,
            analysis = it.analysis?.let { VoiceLeading.check(prev) })
    }

    fun redo() = _state.update {
        val next = it.redo.lastOrNull() ?: return@update it
        it.copy(score = next, redo = it.redo.dropLast(1), undo = it.undo + it.score, selected = null,
            analysis = it.analysis?.let { VoiceLeading.check(next) })
    }

    // ---------- score settings ----------

    fun setKey(k: Key) = edit(s.score.copy(key = k))
    fun setTempo(t: Int) = edit(s.score.copy(tempo = t.coerceIn(20, 300)))
    fun setClef(staff: Int, c: Clef) = edit(s.score.copy(clefs = s.score.clefs.toMutableList().also { it[staff] = c }))
    fun setTime(t: TimeSig) {
        if (s.score.voices.any { !fitsBars(it, t) }) { msg("The existing notes do not fit that meter."); return }
        edit(s.score.copy(time = t))
    }
    fun clearScore() = edit(s.score.copy(voices = List(4) { emptyList() }), null, Voice.SOPRANO)
    fun loadExample() = edit(EXAMPLE, null, Voice.SOPRANO)

    // ---------- analysis ----------

    fun check() = _state.update { it.copy(analysis = VoiceLeading.check(it.score), focusedIssue = null) }
    fun closeAnalysis() = _state.update { it.copy(analysis = null, focusedIssue = null) }
    fun focusIssue(i: Int?) = _state.update { it.copy(focusedIssue = if (it.focusedIssue == i) null else i) }

    // ---------- sound ----------

    private fun preview(p: Pitch) {
        if (!s.soundLoaded || s.playing) return
        Synth.noteOn(p.midi)
        viewModelScope.launch { delay(450); Synth.noteOff(p.midi) }
    }

    fun togglePlay() {
        if (s.playing) { stop(); return }
        if (!s.soundLoaded) { msg("Load a piano SoundFont (.sf2) in Settings to play."); return }
        val score = s.score
        val onsets = score.slices().map { it.time }
        if (onsets.isEmpty()) return
        Synth.play(score)
        _state.update { it.copy(playing = true, selected = null) }
        playJob = viewModelScope.launch {
            delay(50)
            while (true) {
                val sec = Synth.position() ?: break
                val whole = sec * score.tempo / 240.0
                _state.update { st -> st.copy(playTime = onsets.lastOrNull { it.toDouble() <= whole + 1e-9 }) }
                delay(30)
            }
            _state.update { it.copy(playing = false, playTime = null) }
        }
    }

    fun stop() {
        Synth.stop()
        playJob?.cancel()
        _state.update { it.copy(playing = false, playTime = null) }
    }

    fun importSf2(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(sf2.path + ".tmp")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                // Validate before replacing the previous working file
                if (!Synth.load(tmp.path)) { tmp.delete(); false } else { tmp.renameTo(sf2); Synth.load(sf2.path) }
            }.getOrDefault(false)
        }
        _state.update { it.copy(soundLoaded = it.soundLoaded || ok, message = if (ok) "SoundFont loaded." else "Could not read that file as a SoundFont (.sf2).") }
    }
}

/** No event may cross a barline (ties are not supported yet). */
fun fitsBars(events: List<Event>, time: TimeSig): Boolean {
    val ml = time.measure
    var t = Fraction.ZERO
    for (e in events) {
        val end = t + e.duration.value
        val startBar = (t / ml).let { it.num / it.den }
        val endBar = (end / ml).let { (it.num + it.den - 1) / it.den } // ceil
        if (endBar - startBar > 1) return false
        t = end
    }
    return true
}
