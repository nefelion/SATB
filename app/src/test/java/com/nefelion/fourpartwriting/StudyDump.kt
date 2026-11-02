package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** STUDY_DUMP=dir: one file per study score with the issues still lacking a comment, the slices around them. */
class StudyDump {
    @Test fun dump() {
        val out = System.getenv("STUDY_DUMP")?.let(::File)
        assumeTrue(out != null)
        out!!.mkdirs()
        fun f(x: Fraction) = if (x.num == 0L) "0" else "${x.num}/${x.den}"
        for (file in File("src/main/assets/study").listFiles()!!.sortedBy { it.name }) {
            val text = file.readText()
            val sc = ScoreCodec.decode(text)!!
            val done = text.lines().filter { it.startsWith("why ") }.map { it.split(" ").take(4).drop(1).joinToString(" ") }.toSet()
            val a = VoiceLeading.check(sc)
            fun key(i: Int) = a.slices[a.issues[i].slice].time.let { t -> "${sc.barNumber(t)} ${f(sc.inBar(t))} ${a.issues[i].rule.name}" }
            val open = a.issues.indices.filter { key(it) !in done }
            if (open.isEmpty()) continue
            val bySlice = open.groupBy { a.issues[it].slice }
            val show = bySlice.keys.flatMap { (it - 3).coerceAtLeast(0)..minOf(it + 2, a.slices.lastIndex) }.toSortedSet()
            val r = StringBuilder("== ${file.name.removeSuffix(".satb")} · ${sc.key.name} · ${sc.time.beats}/${sc.time.beatUnit} · ${open.size} open\n")
            var last = -2
            for (k in show) {
                if (k != last + 1) r.appendLine("  …")
                last = k
                val sl = a.slices[k]
                val ferm = Voice.entries.any { v -> sl.eventIndex[v.ordinal].let { e -> e >= 0 && sc.voice(v)[e].fermata } }
                r.appendLine("  b${sc.barNumber(sl.time)}+${f(sc.inBar(sl.time))}${if (ferm) "^" else ""} " +
                    sl.notes.mapIndexed { v, p -> (if (sl.attacked[v]) "" else "~") + (p?.name ?: "-") }.joinToString(" ") { it.padEnd(5) } + " " + (a.chords.getOrNull(k)?.roman ?: ""))
                for (i in bySlice[k].orEmpty()) r.appendLine("    ! ${key(i)} · ${a.issues[i].severity.name[0]} ${a.issues[i].message}")
            }
            File(out, file.name.removeSuffix(".satb") + ".txt").writeText(r.toString())
        }
    }
}
