package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.NctType
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Non-homorhythmic writing: florid lines over held chords, syncopation, ornamented resolutions, pedals. */
class PolyphonicEdgeTest {
    private val S = Voice.SOPRANO
    private val A = Voice.ALTO
    private val T = Voice.TENOR
    private val B = Voice.BASS
    private val Am = Key(0, Mode.MINOR)
    private val T24 = TimeSig(2, 4)
    private val T34 = TimeSig(3, 4)

    private fun Analysis.noErrors() = assertTrue(issues.toString(), errorRules().isEmpty())
    private fun Analysis.lacks(r: Rule) = assertFalse(issues.toString(), r in rules())

    // ---- Ornaments over a held chord keep the chord ----

    /** C–D–E–D–C over a C chord held for two bars: one chord, no harmonic-rhythm problem. */
    @Test fun floridSopranoOverHeldChord() {
        val a = linesScore("C5:4 D5:4 E5:4 D5:4 C5:1", "G4:1 G4:1", "E4:1 E4:1", "C3:1 C3:1").analysis()
        assertEquals(List(5) { "I" }, a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(S, 3)?.type)
        a.lacks(Rule.HARMONIC_RHYTHM)
        a.noErrors()
    }

    /** C–B–A–G over a held C chord: passing tones on the beat, not I⁷ and vi⁶₅. */
    @Test fun descendingPassingTonesAreNotSeventhChords() {
        val a = linesScore("C5:4 B4:4 A4:4 G4:4 C5:1", "E4:1 E4:1", "G3:1 G3:1", "C3:1 C3:1").analysis()
        assertEquals(List(5) { "I" }, a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(S, 2)?.type)
        assertTrue(a.issues.toString(), a.issues.isEmpty())
    }

    /** A passing seventh over a held dominant (V8–7) still makes V⁷. */
    @Test fun passingSeventhOverDominantIsVSeven() {
        val a = linesScore("G5:4 F5:4 E5:2", "B4:2 C5:2", "D4:2 C4:2", "G2:2 C3:2").analysis()
        assertEquals(listOf("V", "V⁷", "I"), a.romans())
        a.noErrors()
    }

    /** A passing tone struck twice (C–D–D–E) is one passing tone. */
    @Test fun repeatedPassingTone() {
        val a = linesScore("C5:4 D5:8 D5:8 E5:2", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(S, 2)?.type)
        a.noErrors()
    }

    /** A neighbor struck twice (E–F–F–E) is one neighbor, not a sus4 chord. */
    @Test fun repeatedNeighbor() {
        val a = linesScore("E5:4 F5:8 F5:8 E5:2", "G4:1", "C4:1", "C3:1").analysis()
        assertEquals(listOf("I", "I", "I"), a.romans())
        assertEquals(NctType.NT, a.nct(S, 1)?.type)
        assertEquals(NctType.NT, a.nct(S, 2)?.type)
        a.noErrors()
    }

    /** A bass passing tone under held upper voices keeps the chord in root position. */
    @Test fun bassPassingToneUnderHeldChord() {
        val a = linesScore("E5:1", "C5:1", "G4:1", "C3:4 D3:4 E3:2").analysis()
        assertEquals(listOf("I", "I", "I⁶"), a.romans())
        assertEquals(NctType.PT, a.nct(B, 1)?.type)
    }

