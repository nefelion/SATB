package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.IntervalQuality
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * Figured-bass exercises: a generator that writes a functional progression as a figured bass,
 * and a solver that voices the upper parts over any figured bass.
 */
object Exercises {

    enum class Level(val label: String) { EASY("Easy"), MEDIUM("Medium"), HARD("Hard") }

    /** A chord of the plan: scale degree 0..6 of the root, inversion, seventh; [of]: applied dominant of that degree. */
    private class Step(val degree: Int, val inv: Int, val seventh: Boolean = false, val of: Int? = null)

    private val STEPS = mapOf(
        "I" to Step(0, 0), "I6" to Step(0, 1), "Cad64" to Step(0, 2),
        "ii" to Step(1, 0), "ii6" to Step(1, 1), "ii65" to Step(1, 1, true),
        "IV" to Step(3, 0), "vi" to Step(5, 0),
        "V" to Step(4, 0), "V6" to Step(4, 1), "V7" to Step(4, 0, true), "V65" to Step(4, 1, true),
        "V43" to Step(4, 2, true), "V42" to Step(4, 3, true), "vii6" to Step(6, 1),
        "V/V" to Step(4, 0, of = 4), "V7/V" to Step(4, 0, true, 4), "V65/V" to Step(4, 1, true, 4),
    )

    /** Tonic → predominant → dominant → tonic, with the usual inversions and passing chords. */
    private val NEXT = mapOf(
        "I" to listOf("IV", "ii", "ii6", "ii65", "vi", "V", "I6", "V6", "V65", "V43", "vii6", "V/V"),
        "I6" to listOf("IV", "ii", "ii6", "ii65", "V", "V7", "V7/V"),
        "Cad64" to listOf("V", "V7"),
        "ii" to listOf("V", "V7", "Cad64"),
        "ii6" to listOf("V", "V7", "Cad64"),
        "ii65" to listOf("V", "V7", "Cad64"),
        "IV" to listOf("V", "V7", "ii6", "ii65", "Cad64", "I", "I6", "V/V", "V7/V", "V65/V"),
        "vi" to listOf("IV", "ii", "ii6", "ii65"),
        "V" to listOf("I", "vi"),
        "V6" to listOf("I"),
        "V7" to listOf("I", "vi"),
        "V65" to listOf("I"),
        "V43" to listOf("I", "I6"),
        "V42" to listOf("I6"),
        "vii6" to listOf("I", "I6"),
        "V/V" to listOf("V", "V7", "Cad64"),
        "V7/V" to listOf("V", "V7", "Cad64"),
        "V65/V" to listOf("V", "V7"),
    )

    private val EASY = setOf("I", "I6", "ii", "ii6", "IV", "vi", "V", "V6")
    private val MEDIUM = EASY + setOf("Cad64", "ii65", "V7", "V65", "V43", "V42", "vii6")
    private val PRE_CADENCE = setOf("IV", "ii", "ii6", "ii65", "Cad64", "V/V", "V7/V")

    /**
     * A new exercise: the bass with figures given (locked), the upper voices empty. Its progression is checked
     * by solving it, so at least one answer without errors exists; of the tries, the one whose answer has the
     * fewest warnings wins. Null only if every try failed.
     */
    fun generate(level: Level, rnd: Random = Random.Default): Score? {
        val chords = when (level) { Level.EASY -> 9; Level.MEDIUM -> 13; Level.HARD -> 17 }
        var best: Score? = null
        var bestWarnings = Int.MAX_VALUE
        repeat(40) {
            val minor = level != Level.EASY && rnd.nextBoolean()
            val reach = when (level) { Level.EASY -> 2; Level.MEDIUM -> 3; Level.HARD -> 4 }
            val key = Key(rnd.nextInt(-reach, reach + 1), if (minor) Mode.MINOR else Mode.MAJOR)
            val plan = progression(level, minor, chords, rnd) ?: return@repeat
            val sc = figuredBass(plan, key, rnd) ?: return@repeat
            val solved = solve(sc) ?: return@repeat
            val issues = VoiceLeading.check(solved).issues
            if (issues.any { it.severity == Severity.ERROR } || issues.size >= bestWarnings) return@repeat
            best = sc; bestWarnings = issues.size
            if (bestWarnings == 0) return sc
        }
        return best
    }

