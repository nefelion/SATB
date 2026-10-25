package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.ChordAnalyzer.transpose
import org.junit.Assert.assertTrue
import org.junit.Test
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

    @Test fun randomDiatonicProgressionsAreNamedAsBuilt() {
        val r = Random(7)
        val fails = LinkedHashMap<String, String>()
        repeat(3000) { n ->
            val key = Key(r.nextInt(-7, 8), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            val specs = List(r.nextInt(2, 9)) {
                val sev = r.nextInt(3) == 0
                if (r.nextInt(4) == 0) {
                    val kind = if (r.nextBoolean()) "V" else "vii"
                    val targets = if (key.mode == Mode.MAJOR) majorTargets else if (kind == "V") minorV else minorVii
                    Spec(targets.keys.random(r), true, r.nextInt(4), kind)
                } else Spec(r.nextInt(7), sev, r.nextInt(if (sev) 4 else 3))
            }
            val chords = specs.map { voicing(r, key, it) }
            val sc = Score(key = key, voices = (0 until 4).map { v -> chords.map { Event(Duration(4), it[v]) } })
            val a = sc.analysis()
            val want = specs.mapIndexed { i, s ->
                val next = specs.getOrNull(i + 1)
                if (s.applied == null && s.degree == 0 && !s.seventh && s.inversion == 2 && next != null && next.applied == null && next.degree == 4 && next.inversion == 0) "Cad⁶₄"
                else expected(key, s)
            }
            val got = a.romans()
            for (i in want.indices) {
                if (got.getOrNull(i) != want[i]) {
                    val kind = "${want[i]} named ${got.getOrNull(i)} (${key.mode})"
                    fails.putIfAbsent(kind.replace(Regex("[⁰¹²³⁴⁵⁶⁷⁸⁹₀₁₂₃₄₅₆₇₈₉]"), ""),
                        "#$n ${key.name} $want vs $got | chords ${chords.map { it.joinToString(" ") }}")
                }
            }
            if (a.ncts.isNotEmpty()) fails.putIfAbsent("invented NCT ${a.ncts.values.first().label}",
                "#$n ${key.name} $want vs $got ${a.ncts} | chords ${chords.map { it.joinToString(" ") }}")
        }
        assertTrue(fails.entries.joinToString(" ### ") { "${it.key}: ${it.value}" }, fails.isEmpty())
    }
}
