package com.nefelion.fourpartwriting

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nefelion.fourpartwriting.audio.ChordGather
import com.nefelion.fourpartwriting.audio.MidiIn
import com.nefelion.fourpartwriting.audio.Synth
import com.nefelion.fourpartwriting.engrave.Hit
import com.nefelion.fourpartwriting.engrave.NOTE_POSITIONS
import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.engrave.Notation
import com.nefelion.fourpartwriting.export.MusicXml
import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Edit
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Tuplet
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.Exercises
import com.nefelion.fourpartwriting.theory.FiguredBass
import com.nefelion.fourpartwriting.theory.Voicer
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
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
    /** Name of the open score in the library. */
    val scoreName: String = "",
    /** All saved scores (null: file unreadable), most recently changed first. */
    val scores: List<Pair<String, Score?>> = emptyList(),
    val voice: Voice = Voice.SOPRANO,
    /** Dark theme on; chosen in Settings. */
    val dark: Boolean = false,
    /** False until the user has picked Light or Dark (the first-run picker shows until then). */
    val themeChosen: Boolean = true,
    /** False until the welcome tutorial has been finished or skipped. */
    val tutorialSeen: Boolean = true,
    val duration: Duration = Duration(4),
    /** New notes are tuplets, n in the time of m (m/n of [duration]); turns itself off when the group is full. */
    val tuplet: Pair<Int, Int>? = null,
    /** Accidental armed for the next placed note (null = follow key signature). */
    val accidental: Int? = null,
    val selected: NoteRef? = null,
    /**
     * [selected] is only the note just entered (not one the user picked): length, dot, tuplet and rest
     * then set up the next note instead of changing it.
     */
    val placed: Boolean = false,
    /** The palette changed since the last entered note: the next note takes the palette value instead of copying the chord. */
    val paletteSet: Boolean = false,
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
    /** Names of the connected MIDI keyboards. */
    val midiDevices: List<String> = emptyList(),
    /** Bluetooth MIDI keyboards found by the current or last search. */
    val midiFound: List<String> = emptyList(),
    val midiScanning: Boolean = false,
    /** Result of the last Bluetooth search or connection, shown in Settings. */
    val midiNote: String? = null,
    /** Note a tap would add, shown while the finger is down. */
    val ghost: Ghost? = null,
    val hint: Hint? = null,
    /** Voice locked for input (hold a voice button): every new note goes to it. */
    val locked: Voice? = null,
    /** A selected note's pitch is being dragged. */
    val dragging: Boolean = false,
    /** Movement mode (tap the active voice button): taps only select notes, nothing is added. */
    val moving: Boolean = false,
) {
    /** Voice shown on the caret and voice buttons: while dragging, the dragged note's voice, not the next input voice. */
    val shownVoice: Voice get() = selected?.voice?.takeIf { dragging } ?: voice
    val selectedEvent: Event? get() = selected?.let { score.voice(it.voice).getOrNull(it.index) }
    /** [selectedAt] while it lies inside the selected event, else the event's onset. */
    val selectedTime: Fraction? get() = selected?.let { r ->
        val on = score.onsets(r.voice).getOrNull(r.index) ?: return@let null
        selectedAt?.takeIf { it >= on && it < on + score.voice(r.voice)[r.index].length } ?: on
    }
    /** The selected note, when the palette edits it (picked by the user, not just entered). */
    private val paletteTarget: Event? get() = selectedEvent?.takeIf { !placed }
    /** Duration shown on the palette: the selected note's written value if it has one. */
    val shownDuration: Duration get() = paletteTarget?.let { e ->
        val t = shownTuplet
        Notation.representable(if (t == null) e.length else e.length * Fraction.of(t.first.toLong(), t.second.toLong()))
    } ?: duration
    /** Tuplet shown on the palette: the selected note's (an untagged triplet value from older files counts as 3:2), else the armed one. */
    val shownTuplet: Pair<Int, Int>? get() {
        val e = paletteTarget ?: return tuplet
        return e.tuplet?.let { it.n to it.m }
            ?: (3 to 2).takeIf { Notation.representable(e.length) == null && Notation.representable(e.length * Fraction.of(3, 2)) != null }
    }
    /** Length of a new note from the palette. */
    val armedLength: Fraction get() = tupletLength(duration, tuplet)
    /** Tuplet of a new note from the palette. */
    val armedTuplet: Tuplet? get() = tuplet?.let { (n, m) -> Tuplet(n, m, duration.value) }
}

/** Sounding length of written value [d] inside an n:m tuplet [t]. */
fun tupletLength(d: Duration, t: Pair<Int, Int>?) = if (t == null) d.value else d.value * Fraction.of(t.second.toLong(), t.first.toLong())

/** Preview notehead: staff, staff position, onset. */
/** A faded note: the tap preview, or a hint ([voice] colors it; [alter]: an accidental to show). */
data class Ghost(val staff: Int, val pos: Int, val at: Fraction, val voice: Voice? = null, val alter: Int? = null)

