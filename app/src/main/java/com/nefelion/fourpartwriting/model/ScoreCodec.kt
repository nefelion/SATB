package com.nefelion.fourpartwriting.model

/** Plain-text score file. One setting per line; one line per voice: "S E4:1/4 r:1/2 ...". */
object ScoreCodec {

    fun encode(s: Score): String = buildString {
        appendLine("v1")
        appendLine("key ${s.key.fifths} ${s.key.mode.name}")
        appendLine("time ${s.time.beats} ${s.time.beatUnit} ${s.time.symbol.name}")
        appendLine("tempo ${s.tempo}")
        appendLine("arr " + s.arrangement.staffOf.joinToString(" "))
        appendLine("clefs " + s.clefs.joinToString(" ") { it.name })
        for (v in Voice.entries) {
            append(v.label)
            for (e in s.voice(v)) {
                append(' ')
                append(e.pitch?.let { p -> "${"CDEFGAB"[p.step]}${alterCode(p.alter)}${p.octave}" } ?: "r")
                append(':').append(e.length.num).append('/').append(e.length.den)
            }
            appendLine()
        }
    }

    /** Undo and redo stacks: each score follows a "#U" or "#R" line. */
    fun encodeHistory(undo: List<Score>, redo: List<Score>): String = buildString {
        for (sc in undo) { appendLine("#U"); append(encode(sc)) }
        for (sc in redo) { appendLine("#R"); append(encode(sc)) }
    }

    /** Entries that do not decode are skipped. */
    fun decodeHistory(text: String): Pair<List<Score>, List<Score>> {
        val undo = mutableListOf<Score>(); val redo = mutableListOf<Score>()
        var target: MutableList<Score>? = null
        val buf = StringBuilder()
        fun flush() { target?.let { t -> decode(buf.toString())?.let(t::add) }; buf.clear() }
        for (line in text.lines()) when (line.trim()) {
            "#U" -> { flush(); target = undo }
            "#R" -> { flush(); target = redo }
            else -> buf.appendLine(line)
        }
        flush()
        return undo to redo
    }

    private fun alterCode(a: Int) = when (a) { -2 -> "bb"; -1 -> "b"; 1 -> "#"; 2 -> "x"; else -> "" }

    /** Returns null for anything that is not a valid score file. */
    fun decode(text: String): Score? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.first().trim() == "v1")
        val map = lines.drop(1).associate { l -> l.substringBefore(' ') to l.substringAfter(' ', "") }
        val k = map.getValue("key").split(" ")
        val t = map.getValue("time").split(" ")
        val arrText = map.getValue("arr").trim()
        // Files from older versions store a preset name
        val legacy = mapOf("ONE" to Arrangement.ONE, "CLOSED" to Arrangement.CLOSED, "THREE" to Arrangement.THREE, "OPEN" to Arrangement.OPEN)
        val arr = legacy[arrText] ?: Arrangement(arrText.split(" ").map { it.toInt() })
        val clefs = map.getValue("clefs").trim().split(" ").map { Clef.valueOf(it) }
        val voices = Voice.entries.map { v ->
            map[v.label].orEmpty().trim().split(" ").filter { it.isNotEmpty() }.map { tok ->
                val (p, len) = tok.split(":")
                val (n, d) = len.split("/").map { it.toLong() }
                require(n > 0 && d > 0)
                Event(Fraction.of(n, d), if (p == "r") null else Pitch.parse(p))
            }
        }
        Score(
            key = Key(k[0].toInt(), Mode.valueOf(k[1])),
            time = TimeSig(t[0].toInt(), t[1].toInt(), TimeSig.Symbol.valueOf(t[2])),
            tempo = map.getValue("tempo").trim().toInt().coerceIn(20, 300),
            arrangement = arr,
            clefs = clefs,
            voices = voices,
        )
    }.getOrNull()
}
