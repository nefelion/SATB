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
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Random scores: the checker must not crash and its output must be self-consistent. */
class FuzzEdgeTest {
    private val meters = listOf(TimeSig(4, 4), TimeSig(3, 4), TimeSig(2, 2), TimeSig(6, 8), TimeSig(3, 8), TimeSig(2, 4), TimeSig(12, 8))
    private val durations = listOf(Duration(1), Duration(2), Duration(2, 1), Duration(4), Duration(4, 1), Duration(8), Duration(8, 1), Duration(16))
    private val centers = listOf(Pitch.parse("C5"), Pitch.parse("F4"), Pitch.parse("C4"), Pitch.parse("F3"))

    private fun randomScore(r: Random): Score {
        val key = Key(r.nextInt(-7, 8), if (r.nextBoolean()) Mode.MAJOR else Mode.MINOR)
        val time = meters.random(r)
        val bars = r.nextInt(1, 4)
        val homophonic = r.nextInt(3) == 0
        val rhythm = mutableListOf<Fraction>()
        fun fill(total: Fraction): List<Fraction> {
            val out = mutableListOf<Fraction>()
            var left = total
            while (left > Fraction.ZERO) {
                val d = durations.filter { it.value <= left }.random(r).value
                out += d; left -= d
            }
            return out
        }
        val total = time.measure * Fraction.of(bars.toLong(), 1)
        if (homophonic) rhythm += fill(total)
        val voices = Voice.entries.map { v ->
            val lens = if (homophonic) rhythm else fill(total).let { l -> if (r.nextInt(10) == 0) l.dropLast(1).ifEmpty { l } else l }
            lens.map { len ->
                if (r.nextInt(10) == 0) Event(len, null) else {
                    val d = centers[v.ordinal].diatonic + r.nextInt(-5, 6)
                    val step = Math.floorMod(d, 7)
                    val alter = (key.sigAlter(step) + if (r.nextInt(6) == 0) listOf(-1, 1).random(r) else 0).coerceIn(-2, 2)
                    Event(len, Pitch.fromDiatonic(d, alter))
                }
            }
        }
        return Score(key = key, time = time, voices = voices)
    }

    @Test fun randomScoresGiveConsistentAnalyses() {
        val r = Random(20261025)
        // One example per kind of failure, so a run shows every distinct problem
        val fails = LinkedHashMap<String, String>()
        fun check(ok: Boolean, kind: String, detail: () -> String) { if (!ok) fails.putIfAbsent(kind, detail()) }
        repeat(10000) { n ->
            val sc = randomScore(r)
            val a = try { VoiceLeading.check(sc) } catch (e: Throwable) {
                fails.putIfAbsent("crash ${e.javaClass.simpleName} at ${e.stackTrace.firstOrNull()}", "#$n $sc")
                return@repeat
            }
            check(a.slices.size == a.chords.size, "chords size") { "#$n" }
            check(a.slices.isEmpty() || a.points.firstOrNull() == 0, "first point") { "#$n" }
            check(a.points.sorted().distinct() == a.points, "points order") { "#$n" }
            for (iss in a.issues) {
                check(iss.slice in a.slices.indices, "issue slice ${iss.rule}") { "#$n $iss" }
                for ((si, v) in iss.notes) {
                    check(si in a.slices.indices, "highlight slice ${iss.rule}") { "#$n $iss" }
                    if (si in a.slices.indices && iss.rule != Rule.MISSING_VOICE)
                        check(a.slices[si].notes[v.ordinal] != null, "highlight on rest ${iss.rule}") { "#$n $iss | $sc" }
                }
            }
            for (ref in a.ncts.keys) check(sc.voice(ref.voice).getOrNull(ref.index)?.pitch != null, "NCT on rest") { "#$n $ref" }
            // Muting every issue hides all of them, and no mute is pruned while its issue stays
            val muted = sc.copy(ignored = a.issues.map { VoiceLeading.ignoreKey(it, a.slices) }.toSet())
            val left = VoiceLeading.check(muted).issues
            check(left.isEmpty(), "mute leaks ${left.firstOrNull()?.rule}") { "#$n $left" }
            check(muted.ignored == VoiceLeading.pruneMutes(muted).ignored, "prune drops live mute") { "#$n" }
            // Same analysis an octave higher, and with every note value doubled
            val base = a.fingerprint()
            val up = sc.copy(voices = sc.voices.map { l -> l.map { e -> e.copy(pitch = e.pitch?.let { Pitch.fromDiatonic(it.diatonic + 7, it.alter) }) } })
            check(up.analysis().fingerprint() == base, "octave shift changes analysis") { "#$n ${up.analysis().fingerprint()} vs $base | $sc" }
            val two = Fraction.of(2, 1)
            val aug = sc.copy(time = TimeSig(sc.time.beats, sc.time.beatUnit / 2), voices = sc.voices.map { l -> l.map { it.copy(length = it.length * two) } })
            check(aug.analysis().fingerprint() == base, "augmentation changes analysis") { "#$n ${aug.analysis().fingerprint()} vs $base | $sc" }
            // Same analysis in another key of the same mode
            val k = Key(r.nextInt(-7, 8), sc.key.mode)
            sc.transposedTo(k)?.let { t ->
                val got = t.analysis().fingerprint()
                if (got != base) {
                    val diff = (base.first - got.first.toSet()).map { "-$it" } + (got.first - base.first.toSet()).map { "+$it" }
                    val kind = when {
                        diff.isNotEmpty() -> "transposition changes issues ${diff.map { it.substringAfterLast(' ') }.distinct()}"
                        base.second != got.second -> "transposition changes romans"
                        else -> "transposition changes NCTs"
                    }
                    check(false, kind) { "#$n ${sc.key.name} → ${k.name}: $diff | ${base.second} vs ${got.second} | $sc" }
                }
            }
        }
        assertTrue(fails.entries.joinToString(" ### ") { "${it.key}: ${it.value}" }, fails.isEmpty())
    }
}
