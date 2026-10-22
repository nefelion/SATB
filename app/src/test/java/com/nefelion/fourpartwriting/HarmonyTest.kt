package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.engrave.NoteRef
import com.nefelion.fourpartwriting.model.Duration
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyTest {
    private fun line(s: String) = s.split(" ").map { tok ->
        val (p, d) = tok.split(":")
        Event(Duration(d.trimEnd('.').toInt(), d.count { it == '.' }), if (p == "r") null else Pitch.parse(p))
    }

    private fun check(s: String, a: String, t: String, b: String): Analysis =
        VoiceLeading.check(Score(voices = listOf(line(s), line(a), line(t), line(b))))

    private fun romans(a: Analysis) = a.points.map { a.chords[it].roman }
    private fun nct(a: Analysis, v: Voice, i: Int) = a.ncts[NoteRef(v, i)]
    private fun errors(a: Analysis) = a.issues.filter { it.severity == Severity.ERROR }.map { it.rule }

    @Test fun passingAndNeighbour() {
        val pt = check("E4:8 F4:8 G4:4", "C4:4 E4:4", "G3:2", "C3:2")
        assertEquals(listOf("I", "I"), romans(pt))
        assertEquals(NctType.PT, nct(pt, Voice.SOPRANO, 1)?.type)
        assertTrue(errors(pt).toString(), Rule.UNRESOLVED_NCT !in errors(pt))

        val nt = check("E4:8 F4:8 E4:4", "C4:2", "G3:2", "C3:2")
        assertEquals(NctType.NT, nct(nt, Voice.SOPRANO, 1)?.type)
    }

    @Test fun suspensions() {
        // C5 held over the G chord, resolving to B4: 4–3
        val s43 = check("C5:4. B4:8", "G4:2", "E4:4 D4:4", "C3:4 G2:4")
        assertEquals(listOf("I", "V"), romans(s43))
        assertEquals("4–3", nct(s43, Voice.SOPRANO, 0)?.label)
        // C5 held over D3, resolving to B4: 7–6 over vii°6 (not ii7)
        val s76 = check("C5:4. B4:8", "G4:4 F4:4", "E4:4 D4:4", "C3:4 D3:4")
        assertEquals("vii°⁶", romans(s76)[1])
        assertEquals("7–6", nct(s76, Voice.SOPRANO, 0)?.label)
    }

    @Test fun unresolvedSuspensionIsAnError() {
        val bad = check("C5:4. E5:8", "G4:2", "E4:4 D4:4", "C3:4 G2:4")
        assertTrue(errors(bad).toString(), Rule.UNRESOLVED_NCT in errors(bad))
    }

    @Test fun otherNonChordTones() {
        assertEquals(NctType.APP, nct(check("E4:8 A4:8 G4:4", "C4:4 E4:4", "G3:2", "C3:2"), Voice.SOPRANO, 1)?.type)
        assertEquals(NctType.ESC, nct(check("E4:8 F4:8 D4:4", "C4:4 B3:4", "G3:4 G3:4", "C3:4 G2:4"), Voice.SOPRANO, 1)?.type)
        assertEquals(NctType.ANT, nct(check("D4:8 C4:8 C4:4", "B3:4 C4:4", "G3:4 E3:4", "G2:4 C3:4"), Voice.SOPRANO, 1)?.type)
        val ped = check("E4:4 D4:4", "C4:4 B3:4", "G3:4 F3:4", "C3:2")
        assertEquals(NctType.PED, ped.ncts[NoteRef(Voice.BASS, 0)]?.type)
    }

    @Test fun homophonicExampleUnchanged() {
        val a = VoiceLeading.check(EXAMPLE)
        assertEquals(a.slices.indices.toList(), a.points)
        assertTrue(a.ncts.isEmpty())
        assertEquals(listOf("I", "IV", "V", "I", "vi", "ii⁶", "Cad⁶₄", "V⁷", "I"), romans(a))
    }
}
