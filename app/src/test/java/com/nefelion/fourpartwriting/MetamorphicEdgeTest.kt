package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Edits that change nothing musically must not change the analysis. */
class MetamorphicEdgeTest {
    private val meters = listOf(TimeSig(4, 4), TimeSig(3, 4), TimeSig(6, 8), TimeSig(2, 2))
    private val durations = listOf(Duration(2), Duration(4), Duration(4), Duration(4), Duration(4, 1), Duration(8))
    private val centers = listOf(Pitch.parse("C5"), Pitch.parse("F4"), Pitch.parse("C4"), Pitch.parse("F3"))

    /** Mostly diatonic random voices, often in one rhythm, whole bars. */
    private fun randomScore(r: Random): Score {
        val key = Key(r.nextInt(-7, 8), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
        val time = meters.random(r)
        val total = time.measure * Fraction.of(r.nextInt(1, 4).toLong(), 1)
        fun fill(): List<Fraction> {
            val out = mutableListOf<Fraction>()
            var left = total
            while (left > Fraction.ZERO) {
                val d = durations.filter { it.value <= left }.random(r).value
                out += d; left -= d
            }
            return out
        }
        val shared = fill()
        val voices = Voice.entries.map { v ->
            (if (r.nextInt(3) > 0) shared else fill()).map { len ->
                if (r.nextInt(15) == 0) Event(len, null) else {
                    val d = centers[v.ordinal].diatonic + r.nextInt(-4, 5)
                    val step = Math.floorMod(d, 7)
                    val alter = key.sigAlter(step) + if (r.nextInt(8) == 0) listOf(-1, 1).random(r) else 0
                    Event(len, Pitch.fromDiatonic(d, alter.coerceIn(-2, 2)))
                }
            }
        }
        return Score(key = key, time = time, voices = voices)
    }

    /** What the user sees, independent of slice and event numbering. */
    /** [shift]: time the music was moved later; silence before it is left out. */
    private fun Analysis.view(sc: Score, shift: Fraction = Fraction.ZERO) = Triple(
        issues.map { "${it.severity} ${it.rule}: ${it.message}" }.toSet(),
        points.map { slices[it].time - shift to chords[it].roman }.filter { it.first >= Fraction.ZERO },
        ncts.map { (ref, n) -> "${ref.voice} ${sc.onsets(ref.voice)[ref.index] - shift} ${n.label}" }.toSet(),
    )

    private fun diff(a: Triple<Set<String>, List<Any>, Set<String>>, b: Triple<Set<String>, List<Any>, Set<String>>): String = when {
        a.first != b.first -> "issues " + ((a.first - b.first).map { "-" + it.substringBefore(':') } + (b.first - a.first).map { "+" + it.substringBefore(':') })
        a.second != b.second -> "romans"
        else -> "ncts " + ((a.third - b.third).map { "-" + it.substringAfterLast(' ') } + (b.third - a.third).map { "+" + it.substringAfterLast(' ') })
    }

    private fun lines(sc: Score) = sc.voices.joinToString(" / ") { l -> l.joinToString(" ") { "${it.pitch ?: "r"}:${it.length.num}/${it.length.den}" } }

    private fun run(n: Int, seed: Int, shift: (Score) -> Fraction = { Fraction.ZERO }, edit: (Random, Score, Analysis) -> Score?) {
        val r = Random(seed)
        val fails = LinkedHashMap<String, String>()
        repeat(n) { k ->
            val sc = randomScore(r)
            val a = sc.analysis()
            val ed = edit(r, sc, a) ?: return@repeat
            val before = a.view(sc)
            val after = ed.analysis().view(ed, shift(sc))
            if (before != after) {
                val d = diff(before, after)
                fails.putIfAbsent(d, "#$k ${sc.key.name} ${sc.time.label} | $before\nvs $after\n${lines(sc)}\n=> ${lines(ed)}")
            }
        }
        assertTrue(fails.entries.joinToString("\n### ") { "${it.key}: ${it.value}" }, fails.isEmpty())
    }

    /** A chord tone struck again halfway (nothing else happening meanwhile, off the beat) is the same music. */
    @Test fun restrikingAChordTone() = run(4000, 1) { r, sc, a ->
        val times = a.slices.map { it.time }.toSet()
        val cands = Voice.entries.flatMap { v ->
            val on = sc.onsets(v)
            sc.voice(v).indices.filter { i ->
                val e = sc.voice(v)[i]
                val mid = on[i] + e.length * Fraction.of(1, 2)
                e.pitch != null && com.nefelion.fourpartwriting.engrave.NoteRef(v, i) !in a.ncts &&
                    times.none { it > on[i] && it < on[i] + e.length } && (mid / sc.time.beatLength).den != 1L
            }.map { v to it }
        }
        val (v, i) = cands.randomOrNull(r) ?: return@run null
        val e = sc.voice(v)[i]
        val half = e.length * Fraction.of(1, 2)
        sc.withVoice(v, sc.voice(v).toMutableList().also { it[i] = e.copy(length = half); it.add(i + 1, e.copy(length = half)) })
    }

    /** A bar of rest at the end changes nothing. */
    @Test fun appendingARestBar() = run(4000, 2) { _, sc, _ ->
        sc.copy(voices = sc.voices.map { it + Event(sc.time.measure, null) })
    }

    /** A rest written as two rests (split off the beat) is the same silence. */
    @Test fun splittingARest() = run(4000, 3) { r, sc, _ ->
        val cands = Voice.entries.flatMap { v ->
            val on = sc.onsets(v)
            sc.voice(v).indices.filter { i ->
                val e = sc.voice(v)[i]
                e.pitch == null && ((on[i] + e.length * Fraction.of(1, 2)) / sc.time.beatLength).den != 1L
            }.map { v to it }
        }
        val (v, i) = cands.randomOrNull(r) ?: return@run null
        val e = sc.voice(v)[i]
        val half = e.length * Fraction.of(1, 2)
        sc.withVoice(v, sc.voice(v).toMutableList().also { it[i] = e.copy(length = half); it.add(i + 1, e.copy(length = half)) })
    }

    /** A bar of rest before the music only moves it one bar later. */
    @Test fun prependingARestBar() = run(4000, 4, { it.time.measure }) { _, sc, _ ->
        sc.copy(voices = sc.voices.map { listOf(Event(sc.time.measure, null)) + it })
    }
}
