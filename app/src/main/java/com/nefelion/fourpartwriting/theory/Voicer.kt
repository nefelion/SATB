package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.IntervalQuality
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign

/**
 * Writes the missing voices of a four-part score. Every written note stays; each empty voice is filled after
 * its last note, in the rhythm of the longest voice. The chord at each beat comes from a chord name on a note
 * ("G7/B", "V65"), from the bass figures, or, with neither, is picked to fit the notes already there.
 * Of all voicings the cheapest path wins: forbidden motion (parallels, overlaps, unresolved tendency tones)
 * is ruled out between free notes, and smooth lines and good doubling are preferred.
 */
object Voicer {

    private const val NO = 1e6
    private const val BEAM = 300
    /** Cheapest voicings a fix runs the checker on. */
    private const val CHECKED = 25

    /** A chord possible at a point: [required] must sound; [bass] is the bass the name asks for, if any. */
    private class Option(val pcs: Set<PitchClass>, val required: Set<PitchClass>, val bass: PitchClass?, val chord: Chord,
                         val cost: Double, val inferred: Boolean)

    /** [fixed]: the written note of each voice S A T B sounding here (null: to be filled). */
    private class Point(val fixed: List<Pitch?>, val options: List<Option>)

    private class State(val opt: Option, val v: List<Pitch>, val cost: Double, val from: Int)

    /** Diatonic chord on [degree] (0..6) in [key]: root, third, fifth (and seventh); V and vii° use the leading tone in minor. */
    fun diatonic(key: Key, degree: Int, seventh: Boolean): List<PitchClass> {
        val raise = key.mode == Mode.MINOR && degree in setOf(4, 6)
        return (0 until if (seventh) 4 else 3).map { k ->
            val pc = key.degree(degree + 2 * k)
            if (raise && pc.step == key.leadingTone.step) key.leadingTone else pc
        }
    }

    /** The score with its missing voices written; the same score if nothing is missing; null if no voicing fits. */
    fun fill(score: Score): Score? = (attempt(score) as? Outcome.Done)?.score

    sealed interface Outcome {
        data class Done(val score: Score) : Outcome
        /** No clean voicing reaches the beat at [time] (the written notes up to it block every way on). */
        data class Stuck(val time: Fraction) : Outcome
        data object Empty : Outcome
    }

    fun attempt(score: Score): Outcome {
        val key = score.key
        val rhythm = Voice.entries.filter { v -> score.voice(v).any { it.pitch != null } }
            .maxWithOrNull(compareBy({ score.length(it) }, { when (it) { Voice.SOPRANO -> 2; Voice.BASS -> 1; else -> 0 } })) ?: return Outcome.Empty
        val total = score.length(rhythm)
        val toFill = Voice.entries.filter { score.length(it) < total }
        if (toFill.isEmpty()) return Outcome.Done(score)
        val evs = score.voice(rhythm)
        val on = score.onsets(rhythm)
        val idx = evs.indices.filter { evs[it].pitch != null }
        val figured = score.voice(Voice.BASS).any { it.figures.isNotEmpty() } || Voice.BASS in score.given
        val points = idx.mapIndexed { k, i ->
            val t = on[i]
            // shortcut: a rest in a written voice counts as a free note here (not written); fine for chorale-style parts
            val fixed = Voice.entries.map { v -> if (t < score.length(v)) score.eventAt(v, t).takeIf { it >= 0 }?.let { score.voice(v)[it].pitch } else null }
            Point(fixed, optionsAt(score, t, fixed, figured, last = k == idx.lastIndex, penultimate = k == idx.lastIndex - 1))
        }
        val path = search(points, key).let { (p, stuck) -> p ?: return Outcome.Stuck(on[idx[stuck]]) }
        val upper = Voice.entries.map { v ->
            if (v !in toFill) return@map score.voice(v)
            val start = score.length(v)
            val out = score.voice(v).toMutableList()
            val firstOn = on.getOrNull(evs.indices.firstOrNull { on[it] >= start } ?: -1) ?: total
            if (firstOn > start) out += Event(firstOn - start, null)
            for (i in evs.indices) {
                if (on[i] < start) continue
                val k = idx.indexOf(i)
                out += Event(evs[i].length, if (k >= 0) path[k][v.ordinal] else null, evs[i].tuplet)
            }
            out
        }
        return Outcome.Done(score.copy(voices = upper))
    }

