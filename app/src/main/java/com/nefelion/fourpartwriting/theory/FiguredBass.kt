package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass

/**
 * Figured bass. A figure line is an optional accidental ('#', 'b', 'n' for natural) and an optional number:
 * "6", "#6", "b7", or a lone "#" (the third). Lines are stored top to bottom, as written.
 */
object FiguredBass {

    /** A line as shown in the score ("♯6"). */
    fun display(line: String) = line.map { when (it) { '#' -> '♯'; 'b' -> '♭'; 'n' -> '♮'; else -> it } }.joinToString("")

    /** The whole stack on one line ("6/4"), for messages. */
    fun display(lines: List<String>) = lines.joinToString("/") { display(it) }

    /** Accepts one line; null for anything else. */
    fun valid(line: String) = Regex("[#bn]?[2-9]?").matches(line) && line.isNotEmpty()

    /**
     * Notes the figures ask for above [bass], by interval number (2..7) to pitch class. Abbreviations are filled in
     * the usual way (nothing = ⁵₃, 6 = ⁶₃, 7 = ⁷₅₃, ⁶₅ = ⁶₅₃, ⁴₃ = ⁶₄₃, 2 = ⁴₂ = ⁶₄₂). Numbers follow the key signature
     * unless an accidental raises, lowers or cancels them.
     */
    fun realize(bass: Pitch, lines: List<String>, key: Key): Map<Int, PitchClass> {
        // A lone accidental belongs to the third; 8 doubles the bass and adds nothing
        val acc = HashMap<Int, Char>()
        val raw = mutableSetOf<Int>()
        for (l in lines) {
            val n = l.filter { it.isDigit() }.toIntOrNull() ?: 3
            raw += n
            l.firstOrNull { it in "#bn" }?.let { acc[simple(n)] = it }
        }
        val s = raw.map(::simple).filter { it != 1 }.toSet()
        val full = when {
            9 in raw && s == setOf(2) -> setOf(2, 3, 5)
            s.isEmpty() || s == setOf(3) || s == setOf(5) || s == setOf(3, 5) -> setOf(3, 5)
            s == setOf(6) || s == setOf(3, 6) -> setOf(3, 6)
            s == setOf(4, 6) -> setOf(4, 6)
            s.all { it in setOf(3, 5, 7) } && 7 in s -> setOf(3, 5, 7)
            s == setOf(5, 6) -> setOf(3, 5, 6)
            s == setOf(3, 4) -> setOf(3, 4, 6)
            s == setOf(2) || s == setOf(2, 4) || s == setOf(2, 4, 6) -> setOf(2, 4, 6)
            s == setOf(4) -> setOf(4, 5)
            else -> s
        }
        return full.associateWith { n ->
            val step = (bass.step + n - 1) % 7
            val sig = key.sigAlter(step)
            PitchClass(step, when (acc[n]) { '#' -> sig + 1; 'b' -> sig - 1; 'n' -> 0; else -> sig })
        }
    }

    private fun simple(n: Int) = (n - 1) % 7 + 1

    /** Fifths are often left out; one written explicitly ("5", "♮5") is asked for. */
    fun optional(n: Int, lines: List<String>) = n == 5 && lines.none { l -> l.filter { it.isDigit() } == "5" }
}
