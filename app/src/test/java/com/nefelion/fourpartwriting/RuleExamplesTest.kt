package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.theory.Rule
import com.nefelion.fourpartwriting.theory.RuleExamples
import com.nefelion.fourpartwriting.theory.VoiceLeading
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleExamplesTest {
    /** Every rule has an example whose wrong version breaks it and whose right version does not. */
    @Test fun everyRuleHasAWorkingExample() {
        val failures = Rule.entries.mapNotNull { r ->
            val ex = RuleExamples.ALL[r] ?: return@mapNotNull "$r: no example"
            val bad = VoiceLeading.check(ex.bad, setOf(r)).issues
            val good = VoiceLeading.check(ex.good, setOf(r)).issues
            when {
                bad.isEmpty() -> "$r: wrong version raises nothing"
                good.isNotEmpty() -> "$r: right version raises ${good.map { it.message }}"
                // The card draws only the shown voices, so the offending notes must be among them
                bad.flatMap { i -> i.notes.map { it.second } }.any { it !in ex.show } -> "$r: flags a hidden voice ${bad.map { it.notes }}"
                else -> null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun ignoredIssuesAreHidden() {
        val ex = RuleExamples.ALL.getValue(Rule.PARALLEL_OCTAVES).bad
        val a = VoiceLeading.check(ex)
        val issue = a.issues.first { it.rule == Rule.PARALLEL_OCTAVES }
        val ignored = VoiceLeading.check(ex.copy(ignored = setOf(VoiceLeading.ignoreKey(issue, a.slices)))).issues
        assertTrue(issue !in ignored && ignored.size == a.issues.size - 1)
        assertTrue(VoiceLeading.check(ex.copy(rulesOff = setOf(Rule.PARALLEL_OCTAVES.name))).issues.none { it.rule == Rule.PARALLEL_OCTAVES })
    }
}
