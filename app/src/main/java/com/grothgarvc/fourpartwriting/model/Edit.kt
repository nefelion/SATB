package com.grothgarvc.fourpartwriting.model

/**
 * Score edits in "overwrite" style: changing a length never moves the timing of later notes,
 * so the four voices stay aligned. All functions are pure.
 */
object Edit {

    /** Adds an event at the end of [v], or at [at] when that is later (the gap becomes a rest). */
    fun append(s: Score, v: Voice, length: Fraction, pitch: Pitch?, at: Fraction? = null): Score {
        val gap = at?.let { it - s.length(v) }?.takeIf { it > Fraction.ZERO }
        val list = s.voice(v) + listOfNotNull(gap?.let { Event(it, null) }) + Event(length, pitch)
        return s.withVoice(v, mergeRests(list))
    }

    /**
     * Puts a note at [at] inside a rest of [v]. It takes [length] but at most what is left of the rest,
     * so later notes never move; the rest's other parts stay rests. Returns the score and the note's
     * index, or null when [at] is not in a rest.
     */
    fun fillRest(s: Score, v: Voice, at: Fraction, length: Fraction, pitch: Pitch): Pair<Score, Int>? {
        val i = s.eventAt(v, at).takeIf { it >= 0 } ?: return null
        val rest = s.voice(v)[i]
        if (rest.pitch != null) return null
        val start = s.onsets(v)[i]
        val end = start + rest.length
        val len = minOf(length, end - at)
        val before = Event(at - start, null).takeIf { it.length > Fraction.ZERO }
        val after = Event(end - at - len, null).takeIf { it.length > Fraction.ZERO }
        val list = s.voice(v).subList(0, i) + listOfNotNull(before, Event(len, pitch), after) + s.voice(v).subList(i + 1, s.voice(v).size)
        return s.withVoice(v, list) to i + (if (before != null) 1 else 0)
    }

    fun setPitch(s: Score, v: Voice, i: Int, pitch: Pitch?): Score {
        val list = s.voice(v).toMutableList()
        list[i] = list[i].copy(pitch = pitch)
        return s.withVoice(v, if (pitch == null) mergeRests(list) else list)
    }

    /**
     * Lengthening takes time from the following events (shortening or removing them);
     * shortening leaves a rest. The last event simply changes length.
     */
    fun setLength(s: Score, v: Voice, i: Int, length: Fraction): Score {
        val list = s.voice(v).toMutableList()
        val old = list[i].length
        list[i] = list[i].copy(length = length)
        if (length > old) {
            var debt = length - old
            var j = i + 1
            while (debt > Fraction.ZERO && j < list.size) {
                val e = list[j]
                if (e.length <= debt) { debt -= e.length; list.removeAt(j) }
                else { list[j] = e.copy(length = e.length - debt); debt = Fraction.ZERO }
            }
        } else if (length < old && i < list.lastIndex) {
            list.add(i + 1, Event(old - length, null))
        }
        return s.withVoice(v, mergeRests(list))
    }

    /**
     * Last event: removed. A note in the middle: becomes a rest. A rest in the middle:
     * removed, so later events move left ("close the gap").
     */
    fun delete(s: Score, v: Voice, i: Int): Score {
        val list = s.voice(v).toMutableList()
        when {
            i == list.lastIndex -> list.removeAt(i)
            list[i].pitch != null -> list[i] = list[i].copy(pitch = null)
            else -> list.removeAt(i)
        }
        return s.withVoice(v, mergeRests(list))
    }

    /**
     * Makes the part [from]..[to] of event [i] a rest; its other parts keep the note (deletes one head
     * of a tied note). A part at the very end of the voice is cut off instead.
     */
    fun restPart(s: Score, v: Voice, i: Int, from: Fraction, to: Fraction): Score {
        val list = s.voice(v).toMutableList()
        val e = list[i]
        val start = s.onsets(v)[i]
        val end = start + e.length
        val parts = listOfNotNull(
            Event(from - start, e.pitch).takeIf { from > start },
            Event(to - from, null).takeIf { i < list.lastIndex || to < end },
            Event(end - to, e.pitch).takeIf { to < end },
        )
        list.removeAt(i)
        list.addAll(i, parts)
        return s.withVoice(v, mergeRests(list))
    }

    /** Splits event [i] at [at] (inside it) into two events with its pitch: removes a tie. */
    fun untie(s: Score, v: Voice, i: Int, at: Fraction): Score {
        val list = s.voice(v).toMutableList()
        val e = list[i]
        val start = s.onsets(v)[i]
        list[i] = e.copy(length = at - start)
        list.add(i + 1, e.copy(length = start + e.length - at))
        return s.withVoice(v, list)
    }

