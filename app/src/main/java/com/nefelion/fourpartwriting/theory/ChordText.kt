package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.PitchClass

/** A chord the user named: its pitch classes, root first, and the bass when the name fixes it (an inversion or slash). */
data class ChordSpec(val pcs: List<PitchClass>, val bass: PitchClass?)

/**
 * Reads a chord name: a symbol ("C", "F#m", "Bb7", "G7/B", "Cmaj7", "Bm7b5", "Bdim7", "Eaug", "Dsus4")
 * or a roman numeral in [key] ("V", "ii6", "V65", "vii°7", "bVI", "V7/V", "iiø43"). Null if it is neither.
 */
object ChordText {

    /** Intervals above the root as (generic, semitones), by quality. */
    private val QUALITIES = mapOf(
        "" to listOf(2 to 4, 4 to 7), "m" to listOf(2 to 3, 4 to 7), "dim" to listOf(2 to 3, 4 to 6), "aug" to listOf(2 to 4, 4 to 8),
        "7" to listOf(2 to 4, 4 to 7, 6 to 10), "maj7" to listOf(2 to 4, 4 to 7, 6 to 11), "m7" to listOf(2 to 3, 4 to 7, 6 to 10),
        "m7b5" to listOf(2 to 3, 4 to 6, 6 to 10), "dim7" to listOf(2 to 3, 4 to 6, 6 to 9), "mmaj7" to listOf(2 to 3, 4 to 7, 6 to 11),
        "sus4" to listOf(3 to 5, 4 to 7), "sus2" to listOf(1 to 2, 4 to 7), "7sus4" to listOf(3 to 5, 4 to 7, 6 to 10),
    )
    private val ALIASES = mapOf(
        "M" to "", "maj" to "", "min" to "m", "-" to "m", "°" to "dim", "o" to "dim", "+" to "aug",
        "M7" to "maj7", "Δ" to "maj7", "Δ7" to "maj7", "min7" to "m7", "-7" to "m7", "ø" to "m7b5", "ø7" to "m7b5", "m7♭5" to "m7b5",
        "°7" to "dim7", "o7" to "dim7", "mM7" to "mmaj7", "m(maj7)" to "mmaj7", "sus" to "sus4",
    )

    fun parse(text: String, key: Key): ChordSpec? {
        val t = text.trim().replace('♯', '#').replace('♭', 'b')
        if (t.isEmpty()) return null
        return if (t[0] in "ABCDEFG") symbol(t) else roman(t, key)
    }

    private fun letter(s: String): PitchClass? {
        if (s.isEmpty() || s[0] !in "CDEFGAB") return null
        val alter = s.drop(1).let { a -> when (a) { "" -> 0; "#" -> 1; "##", "x" -> 2; "b" -> -1; "bb" -> -2; else -> return null } }
        return PitchClass("CDEFGAB".indexOf(s[0]), alter)
    }

    private fun build(root: PitchClass, quality: String): List<PitchClass>? {
        val q = QUALITIES[ALIASES[quality] ?: quality] ?: return null
        return listOf(root) + q.map { (g, s) -> ChordAnalyzer.transpose(root, g, s) }
    }

    private fun symbol(t: String): ChordSpec? {
        val main = t.substringBefore('/')
        val slash = t.substringAfter('/', "")
        val rootLen = 1 + main.drop(1).takeWhile { it == '#' || it == 'b' }.length.coerceAtMost(2)
        // "Bb" is B flat, but in "Bbm" too; a 'b' after the letter is always a flat here
        val root = letter(main.take(rootLen)) ?: return null
        val pcs = build(root, main.drop(rootLen)) ?: return null
        val bass = if (slash.isEmpty()) null else letter(slash) ?: return null
        return ChordSpec(pcs, bass)
    }

    private val NUMERALS = listOf("VII", "III", "VI", "IV", "II", "V", "I")

    private fun roman(t: String, key: Key): ChordSpec? {
        val main = t.substringBefore('/')
        val of = t.substringAfter('/', "")
        // An applied chord: its numeral is read in the key of its target ("V7/V")
        val home = if (of.isEmpty()) key else {
            val target = roman(of, key)?.pcs ?: return null
            val minor = target.size >= 2 && Math.floorMod(target[1].semis - target[0].semis, 12) == 3
            (-7..7).map { Key(it, if (minor) Mode.MINOR else Mode.MAJOR) }.firstOrNull { it.tonic == target[0] } ?: return null
        }
        var s = main
        val shift = when { s.startsWith("bb") -> -2; s.startsWith("b") -> -1; s.startsWith("##") -> 2; s.startsWith("#") -> 1; else -> 0 }
        s = s.drop(Math.abs(shift))
        val num = NUMERALS.firstOrNull { s.uppercase().startsWith(it) } ?: return null
        val upper = s.take(num.length).all { it.isUpperCase() }
        if (!upper && s.take(num.length).any { it.isUpperCase() }) return null
        val degree = listOf("I", "II", "III", "IV", "V", "VI", "VII").indexOf(num)
        s = s.drop(num.length)
        val mark = s.takeWhile { it in "°oø+" }
        s = s.drop(mark.length)
        val explicitMaj = s.startsWith("M7") || s.startsWith("maj7")
        if (explicitMaj) s = s.removePrefix("maj7").removePrefix("M7").let { "7$it" }
        val fig = s
        val inv = when (fig) { "", "5", "53" -> 0; "6", "63" -> 1; "64" -> 2; "7" -> 0; "65" -> 1; "43" -> 2; "42", "2" -> 3; else -> return null }
        val seventh = fig in setOf("7", "65", "43", "42", "2")
        // Root: the scale degree, raised leading tone in minor for V and vii, then the written ♭/♯
        var root = home.degree(degree)
        if (home.mode == Mode.MINOR && degree == 6 && (mark.isNotEmpty() || !upper) && shift == 0) root = home.leadingTone
        root = PitchClass(root.step, root.alter + shift)
        val quality = when {
            mark == "ø" -> "m7b5"
            mark == "°" || mark == "o" -> if (seventh) "dim7" else "dim"
            mark == "+" -> "aug"
            upper && seventh -> if (explicitMaj) "maj7" else "7"
            upper -> ""
            seventh -> if (explicitMaj) "mmaj7" else "m7"
            else -> "m"
        }
        val pcs = build(root, quality) ?: return null
        return ChordSpec(pcs, pcs[inv])
    }
}
