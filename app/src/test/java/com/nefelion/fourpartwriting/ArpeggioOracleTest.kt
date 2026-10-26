package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.Quality
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random triad progressions, one chord per bar half (or per bar in 3/4), where one or two voices break their
 * note into chord tones (arpeggio, struck again, octave leaps) between their neighbours. Only chord tones
 * sound, so every beat keeps the chord, nothing is a non-chord tone, and no chord loses its third.
 */
class ArpeggioOracleTest {
    private fun lowestAbove(pc: PitchClass, floor: Pitch): Pitch {
        var p = Pitch(pc.step, pc.alter, floor.octave - 1)
        while (p.midi <= floor.midi || p.diatonic <= floor.diatonic) p = Pitch(pc.step, pc.alter, p.octave + 1)
        return p
    }

    private val triads = setOf(Quality.MAJOR, Quality.MINOR, Quality.DIM, Quality.AUG)

    @Test fun arpeggiatedVoicesKeepTheChord() {
        val r = Random(4242)
        val fails = LinkedHashMap<String, String>()
        var checked = 0
        repeat(6000) { n ->
            val key = Key(r.nextInt(-6, 7), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            val time = listOf(TimeSig(4, 4), TimeSig(3, 4), TimeSig(6, 8)).random(r)
            val span = if (time.beats == 4) Fraction.of(1, 2) else time.measure
            val count = r.nextInt(2, 5)
            val chords = List(count) {
                val deg = listOf(0, 1, 3, 4, 5).random(r)
                val m = (0 until 3).map { key.degree(deg + 2 * it) }
                val bass = lowestAbove(m[if (deg == 0 || r.nextBoolean()) 0 else 1], Pitch.parse("D2"))
                val upper = (m.filter { it != bass.pc } + m[0]).shuffled(r)
                val c = mutableListOf(bass)
                var floor = Pitch.fromDiatonic(bass.diatonic + 2, 0).let { Pitch(it.step, -2, it.octave) }
                for (pc in upper) { val p = lowestAbove(pc, floor); c += p; floor = p }
                c.reversed()
            }
            val plain = chords.map { ChordAnalyzer.analyze(it, key) }
            if (plain.any { it.quality !in triads }) return@repeat
            val movers = (0 until 4).shuffled(r).take(r.nextInt(1, 3)).toSet()
            // Pieces of a span: quarters or eighths (dotted beats split in three eighths in 6/8)
            val unit = if (time.isCompound) Fraction.of(1, 8) else listOf(Fraction.of(1, 4), Fraction.of(1, 8)).random(r)
            val pieces = (span / unit).let { (it.num / it.den).toInt() }
            val lines = List(4) { v ->
                chords.flatMapIndexed { ci, c ->
                    if (v !in movers) return@flatMapIndexed listOf(Event(span, c[v]))
                    // Chord tones strictly between the (held) neighbours, within a tenth of the written note
                    val hi = if (v == 0) c[0].midi + 7 else (if (v - 1 in movers) chords[ci][v - 1].midi - 5 else c[v - 1].midi)
                    val lo = if (v == 3) c[3].midi - 12 else (if (v + 1 in movers) chords[ci][v + 1].midi + 5 else c[v + 1].midi)
                    val pool = (-12..12).map { c[v].diatonic + it }.flatMap { d ->
                        c.map { it.pc }.distinct().filter { Math.floorMod(d, 7) == it.step }.map { Pitch.fromDiatonic(d, it.alter) }
                    }.filter { it.midi in (lo + 1) until hi }.distinct()
                    // The bass keeps its note on the beat so the inversion stays
                    // (its own note somewhere in the span, so the chord is complete over it)
                    val own = if (v == 3) 0 else r.nextInt(pieces)
                    List(pieces) { j -> Event(unit, if (j == own || pool.isEmpty()) c[v] else pool.random(r)) }
                }
            }
            // Leave out the case where two movers end up crossing each other at some moment
            val sc = Score(key = key, time = time, voices = lines)
            val an = sc.analysis()
            if (an.issues.any { it.rule == Rule.CROSSING }) return@repeat
            checked++
            val desc = { "#$n ${key.name} ${time.label} movers=$movers ${an.points.map { an.slices[it].time to an.chords[it].roman }} ncts=${an.ncts} issues=${an.issues.filter { it.rule in setOf(Rule.MISSING_THIRD, Rule.UNRECOGNIZED, Rule.UNRESOLVED_NCT) }.map { it.message }} | ${lines.map { l -> l.joinToString(" ") { "${it.pitch}:${it.length}" } }}" }
            for ((ci, c) in plain.withIndex()) for (p in an.points.filter { an.slices[it].time >= span * Fraction.of(ci.toLong(), 1) && an.slices[it].time < span * Fraction.of(ci + 1L, 1) }) {
                val got = an.chords[p]
                // (the fifth may be missing for a moment)
                if (got.root != c.root || got.quality?.members?.get(2) != c.quality?.members?.get(2)) fails.putIfAbsent("named ${got.quality} for ${c.quality}", desc())
            }
            if (an.ncts.isNotEmpty()) fails.putIfAbsent("ncts", desc())
            for (rule in listOf(Rule.MISSING_THIRD, Rule.UNRECOGNIZED, Rule.UNRESOLVED_NCT))
                if (an.issues.any { it.rule == rule }) fails.putIfAbsent("$rule", desc())
        }
        assertTrue("checked $checked\n" + fails.entries.joinToString("\n### ") { "${it.key}: ${it.value}" }, fails.isEmpty() && checked > 1500)
    }
}
