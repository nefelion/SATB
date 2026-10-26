package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Quality
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bars of one diatonic triad held as whole notes while one or two voices move in quarters on the beats,
 * through stepwise passing and neighbour tones and leaps of a third between chord tones. Every beat must
 * keep the bar's chord, every moving dissonance must be a passing or neighbour tone, and nothing else one.
 */
class FloridOracleTest {
    private fun lowestAbove(pc: PitchClass, floor: Pitch): Pitch {
        var p = Pitch(pc.step, pc.alter, floor.octave - 1)
        while (p.midi <= floor.midi || p.diatonic <= floor.diatonic) p = Pitch(pc.step, pc.alter, p.octave + 1)
        return p
    }

    @Test fun ornamentsOnTheBeatKeepTheChord() = run(2026, tuplets = false)

    /** The same in triplets and syncopations: a moving note may fall off the beat or sound across it. */
    @Test fun tupletOrnamentsKeepTheChord() = run(2027, tuplets = true)

    private fun f(n: Long, d: Long) = Fraction.of(n, d)
    private val rhythms = listOf(
        listOf(f(1, 6), f(1, 6), f(1, 6), f(1, 2)), listOf(f(1, 2), f(1, 6), f(1, 6), f(1, 6)), listOf(f(1, 12), f(1, 12), f(1, 12), f(3, 4)),
        listOf(f(1, 3), f(1, 3), f(1, 6), f(1, 6)), listOf(f(1, 8), f(1, 4), f(1, 8), f(1, 2)), listOf(f(1, 6), f(1, 3), f(1, 6), f(1, 3)),
        listOf(f(1, 4), f(1, 12), f(1, 12), f(7, 12)),
    )

    private fun run(seed: Int, tuplets: Boolean) {
        val r = Random(seed)
        val fails = LinkedHashMap<String, String>()
        repeat(4000) { n ->
            val key = Key(r.nextInt(-6, 7), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            fun note(d: Int) = Pitch.fromDiatonic(d, key.sigAlter(Math.floorMod(d, 7)))
            val bars = r.nextInt(2, 5)
            val lines = List(4) { mutableListOf<Event>() }
            data class Bar(val members: Set<PitchClass>, val moving: Set<Int>, val lineStart: List<Int>)
            val plan = mutableListOf<Bar>()
            repeat(bars) {
                val deg = r.nextInt(7)
                val m = (0 until 3).map { key.degree(deg + 2 * it) }
                val inv = r.nextInt(2)
                val bass = lowestAbove(m[inv], Pitch.parse("D2"))
                val upper = (m.filter { it != m[inv] } + m[0]).shuffled(r)
                val chord = mutableListOf(bass)
                var floor = Pitch.fromDiatonic(bass.diatonic + 2, 0).let { Pitch(it.step, -2, it.octave) }
                for (pc in upper) { val p = lowestAbove(pc, floor); chord += p; floor = p }
                val satb = chord.reversed()
                val moving = (0 until 4).shuffled(r).take(r.nextInt(1, 3)).toSet()
                val starts = List(4) { lines[it].size }
                for (v in 0 until 4) {
                    if (v !in moving) { lines[v] += Event(Duration(1), satb[v]); continue }
                    var d = satb[v].diatonic
                    var dir = 0 // direction into the current note when it is a dissonance
                    val line = mutableListOf(note(d))
                    for (b in 1..3) {
                        val ct = note(d).pc in m
                        val opts = if (!ct) listOf(d + dir, d - dir) // pass on or return
                        // Leaps of a third only between chord tones; end on a chord tone
                        else listOf(d - 2, d + 2, d - 1, d + 1).filter { (abs(it - d) == 1 && b < 3) || note(it).pc in m }
                        val nd = opts.filter { note(it).pc in m || b < 3 }.random(r)
                        dir = if (note(nd).pc in m) 0 else nd - d
                        d = nd
                        line += note(d)
                    }
                    val rhythm = if (tuplets) rhythms.random(r) else List(4) { Duration(4).value }
                    lines[v] += line.mapIndexed { j, p -> Event(rhythm[j], p) }
                }
                plan += Bar(m.toSet(), moving, starts)
            }
            val sc = Score(key = key, voices = lines)
            val a = sc.analysis()
            val desc = { "#$n ${key.name} ${a.romans()} ncts=${a.ncts} | ${lines.map { l -> l.joinToString(" ") { "${it.pitch}:${it.length.num}/${it.length.den}" } }}" }
            for ((k, bar) in plan.withIndex()) {
                val bt = Duration(1).value * com.nefelion.fourpartwriting.model.Fraction.of(k.toLong(), 1)
                val pts = a.points.filter { a.slices[it].time >= bt && a.slices[it].time < bt + Duration(1).value }
                if (pts.isEmpty()) { fails.putIfAbsent("no points in bar $k", desc()); continue }
                val block = ChordAnalyzer.analyze(pts.first().let { a.slices[it].pitches }, key)
                // Ambiguous by design: a dissonance that makes another complete triad (5–6) or V8–7
                val ambiguous = pts.any { p ->
                    val q = ChordAnalyzer.analyze(a.slices[p].pitches, key)
                    q.root != block.root && q.quality != null && !q.quality.seventh && !q.quality.incomplete && q.quality.hasThird ||
                        q.root == block.root && (q.quality == Quality.DOM7 || q.quality == Quality.DOM7_NO5)
                }
                if (ambiguous) continue
                for (p in pts) {
                    val c = a.chords[p]
                    if (c.root != block.root || c.quality != block.quality)
                        fails.putIfAbsent("beat named ${c.quality} instead of ${block.quality}", desc())
                }
                for (v in bar.moving) for (j in 0 until 4) {
                    val idx = bar.lineStart[v] + j
                    val pitch = lines[v][idx].pitch!!
                    val found = a.ncts[NoteRef(Voice.entries[v], idx)]
                    if (pitch.pc in bar.members && found != null) fails.putIfAbsent("chord tone called ${found.type}", desc())
                    if (pitch.pc !in bar.members && found?.type !in setOf(NctType.PT, NctType.NT)) fails.putIfAbsent("ornament called ${found?.type}", desc())
                }
            }
            if (a.issues.any { it.rule == Rule.UNRESOLVED_NCT }) fails.putIfAbsent("unresolved issue", desc())
        }
        assertTrue(fails.entries.joinToString(" ### ") { "${it.key}: ${it.value}" }, fails.isEmpty())
    }
}
