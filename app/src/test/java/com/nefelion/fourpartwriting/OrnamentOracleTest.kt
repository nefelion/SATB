package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Quality
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Random progressions of diatonic triads in one rhythm, then one voice ornamented at one chord change:
 * a passing tone through a third, a neighbour on a common tone, an anticipation of a step, or a
 * suspension of a step down. The chords keep their names and the ornament gets its label.
 */
class OrnamentOracleTest {
    private fun lowestAbove(pc: PitchClass, floor: Pitch): Pitch {
        var p = Pitch(pc.step, pc.alter, floor.octave - 1)
        while (p.midi <= floor.midi || p.diatonic <= floor.diatonic) p = Pitch(pc.step, pc.alter, p.octave + 1)
        return p
    }

    private val triads = setOf(Quality.MAJOR, Quality.MINOR, Quality.DIM, Quality.AUG)

    @Test fun ornamentsAtChordChanges() {
        val r = Random(77)
        val fails = LinkedHashMap<String, String>()
        var checked = 0
        val byKind = HashMap<NctType, Int>()
        repeat(20000) { n ->
            val key = Key(r.nextInt(-6, 7), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
            fun note(d: Int) = Pitch.fromDiatonic(d, key.sigAlter(Math.floorMod(d, 7)))
            val time = listOf(TimeSig(4, 4), TimeSig(3, 4), TimeSig(2, 4), TimeSig(6, 8)).random(r)
            val beat = time.beatLength
            // Chords one beat each, so an ornament on the second half sits off the beat
            val count = (time.measure / beat).num.toInt() * r.nextInt(1, 3)
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
            // Every chord a complete triad of the key's diatonic notes (no raised 7th: plain triads only)
            val plain = chords.map { ChordAnalyzer.analyze(it, key) }
            if (plain.any { it.quality !in triads }) return@repeat
            data class Orn(val v: Int, val k: Int, val kind: NctType, val host: Int, val nct: Pitch, val events: List<Event>)
            val half = beat * Fraction.of(1, 2)
            val quarter = beat * Fraction.of(1, 4)
            fun ornament(v: Int, k: Int): Orn? {
                val a = chords[k][v]; val b = chords[k + 1][v]
                val d = b.diatonic - a.diatonic
                val s = listOf(-1, 1).random(r)
                val opts = mutableListOf<Orn>()
                if (Math.abs(d) == 2) note(a.diatonic + d / 2).let { m ->
                    opts += Orn(v, k, NctType.PT, k, m, listOf(Event(half, a), Event(half, m)))
                    if (v != 3) opts += Orn(v, k, NctType.PT, k + 1, m, listOf(Event(half, m), Event(half, b)))
                }
                if (d == 0) note(a.diatonic + s).let { m -> opts += Orn(v, k, NctType.NT, k, m, listOf(Event(half, a), Event(half, m))) }
                if (Math.abs(d) == 1) {
                    opts += Orn(v, k, NctType.ANT, k, b, listOf(Event(half, a), Event(half, b)))
                    note(a.diatonic - d).let { m -> opts += Orn(v, k, NctType.ESC, k, m, listOf(Event(half, a), Event(half, m))) }
                }
                if (v != 3) note(b.diatonic + s).let { m ->
                    if ((m.diatonic - a.diatonic) * s >= 2) opts += Orn(v, k, NctType.APP, k + 1, m, listOf(Event(half, m), Event(half, b)))
                }
                if (v != 3 && d == -1) {
                    opts += Orn(v, k, NctType.SUS, k + 1, a, listOf(Event(half, a), Event(half, b)))
                    // Ornamented: a leap down to a chord tone first (C–A–B)
                    note(b.diatonic - 1).takeIf { x -> x.pc in chords[k + 1].map { it.pc } }?.let { x ->
                        opts += Orn(v, k, NctType.SUS, k + 1, a, listOf(Event(half, a), Event(quarter, x), Event(quarter, b)))
                    }
                }
                if (v != 3 && d == 1 && b.midi - a.midi == 1) opts += Orn(v, k, NctType.RET, k + 1, a, listOf(Event(half, a), Event(half, b)))
                return opts.randomOrNull(r)
            }
            val voicesUsed = (0 until 4).shuffled(r).take(r.nextInt(1, 3))
            val orns = voicesUsed.mapNotNull { v -> ornament(v, r.nextInt(count - 1)) }
            if (orns.isEmpty() || orns.map { it.host }.distinct().size < orns.size) return@repeat
            val ok = orns.all { o ->
                val hostChord = chords[o.host]
                val sus = o.kind == NctType.SUS || o.kind == NctType.RET
                // The ornament must be a dissonance; a suspension against the bass (2nd, 4th, 7th)
                o.nct.pc !in hostChord.map { it.pc } && (!sus || Interval.between(hostChord[3], o.nct).simpleNumber in setOf(2, 4, 7)) &&
                    // Ambiguous by design: the ornament with the others makes another chord
                    ChordAnalyzer.analyze(hostChord.toMutableList().also { it[o.v] = o.nct }, key).quality == null
            }
            if (!ok) return@repeat
            val lines = List(4) { vi ->
                val o = orns.firstOrNull { it.v == vi }
                chords.flatMapIndexed { ci, c ->
                    when {
                        o == null -> listOf(Event(beat, c[vi]))
                        o.host == o.k && ci == o.k -> o.events
                        o.host == o.k + 1 && ci == o.k + 1 -> o.events
                        else -> listOf(Event(beat, c[vi]))
                    }
                }
            }
            for (o in orns) { checked++; byKind[o.kind] = (byKind[o.kind] ?: 0) + 1 }
            val sc = Score(key = key, time = time, voices = lines)
            val an = sc.analysis()
            val tag = orns.joinToString { "${it.kind}@v${it.v}c${it.host}" }
            val desc = { "#$n ${key.name} ${time.label} $tag ${an.points.map { an.slices[it].time to an.chords[it].roman }} ncts=${an.ncts} | ${lines.map { l -> l.joinToString(" ") { "${it.pitch}:${it.length}" } }}" }
            // Chord names at every chord's beat
            var t = Fraction.ZERO
            for ((ci, c) in plain.withIndex()) {
                val p = an.points.firstOrNull { an.slices[it].time == t }
                if (p == null) fails.putIfAbsent("$tag: no point at chord $ci", desc())
                else if (an.chords[p].root != c.root || an.chords[p].quality != c.quality)
                    fails.putIfAbsent("${orns.joinToString { it.kind.name }}: chord named ${an.chords[p].roman} instead of ${c.roman}", desc())
                t += beat
            }
            val refs = orns.map { o -> NoteRef(Voice.entries[o.v], o.k + 1) to o.kind }
            for ((ref, kind) in refs) {
                val found = an.ncts[ref]?.type
                if (found != kind) fails.putIfAbsent("$kind labelled $found", desc())
            }
            // No other note is a non-chord tone
            val others = an.ncts.keys - refs.map { it.first }.toSet()
            if (others.isNotEmpty()) fails.putIfAbsent("${orns.joinToString { it.kind.name }}: extra ncts", desc())
        }
        assertTrue("checked $checked\n" + fails.entries.joinToString("\n### ") { "${it.key}: ${it.value}" }, fails.isEmpty() && checked > 2000)
    }
}
