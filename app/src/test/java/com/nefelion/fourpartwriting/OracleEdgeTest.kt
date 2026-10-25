package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.ChordAnalyzer.transpose
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Random diatonic progressions with known roman numerals, voiced from chord tones only:
 * the analysis must name every chord as built and find no non-chord tones.
 */
class OracleEdgeTest {
    /** [applied]: secondary seventh chord ("V" or "vii") of the triad on [degree]. */
    private data class Spec(val degree: Int, val seventh: Boolean, val inversion: Int, val applied: String? = null)

    // Targets whose V⁷ / vii°⁷ is chromatic (so it reads as applied, not diatonic)
    private val majorTargets = mapOf(1 to "ii", 2 to "iii", 3 to "IV", 4 to "V", 5 to "vi")
    private val minorV = mapOf(3 to "iv", 4 to "V", 5 to "VI")
    private val minorVii = mapOf(2 to "III", 3 to "iv", 4 to "V", 5 to "VI", 6 to "VII")

    private val numerals = listOf("I", "II", "III", "IV", "V", "VI", "VII")
    // Quality marks per degree: "" upper, "m" lower, "d" lower+°; sevenths: M7, 7, m7, ø7, °7
    private val majorTriads = listOf("", "m", "m", "", "", "m", "d")
    private val minorTriads = listOf("m", "d", "", "m", "", "", "d")
    private val majorSevenths = listOf("M7", "m7", "m7", "M7", "7", "m7", "ø7")
    private val minorSevenths = listOf("m7", "ø7", "M7", "m7", "7", "M7", "°7")

    private fun expected(key: Key, s: Spec): String {
        if (s.applied != null) {
            val label = (if (key.mode == Mode.MAJOR) majorTargets else if (s.applied == "V") minorV else minorVii).getValue(s.degree)
            return (if (s.applied == "V") "V" else "vii°") + listOf("⁷", "⁶₅", "⁴₃", "⁴₂")[s.inversion] + "/" + label
        }
        val q = if (s.seventh) (if (key.mode == Mode.MAJOR) majorSevenths else minorSevenths)[s.degree]
        else (if (key.mode == Mode.MAJOR) majorTriads else minorTriads)[s.degree]
        val upper = q == "" || q == "M7" || q == "7"
        val n = numerals[s.degree].let { if (upper) it else it.lowercase() }
        val mark = when (q) { "d", "°7" -> "°"; "ø7" -> "ø"; "M7" -> "M"; else -> "" }
        val fig = if (s.seventh) listOf("⁷", "⁶₅", "⁴₃", "⁴₂")[s.inversion] else listOf("", "⁶", "⁶₄")[s.inversion]
        return n + mark + fig
    }

    /** Chord members root-up; V and vii° in minor take the raised leading tone. */
    private fun members(key: Key, s: Spec): List<PitchClass> {
        if (s.applied != null) {
            val t = key.degree(s.degree)
            return if (s.applied == "V") transpose(t, 4, 7).let { r -> listOf(r, transpose(t, 6, 11), transpose(r, 4, 7), transpose(r, 6, 10)) }
            else transpose(t, 6, 11).let { r -> listOf(r, transpose(r, 2, 3), transpose(r, 4, 6), transpose(r, 6, 9)) }
        }
        val pcs = (0 until if (s.seventh) 4 else 3).map { key.degree(s.degree + 2 * it) }
        val raise = key.mode == Mode.MINOR && (s.degree == 4 || s.degree == 6)
        return pcs.map { if (raise && it == key.degree(6)) key.leadingTone else it }
    }

    private fun lowestAbove(pc: PitchClass, floor: Pitch): Pitch {
        var p = Pitch(pc.step, pc.alter, floor.octave - 1)
        while (p.midi <= floor.midi || p.diatonic <= floor.diatonic) p = Pitch(pc.step, pc.alter, p.octave + 1)
        return p
    }

