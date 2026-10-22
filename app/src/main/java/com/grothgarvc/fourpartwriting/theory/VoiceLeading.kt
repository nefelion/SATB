package com.grothgarvc.fourpartwriting.theory

import com.grothgarvc.fourpartwriting.model.Fraction
import com.grothgarvc.fourpartwriting.model.Interval
import com.grothgarvc.fourpartwriting.model.IntervalQuality
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.PitchClass
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.Slice
import com.grothgarvc.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign

enum class Severity { ERROR, WARNING }

/** Checks the user can switch on or off. */
enum class Rule(val label: String) {
    PARALLEL_FIFTHS("Parallel fifths"),
    PARALLEL_OCTAVES("Parallel octaves"),
    DIRECT("Direct fifths/octaves"),
    UNEQUAL_FIFTHS("Unequal fifths"),
    CROSSING("Voice crossing"),
    OVERLAP("Voice overlap"),
    SPACING("Spacing"),
    RANGE("Range"),
    MELODIC("Melodic interval"),
    LEADING_TONE("Leading tone"),
    SEVENTH("Seventh"),
    DOUBLING("Doubling"),
    MISSING_THIRD("Missing third"),
    UNRESOLVED_NCT("Non-chord tones"),
    UNRECOGNIZED("Chord"),
    MISSING_VOICE("Missing voice"),
    STRUCTURE("Structure"),
}

/** A rule violation. [notes] are (slice index, voice) pairs to highlight. */
data class Issue(
    val severity: Severity,
    val slice: Int,
    val rule: Rule,
    val message: String,
    val notes: List<Pair<Int, Voice>>,
)

/**
 * [chords]: the chord in effect at every slice; [points]: slices where a chord is named;
 * [ncts]: non-chord tones by note.
 */
data class Analysis(
    val slices: List<Slice>,
    val chords: List<Chord>,
    val issues: List<Issue>,
    val points: List<Int> = slices.indices.toList(),
    val ncts: Map<com.grothgarvc.fourpartwriting.engrave.NoteRef, Nct> = emptyMap(),
)

object VoiceLeading {

    /** Common textbook ranges (MIDI, inclusive). */
    val RANGES = mapOf(
        Voice.SOPRANO to Pitch.parse("C4").midi..Pitch.parse("G5").midi,
        Voice.ALTO to Pitch.parse("G3").midi..Pitch.parse("D5").midi,
        Voice.TENOR to Pitch.parse("C3").midi..Pitch.parse("G4").midi,
        Voice.BASS to Pitch.parse("E2").midi..Pitch.parse("C4").midi,
    )

    private val V = Voice.entries