    /** Chords possible at [t]: the chord named on a note there, else the bass figures, else diatonic chords holding the [fixed] notes. */
    private fun optionsAt(score: Score, t: Fraction, fixed: List<Pitch?>, figured: Boolean, last: Boolean, penultimate: Boolean): List<Option> {
        val key = score.key
        val named = Voice.entries.firstNotNullOfOrNull { v ->
            val j = score.eventAt(v, t)
            score.voice(v).getOrNull(j)?.takeIf { score.onsets(v)[j] == t }?.chord
        }?.let { ChordText.parse(it, key) }
        val bassEvent = score.eventAt(Voice.BASS, t).takeIf { it >= 0 && t < score.length(Voice.BASS) }?.let { score.voice(Voice.BASS)[it] }
        return when {
            named != null -> listOf(option(named.pcs, named.bass.takeIf { b -> fixed[3] == null || fixed[3]!!.pc == b }, key, 0.0, false))
            figured && bassEvent?.pitch != null -> {
                val b = bassEvent.pitch
                val above = FiguredBass.realize(b, bassEvent.figures, key).values
                val spec = ChordAnalyzer.analyze(listOf(b) + above.map { Pitch(it.step, it.alter, 4) }, key)
                listOf(option(listOf(b.pc) + above.filter { it != b.pc }, b.pc, key, 0.0, false, spec))
            }
            else -> inferred(key, fixed, last, penultimate)
        }
    }

    /**
     * The score with the chord at [t] revoiced: of the cheapest clean voicings between its neighbours, the one
     * the checker ([rules]) flags least, then the one that changes the fewest notes, by the smallest steps.
     * Only notes of [movable] voices struck at [t] move (by default all but given voices; never notes held into it).
     * The chord keeps its name or figures; with neither, it may keep its written harmony or take another diatonic chord.
     * The same score if nothing beats the written chord; null if nothing at [t] can move or no clean voicing fits.
     */
    fun fix(score: Score, t: Fraction, rules: Set<Rule> = Rule.entries.toSet(),
            movable: Set<Voice> = Voice.entries.toSet() - score.given): Score? {
        val key = score.key
        fun sounding(at: Fraction) = Voice.entries.map { v -> score.eventAt(v, at).takeIf { it >= 0 }?.let { score.voice(v)[it].pitch } }
        val struck = Voice.entries.filter { v ->
            val j = score.eventAt(v, t)
            v in movable && j >= 0 && score.onsets(v)[j] == t && score.voice(v)[j].pitch != null
        }
        if (struck.isEmpty()) return null
        val times = Voice.entries.flatMap { v -> score.onsets(v).filterIndexed { i, _ -> score.voice(v)[i].pitch != null } }.distinct().sorted()
        val k = times.indexOf(t)
        val figured = score.voice(Voice.BASS).any { it.figures.isNotEmpty() } || Voice.BASS in score.given
        val written = sounding(t)
        val free = written.mapIndexed { i, p -> p.takeIf { Voice.entries[i] !in struck } }
        val opts = optionsAt(score, t, free, figured, last = k == times.lastIndex, penultimate = k == times.lastIndex - 1)
        // The written harmony (a chromatic one too) stays possible when it is a full chord
        val own = actual(written, key).takeIf { it.chord.recognized && it.chord.third != null && opts.all { o -> o.inferred } }
        val prev = times.getOrNull(k - 1)?.let(::sounding)
        val next = times.getOrNull(k + 1)?.let(::sounding)
        val prevOpt = prev?.let { actual(it, key) }
        val nextOpt = next?.let { actual(it, key) }
        val found = mutableListOf<Pair<Double, List<Pitch>>>()
        for (opt in opts + listOfNotNull(own)) {
            val cadential = opt.chord.degree == 0 && opt.chord.inversion == 2
            for (c in voicings(opt, free)) {
                // A voice resting next to the chord counts as holding its note: no motion to judge
                fun around(n: List<Pitch?>) = n.mapIndexed { i, p -> p ?: c[i] }
                var cost = opt.cost + node(c, opt, free, cadential) + away(c, written)
                if (prev != null) cost += move(around(prev), c, around(prev), free, prevOpt!!, opt) + progression(prevOpt, opt, key)
                if (next != null) cost += move(c, around(next), free, around(next), opt, nextOpt!!)
                if (cost < NO) found += cost to c
            }
        }
        if (found.isEmpty()) return null
        fun with(c: List<Pitch>) = score.copy(voices = Voice.entries.map { voice ->
            if (voice !in struck) return@map score.voice(voice)
            val j = score.eventAt(voice, t)
            score.voice(voice).toMutableList().also { it[j] = it[j].copy(pitch = c[voice.ordinal]) }
        })
        // The checker covers what the costs above don't (tendency tones, leaps, cross relations ...); the whole
        // score is checked, so only the difference this chord makes ranks the candidates.
        // shortcut: checks the whole score per candidate; crop to the bars around [t] if long scores get slow
        fun flags(sc: Score) = VoiceLeading.check(sc, rules).issues.sumOf { if (it.severity == Severity.ERROR) 1000.0 else 50.0 }
        val best = found.distinctBy { it.second }.sortedBy { it.first }.take(CHECKED)
            .map { (cost, c) -> with(c).let { Triple(it, flags(it), cost) } }.minBy { it.second + it.third }
        // Your chord stays unless a candidate is flagged less
        return if (best.second < flags(score)) best.first else score
    }

