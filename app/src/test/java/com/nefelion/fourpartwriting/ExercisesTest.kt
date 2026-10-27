package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.ScoreCodec
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Exercises
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExercisesTest {
    /** Every generated exercise has a solution the checker finds no errors in, and the given bass is untouched. */
    @Test fun generatedExercisesAreSolvable() {
        for (level in Exercises.Level.entries) for (seed in 0 until 15) {
            val t0 = System.nanoTime()
            val ex = Exercises.generate(level, Random(seed))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertNotNull("$level seed $seed", ex)
            ex!!
            assertEquals(setOf(Voice.BASS), ex.given)
            assertTrue(ex.voice(Voice.SOPRANO).isEmpty())
            val solved = Exercises.solve(ex)!!
            assertEquals(ex.voice(Voice.BASS), solved.voice(Voice.BASS))
            val issues = VoiceLeading.check(solved).issues
            assertTrue("$level $seed: ${issues.filter { it.severity == Severity.ERROR }.map { it.message }}", issues.none { it.severity == Severity.ERROR })
            assertEquals(ex, ScoreCodec.decode(ScoreCodec.encode(ex)))
            println("$level seed $seed: ${ms}ms, ${issues.size} warnings: ${issues.map { it.rule }} | ${ex.key.name} " +
                ex.voice(Voice.BASS).joinToString(" ") { "${it.pitch}${if (it.figures.isEmpty()) "" else "(" + it.figures.joinToString("/") + ")"}" })
        }
    }

    /** Every given-soprano exercise ends on the tonic and has a solution without errors that keeps the melody. */
    @Test fun sopranoExercisesAreSolvable() {
        for (level in Exercises.Level.entries) for (seed in 0 until 15) {
            val t0 = System.nanoTime()
            val ex = Exercises.generate(level, Random(seed), soprano = true)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertNotNull("$level seed $seed", ex)
            ex!!
            assertEquals(setOf(Voice.SOPRANO), ex.given)
            assertTrue(ex.voice(Voice.BASS).isEmpty())
            assertEquals(ex.key.tonic, ex.voice(Voice.SOPRANO).last().pitch?.pc)
            val solved = Exercises.solve(ex)!!
            assertEquals(ex.voice(Voice.SOPRANO), solved.voice(Voice.SOPRANO))
            val issues = VoiceLeading.check(solved).issues
            assertTrue("$level $seed: ${issues.filter { it.severity == Severity.ERROR }.map { it.message }}", issues.none { it.severity == Severity.ERROR })
            assertEquals(ex, ScoreCodec.decode(ScoreCodec.encode(ex)))
            println("$level seed $seed: ${ms}ms, ${issues.size} warnings | ${ex.key.name} " +
                ex.voice(Voice.SOPRANO).joinToString(" ") { "${it.pitch}" })
        }
    }
}
