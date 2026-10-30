package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.Overtones
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the overtone lesson shows, checked against the model it draws and plays. */
class OvertonesTest {
    private fun cmp(r: Rule) = Overtones.COMPARE.single { it.rule == r }
    private val cMajor = listOf("C", "E", "G").map { Pitch.parse(it + "4").pc }

    @Test fun seriesOfC4() {
        val c4 = Pitch.parse("C4").midi
        assertEquals(listOf("C4", "C5", "G5", "C6", "E6", "G6", "B♭6", "C7"), (1..8).map { Overtones.name(c4, it) })
        assertEquals(listOf(7, 11, 13, 14), (1..16).filter { Overtones.outOfTune(it) })
    }

    @Test fun doubledThirdTakesOver() = with(cmp(Rule.DOUBLING)) {
        val good = Overtones.shares(Overtones.parse(good), cMajor); val bad = Overtones.shares(Overtones.parse(bad), cMajor)
        assertEquals(0, good.withIndex().maxBy { it.value }.index)
        assertEquals(1, bad.withIndex().maxBy { it.value }.index)
    }

    @Test fun lowAndCloseRubs() = with(cmp(Rule.SPACING)) {
        assertTrue(Overtones.roughness(Overtones.parse(good)) < 0.5)
        assertTrue(Overtones.roughness(Overtones.parse(bad)) > 1.2)
    }

    @Test fun sixFourLeavesTheBassSeries() = with(cmp(Rule.SIX_FOUR)) {
        fun inSeries(v: String) = Overtones.parse(v).let { n -> n.dropLast(1).count { Overtones.inSeries(n.last(), it) } }
        assertEquals(3, inSeries(good))
        assertEquals(1, inSeries(bad))
    }

    @Test fun pianoModel() {
        val p = Overtones.piano(Pitch.parse("C4").midi)
        val f0 = p[0].hz
        // A stiff string runs sharp up high, high partials fade faster, and the 8th sits near the hammer's still point
        assertTrue(p[9].hz > 10 * f0 * 1.005)
        assertTrue(p[9].level(0.5) / p[9].amp < p[0].level(0.5) / p[0].amp)
        assertTrue(p[7].amp < p[6].amp && p[7].amp < p[8].amp)
        assertTrue(p.all { it.hz < 10_000 } && p.size > 20)
    }

    /** A "like this" voicing breaks no rule (the final cadence aside: it is one chord). */
    @Test fun goodVoicingsAreClean() = Overtones.COMPARE.forEach { c ->
        val sc = Score(voices = Overtones.parse(c.good).map { listOf(Event(Fraction.of(1, 1), it)) })
        assertEquals(c.tab, emptyList<Any>(), VoiceLeading.check(sc, Rule.entries.toSet() - Rule.CADENCE).issues.map { it.message })
    }
}
