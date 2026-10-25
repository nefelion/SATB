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
            i == 0 || (s.time / beat).den == 1L || s.attacked.count { it } >= 3
        }
        val ncts = HashMap<NoteRef, Nct>()
        val pointChord = HashMap<Int, Chord>()

        fun pitchOf(v: Voice, idx: Int): Pitch? = score.voice(v).getOrNull(idx)?.pitch
        fun ref(s: Slice, v: Voice) = NoteRef(v, s.eventIndex[v.ordinal])

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
                val held = !s.attacked[v.ordinal] || prev == cur
                val dIn = prev?.let { motion(it, cur) }
                val dOut = next?.let { motion(cur, it) }
                // A suspension resolves over the same bass; resolving only when the bass moves on is not one
                val resAt = score.onsets(v).getOrNull(i + 1)
                val sameBass = v == Voice.BASS || resAt == null ||
                    score.eventAt(Voice.BASS, resAt).let { b -> b >= 0 && score.voice(Voice.BASS)[b].pitch == bass }
                when {
                    held && sameBass && dOut == -1 -> cands += Cand(v, Nct(NctType.SUS, susFigure(v, cur, next!!, bass)), next, 0)
                    held && sameBass && dOut == 1 -> cands += Cand(v, Nct(NctType.RET, susFigure(v, cur, next!!, bass)), next, 1)
                    !held && dIn != null && abs(dIn) > 1 && dOut != null && abs(dOut) == 1 && dIn.sign != dOut.sign ->
                        cands += Cand(v, Nct(NctType.APP, "app"), next, 2)
                    !held && dIn != null && abs(dIn) == 1 && dOut != null && abs(dOut) == 1 && dIn.sign == dOut.sign ->
                        cands += Cand(v, Nct(NctType.PT, "PT"), next, 3)
                    // Accented neighbour: steps off a note on the beat and back to it
                    !held && dIn != null && abs(dIn) == 1 && next == prev ->
                        cands += Cand(v, Nct(NctType.NT, "NT"), next, 3)
                    v == Voice.BASS && !s.attacked[v.ordinal] -> cands += Cand(v, Nct(NctType.PED, "ped"), null, 4)
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
            // A recognized seventh/incomplete chord stays unless a suspension turns it into a complete triad (7–6, 9–8 ...)
            val best = if (directQ != null && !directQ.name.contains("SUS"))
                tryCombos(singles.filter { it[0].rank <= 1 }, triadOnly = true)
            else tryCombos(singles + pairs, triadOnly = false)
                // No chord of its own: the previous chord continues if the explained NCTs are all that is foreign to it
                ?: points.lastOrNull { it < p }?.let { pointChord[it] }?.takeIf { it.recognized }?.let { prevChord ->
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
        for (i in slices.indices) {
            if (i in pointChord) cur = i
            val chord = pointChord.getValue(cur)
            chordOf += chord
            if (i == cur || !chord.recognized) continue
            val s = slices[i]
            val nextPoint = points.firstOrNull { it > i }?.let { pointChord[it] }
            for (v in V) {
                if (!s.attacked[v.ordinal]) continue
                val p = s.notes[v.ordinal] ?: continue
                if (isChordTone(chord, p)) continue
                val idx = s.eventIndex[v.ordinal]
                val prev = pitchOf(v, idx - 1)
                val next = pitchOf(v, idx + 1)
                ncts[NoteRef(v, idx)] = classify(prev, p, next, nextPoint)
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

    private fun classify(prev: Pitch?, cur: Pitch, next: Pitch?, nextChord: Chord?): Nct {
        val dIn = prev?.let { motion(it, cur) }
        val dOut = next?.let { motion(cur, it) }
        return when {
            next == cur && nextChord != null && isChordTone(nextChord, cur) -> Nct(NctType.ANT, "ant")
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