/** Notes suggested by Hint, shown while the score is still [score] (any edit hides them). */
data class Hint(val score: Score, val notes: List<Ghost>)

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
    private val library = ScoreLibrary(File(app.filesDir, "scores"))
    /** Undo/redo stacks of the open score, written when the app goes to the background. */
    private val historyFile = File(app.filesDir, "history.txt")
    private val history = runCatching { ScoreCodec.decodeHistory(historyFile.readText()) }.getOrNull()
    /** Open score's name; the library is filled on first start (the one score older versions kept, or the example). */
    private val startName: String = run {
        prefs.getString("current", null)?.takeIf(library::exists)?.let { return@run it }
        library.names().firstOrNull()?.let { return@run it }
        val old = File(app.filesDir, "score.txt")
        val oldScore = runCatching { old.readText() }.getOrNull()?.let(ScoreCodec::decode)
        val name = if (oldScore != null) "My score" else "Example"
        if (library.write(name, oldScore ?: EXAMPLE)) old.delete()
        name
    }.also { prefs.edit().putString("current", it).apply() }
    private val _state = MutableStateFlow(
        UiState(
            scoreName = startName,
            scores = library.list(),
            score = library.read(startName)?.let(Edit::dropEmptyLastBars) ?: EXAMPLE,
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

    /** MIDI keyboard: keys pressed together (held until all are released) arrive as one chord. */
    private val midi = MidiIn(app, { n, on -> midiKey(n, on) }, { names ->
        midiChord.reset() // a keyboard unplugged mid-chord never sends its key releases
        _state.update { it.copy(midiDevices = names) }
    })
    private val btFound = mutableListOf<android.bluetooth.BluetoothDevice>()
    private val midiChord = ChordGather()

    /** Last zoom (px per staff space) and horizontal offset, restored on the next start. */
    var viewport: Pair<Float, Float>? = prefs.getFloat("viewSp", -1f).takeIf { it > 0 }?.let { it to prefs.getFloat("viewX", 0f) }
        private set

    init {
        reconnectMidi()
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
            var lastName = s.scoreName
            state.collect { st ->
                // Opening another score is not an edit: nothing to save
                if (st.scoreName != lastName) { lastName = st.scoreName; last = st.score }
                if (st.score != last) {
                    last = st.score
                    saveJob?.cancel()
                    // On the main thread (a few KB): a rename or switch can then cancel it without a write racing behind
                    saveJob = launch { delay(500); library.write(st.scoreName, st.score) }
                }
            }
        }
    }

    /** Called when the app goes to the background. */
    fun saveNow() {
        flushScore()
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
        edited: Score, selected: NoteRef? = s.selected, voice: Voice = s.voice,
        selectedAt: Fraction? = if (selected == s.selected) s.selectedAt else null,
    ) {
        // An exercise's given voices stay as they are (naming their chords and fermatas is fine)
        s.score.given.firstOrNull { v -> v in edited.given && edited.voice(v).map { it.copy(chord = null, fermata = false) } != s.score.voice(v).map { it.copy(chord = null, fermata = false) } }?.let { v ->
            return msg("The ${v.name.lowercase()} is given in this exercise.")
        }
        val new = if (dragBase != null) edited else VoiceLeading.pruneMutes(edited)
        val placed = s.placed && selected == s.selected
        if (new == s.score) { _state.update { it.copy(selected = selected, selectedAt = selectedAt, voice = voice, placed = placed) }; return }
        val dragging = dragBase != null
        _state.update {
            it.copy(
                score = new, selected = selected, selectedAt = selectedAt, voice = voice, placed = placed,
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

    /** Where a tap at [hit] puts a note: its pitch, time, and whether it fills a rest (else appends). Null = no note. */
    private data class Target(val pitch: Pitch, val at: Fraction, val inRest: Boolean)

    private fun measureOf(t: Fraction) = (t / s.score.time.measure).let { it.num / it.den }

    /** The tap is in the slot for the input voice's next note (at or after its end). */
    private fun appendSlot(hit: Hit): Boolean {
        val end = s.score.length(s.locked ?: s.voice)
        return hit.time >= end || (hit.afterLast && (measureOf(hit.time) == measureOf(end) ||
            (measureOf(hit.time) == measureOf(end) - 1 && (end / s.score.time.measure).den == 1L)))
    }

    private fun target(hit: Hit): Target? {
        if (s.moving) return null
        val score = s.score
        val v = s.locked ?: s.voice
        val end = score.length(v)
        val ml = score.time.measure
        val base = score.clefs[hit.staff].pitchAt(hit.position)
        val pitch = onStaff(score, v, Pitch(base.step, s.accidental ?: score.key.sigAlter(base.step), base.octave))
        if (appendSlot(hit)) {
            // In the slot for the next note, only a tap right on an existing note selects it;
            // on another voice's staff (where the input voice can't go) a tap near a note does too.
            if (hit.exact != null || (hit.near != null && hit.staff != score.staffOf(v))) return null
            // The note goes where it was tapped; the time skipped becomes a rest. A tap after the last
            // column of a later bar means that bar's start; of the current bar, right after the end.
            val at = when {
                hit.time <= end -> end
                !hit.afterLast -> hit.time
                measureOf(hit.time) > measureOf(end) -> (ml * Fraction.of(measureOf(hit.time), 1)).coerceAtLeast(end)
                else -> end
            }
            return Target(pitch, at, false)
        }
        // A tap in a rest of the input voice (not on a note) puts a note there
        if (hit.exact != null || hit.near != null) return null
        val i = score.eventAt(v, hit.time).takeIf { it >= 0 } ?: return null
        return if (score.voice(v)[i].pitch == null) Target(pitch, hit.time, true) else null
    }

    /** Finger down on the score: show and play the note a tap there would add, before the finger lifts. */
    fun press(hit: Hit?): Int? = hit?.let(::aim).also { if (it == null) _state.update { s -> s.copy(ghost = null) } }

    /**
     * Finger held and moved: shows and plays the note a release at [hit] would add.
     * Returns its position on [hit]'s staff (it may differ: the pitch is kept in range), or null (nothing changes) if no note can go there.
     */
    fun aim(hit: Hit): Int? {
        val t = target(hit) ?: return null
        val sc = s.score
        val g = Ghost(sc.staffOf(s.locked ?: s.voice), sc.clefs[sc.staffOf(s.locked ?: s.voice)].position(t.pitch), t.at)
        if (g != s.ghost) { _state.update { it.copy(ghost = g) }; preview(t.pitch) }
        return sc.clefs[hit.staff].position(t.pitch)
    }

    /** Returns true when a note was placed. */
    fun tap(hit: Hit): Boolean {
        _state.update { it.copy(ghost = null) }
        if (s.moving) {
            if (hit.near != null) select(hit.near, hit.nearAt) else if (hit.exact != null) select(hit.exact, hit.exactAt)
            else if (hit.rest != null) select(hit.rest, hit.restAt)
            return false
        }
        val t = target(hit)
        if (t != null) {
            val score = s.score
            val v = s.locked ?: s.voice
            val e = input(score, v, t.at)
            val (new, i) = if (t.inRest) Edit.fillRest(score, v, t.at, e.length, t.pitch, e.tuplet) ?: return false
                else Edit.append(score, v, e.length, t.pitch, t.at, e.tuplet).let { it to it.voice(v).lastIndex }
            edit(new, NoteRef(v, i), nextVoice(new))
            resumeVoice = null
            _state.update { it.copy(accidental = null, placed = true) }
            entered(new)
            // Already heard on finger-down
            return true
        }
        val slot = appendSlot(hit)
        when {
            slot && hit.exact != null -> select(hit.exact, hit.exactAt)
            slot && hit.near != null -> select(hit.near, hit.nearAt)
            // Tapping the selected head again, or empty space, clears the selection; another head of a tied note selects that head
            hit.near == null || (hit.near == s.selected && hit.nearAt == s.selectedTime) -> deselect()
            else -> select(hit.near, hit.nearAt)
        }
        return false
    }

    /**
     * Length and tuplet of a new event at [at] (no pitch). Inside its own unfinished tuplet group a voice goes on in
     * that tuplet; else in chord mode (no lock, palette untouched since the last note) it copies the voice entered
     * first; else the palette value.
     */
    private fun input(sc: Score, v: Voice, at: Fraction): Event {
        Notation.tupletGroups(sc.voice(v), sc.time).firstOrNull { at > it.start && at < it.end }?.let { g ->
            // Palette untouched: repeat the voice's last note in the group (another voice may have changed the palette)
            val on = sc.onsets(v)
            val prev = sc.voice(v).indices.lastOrNull { on[it] >= g.start && on[it] < at }?.let { sc.voice(v)[it] }
            if (!s.paletteSet && prev?.pitch != null && at + prev.length <= g.end) return Event(prev.length, null, prev.tuplet)
            return Event(tupletLength(s.duration, g.n to g.m), null, Tuplet(g.n, g.m, s.duration.value))
        }
        return (if (s.locked == null && !s.paletteSet) Edit.follow(sc, v, at) else null) ?: Event(s.armedLength, null, s.armedTuplet)
    }

    /** Voice for the next new note: the locked one, else the one ending earliest in [sc] (ties go S→A→T→B). */
    private fun nextVoice(sc: Score) = s.locked ?: earliestVoice(sc)

    private fun earliestVoice(sc: Score) = Voice.entries.filter { it !in sc.given }.ifEmpty { Voice.entries }.minBy { sc.length(it) }

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
        // Selecting in movement mode ends it; the note's voice becomes the input voice
        if (s.moving) { resumeVoice = ref.voice; _state.update { it.copy(moving = false) } }
        // Selecting another voice's note unlocks for good; that voice stays the input voice afterwards
        if (s.locked != null && s.locked != ref.voice) { resumeVoice = ref.voice; _state.update { it.copy(locked = null) } }
        if (resumeVoice == null) resumeVoice = s.voice
        _state.update { it.copy(selected = ref, selectedAt = at, voice = ref?.voice ?: it.voice, placed = false) }
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
        // The active voice button again enters movement mode (and drops the selection); any voice button leaves it
        if (s.moving) _state.update { it.copy(moving = false) }
        else if (v == s.voice) { resumeVoice = null; _state.update { it.copy(moving = true, selected = null) }; return }
        // Any other voice button unlocks and switches to that voice
        // (it becomes the input voice too, so clearing a jumped-to selection doesn't return to the old locked one)
        if (s.locked != null && s.locked != v) { resumeVoice = null; _state.update { it.copy(locked = null, voice = v) } }
        if (sel == null) { resumeVoice = null; _state.update { it.copy(voice = v) }; return }
        val t = s.selectedTime ?: onsetOf(sel)
        val ref = refAt(s.score, v, t)
        if (ref != null) { select(ref, t); _state.update { it.copy(followTick = it.followTick + 1) } }
        else _state.update { it.copy(voice = v, selected = null) }
    }

    /** Selects the bass note to put figures on: the one sounding at the selected time, else the first. False if the bass is empty. */
    fun startFigures(): Boolean {
        val bass = s.score.voice(Voice.BASS)
        if (bass.none { it.pitch != null }) { msg("Enter a bass line first."); return false }
        if (s.selected?.voice == Voice.BASS) return true
        val at = s.selectedTime?.let { s.score.eventAt(Voice.BASS, it) }?.takeIf { it >= 0 }
        select(NoteRef(Voice.BASS, at ?: bass.indexOfFirst { it.pitch != null }))
        _state.update { it.copy(followTick = it.followTick + 1) }
        return true
    }

    /** Figures of the selected bass note, top to bottom (see FiguredBass). */
    fun setFigures(figures: List<String>) {
        val r = s.selected?.takeIf { it.voice == Voice.BASS } ?: return
        val evs = s.score.voice(Voice.BASS)
        val e = evs.getOrNull(r.index)?.takeIf { it.pitch != null } ?: return
        edit(s.score.withVoice(Voice.BASS, evs.toMutableList().also { it[r.index] = e.copy(figures = figures) }))
    }

    /** Selects a note to name a chord on: the one selected, else the soprano at the selected time, else the first. False if the score is empty. */
    fun startChords(): Boolean {
        if (s.selectedEvent?.pitch != null) return true
        val v = Voice.entries.firstOrNull { v -> s.score.voice(v).any { it.pitch != null } } ?: run { msg("Write some notes first."); return false }
        val at = s.selectedTime?.let { s.score.eventAt(v, it) }?.takeIf { it >= 0 && s.score.voice(v)[it].pitch != null }
        select(NoteRef(v, at ?: s.score.voice(v).indexOfFirst { it.pitch != null }))
        _state.update { it.copy(followTick = it.followTick + 1) }
        return true
    }

    /** Chord name on the selected note (blank clears it); checked by the Named chords rule. */
    fun setChord(name: String) {
        val r = s.selected ?: return
        val evs = s.score.voice(r.voice)
        val e = evs.getOrNull(r.index)?.takeIf { it.pitch != null } ?: return
        val clean = name.filter { !it.isWhitespace() && it !in ":@!," }.ifEmpty { null }
        val spec = clean?.let { com.nefelion.fourpartwriting.theory.ChordText.parse(it, s.score.key) }
        if (clean != null && spec == null) return msg("\"$clean\" is not a chord. Try C, F#m, G7/B, Bm7b5, V65, ii6 or V7/V.")
        // Over a given (locked) bass the figures fix the chord: only a name with exactly its notes and bass fits
        if (spec != null && Voice.BASS in s.score.given) {
            val t = s.score.onsets(r.voice)[r.index]
            val b = s.score.eventAt(Voice.BASS, t).takeIf { it >= 0 }?.let { s.score.voice(Voice.BASS)[it] }
            val bp = b?.pitch
            if (bp != null) {
                val want = FiguredBass.realize(bp, b.figures, s.score.key).values.toSet() + bp.pc
                if (spec.pcs.toSet() != want || (spec.bass ?: spec.pcs[0]) != bp.pc)
                    return msg("$clean doesn't fit the bass ${bp.pc.name}${if (b.figures.isEmpty()) "" else " with figures " + FiguredBass.display(b.figures)}. Read the figures again.")
            }
        }
        edit(s.score.withVoice(r.voice, evs.toMutableList().also { it[r.index] = e.copy(chord = clean) }))
    }

    /** Names the chord and the notes for the next beat where a voice is missing; writes nothing. */
    fun hint() = viewModelScope.launch {
        val sc = s.score
        val out = withContext(Dispatchers.Default) { Voicer.attempt(sc) }
        val filled = when (out) {
            is Voicer.Outcome.Done -> out.score
            is Voicer.Outcome.Stuck -> return@launch msg("Your notes up to bar ${barOf(out.time)} leave no clean way on. Press ✓ to find the problem.")
            Voicer.Outcome.Empty -> return@launch msg("Write a melody or a bass first.")
        }
        // The first beat where the fill adds a note
        val t = Voice.entries.mapNotNull { v ->
            val start = sc.length(v)
            filled.onsets(v).withIndex().firstOrNull { (i, on) -> on >= start && filled.voice(v)[i].pitch != null }?.value
        }.minOrNull() ?: return@launch msg("Every voice is written. Press ✓ to check.")
        // The checker may flag the solver's chord (it doesn't model every rule): revoice it against your notes alone
        val open = Voice.entries.filter { sc.length(it) <= t }.toSet()
        val upTo = sc.copy(voices = Voice.entries.map { v ->
            if (v !in open) sc.voice(v) else filled.voice(v).filterIndexed { i, _ -> filled.onsets(v)[i] <= t }
        })
        val rules = s.rules
        val best = withContext(Dispatchers.Default) { Voicer.fix(upTo, t, rules, open) } ?: upTo
        if (s.score != sc) return@launch
        val notes = Voice.entries.map { v -> best.eventAt(v, t).takeIf { it >= 0 }?.let { best.voice(v)[it].pitch } }
        val chord = ChordAnalyzer.analyze(notes.filterNotNull(), sc.key)
        val ghosts = Voice.entries.filter { sc.length(it) <= t }.mapNotNull { v ->
            val p = notes[v.ordinal] ?: return@mapNotNull null
            val staff = sc.staffOf(v)
            Ghost(staff, sc.clefs[staff].position(p), t, v, p.alter.takeIf { it != sc.key.sigAlter(p.step) })
        }
        _state.update { it.copy(hint = Hint(sc, ghosts), followTick = it.followTick + 1) }
        // Plain digits: the snackbar cannot stack figures
        val roman = chord.roman.map { c -> "⁰¹²³⁴⁵⁶⁷⁸⁹".indexOf(c).takeIf { it >= 0 }?.let { '0' + it } ?: "₀₁₂₃₄₅₆₇₈₉".indexOf(c).takeIf { it >= 0 }?.let { '0' + it } ?: c }.joinToString("")
        msg("Hint for bar ${barOf(t)}: ${roman.ifEmpty { chord.symbol }} (${chord.symbol}). The faded notes show one way to voice it.")
    }

    /** Revoices the selected note's chord: the clean voicing nearest the written one (one undo step). */
    fun fixChord() = viewModelScope.launch {
        val sc = s.score
        val r = s.selected ?: return@launch msg("Select a note of the chord to fix.")
        val t = sc.onsets(r.voice)[r.index]
        val rules = s.rules
        val fixed = withContext(Dispatchers.Default) { Voicer.fix(sc, t, rules) }
            ?: return@launch msg("No clean voicing of this chord fits the chords around it. Try fixing a neighbor first.")
        if (fixed == sc) return@launch msg("No cleaner voicing nearby. Press ✓ to see what's flagged.")
        if (s.score == sc) { edit(fixed); msg("Chord revoiced. Undo brings yours back.") }
    }

    private fun barOf(t: Fraction) = (t / s.score.time.measure).let { it.num / it.den + 1 }

    /** Next (+1) or previous (-1) bass note, skipping rests. */
    fun stepBass(dir: Int) {
        val evs = s.score.voice(Voice.BASS)
        val cur = s.selected?.takeIf { it.voice == Voice.BASS }?.index ?: return
        val i = generateSequence(cur + dir) { it + dir }.takeWhile { it in evs.indices }.firstOrNull { evs[it].pitch != null } ?: return
        select(NoteRef(Voice.BASS, i))
        _state.update { it.copy(followTick = it.followTick + 1) }
    }

    fun deselect() {
        val back = s.locked ?: resumeVoice
        _state.update { it.copy(selected = null, voice = back ?: it.voice, placed = false) }
        resumeVoice = null
    }

    /** Hold a voice button: all new notes go to [v] until another voice button is tapped. */
    fun lockVoice(v: Voice) {
        resumeVoice = null
        _state.update { it.copy(locked = v, voice = v, selected = null, moving = false) }
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

    fun setDuration(base: Int) = arm(Duration(base, s.duration.dots), s.shownTuplet)

    /** Triplet on/off (tap). Off also ends any other tuplet. */
    fun toggleTriplet() {
        if (s.shownTuplet == null && s.score.time.isCompound) { msg("Triplets are for simple meters (2/4, 3/4, 4/4 …). Hold for other tuplets."); return }
        setTuplet(if (s.shownTuplet == null) 3 to 2 else null)
    }

    /**
     * Tuplet [n] in the time of [m] (hold the triplet button) for the next notes; it turns itself off
     * when the group is full. A note the user picked becomes that tuplet; the note just entered is left as it is.
     */
    fun setTuplet(n: Int, m: Int) = setTuplet((n to m).takeIf { n != m })

    private fun setTuplet(t: Pair<Int, Int>?) = arm(s.shownDuration, t)

    /** Palette value [d] in tuplet [t] for the next notes, and for the note the user picked. */
    private fun arm(d: Duration, t: Pair<Int, Int>?) {
        _state.update { it.copy(duration = d, tuplet = t, paletteSet = true) }
        if (!s.placed) editSelected { sc, v, i ->
            // A note keeping its ratio keeps its group's span
            val tag = t?.let { (n, m) -> sc.voice(v)[i].tuplet?.takeIf { it.n == n && it.m == m } ?: Tuplet(n, m, d.value) }
            Edit.setLength(sc, v, i, tupletLength(d, t), tag)
        }
    }

    /** After a note or rest is entered: the next one follows the chord again, and tuplets turn off once no voice ends inside an unfinished group. */
    private fun entered(sc: Score) {
        val open = Voice.entries.any { v ->
            val end = sc.length(v)
            Notation.tupletGroups(sc.voice(v), sc.time).any { end > it.start && end < it.end }
        }
        _state.update { it.copy(paletteSet = false, tuplet = it.tuplet.takeIf { open }) }
    }

    /** Cycles 0 → 1 → 2 dots on the palette value (and the selected note). */
    fun toggleDot() {
        val cur = s.shownDuration
        arm(Duration(cur.base, (cur.dots + 1) % 3), s.shownTuplet)
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

    /** Pitch drag: null = started, true = finished (one undo step), false = canceled (restore). */
    fun dragPitch(done: Boolean?) {
        when (done) {
            null -> { dragBase = s.score; _state.update { it.copy(dragging = true) } }
            true -> {
                val base = dragBase ?: return
                dragBase = null
                _state.update { it.copy(dragging = false) }
                if (base != s.score) _state.update {
                    val sc = VoiceLeading.pruneMutes(it.score)
                    it.copy(score = sc, undo = (it.undo + base).takeLast(200), redo = emptyList(),
                        analysis = it.analysis?.let { _ -> VoiceLeading.check(sc, it.rules) }, focusedIssue = null)
                }
                checkOrder()
                deselect() // dragged note is done: next touch enters notes again
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
        // A picked note becomes a rest; after entering a note, a rest follows it
        if (!s.placed && editSelected { sc, v, i -> Edit.dropEmptyLastBars(Edit.setPitch(sc, v, i, null)) }) return
        if (s.moving) return
        val v = s.locked ?: s.voice
        val e = input(s.score, v, s.score.length(v))
        val new = Edit.append(s.score, v, e.length, null, tuplet = e.tuplet)
        edit(new, null, nextVoice(new))
        resumeVoice = null
        entered(new)
    }

    fun fermata() {
        val r = s.selected ?: return msg("Select a note to put a fermata on its chord.")
        if (s.score.voice(r.voice).getOrNull(r.index)?.pitch == null) return msg("Select a note to put a fermata on its chord.")
        edit(Edit.fermata(s.score, onsetOf(r)))
    }

    fun tie() {
        val r = s.selected ?: run { msg("Select a note to tie it to the next one."); return }
        val e = s.selectedEvent ?: return
        // A tapped head with a tie going out of it: remove that tie (the note splits in two)
        if (e.pitch != null) {
            val t = onsetOf(r)
            val segs = Notation.splitVoice(s.score.voice(r.voice), s.score.time)[r.index]
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
            if (s.moving) return
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
        // Removing a rest inside a voice would pull every later note left, out of line with the other voices
        if (e.pitch == null && r.index < list.lastIndex) return msg("Tap a note into the rest to replace it.")
        val segs = if (e.pitch == null) emptyList() else Notation.splitVoice(score.voice(r.voice), score.time)[r.index]
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
            // Back to the voice the undone edit went to (an entered note had moved input on to the next voice)
            val changed = Voice.entries.filter { v -> prev.voice(v) != it.score.voice(v) }.singleOrNull()
            it.copy(score = prev, undo = it.undo.dropLast(1), redo = it.redo + it.score, selected = null, placed = false,
                voice = changed ?: it.voice,
                analysis = it.analysis?.let { _ -> VoiceLeading.check(prev, it.rules) })
        }
        checkOrder()
        Synth.setTempo(s.score.qpm) // undoing a tempo change applies to playback too
    }

    fun redo() {
        _state.update {
            val next = it.redo.lastOrNull() ?: return@update it
            it.copy(score = next, redo = it.redo.dropLast(1), undo = it.undo + it.score, selected = null, placed = false,
                analysis = it.analysis?.let { _ -> VoiceLeading.check(next, it.rules) })
        }
        checkOrder()
        Synth.setTempo(s.score.qpm) // undoing a tempo change applies to playback too
    }

    // ---------- score settings ----------

    fun clearScore() = edit(blank(), null, earliestVoice(blank()))

    /** The open score's key, time, tempo and staves with no notes (an exercise keeps its given voices). */
    private fun blank() = s.score.copy(voices = Voice.entries.map { if (it in s.score.given) s.score.voice(it) else emptyList() },
        rulesOff = emptySet(), ignored = emptySet())

    // ---------- score library ----------

    /** Writes the open score now and drops a pending autosave. */
    private fun flushScore() { saveJob?.cancel(); library.write(s.scoreName, s.score) }

    fun refreshScores() = _state.update { it.copy(scores = library.list()) }

    /** Shows [score] as [name], starting fresh: no undo history or selection from the score left behind. */
    private fun show(name: String, score: Score) {
        stop()
        historyFile.delete()
        keptOrder.clear()
        prefs.edit().putString("current", name).apply()
        _state.update {
            it.copy(scoreName = name, score = score, scores = library.list(), undo = emptyList(), redo = emptyList(),
                selected = null, selectedAt = null, placed = false, analysis = null, focusedIssue = null, reorder = emptyList())
        }
        Synth.setTempo(score.qpm)
        checkOrder()
    }

    private fun readScore(name: String) = library.read(name)?.let(Edit::dropEmptyLastBars)

    fun openScore(name: String) {
        if (name == s.scoreName) return
        flushScore()
        show(name, readScore(name) ?: return msg("Could not open $name."))
    }

    /** Empty score with the key, time, tempo, staves and clefs of [settings]. */
    fun newScore(name: String, settings: Score) {
        flushScore()
        newBlank(name, settings.copy(voices = List(4) { emptyList() }, rulesOff = emptySet(), ignored = emptySet(), tempo = settings.tempo.coerceIn(20, 300)))
    }

    /** A new generated exercise (figured bass, or given [soprano]), opened as a new score. */
    fun newExercise(level: Exercises.Level, soprano: Boolean = false) = viewModelScope.launch {
        val ex = withContext(Dispatchers.Default) { Exercises.generate(level, soprano = soprano) } ?: return@launch msg("Could not make an exercise. Try again.")
        flushScore()
        newBlank("${level.label} ${if (soprano) "soprano" else "bass"} exercise", ex)
        _state.update { it.copy(voice = if (soprano) Voice.BASS else Voice.SOPRANO) }
    }

    /** Writes the missing voices of an exercise around its given voice (one undo step). */
    fun showSolution() = viewModelScope.launch {
        val sc = s.score
        // Your notes are kept when they can be continued; otherwise the answer starts over from the given voices
        val fresh = sc.copy(voices = Voice.entries.map { if (it in sc.given) sc.voice(it) else emptyList() })
        val rules = s.rules
        val (solved, note) = withContext(Dispatchers.Default) {
            // The checker may flag chords the solver wrote (it doesn't model every rule): polish revoices them
            when (val out = Voicer.attempt(sc)) {
                is Voicer.Outcome.Done -> Voicer.polish(out.score, sc, rules) to null
                is Voicer.Outcome.Stuck -> Voicer.fill(fresh)?.let { Voicer.polish(it, fresh, rules) } to "Your notes up to bar ${barOf(out.time)} can't be continued cleanly, so this answer starts over. Undo brings yours back."
                Voicer.Outcome.Empty -> null to null
            }
        }
        if (solved == null) return@launch msg("No solution found for this exercise.")
        if (s.score == sc) { edit(solved, null); note?.let(::msg) }
    }

    private fun newBlank(wanted: String = "Untitled", sc: Score = blank()) {
        val name = library.freeName(wanted)
        if (!library.write(name, sc)) return msg("Could not create a score.")
        show(name, sc)
    }

    /** Gives score [name] the key, time, tempo, staves and clefs of [settings]. The open score changes as one undo step. */
    fun editScore(name: String, settings: Score) {
        // An exercise keeps its key and time signature (its figures and cadence depend on them)
        fun apply(sc: Score) = sc.copy(key = if (sc.given.isEmpty()) settings.key else sc.key, time = if (sc.given.isEmpty()) settings.time else sc.time,
            tempo = settings.tempo.coerceIn(20, 300),
            arrangement = settings.arrangement, clefs = settings.clefs)
        if (name == s.scoreName) {
            edit(apply(s.score))
            Synth.setTempo(s.score.qpm)
        } else {
            flushScore()
            val sc = library.read(name) ?: return msg("Could not read $name.")
            if (!library.write(name, apply(sc))) return msg("Could not save $name.")
        }
        refreshScores()
    }

    fun renameScore(from: String, to: String) {
        val name = ScoreLibrary.clean(to)
        if (name == from) return
        if (library.exists(name)) return msg("A score named $name already exists.")
        flushScore()
        if (!library.rename(from, name)) return msg("Could not rename $from.")
        if (from == s.scoreName) {
            prefs.edit().putString("current", name).apply()
            _state.update { it.copy(scoreName = name) }
        }
        refreshScores()
    }

    /** Suggested name for a copy of [name]: "Name copy", or "Name copy (2)" if taken. */
    fun copyName(name: String) = library.freeName("$name copy")

    /** Copies score [name] as [newName] and opens the copy. */
    fun duplicateScore(name: String, newName: String) {
        val to = ScoreLibrary.clean(newName)
        if (library.exists(to)) return msg("A score named $to already exists.")
        flushScore()
        val sc = library.read(name) ?: return msg("Could not read $name.")
        if (!library.write(to, sc)) return msg("Could not copy $name.")
        show(to, sc)
    }

    /** Deleting the open score opens the most recent other one (or a new empty one). */
    fun deleteScore(name: String) {
        val open = name == s.scoreName
        if (open) saveJob?.cancel() else flushScore()
        if (!library.delete(name)) return msg("Could not delete $name.")
        if (open) {
            val next = library.names().firstNotNullOfOrNull { n -> readScore(n)?.let { n to it } }
            if (next != null) show(next.first, next.second) else newBlank()
        } else refreshScores()
        msg("$name deleted.")
    }

    /** Saves a copy of a score to a file the user picked (to back up or share). */
    fun exportScoreFile(name: String, uri: Uri) = viewModelScope.launch {
        flushScore()
        val text = library.read(name)?.let { ScoreCodec.encode(it, name) }
        val ok = text != null && withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }.isSuccess
        }
        msg(if (ok) "$name saved to file." else "Could not write the file.")
    }

    /** Adds a .satb file to the library and opens it. */
    fun importScoreFile(uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        val (fileName, size) = withContext(Dispatchers.IO) { fileInfo(uri) }
        // A score file is a few KB; refuse anything big before reading it into memory
        val text = if ((size ?: 0) > 1_000_000) null else withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)!!.use { it.reader().readText() } }.getOrNull()
        }
        val sc = text?.let(ScoreCodec::decode) ?: return@launch msg("${fileName ?: "That file"} is not a SATB score.")
        flushScore()
        // The name saved inside the file, else the file's name
        val name = library.freeName(ScoreCodec.title(text) ?: fileName?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported")
        if (!library.write(name, sc)) return@launch msg("Could not save the score.")
        show(name, Edit.dropEmptyLastBars(sc))
        msg("Opened $name.")
    }

    /** Display name and size in bytes of a picked file, when the provider tells them. */
    private fun fileInfo(uri: Uri): Pair<String?, Long?> {
        val row = runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getString(0) to (if (c.isNull(1)) null else c.getLong(1))
            }
        }.getOrNull()
        return (row?.first ?: uri.lastPathSegment?.substringAfterLast('/')) to row?.second
    }

    // ---------- analysis ----------

    /** ✓ button: show results, or hide them when they are shown. */
    fun check() {
        if (s.analysis == null) toMovement()
        _state.update {
            if (it.analysis != null) it.copy(analysis = null, focusedIssue = null)
            else it.copy(analysis = VoiceLeading.check(it.score, it.rules), focusedIssue = null)
        }
        // An exercise with every voice written and no errors is solved
        val a = s.analysis ?: return
        val sc = s.score
        if (sc.given.isNotEmpty() && Voice.entries.all { sc.length(it) == sc.length && sc.voice(it).isNotEmpty() } && a.issues.none { it.severity == Severity.ERROR })
            msg(if (a.issues.isEmpty()) "Solved, with no errors or warnings!" else "Solved: no errors. Look at the warnings to make it better.")
    }

    /** Opening the check or tapping a found issue drops the selection into movement mode. */
    private fun toMovement() { deselect(); _state.update { it.copy(moving = true) } }
    fun closeAnalysis() = _state.update { it.copy(analysis = null, focusedIssue = null) }
    fun focusIssue(i: Int?) {
        toMovement()
        _state.update { it.copy(focusedIssue = if (it.focusedIssue == i) null else i) }
    }

    /** Long-press menu on an issue: hide just this one. */
    fun ignoreIssue(i: Int) {
        val a = s.analysis ?: return
        val issue = a.issues.getOrNull(i) ?: return
        edit(s.score.copy(ignored = s.score.ignored + VoiceLeading.ignoreKey(issue, a.slices)))
    }
    fun ignoreRuleInScore(r: Rule) = edit(s.score.copy(rulesOff = s.score.rulesOff + r.name))

    /** Switches rule [r] on or off for score [name] only; the open score changes as one undo step. */
    fun toggleScoreRule(name: String, r: Rule) {
        fun apply(sc: Score) = sc.copy(rulesOff = if (r.name in sc.rulesOff) sc.rulesOff - r.name else sc.rulesOff + r.name)
        if (name == s.scoreName) return edit(apply(s.score))
        flushScore()
        val sc = library.read(name) ?: return msg("Could not read $name.")
        if (!library.write(name, apply(sc))) return msg("Could not save $name.")
        refreshScores()
    }

    fun toggleRule(r: Rule) {
        _state.update {
            val rules = if (r in it.rules) it.rules - r else it.rules + r
            it.copy(rules = rules, analysis = it.analysis?.let { _ -> VoiceLeading.check(it.score, rules) }, focusedIssue = null)
        }
        prefs.edit().putStringSet("rulesOff", (Rule.entries - s.rules).map { it.name }.toSet()).apply()
    }

    // ---------- MIDI keyboard ----------

    private fun midiKey(n: Int, on: Boolean) {
        // Sound follows the keys, so a silent controller can be played like an instrument
        if (s.soundLoaded && !s.playing) if (on) Synth.noteOn(n) else Synth.noteOff(n)
        // A locked voice takes each key as it goes down, so legato playing stays a melody (its release is a stray one to the gatherer)
        val notes = (if (on && s.locked != null) listOf(n) else null) ?: midiChord.key(n, on) ?: return
        muted = true
        try { midiNotes(notes) } finally { muted = false }
    }

    /**
     * Writes notes played on a MIDI keyboard. A selected note gets the pitch (then the next note is selected,
     * for a pitch pass over written rhythm). Otherwise one note goes to the input voice like a tap; a chord
     * (not locked) fills the voices that end earliest, highest note to the highest voice.
     */
    internal fun midiNotes(notes: List<Int>) {
        if (notes.isEmpty() || s.playing) return
        val key = s.score.key
        val desc = notes.distinct().sortedDescending().map { Pitch.fromMidi(it, key) }
        fun pick(v: Voice) = if (v == Voice.BASS) desc.last() else desc.first()
        val sel = s.selected
        // A just-entered note stays selected (placed) but is not re-pitched: the next key adds a new note
        if (sel != null && s.selectedEvent != null && !s.placed) {
            editSelected { sc, v, i -> Edit.setPitch(sc, v, i, pick(v)) }
            if (sel.index < s.score.voice(sel.voice).lastIndex) step(1) else deselect()
            return
        }
        if (s.moving) return
        val voices = if (s.locked != null || desc.size == 1) listOf(s.locked ?: s.voice)
            else Edit.chordVoices(s.score, desc.size)
        // Five or more keys: keep the top three and the bass
        val pitches = if (voices.size == 1) listOf(pick(voices[0])) else if (desc.size > 4) desc.take(3) + desc.last() else desc
        var sc = s.score
        for ((v, p) in voices.zip(pitches)) sc = input(sc, v, sc.length(v)).let { e -> Edit.append(sc, v, e.length, p, tuplet = e.tuplet) }
        val last = voices.last()
        edit(sc, NoteRef(last, sc.voice(last).lastIndex), nextVoice(sc))
        resumeVoice = null
        _state.update { it.copy(accidental = null, placed = true) }
        entered(sc)
    }

    /** Searches for Bluetooth MIDI keyboards (the Bluetooth permissions are already granted). */
    fun startMidiScan() {
        btFound.clear()
        _state.update { it.copy(midiFound = emptyList(), midiScanning = true, midiNote = null) }
        midi.scan(getApplication(), found = { dev, name ->
            btFound += dev
            _state.update { it.copy(midiFound = it.midiFound + name) }
        }, ended = { m ->
            _state.update { it.copy(midiScanning = false, midiNote = m ?: if (it.midiFound.isEmpty()) "No Bluetooth MIDI keyboard found." else null) }
        })
    }

    fun connectMidi(i: Int) {
        val dev = btFound.getOrNull(i) ?: return
        val name = s.midiFound.getOrNull(i)
        midi.stopScan(getApplication())
        _state.update { it.copy(midiScanning = false) }
        midi.connectBluetooth(dev) { ok ->
            // Remembered, so the keyboard reconnects on the next start
            if (ok) prefs.edit().putString("midiBt", dev.address).apply()
            _state.update { it.copy(midiNote = if (ok) null else "Could not connect to $name. Is it on, and not connected to another device?",
                midiFound = if (ok) emptyList() else it.midiFound) }
        }
    }

    /** Reconnects the Bluetooth keyboard used last time (quietly; needs the permission granted earlier). */
    private fun reconnectMidi() {
        val addr = prefs.getString("midiBt", null) ?: return
        val ctx = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager?)?.adapter ?: return
        if (!adapter.isEnabled) return
        runCatching { midi.connectBluetooth(adapter.getRemoteDevice(addr)) {} }
    }

    override fun onCleared() { midi.stopScan(getApplication()); midi.close() }

    // ---------- sound ----------

    /** MIDI input is heard from the keys themselves, not again as a preview. */
    private var muted = false

    private fun preview(p: Pitch) {
        if (!s.soundLoaded || s.playing || muted) return
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
                _state.update { st -> st.copy(playTime = onsets.lastOrNull { Synth.played(it) <= whole + 1e-9 }) }
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
        val name = withContext(Dispatchers.IO) { fileInfo(uri).first }
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
