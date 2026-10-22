package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Interval
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Mode
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.Voice
import com.grothgarvc.fourpartwriting.theory.ChordAnalyzer
import com.grothgarvc.fourpartwriting.theory.Severity
import com.grothgarvc.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheoryTest {
    private val C = Key(0, Mode.MAJOR)
    private val Am = Key(0, Mode.MINOR)
    private val Eb = Key(-3, Mode.MAJOR)
    private val Fsm = Key(3, Mode.MINOR)

    private fun ch(key: Key, vararg notes: String) =
        ChordAnalyzer.analyze(notes.map { Pitch.parse(it) }, key)

    private fun assertChord(key: Key, symbol: String, roman: String, vararg notes: String) {
        val c = ch(key, *notes)
        assertEquals("symbol of ${notes.toList()}", symbol, c.symbol)
        assertEquals("roman of ${notes.toList()}", roman, c.roman)
    }

    @Test fun intervals() {
        assertEquals("P5", Interval.between(Pitch.parse("C4"), Pitch.parse("G4")).name)
        assertEquals("A4", Interval.between(Pitch.parse("F4"), Pitch.parse("B4")).name)
        assertEquals("d5", Interval.between(Pitch.parse("B3"), Pitch.parse("F4")).name)
        assertEquals("A2", Interval.between(Pitch.parse("F4"), Pitch.parse("G#4")).name)
        assertEquals("m3", Interval.between(Pitch.parse("A4"), Pitch.parse("F#4")).name) // descending
        assertEquals("d7", Interval.between(Pitch.parse("G#3"), Pitch.parse("F4")).name)
        assertEquals("P8", Interval.between(Pitch.parse("C3"), Pitch.parse("C4")).name)
        assertEquals("M10", Interval.between(Pitch.parse("C3"), Pitch.parse("E4")).name)
    }

    @Test fun keys() {
        assertEquals("C", C.tonic.name)
        assertEquals("A", Am.tonic.name)
        assertEquals("E♭", Eb.tonic.name)
        assertEquals("F♯", Fsm.tonic.name)
        assertEquals("C♭", Key(-7, Mode.MAJOR).tonic.name)
        assertEquals("A♯", Key(7, Mode.MINOR).tonic.name)
        assertEquals("G♯", Am.leadingTone.name)
    }

    @Test fun triadsAndInversions() {
        assertChord(C, "C", "I", "C3", "G3", "E4", "C5")
        assertChord(C, "C/E", "I⁶", "E3", "G3", "C4", "C5")
        assertChord(C, "C/G", "I⁶₄", "G2", "G3", "C4", "E4")
        assertChord(C, "Dm", "ii", "D3", "A3", "F4", "D5")
        assertChord(C, "Bdim/D", "vii°⁶", "D3", "F3", "B3", "D4")
        assertChord(Am, "E", "V", "E3", "B3", "G#4", "E5")
        assertChord(Am, "Em", "v", "E3", "B3", "G4", "E5")
        assertChord(Am, "C+/E", "III+⁶", "E3", "C4", "G#4", "E5")
        assertChord(Am, "G", "VII", "G3", "D4", "B4", "G5")
        assertChord(Am, "D", "IV", "D3", "A3", "F#4", "D5")
        assertChord(Fsm, "C♯", "V", "C#3", "G#3", "E#4", "C#5")
    }

    @Test fun sevenths() {
        assertChord(C, "G7", "V⁷", "G2", "F3", "B3", "D4")
        assertChord(C, "G7/B", "V⁶₅", "B2", "F3", "G3", "D4")
        assertChord(C, "G7/D", "V⁴₃", "D3", "F3", "B3", "G4")
        assertChord(C, "G7/F", "V⁴₂", "F3", "G3", "B3", "D4")
        assertChord(C, "Dm7/F", "ii⁶₅", "F3", "A3", "C4", "D4")
        assertChord(C, "Cmaj7", "IM⁷", "C3", "E3", "G3", "B3")
        assertChord(C, "Bm7♭5", "viiø⁷", "B2", "D3", "F3", "A3")
        assertChord(Am, "G♯dim7", "vii°⁷", "G#2", "D3", "F3", "B3")
        assertChord(Am, "G♯dim7/B", "vii°⁶₅", "B2", "D3", "F3", "G#3")
        assertChord(Eb, "B♭7", "V⁷", "Bb2", "Ab3", "D4", "F4")
        // incomplete V7 (no fifth) is still V7
        assertChord(C, "G7(no5)", "V⁷", "G2", "G3", "B3", "F4")
    }

    @Test fun enharmonicSpellingDecides() {
        // A♭–C–E♭–F♯ is a German sixth, A♭–C–E♭–G♭ is a dominant seventh.
        assertChord(C, "Ger+6 on A♭", "Ger⁺⁶", "Ab2", "C4", "Eb4", "F#4")
        assertChord(C, "A♭7", "♭VI⁷", "Ab2", "C4", "Eb4", "Gb4")
        assertChord(C, "It+6 on A♭", "It⁺⁶", "Ab2", "C4", "C4", "F#4")
        assertChord(C, "Fr+6 on A♭", "Fr⁺⁶", "Ab2", "C4", "D4", "F#4")
        assertChord(Am, "Ger+6 on F", "Ger⁺⁶", "F2", "A3", "C4", "D#4")
    }

    @Test fun chromaticChords() {
        assertChord(C, "D♭/F", "N⁶", "F3", "Ab3", "Db4", "F4")
        assertChord(Am, "B♭/D", "N⁶", "D3", "F3", "Bb3", "D4")
        assertChord(C, "D7", "V⁷/V", "D3", "F#3", "C4", "A4")
        assertChord(C, "A/C♯", "V⁶/ii", "C#3", "E3", "A3", "E4")
        assertChord(C, "F♯dim7", "vii°⁷/V", "F#3", "C4", "Eb4", "A4")
        assertChord(C, "C7", "V⁷/IV", "C3", "Bb3", "E4", "G4")
        assertChord(C, "A♭", "♭VI", "Ab2", "Eb3", "C4", "Ab4")
        assertChord(C, "Fm", "iv", "F2", "C3", "Ab3", "F4")
        assertChord(Am, "B7", "V⁷/V", "B2", "D#3", "A3", "F#4")
    }

    @Test fun cadentialSixFourAndPicardy() {
        val prog = listOf(
            listOf("G2", "G3", "C4", "E4"), listOf("G2", "F3", "B3", "D4"), listOf("C3", "E3", "C4", "C4")
        ).map { s -> s.map { Pitch.parse(it) } }
        val r = ChordAnalyzer.analyzeAll(prog, C).map { it.roman }
        assertEquals(listOf("Cad⁶₄", "V⁷", "I"), r)

        val pic = listOf(listOf("E2", "B3", "E4", "G#4"), listOf("A2", "A3", "E4", "C#5")).map { s -> s.map { Pitch.parse(it) } }
        assertEquals("I (Picardy)", ChordAnalyzer.analyzeAll(pic, Am).last().roman)
    }

    @Test fun unrecognized() {
        assertEquals("?", ch(C, "C4", "D4", "E4").roman)
        assertTrue(ch(C, "C3", "G3", "C4", "G4").symbol.contains("no 3rd"))
    }

    // ---- voice leading ----

    private fun score(key: Key, vararg chords: String): Score {
        // each chord string: "S A T B"
        val parsed = chords.map { it.split(" ").map { p -> Pitch.parse(p) } }
        var s = Score(key = key)
        for (v in Voice.entries) s = s.withVoice(v, parsed.map { Event(Duration(4), it[v.ordinal]) })
        return s
    }

    private fun rules(s: Score) = VoiceLeading.check(s).issues.filter { it.severity == Severity.ERROR }.map { it.rule.label }

    @Test fun disabledRulesAreNotReported() {
        val s = score(C, "G4 E4 C4 C3", "A4 F4 D4 D3")
        val all = VoiceLeading.check(s).issues.map { it.rule }
        assertTrue(com.grothgarvc.fourpartwriting.theory.Rule.PARALLEL_FIFTHS in all)
        val some = VoiceLeading.check(s, com.grothgarvc.fourpartwriting.theory.Rule.entries.toSet() - com.grothgarvc.fourpartwriting.theory.Rule.PARALLEL_FIFTHS).issues.map { it.rule }
        assertTrue(com.grothgarvc.fourpartwriting.theory.Rule.PARALLEL_FIFTHS !in some)
    }

    @Test fun cleanProgressionHasNoErrors() {
        val s = score(C, "E4 C4 G3 C3", "F4 C4 A3 F2", "D4 B3 G3 G2", "E4 C4 G3 C3")
        assertEquals(emptyList<String>(), rules(s))
        assertEquals(emptyList<String>(), VoiceLeading.check(EXAMPLE).issues.map { it.message })
    }

    private fun warnings(s: Score) = VoiceLeading.check(s).issues.map { it.rule.label }

    @Test fun seventhLeap() {
        assertTrue("Melodic interval" in rules(score(C, "E4 C4 G3 C3", "F4 C4 A3 F3", "D4 B3 G3 G2")))
    }

    @Test fun leapHandling() {
        // S leaps a sixth up then keeps going up
        assertTrue("Leap handling" in warnings(score(C, "E4 C4 G3 C3", "C5 C4 A3 F3", "D5 B3 G3 G3")))
        // two thirds up outline a triad: fine; two fourths up span a seventh: not
        assertTrue("Leap handling" !in warnings(score(C, "C4 G3 E3 C3", "E4 G3 C3 C3", "G4 G3 C3 C3")))
        assertTrue("Leap handling" in warnings(score(C, "C4 G3 E3 C3", "F4 G3 C3 C3", "B4 G3 D3 G2")))
    }

    private fun messages(s: Score) = VoiceLeading.check(s).issues.map { it.message }

    @Test fun sixFourUsage() {
        // ii – I6/4 – ii: bass neither passes, holds nor arpeggiates
        assertTrue("Six-four chords" in warnings(score(C, "F4 D4 A3 F3", "E4 C4 C4 G3", "F4 D4 A3 F3")))
        // passing V6/4 between I and I6
        assertTrue("Six-four chords" !in warnings(score(C, "E4 C4 G3 C3", "D4 B3 G3 D3", "C4 C4 G3 E3")))
    }

    @Test fun doublingPreferences() {
        assertTrue(messages(score(C, "E4 G3 E3 C3")).any { it.startsWith("Doubled third") })
        assertTrue(messages(score(C, "E4 C4 G3 C3")).none { it.startsWith("Doubled third") })
    }

    @Test fun innerLeadingTone() {
        // T: B3 → E3 (neither up nor a third down)
        val bad = score(C, "G4 D4 B3 G2", "G4 C4 E3 C3")
        assertTrue("Leading tone" in warnings(bad))
        assertTrue("Leading tone" !in rules(bad))
        // T: B3 → G3, frustrated leading tone
        assertTrue("Leading tone" !in warnings(score(C, "G4 D4 B3 G2", "G4 E4 G3 C3")))
    }

    @Test fun tendencyTones() {
        // It+6 → V: A♭ down, F♯ up
        assertTrue("Tendency tones" !in rules(score(C, "F#4 C4 C4 Ab3", "G4 D4 B3 G3")))
        assertTrue("Tendency tones" in rules(score(C, "F#4 C4 C4 Ab3", "G4 D4 B3 G2")))
        // vii°7 → I with the fifth (F) going up
        assertTrue("Tendency tones" in warnings(score(C, "Ab4 F4 D4 B2", "G4 G4 E4 C3")))
    }

    @Test fun lineAndSpacing() {
        assertTrue("Spacing" in warnings(score(C, "G5 E5 C5 C3")))
        val line = score(C, "F4 C4 A3 F3", "G4 C4 A3 F3", "A4 C4 A3 F3", "B4 D4 G3 G3", "G4 D4 G3 G3")
        assertTrue(messages(line).any { "tritone" in it })
        // T: B3 up to F4 (d5), then on up (bad) or back by step (fine)
        assertTrue(messages(score(C, "D5 G4 B3 G2", "D5 A4 F4 D3", "E5 C5 G4 C3")).any { "diminished leap" in it })
        assertTrue(messages(score(C, "D5 G4 B3 G2", "D5 A4 F4 D3", "E5 C5 E4 C3")).none { "diminished leap" in it })
    }

    @Test fun harmonicRhythm() {
        val s = score(C, "E4 C4 G3 C3", "F4 C4 A3 F3", "D4 B3 G3 G2", "E4 C4 G3 C3", "G4 E4 C4 C3")
        assertTrue("Harmonic rhythm" in warnings(s))
    }

    @Test fun crossRelationAndSimilarMotion() {
        // F in S, then F# in B
        assertTrue("Cross relation" in warnings(score(C, "F4 C4 A3 D3", "A4 E4 C4 F#3")))
        // F → F# inside one voice is fine
        assertTrue("Cross relation" !in warnings(score(C, "F4 C4 A3 D3", "F#4 C4 A3 D3")))
        assertTrue("All voices in one direction" in warnings(score(C, "E4 C4 G3 C3", "F4 D4 A3 D3")))
    }

    @Test fun parallelFifthsAndOctaves() {
        // C→D in bass and soprano a 5th/octave above: parallels
        assertTrue("Parallel fifths" in rules(score(C, "G4 E4 C4 C3", "A4 F4 D4 D3")))
        assertTrue("Parallel octaves" in rules(score(C, "C5 G4 E4 C3", "D5 A4 F4 D3")))
    }

    @Test fun leadingToneAndSeventh() {
        // V7 → I with soprano B going down and F going up
        val bad = rules(score(C, "B4 F4 D4 G2", "G4 G4 E4 C3"))
        assertTrue(bad.toString(), "Leading tone" in bad)
        assertTrue(bad.toString(), "Seventh" in bad)
        val good = rules(score(C, "B4 F4 D4 G2", "C5 E4 C4 C3"))
        assertTrue(good.toString(), "Leading tone" !in good && "Seventh" !in good)
    }

    @Test fun crossingSpacingAndMelody() {
        assertTrue("Voice crossing" in rules(score(C, "C4 E4 G3 C3")))
        assertTrue("Spacing" in rules(score(C, "E5 C4 G3 C3")))
        assertTrue("Melodic interval" in rules(score(Am, "F4 C4 A3 D3", "G#4 B3 E3 E3")))
        assertTrue("Doubling" in rules(score(C, "B4 B3 G3 G2")))
    }

    @Test fun clefsAndKeySignatures() {
        assertEquals(2, Clef.TREBLE.position(Pitch.parse("G4")))
        assertEquals(6, Clef.BASS.position(Pitch.parse("F3")))
        assertEquals(4, Clef.ALTO.position(Pitch.parse("C4")))
        assertEquals(6, Clef.TENOR.position(Pitch.parse("C4")))
        assertEquals(Pitch.parse("C4"), Clef.TREBLE_8VB.pitchAt(-2 + 7))
        assertEquals(listOf(8, 5, 9, 6, 3, 7, 4), Clef.TREBLE.keySigPositions(7))
        assertEquals(listOf(4, 7, 3, 6, 2, 5, 1), Clef.TREBLE.keySigPositions(-7))
        assertEquals(listOf(6, 3, 7, 4, 1, 5, 2), Clef.BASS.keySigPositions(7))
        assertEquals(listOf(2, 5, 1, 4, 0, 3, -1), Clef.BASS.keySigPositions(-7))
        assertEquals(listOf(7, 4, 8, 5, 2, 6, 3), Clef.ALTO.keySigPositions(7))
        assertEquals(listOf(8, 5, 9, 6, 3, 7, 4), Clef.TREBLE_8VB.keySigPositions(7))
        assertEquals(listOf(5, 8, 4, 7, 3, 6, 2), Clef.TENOR.keySigPositions(-7))
    }
}