    /**
     * [solved] with every flagged chord the solver wrote revoiced by [fix], in two passes over the checker's
     * findings ([rules]). Notes already written in [mine] (and given voices) stay.
     */
    fun polish(solved: Score, mine: Score, rules: Set<Rule> = Rule.entries.toSet()): Score {
        var cur = solved
        repeat(2) {
            val a = VoiceLeading.check(cur, rules)
            val times = a.issues.flatMap { i -> listOf(i.slice) + i.notes.map { it.first } }
                .mapNotNull { a.slices.getOrNull(it)?.time }.distinct().sorted()
            if (times.isEmpty()) return cur
            for (t in times) {
                val movable = Voice.entries.filter { mine.length(it) <= t }.toSet()
                if (movable.isNotEmpty()) cur = fix(cur, t, rules, movable) ?: cur
            }
        }
        return cur
    }

    /** The chord the written [notes] spell, keeping its bass. */
    private fun actual(notes: List<Pitch?>, key: Key): Option {
        val ps = notes.filterNotNull()
        val chord = ChordAnalyzer.analyze(ps, key)
        return option(ps.map { it.pc }.distinct(), chord.bass, key, 0.0, false, chord)
    }

    private fun option(pcs: List<PitchClass>, bass: PitchClass?, key: Key, cost: Double, inferred: Boolean, known: Chord? = null): Option {
        val chord = known ?: ChordAnalyzer.analyze(listOf(Pitch((bass ?: pcs[0]).step, (bass ?: pcs[0]).alter, 3)) + pcs.map { Pitch(it.step, it.alter, 4) }, key)
        val fifth = pcs.firstOrNull { chord.member(it) == 4 }
        // A seventh chord may leave out its fifth
        val optional = if (chord.quality?.seventh == true && fifth != null && fifth != bass) setOf(fifth) else emptySet()
        return Option(pcs.toSet(), pcs.toSet() - optional, bass, chord, cost, inferred)
    }

    /** Diatonic triads and common sevenths holding every written note, cheapest for the most usual chords. */
    private fun inferred(key: Key, fixed: List<Pitch?>, last: Boolean, penultimate: Boolean): List<Option> {
        val minor = key.mode == Mode.MINOR
        // (degree, seventh, cost)
        val list = listOf(
            Triple(0, false, 0.0), Triple(3, false, 0.5), Triple(4, false, 0.0), Triple(1, false, if (minor) 3.0 else 1.0),
            Triple(5, false, 2.0), Triple(2, false, 5.0), Triple(6, false, 4.0),
            Triple(4, true, 1.0), Triple(1, true, 3.0), Triple(6, true, if (minor) 3.0 else 6.0),
        )
        val pcsHere = fixed.filterNotNull().map { it.pc }.toSet()
        return list.mapNotNull { (d, sev, c) ->
            val pcs = diatonic(key, d, sev)
            if (!pcs.containsAll(pcsHere)) return@mapNotNull null
            var cost = c
            if (last && d != 0) cost += 25
            if (penultimate && d != 4 && d != 6) cost += 6
            option(pcs, null, key, cost, true)
        }
    }

