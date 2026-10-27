package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.ChordText
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.Voicer
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicerTest {
    private val C = Key(0, Mode.MAJOR)
    private val Am = Key(0, Mode.MINOR)
    private fun names(t: String, key: Key = C) = ChordText.parse(t, key)?.let { s -> s.pcs.joinToString(" ") { it.name } + (s.bass?.let { " /${it.name}" } ?: "") }

    @Test fun symbols() {
        assertEquals("C E G", names("C"))
        assertEquals("F♯ A C♯", names("F#m"))
        assertEquals("B♭ D F A♭", names("Bb7"))
        assertEquals("G B D F /B", names("G7/B"))
        assertEquals("B D F A", names("Bm7b5"))
        assertEquals("B D F A♭", names("Bdim7"))
        assertEquals("C E G B", names("Cmaj7"))
        assertNull(names("H7"))
        assertNull(names("Cxyz"))
    }

    @Test fun romanNumerals() {
        assertEquals("G B D /G", names("V"))
        assertEquals("G B D F /B", names("V65"))
        assertEquals("D F A /F", names("ii6"))
        assertEquals("C E G /G", names("I64"))
        assertEquals("D F♯ A C /D", names("V7/V"))
        assertEquals("A♭ C E♭ /A♭", names("bVI"))
        // Minor: V and vii° take the leading tone
        assertEquals("E G♯ B /E", names("V", Am))
        assertEquals("G♯ B D F /G♯", names("vii°7", Am))
        assertEquals("B D F A /D", names("iiø65", Am))
        assertNull(names("V99"))
    }

    private fun q(p: String?, chord: String? = null) = Event(Fraction.of(1, 4), p?.let(Pitch::parse), chord = chord)
    private fun melody(notes: String, chords: List<String?> = emptyList()) = Score(voices = listOf(
        notes.split(" ").mapIndexed { i, n -> q(n, chords.getOrNull(i)) }, emptyList(), emptyList(), emptyList()))

    private fun assertClean(sc: Score) {
        val errors = VoiceLeading.check(sc).issues.filter { it.severity == Severity.ERROR }
        assertTrue(errors.map { it.message }.toString(), errors.isEmpty())
    }

    @Test fun fillsUnderAMelodyWithChosenChords() {
        val sc = melody("E5 F5 D5 C5", listOf("C", "F", "G7/B", "C"))
        val out = Voicer.fill(sc)!!
        assertEquals(sc.voice(Voice.SOPRANO), out.voice(Voice.SOPRANO))
        assertEquals("B", out.voice(Voice.BASS)[2].pitch!!.pc.name)
        assertClean(out)
    }

    @Test fun picksChordsForABareMelody() {
        val out = Voicer.fill(melody("E5 D5 C5 B4 C5"))
        assertNotNull(out)
        assertClean(out!!)
        // Ends on the tonic in root position
        assertEquals("C", out.voice(Voice.BASS).last().pitch!!.pc.name)
    }

    @Test fun keepsWrittenNotesAndFillsTheRest() {
        val base = melody("E5 F5 D5 C5", listOf("C", "F", "G", "C"))
        val partial = base.withVoice(Voice.ALTO, listOf(q("G4"), q("A4")))
        val out = Voicer.fill(partial)!!
        assertEquals(listOf("G4", "A4"), out.voice(Voice.ALTO).take(2).map { it.pitch!!.name })
        assertEquals(4, out.voice(Voice.ALTO).size)
        assertClean(out)
    }

    @Test fun chordNamesSurviveTheFile() {
        val sc = melody("E5 F5", listOf("C/E", "V65/V"))
        assertEquals(sc, ScoreCodec.decode(ScoreCodec.encode(sc)))
    }

    @Test fun longMelodyIsQuick() {
        val tune = "E5 D5 C5 D5 E5 E5 E5 D5 D5 D5 E5 G5 G5 E5 D5 C5 D5 E5 E5 E5 E5 D5 D5 E5 D5 C5 G4 A4 B4 D5 D5 C5"
        val t0 = System.nanoTime()
        val out = Voicer.fill(melody(tune))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("32 notes: ${ms}ms")
        assertNotNull(out)
        assertTrue("took ${ms}ms", ms < 3000)
    }

    /** A correct start is always continued: cut the solution of an exercise anywhere and fill it again. */
    @Test fun continuesAnyCorrectStart() {
        var fails = 0; var total = 0
        for (level in com.nefelion.fourpartwriting.theory.Exercises.Level.entries) for (seed in 0 until 10) {
            val ex = com.nefelion.fourpartwriting.theory.Exercises.generate(level, kotlin.random.Random(seed))!!
            val solved = Voicer.fill(ex)!!
            val n = ex.voice(Voice.BASS).size
            for (cut in 1 until n) for (v in listOf(Voice.SOPRANO, Voice.ALTO, Voice.TENOR)) {
                total++
                // The other voices keep cut notes too, this one keeps one more
                val partial = solved.copy(voices = Voice.entries.map { w -> if (w == Voice.BASS) solved.voice(w) else solved.voice(w).take(if (w == v) cut else cut - 1) })
                if (Voicer.fill(partial) == null) { fails++; if (fails < 6) println("FAIL $level $seed cut $cut $v") }
            }
        }
        println("continue fails: $fails / $total")
        assertEquals(0, fails)
    }
}