    private fun progression(level: Level, minor: Boolean, n: Int, rnd: Random): List<String>? {
        val allowed = when (level) { Level.EASY -> EASY; Level.MEDIUM -> MEDIUM; Level.HARD -> STEPS.keys }
            .let { if (minor) it - "ii" else it } // ii° in root position is avoided
        repeat(2000) {
            val p = mutableListOf("I")
            while (p.size < n) {
                val next = NEXT.getValue(p.last()).filter { it in allowed }
                // The last three chords make the cadence: a predominant (or cadential ⁶₄), V, I
                val opts = when (p.size) {
                    n - 1 -> next.filter { it == "I" }
                    n - 2 -> next.filter { it == "V" || it == "V7" }
                    n - 3 -> next.filter { it in PRE_CADENCE }
                    else -> next - "Cad64" // a cadential ⁶₄ away from the cadence falls on a weak beat
                }
                if (opts.isEmpty()) break
                p += opts[rnd.nextInt(opts.size)]
            }
            if (p.size == n) return p
        }
        return null
    }

    /** Pitch classes of [st] in [key], root first (harmonic minor for V and vii°). */
    private fun pcs(st: Step, key: Key): List<PitchClass> {
        if (st.of != null) {
            val root = ChordAnalyzer.transpose(key.degree(st.of), 4, 7)
            return listOfNotNull(root, ChordAnalyzer.transpose(root, 2, 4), ChordAnalyzer.transpose(root, 4, 7),
                if (st.seventh) ChordAnalyzer.transpose(root, 6, 10) else null)
        }
        val raise = key.mode == Mode.MINOR && st.degree in setOf(4, 6)
        return (0 until if (st.seventh) 4 else 3).map { k ->
            val pc = key.degree(st.degree + 2 * k)
            if (raise && pc.step == key.leadingTone.step) key.leadingTone else pc
        }
    }

    /** Bass notes (quarters, the last a whole note in 4/4) with figures; null if the figures would not spell the chord. */
    private fun figuredBass(plan: List<String>, key: Key, rnd: Random): Score? {
        val bass = mutableListOf<Event>()
        var prev: Pitch? = null
        for ((i, name) in plan.withIndex()) {
            val st = STEPS.getValue(name)
            val chord = pcs(st, key)
            val pc = chord[st.inv]
            // The octave nearest the last bass note (D3 to start), inside the bass range
            val range = VoiceLeading.RANGES.getValue(Voice.BASS)
            val b = (1..4).map { Pitch(pc.step, pc.alter, it) }.filter { it.midi in range }
                .minByOrNull { abs(it.midi - (prev?.midi ?: 50)) + rnd.nextDouble() } ?: return null
            val figs = figures(b, chord, st, key)
            if (FiguredBass.realize(b, figs, key).values.toSet() != chord.toSet() - b.pc) return null
            bass += Event(if (i == plan.lastIndex) Fraction.of(1, 1) else Fraction.of(1, 4), b, figures = figs)
            prev = b
        }
        return Score(key = key, time = TimeSig(4, 4), voices = listOf(emptyList(), emptyList(), emptyList(), bass), given = setOf(Voice.BASS))
    }