    private fun voicing(r: Random, key: Key, s: Spec): List<Pitch> {
        val m = members(key, s)
        val bassPc = m[s.inversion]
        var bass = lowestAbove(bassPc, Pitch.parse("D2"))
        if (r.nextBoolean() && bass.midi + 12 <= Pitch.parse("B2").midi + 5) bass = Pitch(bass.step, bass.alter, bass.octave + 1)
        // Upper three: every member except the bass, plus a doubled root (or bass for a ⁶₄) for triads
        val upper = m.filter { it != bassPc }.toMutableList()
        if (!s.seventh) upper += if (s.inversion == 2) bassPc else m[0]
        upper.shuffle(r)
        val out = mutableListOf(bass)
        var floor = Pitch.fromDiatonic(bass.diatonic + 2, 0).let { Pitch(it.step, -2, it.octave) }
        for (pc in upper) {
            var p = lowestAbove(pc, floor)
            if (r.nextInt(4) == 0) p = Pitch(p.step, p.alter, p.octave + 1)
            out += p
            floor = p
        }
        return out.reversed() // S A T B
    }

    private fun randomSpecs(r: Random, key: Key) = List(r.nextInt(2, 9)) {
        val sev = r.nextInt(3) == 0
        if (r.nextInt(4) == 0) {
            val kind = if (r.nextBoolean()) "V" else "vii"
            val targets = if (key.mode == Mode.MAJOR) majorTargets else if (kind == "V") minorV else minorVii
            Spec(targets.keys.random(r), true, r.nextInt(4), kind)
        } else Spec(r.nextInt(7), sev, r.nextInt(if (sev) 4 else 3))
    }

    private fun wanted(key: Key, specs: List<Spec>) = specs.mapIndexed { i, s ->
        // A ⁶₄ struck again is still the cadential one when V follows
        val next = specs.drop(i + 1).firstOrNull { it != s }
        if (s.applied == null && s.degree == 0 && !s.seventh && s.inversion == 2 && next != null && next.applied == null && next.degree == 4 && next.inversion == 0) "Cad⁶₄"
        else expected(key, s)
    }

    private fun kindOf(w: String, g: String?, key: Key) =
        "$w named $g (${key.mode})".replace(Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹₀₁₂₃₄₅₆₇₈₉]"), "")

    private fun report(fails: Map<String, String>) =
        assertTrue(fails.entries.joinToString(" ### ") { "${it.key}: ${it.value}" }, fails.isEmpty())