    /** The cheapest voicing per point, or null and the index of the first point no clean voicing reaches. */
    private fun search(points: List<Point>, key: Key): Pair<List<List<Pitch>>?, Int> {
        val layers = mutableListOf<List<State>>()
        for ((i, pt) in points.withIndex()) {
            val prev = layers.lastOrNull()
            val states = mutableListOf<State>()
            for (opt in pt.options) {
                val cadential = opt.chord.degree == 0 && opt.chord.inversion == 2
                for (v in voicings(opt, pt.fixed)) {
                    val own = opt.cost + node(v, opt, pt.fixed, cadential)
                    if (prev == null) { states += State(opt, v, own, -1); continue }
                    var best = NO; var from = -1
                    for ((j, p) in prev.withIndex()) {
                        if (p.cost >= best) continue
                        val t = p.cost + own + move(p.v, v, points[i - 1].fixed, pt.fixed, p.opt, opt) + progression(p.opt, opt, key)
                        if (t < best) { best = t; from = j }
                    }
                    if (from >= 0 && best < NO) states += State(opt, v, best, from)
                }
            }
            if (states.isEmpty()) return null to i
            layers += states.sortedBy { it.cost }.take(BEAM)
        }
        var s = layers.last().first()
        val out = ArrayList<List<Pitch>>()
        for (i in layers.indices.reversed()) { out += s.v; if (i > 0) s = layers[i - 1][s.from] }
        return out.reversed() to -1
    }

    private fun cands(pcs: Set<PitchClass>, v: Voice) = pcs.flatMap { pc -> (1..5).map { Pitch(pc.step, pc.alter, it) } }
        .filter { it.midi in VoiceLeading.RANGES.getValue(v) }

    /** Voicings S A T B over [fixed] notes: in range, uncrossed, spaced, spelling the chord, tendency tones single. */
    private fun voicings(o: Option, fixed: List<Pitch?>): List<List<Pitch>> {
        fun list(v: Voice) = fixed[v.ordinal]?.let { listOf(it) }
            ?: cands(if (v == Voice.BASS && o.bass != null) setOf(o.bass) else o.pcs, v)
        fun free(v: Int) = fixed[v] == null
        val out = mutableListOf<List<Pitch>>()
        val lt = o.chord.leadingTone
        val seventh = o.chord.seventh
        for (b in list(Voice.BASS)) for (tn in list(Voice.TENOR)) {
            if ((free(2) || free(3)) && (tn.midi <= b.midi || Interval.between(b, tn).number > 12)) continue
            for (a in list(Voice.ALTO)) {
                if ((free(1) || free(2)) && (a.midi <= tn.midi || a.midi - tn.midi > 12)) continue
                for (s in list(Voice.SOPRANO)) {
                    if ((free(0) || free(1)) && (s.midi <= a.midi || s.midi - a.midi > 12)) continue
                    val v = listOf(s, a, tn, b)
                    val pcs = v.map { it.pc }
                    if (!pcs.containsAll(o.required)) continue
                    fun single(pc: PitchClass?) = pc == null || pcs.count { it == pc } < 2 || v.indices.none { free(it) && pcs[it] == pc }
                    if (!single(lt) || !single(seventh)) continue
                    out += v
                }
            }
        }
        return out
    }

    /** Doubling (root, then fifth, then third; a tonic ⁶₄ doubles its bass) and, for a chosen chord, its inversion. */
    private fun node(v: List<Pitch>, o: Option, fixed: List<Pitch?>, cadential: Boolean): Double {
        val pcs = v.map { it.pc }
        val bass = pcs[3]
        var c = 0.0
        for (pc in pcs.groupingBy { it }.eachCount().filter { it.value > 1 }.keys) c += when {
            cadential && pc == bass -> 0.0
            else -> when (o.chord.member(pc)) { 0 -> 0.0; 4 -> 2.0; 2 -> 4.0; else -> 6.0 }
        }
        if (cadential && pcs.count { it == bass } < 2) c += 4
        if (o.inferred || (o.bass == null && fixed[3] == null)) c += when (o.chord.member(bass)) { 0 -> 0.0; 2 -> 3.0; 6 -> 4.0; else -> 12.0 }
        return c
    }