    /** The usual figures for the inversion, plus an accidental on each note the key signature does not give. */
    private fun figures(bass: Pitch, chord: List<PitchClass>, st: Step, key: Key): List<String> {
        val lines = (if (st.seventh) listOf(listOf("7"), listOf("6", "5"), listOf("4", "3"), listOf("4", "2"))
        else listOf(emptyList(), listOf("6"), listOf("6", "4")))[st.inv].toMutableList()
        for (pc in chord) {
            if (pc == bass.pc) continue
            val sig = key.sigAlter(pc.step)
            if (pc.alter == sig) continue
            val acc = when { pc.alter == 0 -> "n"; pc.alter > sig -> "#"; else -> "b" }
            val n = Math.floorMod(pc.step - bass.step, 7) + 1
            val at = lines.indexOfFirst { it == "$n" }
            when {
                at >= 0 -> lines[at] = acc + lines[at]
                n == 3 -> lines += acc
                else -> lines.add(lines.indexOfFirst { l -> (l.filter { it.isDigit() }.toIntOrNull() ?: 3) < n }.let { if (it < 0) lines.size else it }, "$acc$n")
            }
        }
        return lines
    }

    // ---------- solver ----------

    private const val NO = 1e6

    /** The chord over one bass note: its notes, the members that must sound, and what the analyzer knows of it. */
    private class Target(val bass: Pitch, val pcs: Set<PitchClass>, val required: Set<PitchClass>, val chord: Chord, val cadential: Boolean)

    /**
     * The score with soprano, alto and tenor written over its figured bass (rhythm of the bass): the voicing
     * with the smoothest, rule-abiding lines. Null if the bass has rests or some figure has no voicing.
     */
    fun solve(score: Score): Score? {
        val bass = score.voice(Voice.BASS)
        if (bass.isEmpty() || bass.any { it.pitch == null }) return null
        val key = score.key
        val targets = bass.mapIndexed { i, e ->
            val b = e.pitch!!
            val above = FiguredBass.realize(b, e.figures, key).values.toSet()
            val chord = ChordAnalyzer.analyze(listOf(b) + above.map { Pitch(it.step, it.alter, 4) }, key)
            val fifth = above.firstOrNull { chord.member(it) == 4 }
            // A seventh chord may leave out its fifth (and double the root instead)
            val optional = if (chord.quality?.seventh == true && fifth != null && e.figures.none { it.filter(Char::isDigit) == "5" }) setOf(fifth) else emptySet()
            val next = bass.getOrNull(i + 1)?.pitch
            Target(b, above + b.pc, above + b.pc - optional, chord, chord.inversion == 2 && chord.degree == 0 && next != null && next.pc == b.pc)
        }
        val states = targets.map { voicings(it) }
        if (states.any { it.isEmpty() }) return null
        // Cheapest path through one voicing per chord
        var cost = states[0].map { node(it, targets[0]) }.toDoubleArray()
        val back = mutableListOf<IntArray>()
        for (i in 1 until states.size) {
            val c = DoubleArray(states[i].size) { NO }
            val from = IntArray(states[i].size) { -1 }
            for ((k, v) in states[i].withIndex()) {
                val own = node(v, targets[i])
                for ((j, u) in states[i - 1].withIndex()) {
                    if (cost[j] >= NO) continue
                    val t = cost[j] + own + move(u, v, targets[i - 1], targets[i])
                    if (t < c[k]) { c[k] = t; from[k] = j }
                }
            }
            cost = c
            back += from
        }
        var k = cost.indices.minByOrNull { cost[it] } ?: return null
        if (cost[k] >= NO) return null
        val path = IntArray(states.size)
        for (i in states.lastIndex downTo 0) { path[i] = k; if (i > 0) k = back[i - 1][k] }
        val upper = (0 until 3).map { v -> bass.mapIndexed { i, e -> Event(e.length, states[i][path[i]][v], e.tuplet) } }
        return score.copy(voices = upper + listOf(bass))
    }

