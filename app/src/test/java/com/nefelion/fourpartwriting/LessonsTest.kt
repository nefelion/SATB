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
        assertEquals("lesson $i hear", emptyList<Any>(), issues(l.hear.good, i).map { it.message })
        assertEquals("lesson $i watch", emptyList<Any>(), issues(l.watch.score, i).map { it.message })
        // Strict too: a good example should not teach something the later lessons forbid
        assertEquals("lesson $i hear, all rules", emptyList<Any>(), strict(l.hear.good))
        assertEquals("lesson $i watch, all rules", emptyList<Any>(), strict(l.watch.score))
    }

    @Test fun tasksMatchTheChecker() = Lessons.ALL.forEachIndexed { i, l ->
        for ((k, t) in l.tasks.withIndex()) {
            val best = t.options.single { it.verdict == Verdict.BEST }
            assertEquals("lesson $i task $k: the score holds the best answer", t.score, t.with(best.note))
            assertEquals("lesson $i task $k best", emptyList<Any>(), strict(t.score))
            for (o in t.options) {
                val all = issues(t.with(o.note), i)
                val errs = all.filter { it.severity == Severity.ERROR }
                // A wrong answer of the first lesson breaks one of its rules; later lessons also teach smoothness, which no rule checks
                if (o.verdict == Verdict.OK) assertEquals("lesson $i task $k ${o.note} is fine", emptyList<Any>(), errs.map { it.message })
                if (o.verdict == Verdict.WRONG && i == 0) assertTrue("lesson $i task $k ${o.note} is flagged", all.isNotEmpty())
            }
        }
    }

    @Test fun findHasItsMistakes() {
        val want = listOf(setOf(Rule.MISSING_THIRD, Rule.SPACING), setOf(Rule.MELODIC, Rule.LEAPS, Rule.OVERLAP))
        Lessons.ALL.forEachIndexed { i, l ->
            val got = issues(l.find, i)
            assertEquals("lesson $i find", want[i], got.map { it.rule }.toSet())
            // Lesson 2's bass leap is both a seventh and a leap not turned back
            assertEquals("lesson $i find", if (i == 1) 3 else 2, got.size)
        }
    }

    @Test fun exercisesUseTheLessonChords() = Lessons.ALL.forEach { l ->
        repeat(5) { seed ->
            assertNotNull(Exercises.generate(Exercises.Level.EASY, Random(seed), only = l.chords))
        }
    }
}
