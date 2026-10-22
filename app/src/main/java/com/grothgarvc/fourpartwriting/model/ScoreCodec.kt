package com.grothgarvc.fourpartwriting.model

/** Plain-text score file. One setting per line; one line per voice: "S E4:1/4 r:1/2 ...". */
object ScoreCodec {

    fun encode(s: Score): String = buildString {
        appendLine("v1")
        appendLine("key ${s.key.fifths} ${s.key.mode.name}")
        appendLine("time ${s.time.beats} ${s.time.beatUnit} ${s.time.symbol.name}")
        appendLine("tempo ${s.tempo}")
        appendLine("arr ${s.arrangement.name}")
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

    private fun alterCode(a: Int) = when (a) { -2 -> "bb"; -1 -> "b"; 1 -> "#"; 2 -> "x"; else -> "" }

    /** Returns null for anything that is not a valid score file. */
    fun decode(text: String): Score? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.first().trim() == "v1")
        val map = lines.drop(1).associate { l -> l.substringBefore(' ') to l.substringAfter(' ', "") }
        val k = map.getValue("key").split(" ")
        val t = map.getValue("time").split(" ")
        val arr = Arrangement.valueOf(map.getValue("arr").trim())
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
