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

/** A rule violation. [notes] are (slice index, voice) pairs to highlight. */
data class Issue(
    val severity: Severity,
    val slice: Int,
    val rule: String,
    val message: String,
    val notes: List<Pair<Int, Voice>>,
)

data class Analysis(val slices: List<Slice>, val chords: List<Chord>, val issues: List<Issue>)

object VoiceLeading {

    /** Common textbook ranges (MIDI, inclusive). */
    val RANGES = mapOf(
        Voice.SOPRANO to Pitch.parse("C4").midi..Pitch.parse("G5").midi,
        Voice.ALTO to Pitch.parse("G3").midi..Pitch.parse("D5").midi,
        Voice.TENOR to Pitch.parse("C3").midi..Pitch.parse("G4").midi,
        Voice.BASS to Pitch.parse("E2").midi..Pitch.parse("C4").midi,
    )

    private val V = Voice.entries

    fun check(score: Score): Analysis {
        val slices = score.slices()
        val chords = ChordAnalyzer.analyzeAll(slices.map { it.pitches }, score.key)
        val out = mutableListOf<Issue>()
        fun add(sev: Severity, i: Int, rule: String, msg: String, vararg n: Pair<Int, Voice>) {
            out += Issue(sev, i, rule, msg, n.toList())
        }

        // Structure
        val lengths = V.map { score.length(it) }
        if (lengths.distinct().size > 1)
            add(Severity.WARNING, 0, "Structure", "Voices have different lengths.")
        val measure = score.time.measure
        val total = score.length
        if (total > Fraction.ZERO && (total / measure).den != 1L)
            add(Severity.WARNING, slices.lastIndex.coerceAtLeast(0), "Structure", "Last measure is incomplete.")

        for ((i, s) in slices.withIndex()) {
            val n = s.notes
            val chord = chords[i]
            for (v in V) if (n[v.ordinal] == null)
                add(Severity.WARNING, i, "Missing voice", "${v.name.lowercase().cap()} has no note here.")

            // Ranges
            for (v in V) {
                val p = n[v.ordinal] ?: continue
                if (p.midi !in RANGES.getValue(v))
                    add(Severity.WARNING, i, "Range", "${v.name.lowercase().cap()} ${p.name} is out of its usual range.", i to v)
            }
            // Spacing and crossing
            for (k in 0 until 3) {
                val up = n[k] ?: continue
                val lo = n[k + 1] ?: continue
                if (lo.midi > up.midi)
                    add(Severity.ERROR, i, "Voice crossing", "${V[k + 1].label} (${lo.name}) is above ${V[k].label} (${up.name}).", i to V[k], i to V[k + 1])
                else if (k < 2 && up.midi - lo.midi > 12)
                    add(Severity.ERROR, i, "Spacing", "More than an octave between ${V[k].label} and ${V[k + 1].label}.", i to V[k], i to V[k + 1])
            }

            // Chord construction
            if (s.pitches.size >= 3) chordRules(i, s, chord, ::add)

            if (i > 0) motionRules(i, slices[i - 1], s, chords[i - 1], chord, ::add)
        }
        return Analysis(slices, chords, out)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    private fun voicesWith(s: Slice, pc: PitchClass) =
        V.filter { s.notes[it.ordinal]?.pc == pc }

    private fun chordRules(
        i: Int, s: Slice, c: Chord,
        add: (Severity, Int, String, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        if (!c.recognized) {
            if (c.symbol.contains("no 3rd")) add(Severity.ERROR, i, "Missing third", "The chord has no third: ${c.symbol}.", emptyArray())
            else add(Severity.WARNING, i, "Chord", "Unrecognized sonority: ${c.symbol}.", emptyArray())
            return
        }
        val q = c.quality!!
        c.leadingTone?.let { lt ->
            val vs = voicesWith(s, lt)
            if (vs.size > 1) add(Severity.ERROR, i, "Doubling", "Doubled leading tone (${lt.name}).", vs.map { i to it }.toTypedArray())
        }
        c.seventh?.let { sev ->
            val vs = voicesWith(s, sev)
            if (vs.size > 1) add(Severity.ERROR, i, "Doubling", "Doubled chordal seventh (${sev.name}).", vs.map { i to it }.toTypedArray())
        }
        if (!q.seventh && c.inversion == 2 && voicesWith(s, c.bass!!).size < 2)
            add(Severity.WARNING, i, "Doubling", "In a ⁶₄ chord, double the bass (${c.bass.name}).", emptyArray())
        if (q == Quality.DIM && c.inversion in 0..1 && voicesWith(s, c.root!!).size > 1)
            add(Severity.WARNING, i, "Doubling", "Do not double the root of a diminished triad; double the third.",
                voicesWith(s, c.root).map { i to it }.toTypedArray())
    }

    private fun motionRules(
        i: Int, a: Slice, b: Slice, ca: Chord, cb: Chord,
        add: (Severity, Int, String, String, Array<out Pair<Int, Voice>>) -> Unit,
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
                add(Severity.ERROR, i, "Melodic interval", "${v.label}: augmented melodic interval (${iv.name}).", both(v))
            else if (iv.number > 8 || (iv.number == 8 && iv.quality != IntervalQuality.PERFECT))
                add(Severity.ERROR, i, "Melodic interval", "${v.label}: leap larger than an octave (${iv.name}).", both(v))
        }

        // Overlap
        for (k in 0 until 3) {
            val up = V[k]; val lo = V[k + 1]
            val upA = a.notes[k]; val loA = a.notes[k + 1]; val upB = b.notes[k]; val loB = b.notes[k + 1]
            if (upA == null || loA == null || upB == null || loB == null) continue
            if (loB.midi > upA.midi && moved(lo))
                add(Severity.ERROR, i, "Voice overlap", "${lo.label} moves above the previous ${up.label} note.", arrayOf(h to up, i to lo))
            if (upB.midi < loA.midi && moved(up))
                add(Severity.ERROR, i, "Voice overlap", "${up.label} moves below the previous ${lo.label} note.", arrayOf(h to lo, i to up))
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
                isP5(ia) && isP5(ib) -> add(Severity.ERROR, i, "Parallel fifths",
                    if (same) "Parallel fifths between $names." else "Consecutive fifths by contrary motion between $names.", hl)
                isP8(ia) && isP8(ib) -> add(Severity.ERROR, i, "Parallel octaves",
                    if (same) "Parallel ${if (ib.number == 1) "unisons" else "octaves"} between $names."
                    else "Consecutive octaves by contrary motion between $names.", hl)
                vy == Voice.BASS && isD5(ia) && isP5(ib) && same ->
                    add(Severity.WARNING, i, "Unequal fifths", "d5 → P5 between $names.", hl)
                x == 0 && vy == Voice.BASS && same && (isP5(ib) || isP8(ib)) &&
                    abs(bx.diatonic - ax.diatonic) > 1 ->
                    add(Severity.WARNING, i, "Direct ${if (isP5(ib)) "fifths" else "octaves"}",
                        "Outer voices move in similar motion into a perfect ${if (isP5(ib)) "fifth" else "octave"} with a leap in the soprano.", hl)
            }
        }

        // Leading tone resolution (outer voices)
        val lt = ca.leadingTone
        val target = ca.target
        if (lt != null && target != null && cb.root != null && cb.notes.any { it.pc == target }) {
            val toTarget = cb.root == target || cb.root.step == (target.step + 5) % 7
            if (toTarget) for (v in listOf(Voice.SOPRANO, Voice.BASS)) {
                val p = a.notes[v.ordinal] ?: continue
                val q = b.notes[v.ordinal] ?: continue
                if (p.pc == lt && !(q.pc == target && q.diatonic == p.diatonic + 1))
                    add(Severity.ERROR, i, "Leading tone", "${v.label}: leading tone ${lt.name} should resolve up to ${target.name}.", both(v))
            }
        }

        // Chordal seventh resolution
        val sev = ca.seventh
        if (sev != null && cb.recognized && cb.root != ca.root) {
            val passing43 = ca.inversion == 2 && ca.degree == 4 && cb.degree == 0 && cb.inversion == 1
            for (v in V) {
                val p = a.notes[v.ordinal] ?: continue
                val q = b.notes[v.ordinal] ?: continue
                if (p.pc != sev) continue
                val step = q.diatonic - p.diatonic
                val ok = step == -1 || (step == 0 && q == p) || (passing43 && step == 1)
                if (!ok) add(Severity.ERROR, i, "Seventh", "${v.label}: chordal seventh ${sev.name} should resolve down by step.", both(v))
            }
        }
    }

    private fun isP5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.PERFECT
    private fun isD5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.D
    private fun isP8(iv: Interval) = iv.simpleNumber == 1 && iv.quality == IntervalQuality.PERFECT
}
