package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.theory.Analysis
import com.nefelion.fourpartwriting.theory.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Invariants: transposing a whole score to another key of the same mode, or doubling every
 * note value (with a matching time signature), must not change the analysis.
 */
class PropertyEdgeTest {
    private val Am = Key(0, Mode.MINOR)

    private val corpus: List<Pair<String, Score>> = listOf(
        "example" to EXAMPLE,
        "I IV V I" to chordsScore(KC, "E4 C4 G3 C3", "F4 C4 A3 F2", "D4 B3 G3 G2", "E4 C4 G3 C3"),
        "parallel 5ths" to chordsScore(KC, "G4 E4 C4 C3", "A4 F4 D4 D3"),
        "V7 bad resolution" to chordsScore(KC, "B4 F4 D4 G2", "G4 G4 E4 C3"),
        "retardation" to chordsScore(KC, "B4 D4 G3 G2", "B4 E4 G3 C3", "C5 E4 G3 C3"),
        "secondary dominants" to chordsScore(KC, "E4 C4 G3 C3", "F#4 C4 A3 D3", "G4 B3 G3 G2", "E4 C4 G3 C3"),
        "V7/V to V7" to chordsScore(KC, "F#4 C4 A3 D3", "F4 B3 G3 G2", "E4 C4 G3 C3"),
        "V/V to Cad64" to chordsScore(KC, "A4 F#4 D4 D3", "C5 G4 E4 G2", "B4 G4 D4 G2", "C5 G4 E4 C3"),
        "Ger6 Cad64" to chordsScore(KC, "F#4 Eb4 C4 Ab2", "G4 E4 C4 G2", "G4 D4 B3 G2", "G4 E4 C4 C3"),
        "mixture iv" to chordsScore(KC, "C5 Ab4 F4 F3", "D5 A4 F4 D4"),
        "N6 minor" to chordsScore(Key(-3, Mode.MINOR), "Db5 Ab4 F4 F3", "C5 G4 Eb4 G3", "B4 G4 D4 G3", "C5 G4 Eb4 C3"),
        "minor cadence" to chordsScore(Am, "C5 A4 E4 A3", "D5 A4 D4 F3", "B4 G#4 E4 E3", "C5 A4 E4 A2"),
        "picardy" to chordsScore(Am, "B4 E4 G#3 E3", "C#5 E4 A3 A2"),
        "frustrated LT" to chordsScore(Key(-3, Mode.MINOR), "D5 G4 B3 G2", "C5 Ab4 G3 Ab2"),
        "aug2 melodic" to chordsScore(Am, "F4 C4 A3 D3", "G#4 B3 E3 E3"),
        "cross relation" to chordsScore(KC, "F4 C4 A3 D3", "A4 E4 C4 F#3"),
        "six-four" to chordsScore(KC, "F4 D4 A3 F3", "E4 C4 C4 G3", "F4 D4 A3 F3"),
        "sus chord" to chordsScore(KC, "C5 E4 G3 C3", "F4 C4 G3 C3", "E4 C4 G3 C3"),
        "chromatic PT" to linesScore("C5:8 C#5:8 D5:4", "G4:4 A4:4", "E4:4 F4:4", "C3:4 F3:4"),
        "accented NT" to linesScore("E5:4 F5:8 E5:8", "G4:2", "C4:2", "C3:2"),
        "4-3" to linesScore("C5:4. B4:8", "G4:2", "E4:4 D4:4", "C3:4 G2:4"),
        "9-8" to linesScore("D5:4. C5:8", "B4:4 G4:4", "G4:4 E4:4", "G3:4 C3:4"),
        "2-3 bass" to linesScore("G4:2", "E4:4 D4:4", "C4:4 B3:4", "C3:4. B2:8"),
        "double sus" to linesScore("C5:4. B4:8", "A4:4. G4:8", "F4:4 D4:4", "F3:4 G3:4"),
        "ornamented sus" to linesScore("C5:4. A4:16 B4:16", "G4:2", "E4:4 D4:4", "C3:4 G2:4"),
        "hidden 5ths" to linesScore("E5:8 D5:8 C5:4", "C5:4 A4:4", "E4:4 F4:4", "A3:4 F3:4"),
        "esc/app/ant" to linesScore("E4:8 F4:8 D4:4 D4:8 C4:8 C4:4", "C4:4 B3:4 B3:4 C4:4", "G3:4 G3:4 G3:4 E3:4", "C3:4 G2:4 G2:4 C3:4"),
        "pedal" to linesScore("E4:4 D4:4", "C4:4 B3:4", "G3:4 F3:4", "C3:2"),
        "minor PT" to linesScore("B4:4 C5:8 B4:8 A4:4", "E4:4 E4:4 E4:4", "G#3:4 A3:4 C4:4", "E3:4 A2:4 A2:4", key = Am),
        "unresolved sus" to linesScore("C5:4. E5:8", "G4:2", "E4:4 D4:4", "C3:4 G2:4"),
        "rests" to linesScore("r:4 E5:4", "G4:2", "C4:2", "C3:2"),
    )

    @Test fun transpositionDoesNotChangeTheAnalysis() {
        val failures = mutableListOf<String>()
        for ((name, sc) in corpus) {
            val base = sc.analysis().fingerprint()
            for (f in -7..7) {
                val k = Key(f, sc.key.mode)
                val t = sc.transposedTo(k) ?: continue
                val got = t.analysis().fingerprint()
                if (got != base) failures += "$name in ${k.name}:\n  base $base\n  got  $got"
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun doublingNoteValuesDoesNotChangeTheAnalysis() {
        val failures = mutableListOf<String>()
        for ((name, sc) in corpus) {
            val two = Fraction.of(2, 1)
            val time = TimeSig(sc.time.beats, sc.time.beatUnit / 2)
            val aug = sc.copy(time = time, voices = sc.voices.map { l -> l.map { it.copy(length = it.length * two) } })
            val base = sc.analysis().fingerprint()
            val got = aug.analysis().fingerprint()
            if (got != base) failures += "$name:\n  base $base\n  got  $got"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