    /** Ties note [i] to the next event: one event, pitch of [i], lengths added. */
    fun tie(s: Score, v: Voice, i: Int): Score {
        val list = s.voice(v).toMutableList()
        val a = list.getOrNull(i) ?: return s
        val b = list.getOrNull(i + 1) ?: return s
        if (a.pitch == null) return s
        list[i] = Event(a.length + b.length, a.pitch)
        list.removeAt(i + 1)
        return s.withVoice(v, list)
    }

    /**
     * Removes bar [bar] (0-based) from every voice; later music moves left. A note sounding across
     * the bar keeps its parts before and after it.
     */
    fun deleteMeasure(s: Score, bar: Int): Score {
        val ml = s.time.measure
        val b0 = ml * Fraction.of(bar.toLong(), 1)
        val b1 = b0 + ml
        var out = s
        for (v in Voice.entries) {
            val list = mutableListOf<Event>()
            var t = Fraction.ZERO
            for (e in s.voice(v)) {
                val end = t + e.length
                val before = minOf(end, b0) - t
                val after = end - maxOf(t, b1)
                val keep = maxOf(before, Fraction.ZERO) + maxOf(after, Fraction.ZERO)
                if (keep > Fraction.ZERO) list += e.copy(length = keep)
                t = end
            }
            out = out.withVoice(v, mergeRests(list))
        }
        return out
    }

    /** Drops last bars in which no voice has a note sounding (only rests), cutting the voices there. */
    fun dropEmptyLastBars(s: Score): Score {
        var sc = s
        val ml = s.time.measure
        while (sc.length > Fraction.ZERO) {
            val bars = (sc.length / ml).let { (it.num + it.den - 1) / it.den }
            val barStart = ml * Fraction.of(bars - 1, 1)
            val sounding = Voice.entries.any { v ->
                var t = Fraction.ZERO
                sc.voice(v).any { e -> val end = t + e.length; (e.pitch != null && end > barStart).also { t = end } }
            }
            if (sounding) break
            sc = Voice.entries.fold(sc) { acc, v ->
                var t = Fraction.ZERO
                val cut = acc.voice(v).mapNotNull { e ->
                    val start = t
                    t += e.length
                    when {
                        start >= barStart -> null
                        t > barStart -> e.copy(length = barStart - start)
                        else -> e
                    }
                }
                acc.withVoice(v, cut)
            }
        }
        return sc
    }

    /**
     * Onsets where all four voices start a note of the same length (a block chord) but the pitches
     * are not ordered S ≥ A ≥ T ≥ B, so they can be swapped without touching the rhythm.
     */
    fun misorderedChords(s: Score): List<Fraction> {
        val starts = Voice.entries.map { v -> s.onsets(v).withIndex().associate { (i, t) -> t to i } }
        return s.onsets(Voice.SOPRANO).filter { t ->
            val evs = Voice.entries.map { v -> starts[v.ordinal][t]?.let { s.voice(v)[it] } }
            if (evs.any { it?.pitch == null } || evs.map { it!!.length }.distinct().size > 1) return@filter false
            evs.zipWithNext().any { (up, lo) -> up!!.pitch!!.midi < lo!!.pitch!!.midi }
        }
    }

    /** Re-deals the pitches of the chords at [times] top to bottom: highest to S ... lowest to B. */
    fun sortChords(s: Score, times: List<Fraction>): Score {
        var out = s
        for (t in times) {
            val idx = Voice.entries.map { s.eventAt(it, t) }
            if (idx.any { it < 0 }) continue
            val pitches = Voice.entries.map { s.voice(it)[idx[it.ordinal]].pitch }
            if (pitches.any { it == null }) continue
            val desc = pitches.filterNotNull().sortedWith(compareByDescending<Pitch> { it.midi }.thenByDescending { it.diatonic })
            for (v in Voice.entries) out = setPitch(out, v, idx[v.ordinal], desc[v.ordinal])
        }
        return out
    }

    /** Adjacent rests become one rest; the engraver writes it out with correct values. */
    private fun mergeRests(list: List<Event>): List<Event> {
        val out = mutableListOf<Event>()
        for (e in list) {
            val last = out.lastOrNull()
            if (e.pitch == null && last != null && last.pitch == null) out[out.lastIndex] = Event(last.length + e.length, null)
            else out += e
        }
        return out
    }
}
