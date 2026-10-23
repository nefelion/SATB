package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Edit
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteTest {
    private val S = Voice.SOPRANO
    private fun keys(sc: com.nefelion.fourpartwriting.model.Score) =
        VoiceLeading.check(sc).let { a -> a.issues.map { VoiceLeading.ignoreKey(it, a.slices) } }

    @Test fun ignoreHereEndsOnceTheIssueGoesAway() {
        // Soprano B4 over G/B with B3 in the alto: doubled leading tone
        val bad = Edit.setPitch(EXAMPLE, S, 2, Pitch.parse("B4"))
        val key = keys(bad).first { it.startsWith(Rule.DOUBLING.name) }
        val muted = bad.copy(ignored = setOf(key))
        assertFalse(key in keys(muted))
        // Kept while the issue is still there
        assertTrue(key in VoiceLeading.pruneMutes(muted).ignored)
        // Note moved up and back down: the mute is gone and the issue is shown again
        val up = VoiceLeading.pruneMutes(Edit.setPitch(muted, S, 2, Pitch.parse("C5")))
        val back = VoiceLeading.pruneMutes(Edit.setPitch(up, S, 2, Pitch.parse("B4")))
        assertTrue(back.ignored.isEmpty())
        assertTrue(key in keys(back))
    }
}
