package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.theory.ChordAnalyzer
import com.nefelion.fourpartwriting.theory.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ChordEdgeTest {
    private val Am = Key(0, Mode.MINOR)
    private val Cm = Key(-3, Mode.MINOR)
    private val Dm = Key(-1, Mode.MINOR)
    private val Csharp = Key(7, Mode.MAJOR)
    private val Cb = Key(-7, Mode.MAJOR)
    private val Asharpm = Key(7, Mode.MINOR)
    private val Ebm = Key(-6, Mode.MINOR)

    private fun assertChord(key: Key, symbol: String, roman: String, vararg notes: String) {
        val c = chordOf(key, *notes)
        assertEquals("symbol of ${notes.toList()} in ${key.name}", symbol, c.symbol)
        assertEquals("roman of ${notes.toList()} in ${key.name}", roman, c.roman)
    }

    @Test fun extremeKeys() {
        assertChord(Csharp, "C♯", "I", "C#3", "G#3", "E#4", "C#5")
        assertChord(Csharp, "G♯7", "V⁷", "G#2", "F#3", "B#3", "D#4")
        assertChord(Cb, "C♭", "I", "Cb3", "Gb3", "Eb4", "Cb5")
        assertChord(Cb, "G♭7/B♭", "V⁶₅", "Bb2", "Fb3", "Gb3", "Db4")
        assertChord(Asharpm, "E♯", "V", "E#3", "B#3", "Gx4", "E#5")
        assertChord(Asharpm, "G𝄪dim7", "vii°⁷", "Gx2", "D#3", "F#3", "B#3")
        assertChord(Ebm, "B♭", "V", "Bb2", "F3", "D4", "Bb4")
        assertChord(Ebm, "E♭m", "i", "Eb3", "Bb3", "Gb4", "Eb5")
    }

    @Test fun doubleSharpSecondaries() {
        assertChord(Csharp, "D♯7", "V⁷/V", "D#3", "Fx3", "C#4", "A#4")
        assertChord(Csharp, "F𝄪dim7", "vii°⁷/V", "Fx3", "C#4", "E4", "A#4")
        assertChord(Csharp, "B♯", "V/iii", "B#2", "Fx3", "Dx4", "B#4")
        assertChord(Csharp, "C𝄪dim7", "vii°⁷/ii", "Cx3", "G#3", "B3", "E#4")
    }

    @Test fun neapolitans() {
        assertChord(Dm, "E♭", "N", "Eb3", "Bb3", "G4", "Eb5")
        assertChord(Cb, "D𝄫/F♭", "N⁶", "Fb3", "Abb3", "Dbb4", "Fb4")
        assertChord(KC, "D♭/F", "N⁶", "F3", "F4", "Ab4", "Db5")
        // A minor triad on ♭2̂ is not a Neapolitan
        assertFalse(chordOf(KC, "Db3", "Ab3", "Fb4").roman.startsWith("N"))
    }

    @Test fun mixtureAndMinorKeyNumerals() {
        assertChord(KC, "E♭", "♭III", "Eb3", "Bb3", "G4", "Eb5")
        assertChord(KC, "B♭", "♭VII", "Bb2", "F3", "D4", "Bb4")
        assertChord(KC, "Ddim", "ii°", "D3", "Ab3", "F4", "D5")
        assertChord(Am, "F♯dim", "♯vi°", "F#3", "C4", "A4", "F#5")
        assertChord(Am, "G♯dim", "vii°", "G#3", "D4", "B4", "G#5")
        assertChord(Am, "Dm", "iv", "D3", "A3", "F4", "D5")
        assertChord(Am, "Bm", "ii", "B2", "F#3", "D4", "B4")
        assertChord(Am, "Bdim", "ii°", "B2", "F3", "D4", "B4")
        assertChord(Am, "C", "III", "C3", "G3", "E4", "C5")
    }

    @Test fun augmentedSixths() {
        assertChord(KC, "Fr+6 on E♭", "Fr⁺⁶/V", "Eb3", "G3", "A3", "C#4")
        assertChord(KC, "Ger+6 (♯2̂) on A♭", "Ger⁺⁶", "Ab2", "C4", "D#4", "F#4")
        assertChord(Cb, "Ger+6 on A𝄫", "Ger⁺⁶", "Abb2", "Cb4", "Ebb4", "F4")
        assertChord(Cm, "Ger+6 on A♭", "Ger⁺⁶", "Ab2", "C4", "Eb4", "F#4")
        // Inversions keep the name
        val inv = chordOf(KC, "C3", "Ab3", "C4", "F#4")
        assertEquals(Quality.IT6, inv.quality)
        assertEquals("It⁺⁶", inv.roman)
        assertEquals(-1, inv.inversion)
        // V7 of ♭II spelled with G♭ is not an augmented sixth
        assertEquals(Quality.DOM7, chordOf(KC, "Ab2", "Eb3", "C4", "Gb4").quality)
    }

    @Test fun ambiguousSonoritiesPreferTheBassAsRoot() {
        assertEquals("Csus2", chordOf(KC, "C3", "G3", "D4", "G4").symbol)
        assertEquals("Gsus4", chordOf(KC, "G2", "D3", "C4", "G4").symbol)
        assertEquals("Fsus2", chordOf(KC, "F2", "C3", "G3", "F4").symbol)
        assertEquals("Am7/C", chordOf(KC, "C3", "E3", "G3", "A3").symbol)
        assertEquals("vi⁶₅", chordOf(KC, "C3", "E3", "G3", "A3").roman)
        assertEquals("Bm7♭5/D", chordOf(KC, "D3", "F3", "A3", "B3").symbol)
    }

    @Test fun dyadsAndUnisons() {
        assertEquals("C(no5)", chordOf(KC, "C3", "E4").symbol)
        assertEquals("Cm(no5)", chordOf(KC, "C3", "Eb4").symbol)
        assertNull(chordOf(KC, "B3", "F4").quality) // a bare tritone
        assertEquals("?", chordOf(KC, "C3", "C4", "C5").roman)
        assertEquals("—", chordOf(KC).symbol)
    }

    @Test fun incompleteSevenths() {
        assertChord(KC, "G7(no5)/B", "V⁶₅", "B2", "G3", "F4", "G4")
        assertChord(KC, "Cmaj7(no5)", "IM⁷", "C3", "B3", "E4", "C5")
        assertChord(KC, "Dm7(no5)", "ii⁷", "D3", "C4", "F4", "D5")
    }

    @Test fun contextLabels() {
        fun prog(key: Key, vararg cs: String) = ChordAnalyzer.analyzeAll(cs.map { c -> c.split(" ").map { Pitch.parse(it) } }, key).map { it.roman }
        assertEquals(listOf("Cad⁶₄", "V⁷", "i"), prog(Am, "E2 A3 C4 E4", "E2 G#3 B3 D4", "A2 A3 C4 E4"))
        assertEquals(listOf("V", "I⁶ (Picardy)"), prog(Am, "E3 B3 E4 G#4", "C#3 A3 E4 A4"))
        // A major tonic in the middle of an A minor piece is V/iv
        assertEquals(listOf("V/iv", "iv"), prog(Am, "A2 E3 C#4 A4", "D3 A3 F4 D5"))
        // I⁶₄ moving to IV is not cadential
        assertEquals(listOf("I⁶₄", "IV"), prog(KC, "G2 G3 C4 E4", "F2 A3 C4 F4"))
    }
}
