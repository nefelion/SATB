package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * STUDY_DUMP=dir: one file per study score with the issues still lacking a comment, the slices around them,
 * and the comments whose issue is gone (after a checker fix).
 */
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
            val keys = a.issues.indices.map(::key).toSet()
            val stale = text.lines().filter { it.startsWith("why ") && it.split(" ").take(4).drop(1).joinToString(" ") !in keys }
            if (open.isEmpty() && stale.isEmpty()) continue
            val bySlice = open.groupBy { a.issues[it].slice }
            val show = bySlice.keys.flatMap { (it - 3).coerceAtLeast(0)..minOf(it + 2, a.slices.lastIndex) }.toSortedSet()
            val r = StringBuilder("== ${file.name.removeSuffix(".satb")} · ${sc.key.name} · ${sc.time.beats}/${sc.time.beatUnit} · ${open.size} open\n")
            for (l in stale) r.appendLine("  - $l")
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

    /** STUDY_BARS="BWV 126.6 23 24" (several joined by ";"): every slice of those bars, with chords, NCT marks and issues. */
    @Test fun bars() {
        val spec = System.getenv("STUDY_BARS")
        assumeTrue(spec != null)
        fun f(x: Fraction) = if (x.num == 0L) "0" else "${x.num}/${x.den}"
        val r = StringBuilder()
        for (one in spec!!.split(";")) {
            val w = one.trim().split(" ")
            val id = w.dropLast(2).joinToString(" ")
            val (from, to) = w.takeLast(2).map { it.toInt() }
            val sc = ScoreCodec.decode(File("src/main/assets/study/$id.satb").readText())!!
            val a = VoiceLeading.check(sc)
            r.appendLine("== $id · ${sc.key.name} · ${sc.time.beats}/${sc.time.beatUnit}")
            for ((k, sl) in a.slices.withIndex()) {
                if (sc.barNumber(sl.time) !in from..to) continue
                val ferm = Voice.entries.any { v -> sl.eventIndex[v.ordinal].let { e -> e >= 0 && sc.voice(v)[e].fermata } }
                val marks = Voice.entries.map { v -> a.ncts[com.nefelion.fourpartwriting.engrave.NoteRef(v, sl.eventIndex[v.ordinal])]?.label ?: "" }
                r.appendLine("  b${sc.barNumber(sl.time)}+${f(sc.inBar(sl.time))}${if (ferm) "^" else ""} ${if (k in a.points) "*" else " "} " +
                    sl.notes.mapIndexed { v, p -> ((if (sl.attacked[v]) "" else "~") + (p?.name ?: "-") + (if (marks[v].isEmpty()) "" else "(${marks[v]})")).padEnd(11) }.joinToString("") +
                    " " + a.chords[k].roman + " [" + a.chords[k].symbol + "]")
                for (i in a.issues.filter { it.slice == k }) r.appendLine("      ! ${i.rule.name} ${i.severity.name[0]} ${i.message}")
            }
        }
        File(System.getenv("STUDY_DUMP") ?: "study-bars.txt").also { it.mkdirs() }.resolve("bars.txt").writeText(r.toString())
    }
}
