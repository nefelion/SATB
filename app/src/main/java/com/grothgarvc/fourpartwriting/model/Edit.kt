package com.grothgarvc.fourpartwriting.model

/**
 * Score edits in "overwrite" style: changing a length never moves the timing of later notes,
 * so the four voices stay aligned. All functions are pure.
 */
object Edit {

    fun append(s: Score, v: Voice, length: Fraction, pitch: Pitch?): Score =
        s.withVoice(v, mergeRests(s.voice(v) + Event(length, pitch)))

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