    @Test fun randomDiatonicProgressionsAreNamedAsBuilt() {
        val r = Random(7)
        val fails = LinkedHashMap<String, String>()
        repeat(3000) { n ->
            val key = Key(r.nextInt(-7, 8), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            val specs = randomSpecs(r, key)
            val chords = specs.map { voicing(r, key, it) }
            val sc = Score(key = key, voices = (0 until 4).map { v -> chords.map { Event(Duration(4), it[v]) } })
            val a = sc.analysis()
            val want = wanted(key, specs)
            val got = a.romans()
            for (i in want.indices) if (got.getOrNull(i) != want[i])
                fails.putIfAbsent(kindOf(want[i], got.getOrNull(i), key), "#$n ${key.name} $want vs $got | chords ${chords.map { it.joinToString(" ") }}")
            if (a.ncts.isNotEmpty()) fails.putIfAbsent("invented NCT ${a.ncts.values.first().label}",
                "#$n ${key.name} $want vs $got ${a.ncts} | chords ${chords.map { it.joinToString(" ") }}")
        }
        report(fails)
    }

    private enum class Emb { PT, NT, ANT, SUS }

    /**
     * The same progressions decorated in up to two voices per beat: passing and upper neighbour tones and
     * anticipations on the second eighth of a beat, and suspensions tied into the next beat that resolve
     * down on its second eighth. Chords keep their names; each added note is the expected non-chord tone
     * (or none when it happens to be a chord tone), and nothing else is one.
     */
    @Test fun embellishedProgressionsKeepTheirChords() {
        val r = Random(11)
        val fails = LinkedHashMap<String, String>()
        val e8 = Duration(8).value
        repeat(3000) { n ->
            val key = Key(r.nextInt(-7, 8), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            val specs = randomSpecs(r, key)
            val chords = specs.map { voicing(r, key, it) } // S A T B
            val pcs = specs.map { members(key, it).toSet() }
            fun at(v: Int, k: Int) = chords[k][v]
            fun diatonicNote(d: Int) = Pitch.fromDiatonic(d, key.sigAlter(Math.floorMod(d, 7)))
            val plan = Array(4) { arrayOfNulls<Emb>(specs.size) }
            for (k in 0 until specs.size - 1) {
                // A suspension from the last beat resolves on this beat's second eighth: one off-beat attack already, and the bass must hold
                val susIn = (0 until 4).count { k > 0 && plan[it][k - 1] == Emb.SUS }
                var busy = susIn
                for (v in (0 until 4).shuffled(r)) {
                    if (busy >= 2 || r.nextInt(3) != 0 || (k > 0 && plan[v][k - 1] == Emb.SUS) || (v == 3 && susIn > 0)) continue
                    val p = at(v, k); val q = at(v, k + 1)
                    val d = q.diatonic - p.diatonic
                    val options = buildList {
                        if (abs(d) == 2) add(Emb.PT)
                        if (p == q) add(Emb.NT)
                        if (p != q) add(Emb.ANT)
                        // Suspensions into triads only, and not when the held note makes a triad of its own with the others
                        // (F♯ held over C♯–A♯ reads as VI⁶₄): both are ambiguous, not checked here
                        val held = chords[k + 1].toMutableList().also { it[v] = p }
                        val alone = com.nefelion.fourpartwriting.theory.ChordAnalyzer.analyze(held, key).quality
                        if (v != 3 && d == -1 && !specs[k + 1].seventh && (alone == null || alone.seventh || alone.incomplete || !alone.hasThird) &&
                            Interval.between(at(3, k + 1), p).simpleNumber in setOf(2, 4, 7)) add(Emb.SUS)
                    }
                    if (options.isEmpty()) continue
                    plan[v][k] = options.random(r)
                    busy++
                }
            }
            val expect = HashMap<NoteRef, Emb?>()
            val voices = (0 until 4).map { v ->
                val voice = Voice.entries[v]
                val out = mutableListOf<Event>()
                var k = 0
                while (k < specs.size) {
                    val p = at(v, k)
                    when (val e = plan[v][k]) {
                        null -> out += Event(Duration(4), p)
                        Emb.SUS -> {
                            expect[NoteRef(voice, out.size)] = Emb.SUS
                            out += Event(Duration(4, 1).value, p)
                            out += Event(e8, at(v, k + 1))
                            k++
                        }
                        else -> {
                            val q = at(v, k + 1)
                            val extra = when (e) {
                                Emb.PT -> diatonicNote((p.diatonic + q.diatonic) / 2)
                                Emb.NT -> diatonicNote(p.diatonic + 1)
                                else -> q
                            }
                            out += Event(e8, p)
                            expect[NoteRef(voice, out.size)] = if (extra.pc in pcs[k]) null else e
                            out += Event(e8, extra)
                        }
                    }
                    k++
                }
                out
            }
            // A prepared seventh rising a half step to the root on the off-beat reads as a 7–8 retardation: ambiguous, not checked
            val quarter = Duration(4).value
            val retardation = (1 until specs.size).any { k -> specs[k].seventh && (0 until 3).any { v ->
                val p = at(v, k)
                var t = com.nefelion.fourpartwriting.model.Fraction.ZERO
                val idx = voices[v].indexOfFirst { e -> (t == quarter * com.nefelion.fourpartwriting.model.Fraction.of(k.toLong(), 1)).also { t += e.length } }
                val nx = if (idx >= 0) voices[v].getOrNull(idx + 1)?.pitch else null
                p.pc == members(key, specs[k])[3] && at(v, k - 1) == p && nx != null && nx.midi == p.midi + 1 && nx.diatonic == p.diatonic + 1
            } }
            if (retardation) return@repeat
            val a = Score(key = key, voices = voices).analysis()
            val want = wanted(key, specs)
            val got = a.romans()
            val desc = { "#$n ${key.name} $want vs $got ncts=${a.ncts} | ${voices.map { l -> l.joinToString(" ") { "${it.pitch}:${it.length.den / it.length.num}" } }}" }
            for (i in want.indices) if (got.getOrNull(i) != want[i]) fails.putIfAbsent("emb: " + kindOf(want[i], got.getOrNull(i), key), desc())
            for ((ref, e) in expect) {
                val found = a.ncts[ref]
                if (if (e == null) found != null else found?.type?.name != e.name) fails.putIfAbsent("added $e found ${found?.type}", desc())
            }
            for ((ref, nct) in a.ncts) if (ref !in expect) fails.putIfAbsent("invented NCT ${nct.type} (embellished)", desc())
            a.issues.firstOrNull { it.rule == Rule.UNRESOLVED_NCT && it.severity == com.nefelion.fourpartwriting.theory.Severity.ERROR }?.let {
                fails.putIfAbsent("unresolved issue: ${it.message.substringAfter(": ").take(40)}", desc())
            }
        }
        report(fails)
    }
}
