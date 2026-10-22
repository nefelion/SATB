package com.grothgarvc.fourpartwriting

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.grothgarvc.fourpartwriting.audio.Synth
import com.grothgarvc.fourpartwriting.engrave.Hit
import com.grothgarvc.fourpartwriting.engrave.NOTE_POSITIONS
import com.grothgarvc.fourpartwriting.engrave.NoteRef
import com.grothgarvc.fourpartwriting.engrave.Notation
import com.grothgarvc.fourpartwriting.export.MusicXml
import com.grothgarvc.fourpartwriting.model.Arrangement
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Edit
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.ScoreCodec
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.Analysis
import com.grothgarvc.fourpartwriting.theory.Rule
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
import kotlin.math.abs

data class UiState(
    val score: Score = EXAMPLE,
    val voice: Voice = Voice.SOPRANO,
    /** Dark theme on; chosen in Settings. */
    val dark: Boolean = false,
    /** False until the user has picked Light or Dark (the first-run picker shows until then). */
    val themeChosen: Boolean = true,
    /** False until the welcome tutorial has been finished or skipped. */
    val tutorialSeen: Boolean = true,
    val duration: Duration = Duration(4),
    /** Accidental armed for the next placed note (null = follow key signature). */
    val accidental: Int? = null,
    val selected: NoteRef? = null,
    /** Time of the tapped head of [selected] (a tied note has several); bar and head deletes use it. */
    val selectedAt: Fraction? = null,
    val rules: Set<Rule> = Rule.entries.toSet(),
    val analysis: Analysis? = null,
    val focusedIssue: Int? = null,
    val playing: Boolean = false,
    /** Onset of the slice under the playback cursor. */
    val playTime: Fraction? = null,
    val soundLoaded: Boolean = false,
    /** A SoundFont is being read (on app start or after a pick). */
    val soundLoading: Boolean = false,
    /** Name of the .sf2 file the user picked (null before any pick in this version). */
    val soundName: String? = null,
    val message: String? = null,
    val undo: List<Score> = emptyList(),
    val redo: List<Score> = emptyList(),
    /** Bumped when the view should scroll to the current note (edits, selection jumps). */
    val followTick: Int = 0,
    /** Onsets of block chords whose voices look entered in the wrong order (shown as a prompt). */
    val reorder: List<Fraction> = emptyList(),
    /** Voice locked for input (hold a voice button): every new note goes to it. */
    val locked: Voice? = null,
    /** A selected note's pitch is being dragged. */
    val dragging: Boolean = false,
) {
    /** Voice shown on the caret and voice buttons: while dragging, the dragged note's voice, not the next input voice. */
    val shownVoice: Voice get() = selected?.voice?.takeIf { dragging } ?: voice
    val selectedEvent: Event? get() = selected?.let { score.voice(it.voice).getOrNull(it.index) }
    /** [selectedAt] while it lies inside the selected event, else the event's onset. */
    val selectedTime: Fraction? get() = selected?.let { r ->
        val on = score.onsets(r.voice).getOrNull(r.index) ?: return@let null
        selectedAt?.takeIf { it >= on && it < on + score.voice(r.voice)[r.index].length } ?: on
    }
    /** Duration shown on the palette: the selected note's written value if it has one. */
    val shownDuration: Duration get() = selectedEvent?.let { Notation.representable(it.length) } ?: duration
}

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
        "E4 C4 G3 C3", "F4 C4 A3 F2", "D4 B3 G3 G2", "E4 C4 G3 C3",
        "E4 C4 A3 A2", "F4 D4 A3 F2", "E4 C4 G3 G2", "D4 B3 F3 G2",
        "C4:1 C4:1 E3:1 C3:1",
    ),
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("satb", Context.MODE_PRIVATE)
    private val scoreFile = File(app.filesDir, "score.txt")
    /** Undo/redo stacks, written when the app goes to the background. */
    private val historyFile = File(app.filesDir, "history.txt")
    private val history = runCatching { ScoreCodec.decodeHistory(historyFile.readText()) }.getOrNull()
    private val _state = MutableStateFlow(
        UiState(
            score = runCatching { scoreFile.readText() }.getOrNull()?.let(ScoreCodec::decode)?.let(Edit::dropEmptyLastBars) ?: EXAMPLE,
            undo = history?.first.orEmpty(),
            redo = history?.second.orEmpty(),
            // Stored as the rules switched OFF, so rules added in later versions start on
            rules = Rule.entries.toSet() - prefs.getStringSet("rulesOff", emptySet())!!.mapNotNull { n -> Rule.entries.firstOrNull { it.name == n } }.toSet(),
            // Until the user picks, start in the phone's current mode
            themeChosen = prefs.contains("dark"),
            tutorialSeen = prefs.getBoolean("tutorialSeen", false),
            dark = prefs.getBoolean("dark", app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES),
            soundName = prefs.getString("sf2Name", null),
            voice = Voice.entries.getOrElse(prefs.getInt("voice", 0)) { Voice.SOPRANO },
            duration = runCatching { Duration(prefs.getInt("durBase", 4), prefs.getInt("durDots", 0)) }.getOrDefault(Duration(4)),
        )
    )
    val state: StateFlow<UiState> = _state
    /** Copy of the SoundFont made by older versions; still used until the user picks a file again. */
    private val sf2 = File(app.filesDir, "piano.sf2")
    private var playJob: Job? = null
    /** SoundFont load started on app start, and a Play tap waiting for it. */
    private var loadJob: Job? = null
    private var waitJob: Job? = null
    private var saveJob: Job? = null
    private var tempoBase: Score? = null

    /** Last zoom (px per staff space) and horizontal offset, restored on the next start. */
    var viewport: Pair<Float, Float>? = prefs.getFloat("viewSp", -1f).takeIf { it > 0 }?.let { it to prefs.getFloat("viewX", 0f) }
        private set

    init {
        val savedUri = prefs.getString("sf2Uri", null)?.let(Uri::parse)
        if (savedUri != null || sf2.exists()) loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(soundLoading = true) }
            val ok = if (savedUri != null) loadSf2(savedUri) else Synth.load(sf2.path)
            _state.update {
                it.copy(soundLoaded = ok, soundLoading = false, message = if (ok || savedUri == null) it.message
                    else "Could not open ${it.soundName ?: "the SoundFont"}. Pick it again in Settings.")
            }
        }
        // Save the score shortly after it changes
        viewModelScope.launch {
            var last = s.score
            state.collect { st ->
                if (st.score != last) {
                    last = st.score
                    saveJob?.cancel()
                    saveJob = launch { delay(500); withContext(Dispatchers.IO) { writeScore(st.score) } }
                }
            }
        }
    }

    private fun writeScore(score: Score) = runCatching {
        val tmp = File(scoreFile.path + ".tmp")
        tmp.writeText(ScoreCodec.encode(score))
        tmp.renameTo(scoreFile)
    }

    /** Called when the app goes to the background. */
    fun saveNow() {
        writeScore(s.score)
        runCatching {
            val tmp = File(historyFile.path + ".tmp")
            tmp.writeText(ScoreCodec.encodeHistory(s.undo, s.redo))
            tmp.renameTo(historyFile)
        }
        prefs.edit().putInt("voice", s.voice.ordinal).putInt("durBase", s.duration.base).putInt("durDots", s.duration.dots)
            .apply {
                viewport?.let { (sp, x) -> putFloat("viewSp", sp).putFloat("viewX", x) }
            }.apply()
    }

    fun rememberViewport(sp: Float, offX: Float) { viewport = sp to offX }

    private val s get() = _state.value
    private fun msg(m: String) = _state.update { it.copy(message = m) }
    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Applies a score edit with undo; re-runs the check if results are shown. */
    private fun edit(
        new: Score, selected: NoteRef? = s.selected, voice: Voice = s.voice,
        selectedAt: Fraction? = if (selected == s.selected) s.selectedAt else null,
    ) {
        if (new == s.score) { _state.update { it.copy(selected = selected, selectedAt = selectedAt, voice = voice) }; return }
        val dragging = dragBase != null
        _state.update {
            it.copy(
                score = new, selected = selected, selectedAt = selectedAt, voice = voice,
                followTick = if (dragging) it.followTick else it.followTick + 1,
                undo = if (dragging) it.undo else (it.undo + it.score).takeLast(200), redo = emptyList(),
                // While dragging a pitch, check once on release (dragPitch(true)), not at every step
                analysis = if (dragging) it.analysis else it.analysis?.let { _ -> VoiceLeading.check(new, it.rules) },
                focusedIssue = if (dragging) it.focusedIssue else null,
            )
        }
        if (!dragging) checkOrder() // a dragged pitch may pass through a crossing on its way
    }

    // ---------- voice order ----------

    /** Chords the user said are fine as they are, by onset and pitches (a changed chord is asked about again). */
    private val keptOrder = HashSet<Pair<Fraction, List<Pitch?>>>()

    private fun chordKey(sc: Score, t: Fraction) =
        t to Voice.entries.map { v -> sc.eventAt(v, t).takeIf { it >= 0 }?.let { sc.voice(v)[it].pitch } }

    private fun checkOrder() = _state.update { st ->
        st.copy(reorder = Edit.misorderedChords(st.score).filter { chordKey(st.score, it) !in keptOrder })
    }

    /** Gives the highest note to S ... the lowest to B in every flagged chord (one undo step). */
    fun reorderVoices() = edit(Edit.sortChords(s.score, s.reorder))

    fun keepOrder() {
        s.reorder.forEach { keptOrder += chordKey(s.score, it) }
        _state.update { it.copy(reorder = emptyList()) }
    }

    /** The event of [v] sounding at [t] in [score], as a selection. */
    private fun refAt(score: Score, v: Voice, t: Fraction) = score.eventAt(v, t).takeIf { it >= 0 }?.let { NoteRef(v, it) }

    private fun onsetOf(r: NoteRef) = s.score.onsets(r.voice)[r.index]

    // ---------- input ----------

    fun tap(hit: Hit) {
        val score = s.score
        val v = s.locked ?: s.voice
        val end = score.length(v)
        val ml = score.time.measure
        fun measureOf(t: Fraction) = (t / ml).let { it.num / it.den }
        val appendSlot = hit.time >= end || (hit.afterLast && (measureOf(hit.time) == measureOf(end) ||
            (measureOf(hit.time) == measureOf(end) - 1 && (end / ml).den == 1L)))

        when {
            // In the slot for the next note, only a tap right on an existing note selects it.
            appendSlot && hit.exact != null -> select(hit.exact, hit.exactAt)
            appendSlot -> {
                val base = score.clefs[hit.staff].pitchAt(hit.position)
                val pitch = onStaff(score, v, Pitch(base.step, s.accidental ?: score.key.sigAlter(base.step), base.octave))
                // The note goes where it was tapped; the time skipped becomes a rest. A tap after the last
                // column of a later bar means that bar's start; of the current bar, right after the end.
                val at = when {
                    hit.time <= end -> end
                    !hit.afterLast -> hit.time
                    measureOf(hit.time) > measureOf(end) -> (ml * Fraction.of(measureOf(hit.time), 1)).coerceAtLeast(end)
                    else -> end
                }
                val new = Edit.append(score, v, s.duration.value, pitch, at)
                edit(new, NoteRef(v, new.voice(v).lastIndex), nextVoice(new))
                resumeVoice = null
                _state.update { it.copy(accidental = null) }
                preview(pitch)
            }
            // A tap in a rest of the input voice (not on a note) puts a note there
            hit.exact == null && hit.near == null && fillRest(hit, v) -> {}
            // Tapping the selected head again, or empty space, clears the selection; another head of a tied note selects that head
            hit.near == null || (hit.near == s.selected && hit.nearAt == s.selectedTime) -> deselect()
            else -> select(hit.near, hit.nearAt)
        }
    }

    private fun fillRest(hit: Hit, v: Voice): Boolean {
        val score = s.score
        val base = score.clefs[hit.staff].pitchAt(hit.position)
        val pitch = onStaff(score, v, Pitch(base.step, s.accidental ?: score.key.sigAlter(base.step), base.octave))
        val (new, i) = Edit.fillRest(score, v, hit.time, s.duration.value, pitch) ?: return false
        edit(new, NoteRef(v, i), nextVoice(new))
        resumeVoice = null
        _state.update { it.copy(accidental = null) }
        preview(pitch)
        return true
    }

    /** Voice for the next new note: the locked one, else the one ending earliest in [sc] (ties go S→A→T→B). */
    private fun nextVoice(sc: Score) = s.locked ?: earliestVoice(sc)

    private fun earliestVoice(sc: Score) = Voice.entries.minBy { sc.length(it) }

    /** Keeps [p] within [NOTE_POSITIONS] on [v]'s own staff (a far tap must not make a tower of ledger lines). */
    private fun onStaff(sc: Score, v: Voice, p: Pitch): Pitch {
        val clef = sc.clefs[sc.staffOf(v)]
        val pos = clef.position(p)
        if (pos in NOTE_POSITIONS) return p
        val b = clef.pitchAt(pos.coerceIn(NOTE_POSITIONS))
        return Pitch(b.step, sc.key.sigAlter(b.step), b.octave)
    }

    /** Input voice to return to when the selection is cleared (selecting a note switches to its voice). */
    private var resumeVoice: Voice? = null

    fun select(ref: NoteRef?, at: Fraction? = null) {
        if (ref == null) { deselect(); return }
        if (resumeVoice == null) resumeVoice = s.voice
        _state.update { it.copy(selected = ref, selectedAt = at, voice = ref?.voice ?: it.voice) }
        ref?.let { r -> s.score.voice(r.voice).getOrNull(r.index)?.pitch?.let(::preview) }
    }

    /** Selects the previous (-1) or next (+1) event of the active voice and plays it. */
    fun step(dir: Int) {
        val v = s.voice
        val n = s.score.voice(v).size
        if (n == 0) return
        val cur = s.selected?.takeIf { it.voice == v }?.index
        val i = when {
            cur == null -> if (dir > 0) 0 else n - 1
            else -> (cur + dir).coerceIn(0, n - 1)
        }
        select(NoteRef(v, i))
        _state.update { it.copy(followTick = it.followTick + 1) }
    }

    /** With a note selected, jumps to voice [v]'s note at the same time; otherwise sets the input voice. */
    fun selectVoice(v: Voice) {
        val sel = s.selected
        // Any other voice button unlocks; input then resumes at the voice that ends earliest
        if (s.locked != null && s.locked != v) {
            _state.update { it.copy(locked = null) }
            if (sel == null) { resumeVoice = null; _state.update { it.copy(voice = earliestVoice(it.score)) }; return }
        }
        if (sel == null) { resumeVoice = null; _state.update { it.copy(voice = v) }; return }
        val t = s.selectedTime ?: onsetOf(sel)
        val ref = refAt(s.score, v, t)
        if (ref != null) { select(ref, t); _state.update { it.copy(followTick = it.followTick + 1) } }
        else _state.update { it.copy(voice = v, selected = null) }
    }

    fun deselect() {
        val back = s.locked ?: resumeVoice
        _state.update { it.copy(selected = null, voice = back ?: it.voice) }
        resumeVoice = null
    }

    /** Hold a voice button: all new notes go to [v] until another voice button is tapped. */
    fun lockVoice(v: Voice) {
        resumeVoice = null
        _state.update { it.copy(locked = v, voice = v, selected = null) }
    }

    fun finishTutorial() {
        _state.update { it.copy(tutorialSeen = true) }
        prefs.edit().putBoolean("tutorialSeen", true).apply()
    }

    fun setDark(dark: Boolean) {
        _state.update { it.copy(dark = dark, themeChosen = true) }
        prefs.edit().putBoolean("dark", dark).apply()
    }


    /** Applies [f] to the selected event and keeps the selection on the event at the same onset. */
    private fun editSelected(f: (Score, Voice, Int) -> Score): Boolean {
        val r = s.selected ?: return false
        if (s.score.voice(r.voice).getOrNull(r.index) == null) return false
        val t = onsetOf(r)
        val new = f(s.score, r.voice, r.index)
        edit(new, refAt(new, r.voice, t))
        return true
    }

    fun setDuration(base: Int) {
        val d = Duration(base, s.duration.dots)
        _state.update { it.copy(duration = d) }
        editSelected { sc, v, i -> Edit.setLength(sc, v, i, d.value) }
    }

    /** Cycles 0 → 1 → 2 dots on the palette value (and the selected note). */
    fun toggleDot() {
        val cur = s.shownDuration
        val d = Duration(cur.base, (cur.dots + 1) % 3)
        _state.update { it.copy(duration = d) }
        editSelected { sc, v, i -> Edit.setLength(sc, v, i, d.value) }
    }

    fun setAccidental(alter: Int) {
        val e = s.selectedEvent
        val p = e?.pitch
        if (p != null) {
            val newAlter = if (p.alter == alter) s.score.key.sigAlter(p.step) else alter
            editSelected { sc, v, i -> Edit.setPitch(sc, v, i, p.copy(alter = newAlter)) }
            preview(p.copy(alter = newAlter))
        } else _state.update { it.copy(accidental = if (it.accidental == alter) null else alter) }
    }

    private var dragBase: Score? = null

    /** Pitch drag: null = started, true = finished (one undo step), false = cancelled (restore). */
    fun dragPitch(done: Boolean?) {
        when (done) {
            null -> { dragBase = s.score; _state.update { it.copy(dragging = true) } }
            true -> {
                val base = dragBase ?: return
                dragBase = null
                _state.update { it.copy(dragging = false) }
                if (base != s.score) _state.update {
                    it.copy(undo = (it.undo + base).takeLast(200), redo = emptyList(),
                        analysis = it.analysis?.let { _ -> VoiceLeading.check(it.score, it.rules) }, focusedIssue = null)
                }
                checkOrder()
            }
            false -> {
                val base = dragBase ?: return
                dragBase = null
                _state.update { it.copy(score = base, dragging = false, analysis = it.analysis?.let { _ -> VoiceLeading.check(base, it.rules) }) }
                checkOrder()
            }
        }
    }

    /** Moves the selected note by diatonic steps, spelling it by the key signature. */
    fun nudge(steps: Int) {
        val p = s.selectedEvent?.pitch ?: return
        val q = p.shiftDiatonic(steps).let { it.copy(alter = s.score.key.sigAlter(it.step)) }
        if (q.midi !in 21..108) return
        // Stay within the ledger-line limit, but a note already beyond it may still move back
        val clef = s.score.clefs[s.score.staffOf(s.selected!!.voice)]
        val pos = clef.position(q)
        if (pos !in NOTE_POSITIONS && abs(pos - 4) >= abs(clef.position(p) - 4)) return
        editSelected { sc, v, i -> Edit.setPitch(sc, v, i, q) }
        preview(q)
    }

    fun rest() {
        if (editSelected { sc, v, i -> Edit.dropEmptyLastBars(Edit.setPitch(sc, v, i, null)) }) return
        val v = s.locked ?: s.voice
        val new = Edit.append(s.score, v, s.duration.value, null)
        edit(new, null, nextVoice(new))
        resumeVoice = null
    }

    fun tie() {
        val r = s.selected ?: run { msg("Select a note to tie it to the next one."); return }
        val e = s.selectedEvent ?: return
        // A tapped head with a tie going out of it: remove that tie (the note splits in two)
        if (e.pitch != null) {
            val t = onsetOf(r)
            val segs = Notation.split(t, e.length, s.score.time, false)
            val k = segs.indexOfLast { it.onset <= (s.selectedTime ?: t) }
            if (k < segs.lastIndex) { edit(Edit.untie(s.score, r.voice, r.index, segs[k + 1].onset)); return }
        }
        if (e.pitch == null || r.index == s.score.voice(r.voice).lastIndex) { msg("Nothing to tie to."); return }
        editSelected { sc, v, i -> Edit.tie(sc, v, i) }
    }

    fun delete() {
        val r = s.selected
        val score = s.score
        if (r == null) {
            val v = s.locked ?: s.voice
            val list = score.voice(v)
            if (list.isNotEmpty()) edit(Edit.dropEmptyLastBars(score.withVoice(v, list.dropLast(1))), null)
            return
        }
        val list = score.voice(r.voice)
        if (r.index !in list.indices) return
        val t = onsetOf(r)
        // A tied note: delete only the tapped head
        val e = list[r.index]
        val segs = if (e.pitch == null) emptyList() else Notation.split(t, e.length, score.time, false)
        val at = s.selectedTime ?: t
        val seg = segs.takeIf { it.size > 1 }?.firstOrNull { at >= it.onset && at < it.onset + it.length }
        if (seg != null) {
            val new = Edit.dropEmptyLastBars(Edit.restPart(score, r.voice, r.index, seg.onset, seg.onset + seg.length))
            // Stay at the deleted head (now a rest); when the end was cut off, go to the head before it
            val cut = refAt(new, r.voice, seg.onset) == null
            val back = if (cut) segs[segs.indexOf(seg) - 1].onset else seg.onset
            edit(new, refAt(new, r.voice, back), selectedAt = back)
            return
        }
        val new = Edit.dropEmptyLastBars(Edit.delete(score, r.voice, r.index))
        // Keep deleting backwards from the end; otherwise stay at the same time
        val sel = if (r.index == list.lastIndex) new.voice(r.voice).lastIndex.takeIf { it >= 0 }?.let { NoteRef(r.voice, it) }
        else refAt(new, r.voice, t)
        edit(new, sel)
    }

    /** Hold delete and drop on the bin: removes the whole bar of the selected note, in all voices. */
    fun deleteMeasure() {
        val r = s.selected
        if (r == null || s.selectedEvent == null) { msg("Select a note in the bar you want to delete."); return }
        val ml = s.score.time.measure
        val bar = (s.selectedTime!! / ml).let { (it.num / it.den).toInt() }
        val new = Edit.dropEmptyLastBars(Edit.deleteMeasure(s.score, bar))
        // Stay in place so the next bar can go at once: the first event of the bar that moved into this
        // position, or the last event of the previous bar when the last bar was deleted. Same voice if it has one.
        val later = new.length > ml * Fraction.of(bar.toLong(), 1)
        val b0 = ml * Fraction.of((if (later) bar else maxOf(bar - 1, 0)).toLong(), 1)
        fun inBar(v: Voice) = new.onsets(v).withIndex().filter { (_, t) -> t >= b0 && t < b0 + ml }
            .let { if (later) it.firstOrNull() else it.lastOrNull() }?.let { NoteRef(v, it.index) }
        val sel = inBar(r.voice) ?: Voice.entries.firstNotNullOfOrNull(::inBar)
        edit(new, sel, sel?.voice ?: s.voice)
        msg("Bar ${bar + 1} deleted.")
    }

    fun undo() {
        _state.update {
            val prev = it.undo.lastOrNull() ?: return@update it
            it.copy(score = prev, undo = it.undo.dropLast(1), redo = it.redo + it.score, selected = null,
                analysis = it.analysis?.let { _ -> VoiceLeading.check(prev, it.rules) })
        }
        checkOrder()
        Synth.setTempo(s.score.tempo) // undoing a tempo change applies to playback too
    }

    fun redo() {
        _state.update {
            val next = it.redo.lastOrNull() ?: return@update it
            it.copy(score = next, redo = it.redo.dropLast(1), undo = it.undo + it.score, selected = null,
                analysis = it.analysis?.let { _ -> VoiceLeading.check(next, it.rules) })
        }
        checkOrder()
        Synth.setTempo(s.score.tempo) // undoing a tempo change applies to playback too
    }

    // ---------- score settings ----------

    fun setKey(k: Key) = edit(s.score.copy(key = k))

    /** Live tempo change while dragging; one undo step is added in [commitTempo]. */
    fun setTempo(t: Int) {
        if (tempoBase == null) tempoBase = s.score
        _state.update { it.copy(score = it.score.copy(tempo = t.coerceIn(20, 300))) }
        Synth.setTempo(s.score.tempo) // a playing score follows at once
    }

    fun commitTempo() {
        val base = tempoBase ?: return
        tempoBase = null
        if (base != s.score) _state.update { it.copy(undo = (it.undo + base).takeLast(200), redo = emptyList()) }
    }

    fun setClef(staff: Int, c: Clef) = edit(s.score.copy(clefs = s.score.clefs.toMutableList().also { it[staff] = c }))
    fun setArrangement(a: Arrangement) = edit(s.score.withArrangement(a))
    fun setTime(t: TimeSig) = edit(s.score.copy(time = t))
    fun clearScore() = edit(s.score.copy(voices = List(4) { emptyList() }), null, Voice.SOPRANO)
    fun loadExample() = edit(EXAMPLE.withArrangement(s.score.arrangement), null, Voice.SOPRANO)

    // ---------- analysis ----------

    /** ✓ button: show results, or hide them when they are shown. */
    fun check() = _state.update {
        if (it.analysis != null) it.copy(analysis = null, focusedIssue = null)
        else it.copy(analysis = VoiceLeading.check(it.score, it.rules), focusedIssue = null)
    }
    fun closeAnalysis() = _state.update { it.copy(analysis = null, focusedIssue = null) }
    fun focusIssue(i: Int?) = _state.update { it.copy(focusedIssue = if (it.focusedIssue == i) null else i) }

    fun toggleRule(r: Rule) {
        _state.update {
            val rules = if (r in it.rules) it.rules - r else it.rules + r
            it.copy(rules = rules, analysis = it.analysis?.let { _ -> VoiceLeading.check(it.score, rules) }, focusedIssue = null)
        }
        prefs.edit().putStringSet("rulesOff", (Rule.entries - s.rules).map { it.name }.toSet()).apply()
    }

    // ---------- sound ----------

    private fun preview(p: Pitch) {
        if (!s.soundLoaded || s.playing) return
        Synth.noteOn(p.midi)
        viewModelScope.launch { delay(450); Synth.noteOff(p.midi) }
    }

    fun togglePlay() {
        if (s.playing) { stop(); return }
        // A second tap while waiting cancels the wait
        if (waitJob?.isActive == true) { waitJob?.cancel(); return }
        val loading = loadJob
        if (!s.soundLoaded && loading?.isActive == true) {
            msg("Loading the sound… playback starts when it is ready.")
            // Clear waitJob first, or togglePlay sees an active wait and cancels it
            waitJob = viewModelScope.launch { loading.join(); waitJob = null; togglePlay() }
            return
        }
        if (!s.soundLoaded) { msg("Load a SoundFont (.sf2) in Settings to play."); return }
        val score = s.score
        val onsets = score.slices().map { it.time }
        if (onsets.isEmpty()) return
        Synth.play(score)
        _state.update { it.copy(playing = true, selected = null) }
        playJob = viewModelScope.launch {
            delay(50)
            while (true) {
                val whole = Synth.position() ?: break
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
        val resolver = getApplication<Application>().contentResolver
        val name = withContext(Dispatchers.IO) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
        }
        val old = prefs.getString("sf2Uri", null)
        _state.update { it.copy(soundLoading = true) }
        val ok = withContext(Dispatchers.IO) {
            // Keep read access across restarts: the file is read where it is, never copied
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            loadSf2(uri) // on failure the native side keeps the previous font
        }
        if (ok) {
            prefs.edit().putString("sf2Uri", uri.toString()).putString("sf2Name", name).apply()
            if (old != null && old != uri.toString()) releaseSf2(old)
            sf2.delete() // free the copy older versions kept
        } else if (old != uri.toString()) releaseSf2(uri.toString())
        _state.update {
            it.copy(soundLoaded = it.soundLoaded || ok, soundLoading = false, soundName = if (ok) name else it.soundName,
                message = if (ok) "SoundFont loaded: ${name ?: "file"}" else "Could not read ${name ?: "that file"} as a SoundFont (.sf2).")
        }
    }

    /** Loads the SoundFont straight from the user's file through its file descriptor, without a copy. */
    private fun loadSf2(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")!!.use { Synth.loadFd(it.fd) }
    }.getOrDefault(false)

    private fun releaseSf2(uri: String) = runCatching {
        getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun exportMusicXml(uri: Uri) = viewModelScope.launch {
        val xml = MusicXml.export(s.score)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(xml.toByteArray()) }
            }.isSuccess
        }
        msg(if (ok) "MusicXML exported." else "Could not write the file.")
    }
}
