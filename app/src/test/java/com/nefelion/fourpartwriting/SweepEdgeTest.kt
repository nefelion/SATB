package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.ChordAnalyzer.transpose
import com.nefelion.fourpartwriting.theory.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every key, every chord quality, every inversion. */
class SweepEdgeTest {
    /** Pitch classes stacked upwards from octave 2, each above the previous one. */
    private fun stack(pcs: List<PitchClass>): List<Pitch> {
        val out = mutableListOf<Pitch>()
        for (pc in pcs) {
            var o = 2
            while (out.isNotEmpty() && Pitch(pc.step, pc.alter, o).let { it.midi <= out.last().midi || it.diatonic <= out.last().diatonic }) o++
            out += Pitch(pc.step, pc.alter, o)
        }
        return out
    }

    private fun roman(key: Key, vararg pcs: PitchClass) = ChordAnalyzer.analyze(stack(pcs.toList()), key).roman

    private val keys = (-7..7).flatMap { f -> Mode.entries.map { Key(f, it) } }

    @Test fun diatonicTriadsInEveryKey() {
        for (k in keys) {
            val expected = if (k.mode == Mode.MAJOR) listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
            else listOf("i", "ii°", "III", "iv", "v", "VI", "VII")
            val got = (0..6).map { d -> roman(k, k.degree(d), k.degree(d + 2), k.degree(d + 4)) }
            assertEquals(k.name, expected, got)
        }
    }

    @Test fun dominantsInEveryKey() {
        for (k in keys) {
            val lt = k.leadingTone
            val v = k.degree(4)
            assertEquals("V⁷ in ${k.name}", "V⁷", roman(k, v, lt, k.degree(1), k.degree(3)))
            assertEquals("vii°⁷ in ${k.name}", "vii°⁷", roman(k, lt, k.degree(1), k.degree(3), transpose(lt, 6, 9)))
        }
    }

    @Test fun secondaryDominantsInEveryMajorKey() {
        val labels = listOf(1 to "ii", 2 to "iii", 3 to "IV", 4 to "V", 5 to "vi")
        for (f in -7..7) {
            val k = Key(f, Mode.MAJOR)
            for ((d, label) in labels) {
                val t = k.degree(d)
                val root = transpose(t, 4, 7)
                val third = transpose(t, 6, 11)
                val fifth = transpose(root, 4, 7)
                val seventh = transpose(root, 6, 10)
                assertEquals("V7/$label in ${k.name}", "V⁷/$label", roman(k, root, third, fifth, seventh))
                assertEquals("vii°7/$label in ${k.name}", "vii°⁷/$label",
                    roman(k, third, transpose(third, 2, 3), transpose(third, 4, 6), transpose(third, 6, 9)))
            }
        }
    }

    @Test fun augmentedSixthsAndNeapolitanInEveryKey() {
        for (k in keys) {
            val flat6 = if (k.mode == Mode.MAJOR) k.degree(5).let { PitchClass(it.step, it.alter - 1) } else k.degree(5)
            val sharp4 = k.degree(3).let { PitchClass(it.step, it.alter + 1) }
            if (sharp4.alter > 2 || flat6.alter < -2) continue
            val tonic = k.tonic
            assertEquals("It+6 in ${k.name}", "It⁺⁶", roman(k, flat6, tonic, sharp4))
            val flat3 = transpose(flat6, 4, 7)
            if (flat3.alter >= -2) assertEquals("Ger+6 in ${k.name}", "Ger⁺⁶", roman(k, flat6, tonic, flat3, sharp4))
            val n = k.degree(1).let { PitchClass(it.step, it.alter - 1) }
            if (n.alter >= -2) assertEquals("N⁶ in ${k.name}", "N⁶", roman(k, k.degree(3), transpose(n, 4, 7), n))
        }
    }

    @Test fun everyQualityInEveryInversionKeepsItsRoot() {
        val root = PitchClass(0, 0)
        for (q in Quality.entries) {
            if (q.isAug6 || !q.hasThird) continue
            val members = listOf(0 to 0) + q.members.toList().sortedBy { it.first }
            for ((bassG, bassS) in members) {
                val bass = transpose(root, bassG, bassS)
                val rest = members.filter { it.first != bassG }.map { transpose(root, it.first, it.second) }
                val c = ChordAnalyzer.analyze(stack(listOf(bass) + rest), KC)
                assertEquals("$q with ${bass.name} in the bass", q, c.quality)
                assertEquals("$q with ${bass.name} in the bass", root, c.root)
                val inv = when (bassG) { 0 -> 0; 2 -> 1; 4 -> 2; 6 -> 3; else -> -1 }
                assertEquals("$q inversion with ${bass.name}", inv, c.inversion)
            }
        }
    }
}