    /** Each changed note costs more than a smoother line saves, so a fix moves as few voices as it can. */
    private fun away(v: List<Pitch>, near: List<Pitch?>) = v.indices.sumOf { i ->
        val n = near[i]
        if (n == null || n == v[i]) 0.0 else 25.0 + abs(v[i].midi - n.midi)
    }

    /** Root motion in the key: strong progressions cheap, retrogressions dear. Only guides chords the app picks. */
    private fun progression(a: Option, b: Option, key: Key): Double {
        if (!b.inferred) return 0.0
        val ra = a.chord.root ?: return 0.0
        val rb = b.chord.root ?: return 0.0
        val da = Math.floorMod(ra.step - key.tonic.step, 7)
        val db = Math.floorMod(rb.step - key.tonic.step, 7)
        if (ra == rb) return 3.0
        if ((da == 4 || da == 6) && db in setOf(1, 3)) return 12.0
        if (da == 1 && db in setOf(0, 3)) return 8.0
        return when (Math.floorMod(db - da, 7)) { 3 -> 0.0; 1, 5 -> 1.0; else -> 3.0 }
    }

    /** Cost of moving [p] → [c]; rules are only enforced where a free note is involved (written notes are the user's). */
    private fun move(p: List<Pitch>, c: List<Pitch>, fp: List<Pitch?>, fc: List<Pitch?>, tp: Option, tc: Option): Double {
        fun held(v: Int) = fp[v] != null && fc[v] != null
        var cost = 0.0
        for (i in 0 until 4) for (j in i + 1 until 4) {
            if (held(i) && held(j)) continue
            if (p[i] == c[i] && p[j] == c[j]) continue
            val a = Math.floorMod(p[i].midi - p[j].midi, 12)
            val b = Math.floorMod(c[i].midi - c[j].midi, 12)
            if (a == b && (a == 0 || a == 7)) return NO
        }
        val dirs = IntArray(4) { (c[it].midi - p[it].midi).sign }
        for (v in 0 until 4) {
            if (held(v)) continue
            val d = c[v].midi - p[v].midi
            if (d == 0) continue
            val iv = Interval.between(p[v], c[v])
            if (iv.quality == IntervalQuality.A || iv.quality == IntervalQuality.AA || iv.simpleNumber == 7 || iv.number > 8) return NO
            if (iv.quality == IntervalQuality.D || iv.quality == IntervalQuality.DD) cost += 20
            if (v == 3) { cost += abs(d) * 0.3; continue } // the bass may leap
            cost += abs(d)
            if (abs(d) > 4) cost += 6
            // Overlap: past the previous note of a neighbour
            if (c[v].midi < p[v + 1].midi) return NO
            if (v > 0 && c[v].midi > p[v - 1].midi) return NO
        }
        if (!(held(3) && held(2)) && c[3].midi > p[2].midi) return NO
        // Outer voices: no leap of the soprano in similar motion into a fifth or octave
        val outer = Math.floorMod(c[0].midi - c[3].midi, 12)
        if (!(held(0) && held(3)) && (outer == 0 || outer == 7) && dirs[0] == dirs[3] && dirs[0] != 0 && abs(c[0].midi - p[0].midi) > 2) return NO
        if (dirs.all { it == 1 } || dirs.all { it == -1 }) cost += 15
        // The leading tone of a resolving dominant rises to its target; the seventh falls by step
        val lt = tp.chord.leadingTone
        if (lt != null && tc.chord.root == tp.chord.target) for (v in 0 until 3) if (p[v].pc == lt && !held(v)) {
            if (c[v].pc != tp.chord.target || c[v].midi < p[v].midi) cost += if (v == 0) NO else 40.0
        }
        val sev = tp.chord.seventh
        if (sev != null) for (v in 0 until 4) if (p[v].pc == sev && !held(v) && p[v].diatonic - c[v].diatonic != 1) return NO
        return cost
    }
}
