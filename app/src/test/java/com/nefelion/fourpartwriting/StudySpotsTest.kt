package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.export.MusicXml
import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.IN_BACH
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StudySpotsTest {
    private val dir = File("src/main/assets/study")

    /** Every bundled study score reads back, has four full voices and a title. */
    @Test fun studyScoresRead() {
        val files = dir.listFiles { f -> f.name.endsWith(".satb") }!!
        assertTrue(files.size > 50)
        for (f in files) {
            val text = f.readText()
            val sc = ScoreCodec.decode(text)
            assertNotNull(f.name, sc)
            assertNotNull(f.name, ScoreCodec.title(text))
            assertEquals(f.name, 1, Voice.entries.map { sc!!.length(it) }.distinct().size)
        }
    }

    /** A rule card's spot where Bach breaks the rule is flagged in that bar; one where he keeps it is not. */
    @Test fun spotsMatchTheChecker() {
        for ((rule, spots) in IN_BACH) for (spot in spots) {
            val sc = ScoreCodec.decode(File(dir, "${spot.study}.satb").readText())!!
            val a = VoiceLeading.check(sc)
            val here = a.issues.any { it.rule == rule && sc.barNumber(a.slices[it.slice].time) == spot.bar }
            assertEquals("${rule.name} in ${spot.study} bar ${spot.bar}", spot.breaks, here)
        }
    }

    /** The importer reads the app's own MusicXML export back to the same music. */
    @Test fun musicXmlRoundTrip() {
        val sc = ScoreCodec.decode(File(dir, "BWV 244.44.satb").readText())!!
        val back = MusicXml.read(MusicXml.export(sc).toByteArray())!!.score
        assertEquals(sc.key, back.key)
        assertEquals(sc.time, back.time)
        assertEquals(sc.pickup, back.pickup)
        for (v in Voice.entries) assertEquals(v.name, sc.voice(v).map { it.pitch to it.length }, back.voice(v).map { it.pitch to it.length })
    }
}