    /** Soprano and bass in parallel tenths passing together under a held chord. */
    @Test fun passingTenthsOuterVoices() {
        val a = linesScore("E5:4 F5:4 G5:2", "G4:1", "C4:1", "C3:4 D3:4 E3:2").analysis()
        assertEquals(listOf("I", "I", "I⁶"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(B, 1)?.type)
        a.noErrors()
    }

    /** Double neighbor (C–D–B–C) on the beats over a held chord. */
    @Test fun doubleNeighborOnBeats() {
        val a = linesScore("C5:4 D5:4 B4:4 C5:4", "G4:1", "E4:1", "C3:1").analysis()
        assertEquals(List(4) { "I" }, a.romans())
        a.noErrors()
    }

    /** Cambiata: step down to a dissonance, leap a third down, step back up (E–D–B–C). */
    @Test fun cambiata() {
        val a = linesScore("E5:4 D5:4 B4:4 C5:4", "G4:1", "C4:1", "C3:1").analysis()
        assertEquals(List(4) { "I" }, a.romans())
        assertEquals(NctType.CAMB, a.nct(S, 1)?.type)
        a.noErrors()
    }

    /** A chromatic passing tone over a held chord (C–C♯–D into ii). */
    @Test fun chromaticPassingToneOverHeldChord() {
        val a = linesScore("C5:4 C#5:4 D5:2", "G4:2 F4:2", "E4:2 A3:2", "C3:2 D3:2").analysis()
        assertEquals(listOf("I", "I", "ii"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        a.noErrors()
    }

    /** Quarter-note anticipation on beat 4 (struck again on the downbeat) over V⁷. */
    @Test fun anticipationOnTheBeat() {
        val a = linesScore("E5:2 D5:2 C5:1", "C5:8 B4:8 A4:8 G4:8 A4:8 B4:8 C5:4 C5:1", "G4:2 F4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "I", "V⁷", "V⁷", "I"), a.romans())
        assertEquals(NctType.ANT, a.nct(A, 6)?.type)
        a.lacks(Rule.PROGRESSION)
        a.lacks(Rule.CADENCE)
    }

    // ---- Chord changes are not hidden ----

    /** A held note at a new chord point is a chord tone of the new chord, not an anticipation of the next one. */
    @Test fun heldNoteIsNotAnticipation() {
        val a = linesScore("B4:4 D5:8 C5:8 C5:4", "G4:4 G4:2", "D4:4 E4:2", "G2:4 C3:2", time = T34).analysis()
        assertEquals(listOf("V", "I", "I"), a.romans())
        assertEquals(NctType.APP, a.nct(S, 1)?.type)
        assertTrue(a.ncts.toString(), a.ncts.keys.none { it.voice == T || it.voice == B })
    }

    /** Chorale bar with passing tones in soprano and bass: I – IV⁶ – V – I. */
    @Test fun choraleBarWithPassingTones() {
        val a = linesScore("E5:4 F5:4 D5:4 C5:4", "G4:4 F4:4 G4:8 F4:8 E4:4", "C4:4 C4:4 B3:4 C4:4", "C3:8 B2:8 A2:4 G2:4 C3:4").analysis()
        assertEquals(listOf("I", "IV⁶", "V", "I"), a.romans())
        assertEquals(NctType.PT, a.nct(A, 3)?.type)
        assertEquals(NctType.PT, a.nct(B, 1)?.type)
        a.noErrors()
    }

    /** V on beat 4 held into the downbeat is still a weak-to-strong repetition, ornaments or not. */
    @Test fun weakBeatChordIntoDownbeatStillFlagged() {
        val a = linesScore("E5:2. D5:4 B4:2 C5:2", "G4:2. G4:4 G4:2 G4:2", "C4:2. B3:4 D4:2 E4:2", "C3:2. G2:4 G2:2 C3:2").analysis()
        assertTrue(a.issues.toString(), Rule.HARMONIC_RHYTHM in a.rules())
    }

    /** A chord held from one downbeat over the bar line is fine. */
    @Test fun chordFromDownbeatOverBarLine() {
        val a = linesScore("C5:2. E5:4 E5:1", "G4:2. G4:4 G4:1", "E4:2. C4:4 C4:1", "C3:2. C3:4 C3:1").analysis()
        a.lacks(Rule.HARMONIC_RHYTHM)
    }

    // ---- Ornamented resolutions ----

    /** The leading tone leaps to another note of V and the voice still reaches the tonic (B–D–C). */
    @Test fun leadingToneArpeggiatedBeforeTonic() {
        val a = linesScore("E5:2 B4:4. D5:8 C5:1", "G4:2 G4:2 G4:1", "C4:2 D4:2 E4:1", "C3:2 G2:2 C3:1").analysis()
        a.lacks(Rule.LEADING_TONE)
    }

    /** B over V, then an appoggiatura D over I resolving to C: the leading tone does reach the tonic. */
    @Test fun leadingToneThroughAppoggiatura() {
        val a = linesScore("B4:4 D5:8 C5:8 C5:4", "G4:4 G4:2", "D4:4 E4:2", "G2:4 C3:2", time = T34).analysis()
        a.lacks(Rule.LEADING_TONE)
    }

    /** The leading tone leaps down within V and the voice lands on E: not resolved. */
    @Test fun leadingToneAbandoned() {
        val a = linesScore("B4:8 G4:8 E4:4", "G4:4 C4:4", "D4:4 G3:4", "G2:4 C3:4", time = T24).analysis()
        assertTrue(a.issues.toString(), Rule.LEADING_TONE in a.errorRules())
    }

    /** F–D–E: the seventh's resolution ornamented by a chord tone. */
    @Test fun seventhOrnamentedResolution() {
        val a = linesScore("F5:8 D5:8 E5:4", "B4:4 C5:4", "D4:4 G4:4", "G2:4 C3:4", time = T24).analysis()
        assertEquals(listOf("V⁷", "I"), a.romans())
        a.lacks(Rule.SEVENTH)
    }

    /** F–G–F–E: an upper neighbor before the seventh resolves. */
    @Test fun seventhWithUpperNeighbor() {
        val a = linesScore("F5:16 G5:16 F5:8 E5:4", "B4:4 C5:4", "D4:4 G4:4", "G2:4 C3:4", time = T24).analysis()
        a.lacks(Rule.SEVENTH)
    }

    /** F–B–C: the seventh leaps away and never steps down. */
    @Test fun seventhLeftByLeap() {
        val a = linesScore("F5:8 B4:8 C5:4", "B4:4 G4:4", "D4:4 E4:4", "G2:4 C3:4", time = T24).analysis()
        assertTrue(a.issues.toString(), Rule.SEVENTH in a.errorRules())
    }

    /** F–A–G: an appoggiatura does not turn a rising seventh into a resolution. */
    @Test fun seventhRisesThroughAppoggiatura() {
        val a = linesScore("F5:8 A5:8 G5:4", "B4:4 C5:4", "D4:4 E4:4", "G2:4 C3:4", time = T24).analysis()
        assertTrue(a.issues.toString(), Rule.SEVENTH in a.errorRules())
    }

    /** An escape tone between V and I. */
    @Test fun escapeToneIntoTonic() {
        val a = linesScore("D5:8 E5:8 C5:4", "B4:4 G4:4", "G4:4 E4:4", "G2:4 C3:4", time = T24).analysis()
        assertEquals(NctType.ESC, a.nct(S, 1)?.type)
        a.noErrors()
    }

    // ---- Suspensions ----

    /** The bass leaps an octave as the 4–3 resolves: still a suspension over V. */
    @Test fun suspensionWithBassOctaveLeap() {
        val a = linesScore("C5:4 C5:8 B4:8 C5:4", "G4:4 G4:4 E4:4", "E4:4 D4:4 C4:4", "C3:4 G2:8 G3:8 C3:4", time = T34).analysis()
        assertEquals(listOf("I", "V", "I"), a.romans())
        assertEquals("4–3", a.nct(S, 1)?.label)
        a.noErrors()
    }

    /** 9–8 and 4–3 together, resolving at different times. */
    @Test fun staggeredDoubleSuspension() {
        val a = linesScore("C5:2 B4:4 C5:4", "A4:4 A4:8 G4:8 G4:4 G4:4", "F4:4 D4:4 D4:4 E4:4", "F2:4 G2:4 G2:4 C3:4").analysis()
        assertEquals(listOf("IV", "V", "V", "I"), a.romans())
        assertEquals("4–3", a.nct(S, 0)?.label)
        assertEquals("9–8", a.nct(A, 1)?.label)
        a.noErrors()
    }

    /** Suspension prepared on an off-beat eighth and tied over the beat. */
    @Test fun syncopatedPreparation() {
        val a = linesScore("E5:4 D5:8 C5:4 B4:8 C5:4", "G4:2 G4:4 G4:4", "C4:2 D4:4 E4:4", "C3:2 G2:4 C3:4").analysis()
        assertEquals(listOf("I", "I", "V", "I"), a.romans())
        assertEquals("4–3", a.nct(S, 2)?.label)
        a.noErrors()
    }

    /** The same, with the suspension struck again on the beat. */
    @Test fun restruckOffbeatPreparation() {
        val a = linesScore("E5:4 D5:8 C5:8 C5:8 B4:8 C5:4", "G4:2 G4:4 G4:4", "C4:2 D4:4 E4:4", "C3:2 G2:4 C3:4").analysis()
        assertEquals(listOf("I", "I", "V", "I"), a.romans())
        assertEquals("4–3", a.nct(S, 3)?.label)
        a.noErrors()
    }

    // ---- Pedals ----

    /** Inverted pedal: the soprano holds the tonic over I–V–V–I. */
    @Test fun invertedPedal() {
        val a = linesScore("C5:1", "E4:4 D4:4 D4:4 E4:4", "G3:4 B3:4 B3:4 G3:4", "C3:4 G2:4 G2:4 C3:4").analysis()
        assertEquals(listOf("I", "V", "V", "I"), a.romans())
        assertEquals(NctType.PED, a.nct(S, 0)?.type)
        a.noErrors()
    }

    /** A held note that moves on while still dissonant is no pedal. */
    @Test fun heldDissonanceLeftUpward() {
        val a = linesScore("C5:1 D5:1", "E4:2 D4:2 D4:1", "G3:2 B3:2 B3:1", "C3:2 G2:2 G2:1").analysis()
        assertTrue(a.issues.toString(), Rule.UNRESOLVED_NCT in a.errorRules())
    }

    // ---- Meters ----

    /** 6/8: passing eighths over dotted-quarter chords. */
    @Test fun compoundMeterPassingTones() {
        val a = linesScore("E5:8 F5:8 G5:8 F5:8 E5:8 D5:8 C5:2.", "C5:4. B4:4. G4:2.", "G4:4. G4:4. E4:2.", "C3:4. G2:4. C3:2.", time = TimeSig(6, 8)).analysis()
        assertEquals(listOf("I", "V⁷", "I"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        assertEquals(NctType.PT, a.nct(S, 4)?.type)
        a.noErrors()
    }

    /** 2/2: quarter-note passing tone between half-note beats. */
    @Test fun cutTimePassing() {
        val a = linesScore("C5:4 D5:4 E5:2 D5:2 C5:2", "G4:2 G4:2 G4:2 E4:2", "E4:2 C4:2 B3:2 C4:2", "C3:2 C3:2 G2:2 C3:2", time = TimeSig(2, 2)).analysis()
        assertEquals(listOf("I", "I", "V", "I"), a.romans())
        assertEquals(NctType.PT, a.nct(S, 1)?.type)
        a.noErrors()
    }

    /** Minor: florid alto with the raised leading tone. */
    @Test fun minorFloridAlto() {
        val a = linesScore("E5:2 D5:2 C5:1", "A4:4 G#4:8 A4:8 G#4:2 A4:1", "C4:2 E4:2 E4:1", "A2:2 E3:2 A2:1", key = Am).analysis()
        assertEquals(listOf("i", "i", "V⁷", "i"), a.romans())
        assertEquals(NctType.NT, a.nct(A, 1)?.type)
        a.noErrors()
    }

    // ---- Obligations across a chord held over several beats ----

    /** B on beat 3 of V, passing A on beat 4, G at I: the leading tone still fell. */
    @Test fun leadingToneFallsThroughPassingToneOnLaterBeat() {
        val a = linesScore("E5:2 B4:4 A4:4 G4:1", "G4:2 G4:2 E4:1", "C4:2 D4:2 G3:1", "C3:2 G2:2 C3:1").analysis()
        assertEquals(listOf("I", "V", "V", "I"), a.romans())
        assertTrue(a.issues.toString(), Rule.LEADING_TONE in a.errorRules())
    }

    /** An inner leading tone may pass down to the fifth (B–A–G). */
    @Test fun innerLeadingTonePassesToFifth() {
        val a = linesScore("D5:2 C5:2", "B4:4 A4:4 G4:2", "G4:2 E4:2", "G2:2 C3:2").analysis()
        assertEquals(listOf("V", "V", "I"), a.romans())
        a.lacks(Rule.LEADING_TONE)
    }

    /** The seventh on beat 3, an upper neighbor of it on beat 4, then the step down. */
    @Test fun seventhNeighborOnLaterBeat() {
        val a = linesScore("F5:4 G5:4 F5:4 E5:4", "B4:2. C5:4", "D4:2. C4:4", "G2:2. C3:4", time = T34).analysis()
        a.lacks(Rule.SEVENTH)
    }

    /** The seventh rising by step to the root within V (7–8) is a passing motion, as V⁷ → V. */
    @Test fun seventhRisingToRootWithinChord() {
        val a = linesScore("F5:4 G5:4 A5:4 G5:4", "B4:2. C5:4", "D4:2. E4:4", "G2:2. C3:4").analysis()
        a.lacks(Rule.SEVENTH)
    }

    /** The seventh leaping away to another note of V⁷ on a later beat is left unresolved. */
    @Test fun seventhLeftByLeapOnLaterBeat() {
        val a = linesScore("F5:4 D5:4 C5:2", "B4:2 C5:2", "D4:2 E4:2", "G2:2 C3:2").analysis()
        assertTrue(a.issues.toString(), Rule.SEVENTH in a.errorRules())
    }

    /** The seventh of V⁷ held into I as a 4–3. */
    @Test fun seventhSuspendedIntoTonic() {
        val a = linesScore("F5:2 F5:4 E5:4", "B4:2 C5:2", "D4:2 G4:2", "G2:2 C3:2").analysis()
        assertEquals("4–3", a.nct(S, 1)?.label)
        a.lacks(Rule.SEVENTH)
    }

    /** Bass 2–3 suspension under upper voices that move on. */
    @Test fun bassSuspension() {
        val a = linesScore("E5:4 D5:2", "G4:4 G4:2", "C4:4 D4:2", "C3:2 B2:4", time = T34).analysis()
        assertEquals(listOf("I", "V⁶"), a.romans().distinct())
        assertEquals("2–3", a.nct(B, 0)?.label)
        a.noErrors()
    }

    /** 3/4: V arriving on beat 3 and held into the downbeat under a moving soprano. */
    @Test fun weakBeatChordIntoDownbeatTripleMeter() {
        val a = linesScore("E5:2 D5:4 B4:4 C5:2", "G4:2 G4:4 G4:2.", "C4:2 B3:4 D4:2.", "C3:2 G2:4 G2:2.", time = T34).analysis()
        assertTrue(a.issues.toString(), Rule.HARMONIC_RHYTHM in a.rules())
    }

    /** Fifths between beats hidden by a passing tone (G/C – A – A/D with the A struck again) still count. */
    @Test fun fifthsAcrossAnticipationStillParallel() {
        val a = linesScore("G5:8 A5:8 A5:4", "E5:4 F5:4", "C5:4 D5:4", "C3:4 D3:4", time = T24).analysis()
        assertTrue(a.issues.toString(), Rule.PARALLEL_FIFTHS in a.errorRules())
    }

    /** The seventh handed from soprano to alto (V⁷ → V⁶₅) resolves in the alto; the soprano is free. */
    @Test fun transferredSeventh() {
        val a = chordsScore(KC, "F5 B4 D4 G2", "D5 F4 G3 B2", "C5 E4 G3 C3").analysis()
        assertEquals(listOf("V⁷", "V⁶₅", "I"), a.romans())
        a.lacks(Rule.SEVENTH)
    }
}