    fun check(score: Score, enabled: Set<Rule> = Rule.entries.toSet()): Analysis {
        val slices = score.slices()
        val harmony = HarmonyAnalyzer.analyze(score, slices)
        val points = harmony.points
        // Context labels (cadential 6/4, Picardy) on the sequence of named chords
        val named = ChordAnalyzer.withContext(points.map { harmony.chordOf[it] }, score.key)
        val pointChord = points.indices.associate { points[it] to named[it] }
        var gov = 0
        val chords = slices.indices.map { i -> if (i in pointChord) gov = i; pointChord.getValue(gov) }
        val ncts = harmony.ncts
        val out = mutableListOf<Issue>()
        fun add(sev: Severity, i: Int, rule: Rule, msg: String, vararg n: Pair<Int, Voice>) {
            if (rule in enabled) out += Issue(sev, i, rule, msg, n.toList())
        }

        // Structure
        val lengths = V.map { score.length(it) }
        if (lengths.distinct().size > 1)
            add(Severity.WARNING, 0, Rule.STRUCTURE, "Voices have different lengths.")
        val measure = score.time.measure
        val total = score.length
        if (total > Fraction.ZERO && (total / measure).den != 1L)
            add(Severity.WARNING, slices.lastIndex.coerceAtLeast(0), Rule.STRUCTURE, "Last measure is incomplete.")

        fun isNct(s: Slice, v: Voice) = s.eventIndex[v.ordinal] >= 0 &&
            com.grothgarvc.fourpartwriting.engrave.NoteRef(v, s.eventIndex[v.ordinal]) in ncts

        // Non-chord tones that are not explained by any standard pattern
        for ((ref, nct) in ncts) {
            val v = ref.voice
            val p = score.voice(v).getOrNull(ref.index)?.pitch ?: continue
            // The slice where it acts as a non-chord tone: a chord point it sounds at, else its attack
            val i = points.firstOrNull { slices[it].eventIndex[v.ordinal] == ref.index }
                ?: slices.indexOfFirst { it.eventIndex[v.ordinal] == ref.index && it.attacked[v.ordinal] }
            if (i < 0) continue
            val s = slices[i]
            if (nct.type == NctType.UNRESOLVED)
                add(Severity.ERROR, i, Rule.UNRESOLVED_NCT,
                    "${v.label}: ${p.name} is a dissonance that is not a passing, neighbour, suspension or other standard non-chord tone.", i to v)
            if (nct.type == NctType.SUS && v != Voice.BASS && i in pointChord) {
                // The note of resolution should not sound in another voice against the suspension (9–8 excepted)
                val res = score.voice(v).getOrNull(s.eventIndex[v.ordinal] + 1)?.pitch
                val clash = V.filter { o -> o != v && s.notes[o.ordinal]?.pc == res?.pc && !(o == Voice.BASS && nct.label == "9–8") }
                if (res != null && clash.isNotEmpty())
                    add(Severity.WARNING, i, Rule.UNRESOLVED_NCT, "${v.label}: the note of resolution (${res.pc.name}) already sounds against the suspension.",
                        *(listOf(i to v) + clash.map { i to it }).toTypedArray())
            }
        }

        for ((k, i) in points.withIndex()) {
            val s = slices[i]
            val chord = chords[i]
            for (v in V) if (s.notes[v.ordinal] == null)
                add(Severity.WARNING, i, Rule.MISSING_VOICE, "${v.name.lowercase().cap()} has no note here.")
            // Doubling and construction ignore non-chord tones
            val chordSlice = s.copy(notes = V.map { v -> if (isNct(s, v)) null else s.notes[v.ordinal] })
            if (s.pitches.size >= 3) chordRules(i, chordSlice, chord, ::add)
            if (k > 0) resolutionRules(score, points[k - 1], i, slices, chords[points[k - 1]], chord, ::add)
        }

        for ((i, s) in slices.withIndex()) {
            val n = s.notes

            // Ranges
            for (v in V) {
                val p = n[v.ordinal] ?: continue
                if (p.midi !in RANGES.getValue(v))
                    add(Severity.WARNING, i, Rule.RANGE, "${v.name.lowercase().cap()} ${p.name} is out of its usual range.", i to v)
            }
            // Spacing and crossing
            for (k in 0 until 3) {
                val up = n[k] ?: continue
                val lo = n[k + 1] ?: continue
                if (lo.midi > up.midi)
                    add(Severity.ERROR, i, Rule.CROSSING, "${V[k + 1].label} (${lo.name}) is above ${V[k].label} (${up.name}).", i to V[k], i to V[k + 1])
                else if (k < 2 && up.midi - lo.midi > 12)
                    add(Severity.ERROR, i, Rule.SPACING, "More than an octave between ${V[k].label} and ${V[k + 1].label}.", i to V[k], i to V[k + 1])
            }

            if (i > 0) motionRules(i, slices[i - 1], s, ::add)
        }
        return Analysis(slices, chords, out, points, ncts)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private fun voicesWith(s: Slice, pc: PitchClass) =
        V.filter { s.notes[it.ordinal]?.pc == pc }

    private fun chordRules(
        i: Int, s: Slice, c: Chord,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        if (!c.recognized) {
            if (c.symbol.contains("no 3rd")) add(Severity.ERROR, i, Rule.MISSING_THIRD, "The chord has no third: ${c.symbol}.", emptyArray())
            else add(Severity.WARNING, i, Rule.UNRECOGNIZED, "Unrecognized sonority: ${c.symbol}.", emptyArray())
            return
        }
        val q = c.quality!!
        c.leadingTone?.let { lt ->
            val vs = voicesWith(s, lt)
            if (vs.size > 1) add(Severity.ERROR, i, Rule.DOUBLING, "Doubled leading tone (${lt.name}).", vs.map { i to it }.toTypedArray())
        }
        c.seventh?.let { sev ->
            val vs = voicesWith(s, sev)
            if (vs.size > 1) add(Severity.ERROR, i, Rule.DOUBLING, "Doubled chordal seventh (${sev.name}).", vs.map { i to it }.toTypedArray())
        }
        if (!q.seventh && c.inversion == 2 && voicesWith(s, c.bass!!).size < 2)
            add(Severity.WARNING, i, Rule.DOUBLING, "In a ⁶₄ chord, double the bass (${c.bass.name}).", emptyArray())
        if (q == Quality.DIM && c.inversion in 0..1 && voicesWith(s, c.root!!).size > 1)
            add(Severity.WARNING, i, Rule.DOUBLING, "Do not double the root of a diminished triad; double the third.",
                voicesWith(s, c.root).map { i to it }.toTypedArray())
    }

    private fun motionRules(
        i: Int, a: Slice, b: Slice,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        val h = i - 1
        fun moved(v: Voice) = b.attacked[v.ordinal] && a.notes[v.ordinal] != null && b.notes[v.ordinal] != null &&
            a.notes[v.ordinal] != b.notes[v.ordinal]
        fun both(v: Voice) = arrayOf(h to v, i to v)

        // Melodic intervals
        for (v in V) {
            if (!moved(v)) continue
            val iv = Interval.between(a.notes[v.ordinal]!!, b.notes[v.ordinal]!!)
            if (iv.quality == IntervalQuality.A || iv.quality == IntervalQuality.AA)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: augmented melodic interval (${iv.name}).", both(v))
            else if (iv.number > 8 || (iv.number == 8 && iv.quality != IntervalQuality.PERFECT))
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: leap larger than an octave (${iv.name}).", both(v))
        }

        // Overlap
        for (k in 0 until 3) {
            val up = V[k]; val lo = V[k + 1]
            val upA = a.notes[k]; val loA = a.notes[k + 1]; val upB = b.notes[k]; val loB = b.notes[k + 1]
            if (upA == null || loA == null || upB == null || loB == null) continue
            if (loB.midi > upA.midi && moved(lo))
                add(Severity.ERROR, i, Rule.OVERLAP, "${lo.label} moves above the previous ${up.label} note.", arrayOf(h to up, i to lo))
            if (upB.midi < loA.midi && moved(up))
                add(Severity.ERROR, i, Rule.OVERLAP, "${up.label} moves below the previous ${lo.label} note.", arrayOf(h to lo, i to up))
        }

        // Parallels and direct perfect intervals
        for (x in 0 until 4) for (y in x + 1 until 4) {
            val vx = V[x]; val vy = V[y]
            val ax = a.notes[x]; val ay = a.notes[y]; val bx = b.notes[x]; val by = b.notes[y]
            if (ax == null || ay == null || bx == null || by == null) continue
            if (!moved(vx) || !moved(vy)) continue
            val ia = Interval.between(ay, ax)
            val ib = Interval.between(by, bx)
            val same = (bx.midi - ax.midi).sign == (by.midi - ay.midi).sign
            val names = "${vx.label}–${vy.label}"
            val hl = arrayOf(h to vx, h to vy, i to vx, i to vy)
            when {
                isP5(ia) && isP5(ib) -> add(Severity.ERROR, i, Rule.PARALLEL_FIFTHS,
                    if (same) "Parallel fifths between $names." else "Consecutive fifths by contrary motion between $names.", hl)
                isP8(ia) && isP8(ib) -> add(Severity.ERROR, i, Rule.PARALLEL_OCTAVES,
                    if (same) "Parallel ${if (ib.number == 1) "unisons" else "octaves"} between $names."
                    else "Consecutive octaves by contrary motion between $names.", hl)
                vy == Voice.BASS && isD5(ia) && isP5(ib) && same ->
                    add(Severity.WARNING, i, Rule.UNEQUAL_FIFTHS, "d5 → P5 between $names.", hl)
                x == 0 && vy == Voice.BASS && same && (isP5(ib) || isP8(ib)) &&
                    abs(bx.diatonic - ax.diatonic) > 1 ->
                    add(Severity.WARNING, i, Rule.DIRECT,
                        "Outer voices move in similar motion into a perfect ${if (isP5(ib)) "fifth" else "octave"} with a leap in the soprano.", hl)
            }
        }
    }

    /**
     * Leading tone and chordal seventh, from chord point [h] to the next chord point [i].
     * A voice's resolution is its next note (so ornaments between chords do not hide it).
     */
    private fun resolutionRules(
        score: Score, h: Int, i: Int, slices: List<Slice>, ca: Chord, cb: Chord,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        val a = slices[h]
        fun nextNote(v: Voice): Pitch? {
            val idx = a.eventIndex[v.ordinal]
            return if (idx < 0) null else score.voice(v).getOrNull(idx + 1)?.pitch
        }
        fun both(v: Voice) = arrayOf(h to v, i to v)

        val lt = ca.leadingTone
        val target = ca.target
        if (lt != null && target != null && cb.root != null && cb.notes.any { it.pc == target }) {
            val toTarget = cb.root == target || cb.root.step == (target.step + 5) % 7
            if (toTarget) for (v in listOf(Voice.SOPRANO, Voice.BASS)) {
                val p = a.notes[v.ordinal] ?: continue
                val q = nextNote(v) ?: continue
                if (p.pc == lt && !(q.pc == target && q.diatonic == p.diatonic + 1))
                    add(Severity.ERROR, i, Rule.LEADING_TONE, "${v.label}: leading tone ${lt.name} should resolve up to ${target.name}.", both(v))
            }
        }

        val sev = ca.seventh
        if (sev != null && cb.recognized && cb.root != ca.root) {
            val passing43 = ca.inversion == 2 && ca.degree == 4 && cb.degree == 0 && cb.inversion == 1
            for (v in V) {
                val p = a.notes[v.ordinal] ?: continue
                if (p.pc != sev) continue
                val held = slices[i].notes[v.ordinal] == p && !slices[i].attacked[v.ordinal]
                if (held && cb.notes.any { it.pc == sev }) continue // prolonged into the next chord
                val q = nextNote(v) ?: continue
                val step = q.diatonic - p.diatonic
                val ok = step == -1 || (step == 0 && q == p) || (passing43 && step == 1)
                if (!ok) add(Severity.ERROR, i, Rule.SEVENTH, "${v.label}: chordal seventh ${sev.name} should resolve down by step.", both(v))
            }
        }
    }

    private fun isP5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.PERFECT
    private fun isD5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.D
    private fun isP8(iv: Interval) = iv.simpleNumber == 1 && iv.quality == IntervalQuality.PERFECT
}
