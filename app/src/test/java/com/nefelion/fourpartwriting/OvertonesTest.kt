package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.Lessons
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

    /** Each "not this" breaks its own rule, differs from "like this" in one voice, and has a task under it. */
    @Test fun badVoicingsBreakTheirRule() {
        assertEquals(Overtones.COMPARE.size, Lessons.ALL[0].tasks.size)
        Overtones.COMPARE.forEach { c ->
            assertEquals(c.title, 1, Overtones.parse(c.good).zip(Overtones.parse(c.bad)).count { it.first != it.second })
            val issues = VoiceLeading.check(chord(c.bad), Lessons.rulesUpTo(0)).issues
            assertTrue(c.title + ": " + issues, issues.isNotEmpty() && issues.all { it.rule == c.rule })
        }
    }

    /** The measured A4 says what the captions say. */
    @Test fun measuredPiano() {
        val p = Overtones.A4_PIANO
        assertEquals((1..p.size).toList(), p.map { it.n })
        // A stiff string runs sharp up high, high partials fade faster, and the 9th sits near the hammer's still point
        assertTrue(p[9].hz > 10 * p[0].hz * 1.005)
        assertTrue(p[9].level(0.5) / p[9].amp < p[0].level(0.5) / p[0].amp)
        assertTrue(p[8].amp < p[7].amp && p[8].amp < p[9].amp)
        // Partial 1 is the strongest: the drawings put it at the top
        assertTrue(p.drop(1).all { it.amp < p[0].amp })
    }

    /** A "like this" voicing breaks no rule (the final cadence aside: it is one chord). */
    @Test fun goodVoicingsAreClean() = Overtones.COMPARE.forEach { c ->
        assertEquals(c.title, emptyList<Any>(), VoiceLeading.check(chord(c.good), Rule.entries.toSet() - Rule.CADENCE).issues.map { it.message })
    }

    private fun chord(v: String) = Score(voices = Overtones.parse(v).map { listOf(Event(Fraction.of(1, 1), it)) })
}
