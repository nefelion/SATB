package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.theory.Exercises
import com.nefelion.fourpartwriting.theory.Lessons
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.Severity
import com.nefelion.fourpartwriting.theory.Verdict
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Every lesson passage says what the checker says: good ones clean, mistakes to find flagged. */
class LessonsTest {

    private fun issues(sc: Score, i: Int) = VoiceLeading.check(sc, Lessons.rulesUpTo(i)).issues
    /** Every rule but the final cadence: lesson passages are fragments. */
    private fun strict(sc: Score) = VoiceLeading.check(sc, Rule.entries.toSet() - Rule.CADENCE).issues.map { it.message }

    @Test fun goodPassagesAreClean() = Lessons.ALL.forEachIndexed { i, l ->
        // Strict too: a good example should not teach something the later lessons forbid
        for ((what, sc) in listOfNotNull(l.hear?.let { "hear" to it.good }, l.watch?.let { "watch" to it.score })) {
            assertEquals("lesson $i $what", emptyList<Any>(), issues(sc, i).map { it.message })
            assertEquals("lesson $i $what, all rules", emptyList<Any>(), strict(sc))
        }
    }

    @Test fun tasksMatchTheChecker() = Lessons.ALL.forEachIndexed { i, l ->
        for ((k, t) in l.tasks.withIndex()) {
            val best = t.options.single { it.verdict == Verdict.BEST }
            assertEquals("lesson $i task $k: the score holds the best answer", t.score, t.with(best.note))
            assertEquals("lesson $i task $k best", emptyList<Any>(), strict(t.score))
            for (o in t.options) {
                val all = issues(t.with(o.note), i)
                val errs = all.filter { it.severity == Severity.ERROR }
                // A wrong answer of the first two lessons breaks one of their rules; later lessons also teach smoothness, which no rule checks
                if (o.verdict == Verdict.OK) assertEquals("lesson $i task $k ${o.note} is fine", emptyList<Any>(), errs.map { it.message })
                if (o.verdict == Verdict.WRONG && i <= 1) assertTrue("lesson $i task $k ${o.note} is flagged", all.isNotEmpty())
            }
        }
    }

    @Test fun findHasItsMistakes() {
        val want = listOf(setOf(Rule.DOUBLING, Rule.SPACING, Rule.SIX_FOUR), setOf(Rule.MISSING_THIRD, Rule.SPACING), setOf(Rule.MELODIC, Rule.LEAPS, Rule.OVERLAP))
        Lessons.ALL.forEachIndexed { i, l ->
            val got = issues(l.find, i)
            assertEquals("lesson $i find", want[i], got.map { it.rule }.toSet())
            // Lesson 3's bass leap is both a seventh and a leap not turned back
            assertEquals("lesson $i find", listOf(3, 2, 3)[i], got.size)
        }
    }

    @Test fun exercisesUseTheLessonChords() = Lessons.ALL.forEach { l ->
        repeat(5) { seed ->
            assertNotNull(Exercises.generate(Exercises.Level.EASY, Random(seed), only = l.chords))
        }
    }
}
