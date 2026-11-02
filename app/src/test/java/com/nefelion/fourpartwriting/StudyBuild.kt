package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.export.MusicXml
import com.nefelion.fourpartwriting.model.Arrangement
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Rebuilds the bundled study scores (app/src/main/assets/study) from the BCFB dataset
 * (https://github.com/juyaolongpaul/Bach_chorale_FB, CC BY 4.0). Runs only when BCFB_DIR points at a checkout:
 * BCFB_DIR=... ./gradlew testDebugUnitTest --tests "*StudyBuild"
 * STUDY_REPORT, if set, gets every issue the checker finds, for picking the rule cards' examples.
 */
class StudyBuild {
    @Test fun build() {
        val dir = System.getenv("BCFB_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val titles = readTitles(File(dir, "reference_table.csv"))
        val out = File("src/main/assets/study")
        // The comments on Bach's rule breaks are written by hand: kept across a rebuild
        val why = out.listFiles().orEmpty().associate { f -> f.name to f.readLines().filter { it.startsWith("why ") } }
        out.deleteRecursively(); out.mkdirs()
        val report = StringBuilder()
        val src = File(dir, "FB_source/musicXML_master").listFiles { f -> f.name.endsWith(".musicxml") }!!.sortedBy { it.name }
        for (f in src) {
            val bwv = f.name.removePrefix("BWV_").removeSuffix("_FB.musicxml")
            // Second versions of the same chorale, and the instrumental chorales 250–252
            if (bwv.endsWith("b") || bwv in setOf("250", "251", "252")) continue
            val imp = MusicXml.read(f.readBytes()) ?: continue
            // More than a continuo beside the four voices: a chorale with obbligato instruments
            if (imp.note != null && !imp.note.startsWith("Only the top four voices were read; 1 more")) continue
            var sc = imp.score
            val bass = sc.voice(Voice.BASS).last { it.pitch != null }.pitch!!.pc.semis
            // Old modal signatures (G minor with one flat, G major with no sharp) get the modern one, so the checker reads the right key
            val key = listOf(sc.key, Key(sc.key.fifths, Mode.MINOR), Key(sc.key.fifths, Mode.MAJOR), Key(sc.key.fifths - 1, Mode.MINOR), Key(sc.key.fifths + 1, Mode.MAJOR))
                .filter { it.fifths in -7..7 }.firstOrNull { it.tonic.semis == bass }
                ?: if (bass == (Key(sc.key.fifths, Mode.MINOR).tonic.semis + 7) % 12) Key(sc.key.fifths, Mode.MINOR) else continue
            // Bach's figures are for the continuo player, who fills in what the four voices leave out: not checked against them
            sc = sc.copy(key = key, tempo = Math.round(76 / (sc.time.beatLength.toDouble() * 4)).toInt(), rulesOff = setOf(Rule.FIGURES.name))
                .withArrangement(Arrangement.CLOSED)
            val a = VoiceLeading.check(sc)
            val bars = sc.bars(sc.length).size
            // A lot of crossings, range or missing voices points at an encoding slip, not at Bach
            val odd = a.issues.count { it.rule == Rule.CROSSING || it.rule == Rule.RANGE || it.rule == Rule.MISSING_VOICE }
            if (odd > bars / 2) continue
            val number = bwv.substringBefore('a').let { n -> n.substringBefore('.') + (n.substringAfter('.', "").trimStart('0').let { if (it.isEmpty()) "" else ".$it" }) }
            val text = titles[bwv.removeSuffix("a")] ?: imp.title
            val title = "${TYPOS[text] ?: text} (BWV $number)"
            out.resolve("BWV $number.satb").writeText(ScoreCodec.encode(sc, title) + why["BWV $number.satb"].orEmpty().joinToString("") { it + "\n" })
            report.appendLine("== BWV $number · $title · ${sc.key.name} · $bars bars")
            if (System.getenv("STUDY_DETAIL") != null) for (k in a.points.takeLast(4)) {
                val sl = a.slices[k]
                report.appendLine("  end b${sc.barNumber(sl.time)}+${sc.inBar(sl.time)} " + sl.notes.joinToString(" ") { it?.name?.padEnd(4) ?: "-   " } + "  " + a.chords[k].roman)
            }
            for (i in a.issues) {
                val t = a.slices[i.slice].time
                report.appendLine("  bar ${sc.barNumber(t)} ${i.severity} ${i.rule.name}: ${i.message}")
                // The notes from the slice before the issue to two after, with chord names, to judge the spot by eye
                if (System.getenv("STUDY_DETAIL") != null) for (k in (i.slice - 1).coerceAtLeast(0)..minOf(i.slice + 2, a.slices.lastIndex)) {
                    val sl = a.slices[k]
                    report.appendLine("      ${if (k == i.slice) ">" else " "} b${sc.barNumber(sl.time)}+${sc.inBar(sl.time)} " +
                        sl.notes.joinToString(" ") { it?.name?.padEnd(4) ?: "-   " } + "  " + (a.chords.getOrNull(k)?.roman ?: ""))
                }
            }
        }
        System.getenv("STUDY_REPORT")?.let { File(it).writeText(report.toString()) }
    }

    /** Slips in the dataset's verse lines. */
    private val TYPOS = mapOf(
        "Christus, der uns delig macht" to "Christus, der uns selig macht",
        "Ehr sei Gott im dehn höchten Thron" to "Ehr sei Gott in dem höchsten Thron",
        "Richte dich, Liebste, nach neimen Gefalen und glaübe" to "Richte dich, Liebste, nach meinem Gefallen und gläube",
        "Mir mangelt zwar seht viel" to "Mir mangelt zwar sehr viel",
        "Erkerne mich, mein Hüter" to "Erkenne mich, mein Hüter",
        "Dein Will gessheh, Herr Gott, zugleich" to "Dein Will gescheh, Herr Gott, zugleich",
        "Schüttle deinen Kopf und Sprich" to "Schüttle deinen Kopf und sprich",
    )

    /** BWV number ("3.06") to the first line of the verse Bach set, from the dataset's table. */
    private fun readTitles(csv: File): Map<String, String> {
        // CSV with quoted cells that may hold commas and line breaks
        val rows = mutableListOf(mutableListOf(StringBuilder()))
        var quoted = false
        for (c in csv.readText().removePrefix("﻿").replace("\r", "")) when {
            c == '"' -> quoted = !quoted
            c == ',' && !quoted -> rows.last() += StringBuilder()
            c == '\n' && !quoted -> rows += mutableListOf(StringBuilder())
            else -> rows.last().last().append(c)
        }
        return rows.mapNotNull { r ->
            val bwv = r[0].toString().trimEnd('*').takeIf { it.isNotEmpty() && it[0].isDigit() } ?: return@mapNotNull null
            r.getOrNull(4)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { bwv to it }
        }.toMap()
    }
}
