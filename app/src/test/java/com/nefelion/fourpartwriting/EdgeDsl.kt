package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.Chord
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.Nct
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.VoiceLeading

// Shared helpers for the *EdgeTest files.

val KC = Key(0, Mode.MAJOR)

fun chordOf(key: Key, vararg notes: String): Chord = ChordAnalyzer.analyze(notes.map { Pitch.parse(it) }, key)

/** One voice line: "C5:8 D5:4. r:4" (default length a quarter); any n is 1/n ("C5:12" is a triplet eighth), "C5:5/12" a plain fraction. */
fun voiceLine(s: String) = s.split(" ").map { tok ->
    val (p, d) = tok.split(":").let { it[0] to (it.getOrNull(1) ?: "4") }
    val dots = 1L shl d.count { it == '.' }
    val len = if ("/" in d) d.split("/").let { Fraction.of(it[0].toLong(), it[1].toLong()) }
        else Fraction.of(2 * dots - 1, d.trimEnd('.').toLong() * dots)
    Event(len, if (p == "r") null else Pitch.parse(p))
}

/** Voice lines S, A, T, B. */
fun linesScore(s: String, a: String, t: String, b: String, key: Key = KC, time: TimeSig = TimeSig(4, 4)) =
    Score(key = key, time = time, voices = listOf(voiceLine(s), voiceLine(a), voiceLine(t), voiceLine(b)))

/** Chords as "S A T B" columns, each token "C5" or "C5:8". */
fun chordsScore(key: Key, vararg chords: String, time: TimeSig = TimeSig(4, 4)): Score {
    val cols = chords.map { it.split(" ") }
    val v = (0 until 4).map { i -> cols.joinToString(" ") { it[i] } }
    return linesScore(v[0], v[1], v[2], v[3], key, time)
}

fun Score.analysis(): Analysis = VoiceLeading.check(this)
fun Analysis.errorRules() = issues.filter { it.severity == Severity.ERROR }.map { it.rule }
fun Analysis.rules() = issues.map { it.rule }
fun Analysis.messages() = issues.map { it.message }
fun Analysis.romans() = points.map { chords[it].roman }
fun Analysis.symbols() = points.map { chords[it].symbol }
fun Analysis.nct(v: Voice, i: Int): Nct? = ncts[NoteRef(v, i)]
fun Analysis.has(r: Rule) = r in rules()

/** Moves every pitch by [generic] letters and [semis] semitones; null if a spelling would need a triple accidental. */
fun Pitch.moved(generic: Int, semis: Int): Pitch? {
    val d = diatonic + generic
    val nat = Pitch.fromDiatonic(d, 0)
    val alter = midi + semis - nat.midi
    return if (alter in -2..2) Pitch.fromDiatonic(d, alter) else null
}

fun Score.transposedTo(target: Key): Score? {
    var g = Math.floorMod(target.tonic.step - key.tonic.step, 7)
    var s = Math.floorMod(target.tonic.semis - key.tonic.semis, 12)
    if (s > 6) { g -= 7; s -= 12 }
    val vs = voices.map { line -> line.map { e -> Event(e.length, e.pitch?.let { it.moved(g, s) ?: return null }) } }
    return copy(key = target, voices = vs)
}

fun Analysis.fingerprint() = Triple(
    issues.filter { it.rule != Rule.RANGE }.map { "${it.severity} ${it.slice} ${it.rule}" }.sorted(),
    // "Fr⁺⁶ (on G)" names a real note, which moves with the key
    romans().map { it.replace(Regex(" \\(on [^)]*\\)"), "(on)") },
    ncts.toSortedMap(compareBy({ it.voice }, { it.index })).toString(),
)
