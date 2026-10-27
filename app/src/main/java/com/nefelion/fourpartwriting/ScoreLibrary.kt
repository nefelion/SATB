package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import java.io.File

/** Saved scores: one "<name>.satb" file each in [dir]; the file name is the score's name. */
class ScoreLibrary(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(name: String) = File(dir, "$name$EXT")

    /** Names, most recently changed first. */
    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
        .sortedByDescending { it.lastModified() }.map { it.name.removeSuffix(EXT) }

    /** Every score with its contents, for the list's details. */
    // shortcut: reads all files on the calling (main) thread; fine for a few hundred small scores, move to IO if libraries grow large
    fun list(): List<Pair<String, Score?>> = names().map { it to read(it) }

    fun exists(name: String) = file(name).exists()

    fun read(name: String): Score? = runCatching { ScoreCodec.decode(file(name).readText()) }.getOrNull()

    /** Writes through a temporary file, so a crash mid-write keeps the old version. */
    fun write(name: String, score: Score): Boolean = runCatching {
        val tmp = File(dir, "$name$EXT.tmp")
        tmp.writeText(ScoreCodec.encode(score))
        check(tmp.renameTo(file(name)))
    }.isSuccess

    fun rename(from: String, to: String) = from == to || (!exists(to) && file(from).renameTo(file(to)))

    fun delete(name: String) = file(name).delete()

    /** [wanted] made safe as a file name, plus " (2)", " (3)" … if a score already has it. */
    fun freeName(wanted: String): String {
        val base = clean(wanted)
        if (!exists(base)) return base
        return generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { !exists(it) }
    }

    companion object {
        const val EXT = ".satb"

        fun clean(name: String): String =
            name.filterNot { it in "/\\:*?\"<>|" || it.isISOControl() }.trim().trimStart('.').take(60).trim().ifEmpty { "Untitled" }
    }
}
