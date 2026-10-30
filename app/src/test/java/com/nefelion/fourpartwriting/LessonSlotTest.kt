package com.nefelion.fourpartwriting

import org.junit.Assert.assertEquals
import org.junit.Test

class LessonSlotTest {
    @Test
    fun newestCopyOfEachLessonMoves() {
        val names = listOf("My chorale", "Lesson 2 exercise (3)", "Lesson 1 exercise", "Lesson 2 exercise", "Lesson 2 exercise (2)")
        val lesson = mapOf("Lesson 2 exercise (3)" to 1, "Lesson 1 exercise" to 0, "Lesson 2 exercise" to 1, "Lesson 2 exercise (2)" to 1)
        assertEquals(mapOf(1 to "Lesson 2 exercise (3)", 0 to "Lesson 1 exercise"), ScoreLibrary.slotMoves(names, lesson::get))
    }

    @Test
    fun nameAloneIsNotEnough() {
        // A renamed score of your own, or one whose lesson doesn't match its name, stays put
        val names = listOf("Lesson 3 exercise", "Lesson 4 exercise copy", "Lesson 5 exercise")
        val lesson = mapOf("Lesson 3 exercise" to null, "Lesson 4 exercise copy" to 3, "Lesson 5 exercise" to 1)
        assertEquals(emptyMap<Int, String>(), ScoreLibrary.slotMoves(names, lesson::get))
    }
}
