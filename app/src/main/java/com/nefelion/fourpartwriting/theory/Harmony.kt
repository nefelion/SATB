package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Slice
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign

enum class NctType(val label: String, val full: String) {
    PT("PT", "passing tone"),
    NT("NT", "neighbour tone"),
    SUS("sus", "suspension"),
    RET("ret", "retardation"),
    APP("app", "appoggiatura"),
    ESC("esc", "escape tone"),
    CAMB("camb", "cambiata"),
    ANT("ant", "anticipation"),
    PED("ped", "pedal"),
    UNRESOLVED("!", "unresolved dissonance"),
}

/** A non-chord tone; [label] is what is printed (e.g. "PT", "4–3"). */
data class Nct(val type: NctType, val label: String)

/**
 * Harmony of a (possibly polyphonic) score: chords are named at chord points (beats, or where
 * 3+ voices move together); every other note that does not belong to the chord is classified
 * as a non-chord tone from how its own voice approaches and leaves it.
 */
data class Harmony(
    val points: List<Int>,
    /** For every slice: the chord in effect (the chord of the governing chord point). */
    val chordOf: List<Chord>,
    val ncts: Map<NoteRef, Nct>,
)

object HarmonyAnalyzer {

    private val V = Voice.entries

    fun analyze(score: Score, slices: List<Slice>): Harmony {
        val beat = score.time.beatLength
        val points = slices.indices.filter { i ->
            val s = slices[i]
            // A rest in every voice is silence, not a chord
            i == 0 || s.pitches.isNotEmpty() && ((s.time / beat).den == 1L || V.count { s.attacked[it.ordinal] && s.notes[it.ordinal] != null } >= 3)
        }
        val ncts = HashMap<NoteRef, Nct>()
        val pointChord = HashMap<Int, Chord>()

        fun pitchOf(v: Voice, idx: Int): Pitch? = score.voice(v).getOrNull(idx)?.pitch
        fun ref(s: Slice, v: Voice) = NoteRef(v, s.eventIndex[v.ordinal])
        /** Index of the first note before ([dir] -1) or after (+1) event [idx] that is not the same pitch struck again. */
        fun distinct(v: Voice, idx: Int, dir: Int): Int {
            var j = idx + dir
            while (j in score.voice(v).indices && pitchOf(v, j) == pitchOf(v, idx)) j += dir
            return j
        }
        fun around(v: Voice, idx: Int, dir: Int) = pitchOf(v, distinct(v, idx, dir))
        /**
         * A non-chord tone attacked as event [idx]: an anticipation when it (or the same pitch struck again)
         * still sounds at the next chord point [np] and belongs to its chord; else from its melodic shape.
         */
        fun figure(v: Voice, idx: Int, np: Int?, nextChord: Chord?): Nct {
            val p = pitchOf(v, idx)!!
            val last = distinct(v, idx, 1) - 1
            if (np != null && nextChord != null && slices[np].eventIndex[v.ordinal] in idx..last && isChordTone(nextChord, p))
                return Nct(NctType.ANT, "ant")
            return classify(around(v, idx, -1), p, around(v, idx, 1), around(v, distinct(v, idx, 1), 1))
        }

        // Chords at chord points, removing accented non-chord tones when needed
        for (p in points) {
            val s = slices[p]
            val voices = V.filter { s.notes[it.ordinal] != null }
            val direct = ChordAnalyzer.analyze(voices.map { s.notes[it.ordinal]!! }, score.key)
            val directQ = direct.quality
            // A complete triad needs no explanation; sevenths, incomplete and sus chords may hide a suspension.
            if (directQ != null && !directQ.seventh && !directQ.incomplete && !directQ.name.contains("SUS")) { pointChord[p] = direct; continue }

            // Candidate accented NCTs per voice, with the pitch that replaces them in the chord
            data class Cand(val v: Voice, val nct: Nct, val replace: Pitch?, val rank: Int)
            val cands = mutableListOf<Cand>()
            val bass = s.notes[Voice.BASS.ordinal]
            for (v in voices) {
                val i = s.eventIndex[v.ordinal]
                val cur = s.notes[v.ordinal]!!
                val prev = pitchOf(v, i - 1)
                val next = pitchOf(v, i + 1)
                // Struck again counts as held only when prepared at the previous chord point (not after an anticipation)
                val prevPoint = points.lastOrNull { it < p }
                // The preparation is the first of the repeated notes (C C | C is prepared by the first C)
                var first = i - 1
                while (first > 0 && pitchOf(v, first - 1) == cur) first--
                // (or, where the notes make no chord but a sus chord, on an off-beat as a tone of the previous chord: D–C | C–B over I | V)
                val prepared = prevPoint != null && (score.onsets(v).getOrNull(first)?.let { it <= slices[prevPoint].time } == true ||
                    (directQ == null || directQ.name.contains("SUS")) && pointChord[prevPoint]?.takeIf { it.recognized }?.let { isChordTone(it, cur) } == true)
                val held = !s.attacked[v.ordinal] || (prev == cur && prepared)
                val dIn = prev?.let { motion(it, cur) }
                val dOut = next?.let { motion(cur, it) }
                // Attacked figures count a note struck again once (C–D–D–E is a passing tone)
                val pIn = around(v, i, -1)
                val nOut = around(v, i, 1)
                val aIn = pIn?.let { motion(it, cur) }
                val aOut = nOut?.let { motion(cur, it) }
                // An ornamented resolution first leaps to another note, then steps down (C–A–B over G)
                // (the leaped-to note may be struck again first)
                var j2 = i + 2
                while (next != null && pitchOf(v, j2) == next) j2++
                val next2 = pitchOf(v, j2)
                // (the ornament comes before the next chord point)
                val ornamented = held && dOut != null && abs(dOut) > 1 && next2 != null && motion(cur, next2) == -1 &&
                    points.firstOrNull { it > p }.let { np -> np == null || score.onsets(v)[i + 1] < slices[np].time }
                val res = if (ornamented) next2 else next
                val dRes = res?.let { motion(cur, it) }
                // A suspension resolves over the same bass; resolving only when the bass moves on is not one
                val resAt = score.onsets(v).getOrNull(if (ornamented) j2 else i + 1)
                // (a suspended bass resolves under upper voices that hold; an upper voice's bass may leap an octave)
                val sameBass = resAt == null || if (v == Voice.BASS) V.all { o -> o == v || score.eventAt(o, resAt).let { e -> e >= 0 && score.voice(o)[e].pitch == s.notes[o.ordinal] } }
                    else slices.filter { it.time >= s.time && it.time <= resAt }.all { it.notes[Voice.BASS.ordinal]?.pc == bass?.pc }
                // Only a dissonance against the bass (2nd/9th, 4th, 7th) is suspended
                val dissonant = v == Voice.BASS || bass == null || Interval.between(bass, cur).simpleNumber in setOf(2, 4, 7)
                // In a real seventh chord a re-struck note is a chord tone unless it is the 7th/9th itself
                val suspendable = held && sameBass && dissonant && (directQ == null || directQ.name.contains("SUS") ||
                    !s.attacked[v.ordinal] || direct.member(cur.pc) == 6 || direct.member(cur.pc) == 1)
                when {
                    suspendable && dRes == -1 -> cands += Cand(v, Nct(NctType.SUS, susFigure(v, cur, res!!, bass)), res, 0)
                    // A retardation rises a half step (7–8: leading tone to tonic); a whole step up is just a moving voice
                    suspendable && dOut == 1 && v != Voice.BASS && next!!.midi - cur.midi == 1 -> cands += Cand(v, Nct(NctType.RET, susFigure(v, cur, next!!, bass)), next, 1)
                    !held && aIn != null && abs(aIn) > 1 && aOut != null && abs(aOut) == 1 && aIn.sign != aOut.sign ->
                        cands += Cand(v, Nct(NctType.APP, "app"), nOut, 2)
                    !held && aIn != null && abs(aIn) == 1 && aOut != null && abs(aOut) == 1 && aIn.sign == aOut.sign ->
                        cands += Cand(v, Nct(NctType.PT, "PT"), nOut, 3)
                    // Accented neighbour: steps off a note on the beat and back to it
                    !held && aIn != null && abs(aIn) == 1 && nOut == pIn ->
                        cands += Cand(v, Nct(NctType.NT, "NT"), nOut, 3)
                    v == Voice.BASS && !s.attacked[v.ordinal] -> cands += Cand(v, Nct(NctType.PED, "ped"), null, 4)
                    // Inverted pedal: an upper voice holds through other chords and is a chord tone again where it ends
                    !s.attacked[v.ordinal] && points.last { slices[it].eventIndex[v.ordinal] == i }.let { lp ->
                        lp != p && isChordTone(ChordAnalyzer.analyze(slices[lp].pitches, score.key), cur)
                    } -> cands += Cand(v, Nct(NctType.PED, "ped"), null, 4)
                    // Its next note belongs to a later chord, so it is dropped, not replaced
                    held -> cands += Cand(v, Nct(NctType.UNRESOLVED, "!"), null, 5)
                }
            }
            fun tryCombos(combos: List<List<Cand>>, triadOnly: Boolean): Pair<Chord, List<Cand>>? {
                for (combo in combos) {
                    val notes = voices.mapNotNull { v ->
                        val c = combo.firstOrNull { it.v == v }
                        if (c == null) s.notes[v.ordinal] else c.replace
                    }
                    if (notes.size < 2) continue
                    val ch = ChordAnalyzer.analyze(notes, score.key)
                    val q = ch.quality ?: continue
                    if (q.name.contains("SUS") || (triadOnly && (q.seventh || q.incomplete))) continue
                    return ch to combo
                }
                return null
            }
            val singles = cands.sortedBy { it.rank }.map { listOf(it) }
            val pairs = cands.flatMapIndexed { a, x -> cands.drop(a + 1).map { y -> listOf(x, y) } }.sortedBy { it.sumOf { c -> c.rank } }
            val prevChord = points.lastOrNull { it < p }?.let { pointChord[it] }?.takeIf { it.recognized }
            // No new harmony: one or two voices ornament the previous chord while another holds it
            // (C–B–A over a held C chord), each resolving by the next chord point; a member left out
            // for a moment (C–E–C over held C–G) is still heard
            fun continued(): Pair<Chord, List<Cand>>? {
                val pc = prevChord ?: return null
                val foreign = voices.filter { !isChordTone(pc, s.notes[it.ordinal]!!) }
                if (foreign.size > 2 || voices.all { s.attacked[it.ordinal] }) return null
                val np = points.firstOrNull { it > p }
                val nextChord = np?.let { ChordAnalyzer.analyze(slices[it].pitches, score.key) }
                return pc to foreign.map { v ->
                    val idx = s.eventIndex[v.ordinal]
                    if (!s.attacked[v.ordinal] || pitchOf(v, idx - 1) == pitchOf(v, idx)) return null
                    val f = figure(v, idx, np, nextChord)
                    // A note held into the next chord belongs to it; an anticipation is short and struck again there
                    if (f.type == NctType.ANT && (slices[np!!].eventIndex[v.ordinal] == idx || score.voice(v)[idx].length > beat)) return null
                    val resolved = f.type == NctType.ANT || np == null ||
                        score.onsets(v).getOrNull(distinct(v, idx, 1))?.let { it <= slices[np].time } == true
                    if (f.type == NctType.UNRESOLVED || !resolved) return null
                    Cand(v, f, null, 3)
                }
            }
            // A passing seventh over a held dominant (V8–7) makes V⁷
            val passingSeventh = directQ in setOf(Quality.DOM7, Quality.DOM7_NO5) && direct.root == prevChord?.root
            // A recognized seventh/incomplete chord stays unless a suspension turns it into a complete triad (7–6, 9–8 ...)
            val best = if (directQ != null && !directQ.name.contains("SUS"))
                tryCombos((singles + pairs).filter { combo -> combo.all { it.rank <= 1 } }, triadOnly = true)
                    ?: if (passingSeventh) null else continued()
            // A complete triad (e.g. from a double suspension) beats a seventh chord left by one resolution;
            // without a suspension (or when every note still belongs to it), ornaments of the previous chord come first
            else continued()?.takeIf { c -> cands.none { it.rank <= 1 } || c.second.isEmpty() }
                ?: tryCombos(singles + pairs, triadOnly = true)
                // Dropping a held note (pedal, unresolved) must leave a complete triad
                ?: tryCombos((singles + pairs).filter { combo -> combo.all { it.rank < 4 } }, triadOnly = false)
                // No chord of its own: the previous chord continues if the explained NCTs are all that is foreign to it
                ?: prevChord?.let { prevChord ->
                    (singles + pairs).filter { combo -> combo.all { it.rank <= 4 } }.firstOrNull { combo ->
                        voices.all { v -> combo.any { it.v == v } || isChordTone(prevChord, s.notes[v.ordinal]!!) }
                    }?.let { prevChord to it }
                }
            if (best != null) {
                pointChord[p] = best.first
                for (c in best.second) ncts[ref(s, c.v)] = c.nct
            } else {
                pointChord[p] = direct
                // A sus chord whose 4th (or 2nd) is held and does not resolve: unresolved suspension
                if (directQ != null && directQ.name.contains("SUS")) for (v in voices) {
                    val n = s.notes[v.ordinal]!!
                    val g = direct.member(n.pc)
                    if ((g == 3 || g == 1) && cands.any { it.v == v }) ncts[ref(s, v)] = Nct(NctType.UNRESOLVED, "!")
                }
            }
        }

        // Everything between chord points
        val chordOf = ArrayList<Chord>(slices.size)
        var cur = 0
        var silent = false
        for (i in slices.indices) {
            if (i in pointChord) { cur = i; silent = false }
            // After a rest in every voice the chord is over: notes before the next chord point belong to none
            if (slices[i].pitches.isEmpty()) silent = true
            val chord = pointChord.getValue(cur)
            chordOf += chord
            if (i == cur || silent || !chord.recognized) continue
            val s = slices[i]
            val np = points.firstOrNull { it > i }
            val nextPoint = np?.let { pointChord[it] }
            for (v in V) {
                if (!s.attacked[v.ordinal]) continue
                val p = s.notes[v.ordinal] ?: continue
                if (isChordTone(chord, p)) continue
                val idx = s.eventIndex[v.ordinal]
                ncts[NoteRef(v, idx)] = figure(v, idx, np, nextPoint)
            }
        }
        return Harmony(points, chordOf, ncts)
    }

