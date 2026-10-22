package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.IntervalQuality
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Slice
import com.nefelion.fourpartwriting.model.Voice
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
    LEAPS("Leap handling"),
    CROSS_RELATION("Cross relation"),
    SIMILAR_MOTION("All voices in one direction"),
    TENDENCY("Tendency tones"),
    SIX_FOUR("Six-four chords"),
    HARMONIC_RHYTHM("Harmonic rhythm"),
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
    val ncts: Map<com.nefelion.fourpartwriting.engrave.NoteRef, Nct> = emptyMap(),
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
            com.nefelion.fourpartwriting.engrave.NoteRef(v, s.eventIndex[v.ordinal]) in ncts

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
            val prevI = points.getOrNull(k - 1)
            val nextI = points.getOrNull(k + 1)
            val prev = prevI?.let { chords[it] }
            if (s.pitches.size >= 3) chordRules(i, chordSlice, chord, prev, score.key, ::add)
            if (prevI != null) resolutionRules(score, prevI, i, slices, chords[prevI], chord, ::add)

            val q = chord.quality ?: continue
            // A ⁶₄ chord is cadential, passing (bass steps through), pedal (bass holds) or arpeggiated (same chord around it)
            if (q in setOf(Quality.MAJOR, Quality.MINOR, Quality.DIM, Quality.AUG) && chord.inversion == 2 && chord.roman != "Cad⁶₄") {
                fun bassAt(p: Int?) = p?.let { slices[it].notes[Voice.BASS.ordinal] }
                val pb = bassAt(prevI); val b = bassAt(i); val nb = bassAt(nextI)
                val passing = pb != null && b != null && nb != null && abs(b.diatonic - pb.diatonic) == 1 &&
                    nb.diatonic - b.diatonic == b.diatonic - pb.diatonic
                val pedal = b != null && pb == b && nb == b
                fun same(o: Chord?) = o != null && o.root == chord.root && o.quality == q
                if (!passing && !pedal && !same(prev) && !same(nextI?.let { chords[it] }))
                    add(Severity.WARNING, i, Rule.SIX_FOUR, "${chord.roman}: use a ⁶₄ chord only as a cadential, passing, pedal or arpeggiated ⁶₄.", i to Voice.BASS)
            }
            // The same chord from a weak beat to the downbeat blurs the bar line
            val ml = score.time.measure
            if (prevI != null && prev != null && prev.root == chord.root && prev.quality == q && prev.bass == chord.bass &&
                (s.time / ml).den == 1L && (slices[prevI].time / ml).den != 1L
            ) add(Severity.WARNING, i, Rule.HARMONIC_RHYTHM, "${chord.roman} is repeated from a weak beat to the downbeat.")
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
                else if (k == 2 && Interval.between(lo, up).number > 12)
                    add(Severity.WARNING, i, Rule.SPACING, "More than a twelfth between T and B.", i to V[k], i to V[k + 1])
            }

            if (i > 0) motionRules(i, slices[i - 1], s, ::add)
        }

        // Leaps: one larger than a fifth turns back by step; two in one direction stay within an octave, not a seventh
        for (v in V) {
            val evs = score.voice(v)
            fun sliceOf(idx: Int) = slices.indexOfFirst { it.eventIndex[v.ordinal] == idx && it.attacked[v.ordinal] }
            for (j in 2 until evs.size) {
                val p0 = evs[j - 2].pitch ?: continue
                val p1 = evs[j - 1].pitch ?: continue
                val p2 = evs[j].pitch ?: continue
                val d1 = p1.diatonic - p0.diatonic
                val d2 = p2.diatonic - p1.diatonic
                if (d1 == 0 || d2 == 0) continue
                val i0 = sliceOf(j - 2); val i1 = sliceOf(j - 1); val i2 = sliceOf(j)
                val stepBack = abs(d2) == 1 && d2.sign != d1.sign
                val leap = Interval.between(p0, p1)
                if (leap.quality == IntervalQuality.D && leap.number in 4..5 && !stepBack)
                    add(Severity.WARNING, i2, Rule.LEAPS, "${v.label}: after the diminished leap ${p0.name}–${p1.name}, move back inside by step.", i1 to v, i2 to v)
                else if (abs(d1) >= 5 && !stepBack && !(v == Voice.BASS && abs(d1) == 7))
                    add(Severity.WARNING, i2, Rule.LEAPS, "${v.label}: after the leap ${p0.name}–${p1.name}, turn back by step.", i1 to v, i2 to v)
                else if (d1.sign == d2.sign && abs(d1) >= 2 && abs(d2) >= 2) {
                    val span = Interval.between(p0, p2)
                    if (span.number > 8 || span.simpleNumber == 7)
                        add(Severity.WARNING, i2, Rule.LEAPS,
                            "${v.label}: two leaps in one direction span ${if (span.number > 8) "more than an octave" else "a seventh"} (${p0.name}–${p2.name}).",
                            i0 to v, i1 to v, i2 to v)
                }
            }
            // A line moving one way (2+ moves, between turning points or rests) should not outline a tritone
            var b = 0
            var dir = 0
            fun outline(from: Int, to: Int) {
                val p = evs[from].pitch ?: return
                val q = evs[to].pitch ?: return
                if ((from + 1..to).count { evs[it].pitch != evs[it - 1].pitch } < 2) return
                val iv = Interval.between(p, q)
                if ((iv.number == 4 && iv.quality == IntervalQuality.A) || (iv.number == 5 && iv.quality == IntervalQuality.D))
                    add(Severity.WARNING, sliceOf(to), Rule.LEAPS, "${v.label}: the line ${p.name}–${q.name} outlines a tritone.", sliceOf(from) to v, sliceOf(to) to v)
            }
            for (j in 1..evs.size) {
                val p = evs.getOrNull(j)?.pitch
                val prev = evs[j - 1].pitch
                if (p == null || prev == null) { outline(b, j - 1); b = j; dir = 0; continue }
                val d = (p.midi - prev.midi).sign
                if (d == 0) continue
                if (dir != 0 && d != dir) { outline(b, j - 1); b = j - 1 }
                dir = d
            }
        }
        // Errors first, then warnings; each by position in the score (slices are in time order)
        return Analysis(slices, chords, out.sortedWith(compareBy({ it.severity }, { it.slice })), points, ncts)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private fun voicesWith(s: Slice, pc: PitchClass) =
        V.filter { s.notes[it.ordinal]?.pc == pc }

    private fun chordRules(
        i: Int, s: Slice, c: Chord, prev: Chord?, key: Key,
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
        // Root-position major triad: double the root, not the third (N and the deceptive V–vi excepted)
        val third = c.third
        if ((q == Quality.MAJOR || q == Quality.MAJOR_NO5) && c.inversion == 0 && third != null && third != c.leadingTone && !c.roman.startsWith("N") &&
            !(prev?.degree == 4 && c.degree == 5)
        ) {
            val vs = voicesWith(s, third)
            if (vs.size > 1) add(Severity.WARNING, i, Rule.DOUBLING, "Doubled third in a major triad (${third.name}); double the root.", vs.map { i to it }.toTypedArray())
        }
        // Chromatic notes are tendency tones
        for (pc in s.pitches.map { it.pc }.distinct()) {
            if (key.isDiatonic(pc) || pc == c.leadingTone || pc == c.seventh) continue
            val vs = voicesWith(s, pc)
            if (vs.size > 1) add(Severity.WARNING, i, Rule.DOUBLING, "Doubled chromatic note (${pc.name}).", vs.map { i to it }.toTypedArray())
        }
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
            else if (iv.number == 7)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: leap of a seventh (${iv.name}).", both(v))
        }

        // All four voices moving the same way
        val dirs = V.map { v -> if (moved(v)) (b.notes[v.ordinal]!!.midi - a.notes[v.ordinal]!!.midi).sign else 0 }
        if (dirs.all { it == 1 } || dirs.all { it == -1 })
            add(Severity.WARNING, i, Rule.SIMILAR_MOTION, "All four voices move in the same direction.", V.flatMap { both(it).toList() }.toTypedArray())

        // Cross relation: a letter changes its accidental between two different voices
        for (y in V) {
            if (!b.attacked[y.ordinal]) continue
            val q = b.notes[y.ordinal] ?: continue
            val x = V.firstOrNull { x -> x != y && a.notes[x.ordinal]?.let { it.step == q.step && it.alter != q.alter } == true } ?: continue
            val p = a.notes[x.ordinal]!!
            // Fine when some voice makes the chromatic step itself
            val inOneVoice = V.any { z -> a.notes[z.ordinal]?.pc == p.pc && b.notes[z.ordinal]?.pc == q.pc }
            if (!inOneVoice)
                add(Severity.WARNING, i, Rule.CROSS_RELATION, "Cross relation: ${p.name} in ${x.label}, then ${q.name} in ${y.label}.", arrayOf(h to x, i to y))
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
            if (toTarget) for (v in V) {
                val p = a.notes[v.ordinal] ?: continue
                val q = nextNote(v) ?: continue
                if (p.pc != lt || (q.pc == target && q.diatonic == p.diatonic + 1)) continue
                if (v == Voice.SOPRANO || v == Voice.BASS)
                    add(Severity.ERROR, i, Rule.LEADING_TONE, "${v.label}: leading tone ${lt.name} should resolve up to ${target.name}.", both(v))
                // An inner voice may drop a third to the fifth of the chord (frustrated leading tone)
                else if (q.diatonic != p.diatonic - 2)
                    add(Severity.WARNING, i, Rule.LEADING_TONE, "${v.label}: leading tone ${lt.name} should resolve up to ${target.name} or drop to the fifth.", both(v))
            }
        }

        // Augmented sixth: both notes move outward by step to the octave. Diminished/half-diminished 7th: the fifth falls by step.
        val qa = ca.quality
        if (qa != null && cb.recognized && cb.root != ca.root) for (v in V) {
            val p = a.notes[v.ordinal] ?: continue
            val q = nextNote(v) ?: continue
            val m = ca.member(p.pc)
            val step = q.diatonic - p.diatonic
            if (qa.isAug6 && ((m == 0 && step != -1) || (m == 5 && step != 1)))
                add(Severity.ERROR, i, Rule.TENDENCY, "${v.label}: ${p.name} of the augmented sixth should move outward by step to the octave.", both(v))
            if ((qa == Quality.DIM7 || qa == Quality.HALF_DIM7) && ca.target != null && cb.root == ca.target && m == 4 && step != -1)
                add(Severity.WARNING, i, Rule.TENDENCY, "${v.label}: the fifth of ${ca.roman} (${p.name}) should fall by step.", both(v))
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