    /** All S, A, T over the bass that spell the chord, in range, uncrossed and spaced. Index 0..3 = S A T B. */
    private fun voicings(t: Target): List<List<Pitch>> {
        fun cands(v: Voice) = t.pcs.flatMap { pc -> (2..5).map { Pitch(pc.step, pc.alter, it) } }
            .filter { it.midi in VoiceLeading.RANGES.getValue(v) }
        val out = mutableListOf<List<Pitch>>()
        val b = t.bass
        val lt = t.chord.leadingTone
        val seventh = t.chord.seventh
        for (tn in cands(Voice.TENOR)) {
            if (tn.midi <= b.midi || Interval.between(b, tn).number > 12) continue
            for (a in cands(Voice.ALTO)) {
                if (a.midi <= tn.midi || a.midi - tn.midi > 12) continue
                for (s in cands(Voice.SOPRANO)) {
                    if (s.midi <= a.midi || s.midi - a.midi > 12) continue
                    val v = listOf(s, a, tn, b)
                    val pcs = v.map { it.pc }
                    if (!pcs.containsAll(t.required)) continue
                    // Tendency tones are never doubled
                    if (lt != null && pcs.count { it == lt } > 1) continue
                    if (seventh != null && pcs.count { it == seventh } > 1) continue
                    out += v
                }
            }
        }
        return out
    }

    /** Doubling preference: the root (the bass of a cadential ⁶₄), then the fifth, then the third. */
    private fun node(v: List<Pitch>, t: Target): Double {
        val pcs = v.map { it.pc }
        val doubled = pcs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        var c = 0.0
        for (pc in doubled) c += when {
            t.cadential && pc == t.bass.pc -> 0.0
            else -> when (t.chord.member(pc)) { 0 -> 0.0; 4 -> 2.0; 2 -> 4.0; else -> 6.0 }
        }
        if (t.cadential && t.bass.pc !in doubled) c += 4
        return c
    }

    /** Cost of moving from voicing [p] to [c]: forbidden motion is [NO], the rest is how far the upper voices move. */
    private fun move(p: List<Pitch>, c: List<Pitch>, tp: Target, tc: Target): Double {
        var cost = 0.0
        // Parallel (or contrary) fifths and octaves between any two voices
        for (i in 0 until 4) for (j in i + 1 until 4) {
            if (p[i] == c[i] && p[j] == c[j]) continue
            val a = Math.floorMod(p[i].midi - p[j].midi, 12)
            val b = Math.floorMod(c[i].midi - c[j].midi, 12)
            if (a == b && (a == 0 || a == 7)) return NO
        }
        val dirs = IntArray(4) { (c[it].midi - p[it].midi).sign }
        for (v in 0 until 3) {
            val d = c[v].midi - p[v].midi
            if (d == 0) continue
            val iv = Interval.between(p[v], c[v])
            if (iv.quality == IntervalQuality.A || iv.quality == IntervalQuality.AA || iv.simpleNumber == 7 || iv.number > 8) return NO
            if (iv.quality == IntervalQuality.D || iv.quality == IntervalQuality.DD) cost += 20
            cost += abs(d)
            if (abs(d) > 4) cost += 6
            // Overlap: past the previous note of a neighbour
            if (c[v].midi < p[v + 1].midi) return NO
            if (v > 0 && c[v].midi > p[v - 1].midi) return NO
        }
        if (c[3].midi > p[2].midi) return NO
        // Outer voices: no leap of the soprano in similar motion into a fifth or octave
        val outer = Math.floorMod(c[0].midi - c[3].midi, 12)
        if ((outer == 0 || outer == 7) && dirs[0] == dirs[3] && dirs[0] != 0 && abs(c[0].midi - p[0].midi) > 2) return NO
        if (dirs.all { it == 1 } || dirs.all { it == -1 }) cost += 15
        // The leading tone of a resolving dominant rises to its target; the seventh falls by step
        val lt = tp.chord.leadingTone
        if (lt != null && tc.chord.root == tp.chord.target) for (v in 0 until 3) if (p[v].pc == lt) {
            if (c[v].pc != tp.chord.target || c[v].midi < p[v].midi) cost += if (v == 0) NO else 40.0
        }
        val sev = tp.chord.seventh
        if (sev != null) for (v in 0 until 3) if (p[v].pc == sev && p[v].diatonic - c[v].diatonic != 1) return NO
        return cost
    }
}