    /** Pitch class belongs to the chord by spelling (works also when the chord omits it here). */
    private fun isChordTone(c: Chord, p: Pitch): Boolean {
        val root = c.root ?: return false
        val (g, s) = com.nefelion.fourpartwriting.model.pcInterval(root, p.pc)
        val q = c.quality!!
        // An incomplete chord's omitted fifth still belongs to it
        return q.members[g] == s || g == 0 && s == 0 || q.incomplete && g == 4 && s == 7 && 4 !in q.members
    }

    /** Letter distance from [a] to [b]; a chromatic semitone on one letter (C–C♯) counts as a step. */
    private fun motion(a: Pitch, b: Pitch): Int {
        val d = b.diatonic - a.diatonic
        return if (d == 0) (b.midi - a.midi).sign else d
    }

    private fun classify(prev: Pitch?, cur: Pitch, next: Pitch?, next2: Pitch?): Nct {
        val dIn = prev?.let { motion(it, cur) }
        val dOut = next?.let { motion(cur, it) }
        return when {
            // Steps down, leaps down a third, then steps back up (E–D–B–C)
            dIn == -1 && dOut == -2 && next2 != null && motion(next!!, next2) == 1 -> Nct(NctType.CAMB, "camb")
            dIn != null && dOut != null && abs(dIn) == 1 && abs(dOut) == 1 && dIn.sign == dOut.sign -> Nct(NctType.PT, "PT")
            dIn != null && dOut != null && abs(dIn) == 1 && abs(dOut) == 1 && next == prev -> Nct(NctType.NT, "NT")
            dIn != null && dOut != null && abs(dIn) == 1 && abs(dOut) > 1 && dIn.sign != dOut.sign -> Nct(NctType.ESC, "esc")
            dIn != null && dOut != null && abs(dIn) > 1 && abs(dOut) == 1 && dIn.sign != dOut.sign -> Nct(NctType.APP, "app")
            else -> Nct(NctType.UNRESOLVED, "!")
        }
    }

    /** "4–3", "7–6", "9–8", "7–8" (retardation); a suspended bass is "2–3". */
    private fun susFigure(v: Voice, cur: Pitch, res: Pitch, bass: Pitch?): String {
        if (v == Voice.BASS || bass == null) return "2–3"
        fun simple(p: Pitch) = Interval.between(bass, p).simpleNumber
        val a = simple(cur); val b = simple(res)
        val top = if (a == 2) 9 else a
        val bot = if (b == 1) 8 else b
        return "$top–$bot"
    }
}
