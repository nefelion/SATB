package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalEdgeTest {
    private fun iv(a: String, b: String) = Interval.between(Pitch.parse(a), Pitch.parse(b))

    @Test fun extremeAndCompoundQualities() {
        assertEquals("d8", iv("C4", "Cb5").name)
        assertEquals("A8", iv("C4", "C#5").name)
        assertEquals("AA4", iv("F4", "B#4").name)
        assertEquals("dd5", iv("B3", "Fb4").name)
        assertEquals("M17", iv("C3", "E5").name)
        assertEquals("P15", iv("C3", "C5").name)
        assertEquals("d2", iv("C#4", "Db4").name)
        assertEquals("dd2", iv("B#3", "Cb4").name)
        assertEquals("A6", iv("Ab3", "F#4").name)
        assertEquals("d7", iv("C#4", "Bb4").name)
        assertEquals("d3", iv("C#4", "Eb4").name)
    }

    @Test fun descendingIntervals() {
        val p12 = iv("G4", "C3")
        assertEquals("P12", p12.name)
        assertTrue(p12.descending)
        val a1 = iv("C#4", "C4")
        assertEquals("A1", a1.name)
        assertTrue(a1.descending)
        assertEquals("A4", iv("B3", "F3").name)
        assertEquals("d5", iv("F4", "B3").name)
        assertFalse(iv("C4", "C4").descending)
        assertEquals("P1", iv("C4", "C4").name)
    }

    @Test fun allKeysTonicsAndLeadingTones() {
        val majors = listOf("C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
        val minors = listOf("A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯", "G♯", "D♯", "A♯")
        for (f in -7..7) {
            assertEquals("major $f", majors[f + 7], Key(f, Mode.MAJOR).tonic.name)
            assertEquals("minor $f", minors[f + 7], Key(f, Mode.MINOR).tonic.name)
            // The leading tone is a half step (m2) below the tonic in every key
            for (m in Mode.entries) {
                val k = Key(f, m)
                val lt = Pitch(k.leadingTone.step, k.leadingTone.alter, 4)
                val t = Pitch(k.tonic.step, k.tonic.alter, if (k.tonic.step == 0) 5 else 4)
                assertEquals("LT of ${k.name}", "m2", Interval.between(lt, t).name)
            }
        }
        assertEquals("G𝄪", Key(7, Mode.MINOR).leadingTone.name)
        assertEquals("G", Key(-7, Mode.MINOR).leadingTone.name)
    }

    @Test fun scaleDegreesAndDiatonic() {
        val am = Key(0, Mode.MINOR)
        assertTrue(am.isDiatonic(PitchClass(3, 1))) // F♯ raised 6
        assertTrue(am.isDiatonic(PitchClass(4, 1))) // G♯ raised 7
        assertFalse(am.isDiatonic(PitchClass(6, -1))) // B♭
        assertFalse(am.isDiatonic(PitchClass(0, 1))) // C♯ is not a raised 6/7
        assertFalse(KC.isDiatonic(PitchClass(4, 1))) // G♯ in C major
        val cb = Key(-7, Mode.MAJOR)
        assertEquals(listOf("C♭", "D♭", "E♭", "F♭", "G♭", "A♭", "B♭"), (0..6).map { cb.degree(it).name })
        val asm = Key(7, Mode.MINOR)
        assertEquals(listOf("A♯", "B♯", "C♯", "D♯", "E♯", "F♯", "G♯"), (0..6).map { asm.degree(it).name })
        assertTrue(asm.isDiatonic(PitchClass(4, 2))) // G𝄪
    }

    @Test fun fractionNormalization() {
        assertEquals(Fraction.of(1, 2), Fraction.of(2, 4))
        assertEquals(Fraction.of(-1, 2), Fraction.of(1, -2))
        assertEquals(Fraction.ZERO, Fraction.of(1, 2) - Fraction.of(2, 4))
        assertEquals(Fraction.of(3, 8), Fraction.of(1, 4) + Fraction.of(1, 8))
        assertTrue(Fraction.of(1, 3) < Fraction.of(1, 2))
    }

    @Test fun meters() {
        assertFalse(TimeSig(3, 8).isCompound)
        assertEquals(Fraction.of(1, 8), TimeSig(3, 8).beatLength)
        for (b in listOf(6, 9, 12)) {
            assertTrue(TimeSig(b, 8).isCompound)
            assertEquals(Fraction.of(3, 8), TimeSig(b, 8).beatLength)
        }
        assertTrue(TimeSig(6, 4).isCompound)
        assertEquals(Fraction.of(3, 4), TimeSig(6, 4).beatLength)
        assertEquals(Fraction.of(1, 2), TimeSig(2, 2).beatLength)
        assertEquals(Fraction.of(1, 2), TimeSig(3, 2).beatLength)
        assertFalse(TimeSig(3, 4).isCompound)
    }
}
