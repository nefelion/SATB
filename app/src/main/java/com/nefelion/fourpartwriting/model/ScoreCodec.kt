package com.nefelion.fourpartwriting.model

/** Plain-text score file. One setting per line; one line per voice: "S E4:1/4 r:1/2 ...". A tuplet adds ":n:m:unit" ("E4:1/20:5:4:1/16"),
 * bass figures "@lines" ("E3:1/4@6,4"). */
object ScoreCodec {

    /** [title]: the score's name, kept in files saved for sharing so an import gets it back. */
    fun encode(s: Score, title: String? = null): String = buildString {
        appendLine("v2")
        title?.let { appendLine("title " + it.replace('\n', ' ')) }
        appendLine("key ${s.key.fifths} ${s.key.mode.name}")
        appendLine("time ${s.time.beats} ${s.time.beatUnit} ${s.time.symbol.name}")
        appendLine("tempo ${s.tempo}")
        appendLine("arr " + s.arrangement.staffOf.joinToString(" "))
        appendLine("clefs " + s.clefs.joinToString(" ") { it.name })
        if (s.rulesOff.isNotEmpty()) appendLine("off " + s.rulesOff.joinToString(" "))
        if (s.given.isNotEmpty()) appendLine("given " + s.given.joinToString(" ") { it.label })
        for (k in s.ignored) appendLine("ignore " + k.replace('\n', ' '))
        for (v in Voice.entries) {
            append(v.label)
            for (e in s.voice(v)) {
                append(' ')
                append(e.pitch?.let { p -> "${"CDEFGAB"[p.step]}${alterCode(p.alter)}${p.octave}" } ?: "r")
                append(':').append(e.length.num).append('/').append(e.length.den)
                e.tuplet?.let { append(":${it.n}:${it.m}:${it.unit.num}/${it.unit.den}") }
                if (e.figures.isNotEmpty()) append('@').append(e.figures.joinToString(","))
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

    /** The name stored by [encode], if any. */
    fun title(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("title ") }?.removePrefix("title ")?.trim()?.ifEmpty { null }

    private fun alterCode(a: Int) = when (a) { -2 -> "bb"; -1 -> "b"; 1 -> "#"; 2 -> "x"; else -> "" }

    /** Returns null for anything that is not a valid score file. */
    fun decode(text: String): Score? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }
        val version = lines.first().trim()
        require(version == "v1" || version == "v2")
        val map = lines.drop(1).associate { l -> l.substringBefore(' ') to l.substringAfter(' ', "") }
        // Optional lines; "ignore" repeats, so it is not read from the map
        val ignored = lines.filter { it.startsWith("ignore ") }.map { it.removePrefix("ignore ") }.toSet()
        val k = map.getValue("key").split(" ")
        val t = map.getValue("time").split(" ")
        val arrText = map.getValue("arr").trim()
        // Files from older versions store a preset name
        val legacy = mapOf("ONE" to Arrangement.ONE, "CLOSED" to Arrangement.CLOSED, "THREE" to Arrangement.THREE, "OPEN" to Arrangement.OPEN)
        val arr = legacy[arrText] ?: Arrangement(arrText.split(" ").map { it.toInt() })
        val clefs = map.getValue("clefs").trim().split(" ").map { Clef.valueOf(it) }
        val voices = Voice.entries.map { v ->
            map[v.label].orEmpty().trim().split(" ").filter { it.isNotEmpty() }.map { token ->
                val tok = token.substringBefore('@')
                val figs = token.substringAfter('@', "").split(",").filter { it.isNotEmpty() }
                val parts = tok.split(":")
                fun frac(x: String) = x.split("/").map { it.toLong() }.let { (n, d) -> require(n > 0 && d > 0); Fraction.of(n, d) }
                val tup = if (parts.size >= 5) Tuplet(parts[2].toInt(), parts[3].toInt(), frac(parts[4])) else null
                Event(frac(parts[1]), if (parts[0] == "r") null else Pitch.parse(parts[0]), tup, figs)
            }
        }
        val time = TimeSig(t[0].toInt(), t[1].toInt(), TimeSig.Symbol.valueOf(t[2]))
        val tempo = map.getValue("tempo").trim().toInt()
        Score(
            key = Key(k[0].toInt(), Mode.valueOf(k[1])),
            time = time,
            // v1 stored quarter notes per minute; v2 stores beats
            tempo = (if (version == "v1") Math.round(tempo / (time.beatLength.toDouble() * 4)).toInt() else tempo).coerceIn(20, 300),
            arrangement = arr,
            clefs = clefs,
            voices = voices,
            rulesOff = map["off"].orEmpty().split(" ").filter { it.isNotBlank() }.toSet(),
            ignored = ignored,
            given = map["given"].orEmpty().split(" ").mapNotNull { l -> Voice.entries.firstOrNull { it.label == l } }.toSet(),
        )
    }.getOrNull()
}
