package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice

/**
 * A guided lesson on one idea of voice leading: hear it, why, watch it done, try it, see it in Bach,
 * then practice (find it in a passage, apply it in an exercise). A [lab] lesson instead starts with a note
 * built from its partials, then one page per overtone card with its task. LessonsTest checks every passage against the checker.
 */
class Lesson(
    val title: String,
    /** One line on the lessons list. */
    val idea: String,
    val hear: Hear?,
    val why: String?,
    /** Rules this lesson adds; a lesson's check uses these and those of the lessons before it. */
    val rules: List<Rule>,
    val watch: Watch?,
    val tasks: List<Task>,
    /** A passage with mistakes of this lesson to find yourself, and the message shown with it. */
    val find: Score, val findNote: String,
    /** What the exercise asks for, and its chords (names as in [Exercises]). */
    val apply: String, val chords: Set<String>,
    /** Shows the overtone cards (a note built from its partials, then what that means for voicing), task k under card k. */
    val lab: Boolean = false,
)

/** Two versions of a passage to play; [goodFirst]: version A is the good one. [answer] explains the difference. */
class Hear(val question: String, val good: Score, val bad: Score, val goodFirst: Boolean, val answer: String)

/** A passage written step by step: each step shows the first n notes of each voice ("SATB" digits) and says why. */
class Watch(val score: Score, val steps: List<Pair<String, String>>)

/** Pick the missing last note of [voice] in [score] (the score holds the best answer). */
class Task(val prompt: String, val score: Score, val voice: Voice, val options: List<Option>) {
    /** The passage with [p] as the missing note. */
    fun with(p: Pitch): Score = score.copy(voices = Voice.entries.map { v ->
        val l = score.voice(v)
        if (v == voice) l.dropLast(1) + l.last().copy(pitch = p) else l
    })
    /** What [p] is in the chord of the missing note: "root", "third", "fifth", "seventh", or null. */
    fun role(p: Pitch): String? = when (ChordAnalyzer.analyze(Voice.entries.map { score.voice(it).last().pitch!! }, score.key).member(p.pc)) {
        0 -> "root"; 2 -> "third"; 4 -> "fifth"; 6 -> "seventh"; else -> null
    }
    /** The passage without the missing note. */
    val open: Score get() = score.copy(voices = Voice.entries.map { if (it == voice) score.voice(it).dropLast(1) else score.voice(it) })
}

enum class Verdict { BEST, OK, WRONG }
class Option(val note: Pitch, val verdict: Verdict, val text: String)

object Lessons {

    /** Checked in every lesson: the basics of writing four voices at all. */
    private val BASE = setOf(Rule.STRUCTURE, Rule.MISSING_VOICE, Rule.FIGURES, Rule.UNRECOGNIZED, Rule.NAMED_CHORD)

    /** The rules a score of lesson [i] is checked with. */
    fun rulesUpTo(i: Int): Set<Rule> = BASE + ALL.take(i + 1).flatMap { it.rules }

    /** Chords "S A T B" in C major, whole notes (one per bar of 4/4). */
    private fun chords(vararg cs: String) = Score(voices = Voice.entries.map { v ->
        cs.map { c -> c.split(" ")[v.ordinal].let { Event(Fraction.of(1, 1), if (it == "r") null else Pitch.parse(it)) } }
    })

    private fun opt(note: String, v: Verdict, text: String) = Option(Pitch.parse(note), v, text)

    val ALL: List<Lesson> = listOf(
        Lesson(
            title = "The sound inside a note",
            idea = "What you really hear when one note plays.",
            hear = null, why = null, watch = null,
            rules = listOf(Rule.DOUBLING, Rule.SPACING),
            // One per card of Overtones.COMPARE, in its order: each card's page ends with its task
            tasks = listOf(
                Task("C major, bass C3. Which alto note?", chords("E5 C5 G4 C3"), Voice.ALTO, listOf(
                    opt("C5", Verdict.BEST, "The root, overtone 4 of the bass."),
                    opt("G4", Verdict.OK, "The fifth, overtone 3: fine."),
                    opt("E5", Verdict.WRONG, "E is already overtone 5 of the bass: now it takes over."),
                )),
                Task("A minor, bass A2. Which tenor note?", chords("C5 E4 A3 A2"), Voice.TENOR, listOf(
                    opt("A3", Verdict.BEST, "Close under the alto, far over the bass: spaced like the overtones."),
                    opt("E3", Verdict.OK, "Just an octave under the alto: as wide as it may get."),
                    opt("C3", Verdict.WRONG, "Over an octave under the alto: a hole in the middle."),
                )),
            ),
            find = chords("E5 G4 E4 C3", "F5 A4 C4 F3", "D5 B4 G4 G3", "C5 A4 E3 A2"),
            findNote = "Two chords go against the overtones. Tap a note in each, then Reveal.",
            apply = "Add soprano, alto and tenor over I, IV and V: root doubled, wide at the bottom, close at the top.",
            chords = setOf("I", "IV", "V"),
            lab = true,
        ),
        Lesson(
            title = "One chord, four singers",
            idea = "Three notes, four voices: what to double, where each voice goes.",
            hear = Hear(
                "Which chord sounds fuller?",
                good = chords("C5 G4 E4 C3"), bad = chords("C5 G4 C4 C3"), goodFirst = false,
                answer = "B. Only the tenor differs: in B it sings E, the third. Without the third, a chord sounds hollow.",
            ),
            why = "Four voices, three notes: one note is doubled. The root is the usual choice; the fifth works too. " +
                "Keep the third: it makes the chord major or minor. Keep soprano, alto and tenor within an octave of each other.",
            rules = listOf(Rule.MISSING_THIRD, Rule.RANGE, Rule.CROSSING),
            watch = Watch(chords("D5 G4 B3 G3"), listOf(
                "0001" to "Bass first: G, the root of G major (G, B, D).",
                "1001" to "Soprano: any chord note. Here D.",
                "1011" to "Tenor: B, the third. It makes the chord major.",
                "1111" to "Alto: G, a doubled root. D would work too.",
            )),
            tasks = listOf(
                Task("F major (F, A, C). Which tenor note?", chords("C5 A4 F4 F3"), Voice.TENOR, listOf(
                    opt("F4", Verdict.BEST, "Doubles the root: full and settled."),
                    opt("C4", Verdict.OK, "Doubles the fifth: fine, a bit lighter."),
                    opt("A3", Verdict.OK, "Doubles the third: possible, but it stands out a little."),
                    opt("F3", Verdict.WRONG, "Over an octave below the alto: a hole in the middle."),
                )),
                Task("G major (G, B, D). Which tenor note?", chords("D5 G4 B3 G2"), Voice.TENOR, listOf(
                    opt("B3", Verdict.BEST, "The third: now the chord is complete."),
                    opt("D4", Verdict.WRONG, "No third: the chord sounds hollow."),
                    opt("G3", Verdict.WRONG, "No third: the chord sounds hollow."),
                )),
                Task("C major (C, E, G). Which tenor note?", chords("E5 G4 C4 C3"), Voice.TENOR, listOf(
                    opt("C4", Verdict.BEST, "Doubles the root, close to the alto."),
                    opt("G3", Verdict.OK, "Doubles the fifth: fine."),
                    opt("E3", Verdict.WRONG, "Over an octave below the alto: a hole in the middle."),
                )),
                Task("G major (G, B, D). Which alto note?", chords("D5 G4 B3 G2"), Voice.ALTO, listOf(
                    opt("G4", Verdict.BEST, "Doubles the root, between soprano and tenor."),
                    opt("D4", Verdict.OK, "Doubles the fifth: fine."),
                    opt("G5", Verdict.WRONG, "Above the soprano: it would sound like the melody."),
                )),
            ),
            find = chords("E5 G4 C4 C3", "F5 A4 C4 F3", "D5 G4 D4 G2", "C5 G4 E3 C3"),
            findNote = "Two chords break this lesson's rules. Tap a note in each, then Reveal.",
            apply = "Add soprano, alto and tenor over the bass. Each bass note is the root of I, IV or V.",
            chords = setOf("I", "IV", "V"),
        ),
        Lesson(
            title = "Two chords: hold and step",
            idea = "Each voice is a melody: hold shared notes, step to near ones.",
            hear = Hear(
                "Which middle voices flow better?",
                good = chords("C5 G4 E4 C3", "C5 A4 F4 F3", "B4 G4 D4 G3"),
                bad = chords("C5 E4 G3 C3", "C5 C4 A3 F3", "B4 G4 D4 G3"),
                goodFirst = true,
                answer = "A. Only alto and tenor differ. In A they hold or step; in B they jump around.",
            ),
            why = "Each voice is a melody. Between chords, a voice can hold a shared note; the others move to a near note, often by step. " +
                "The bass may leap. Sevenths and augmented leaps are hard to sing.",
            rules = listOf(Rule.MELODIC, Rule.LEAPS, Rule.OVERLAP),
            watch = Watch(chords("G4 E4 C4 C3", "A4 F4 C4 F3"), listOf(
                "1112" to "C major to F major. The bass leaps C to F.",
                "1122" to "C is in both chords: the tenor holds it.",
                "1222" to "E steps up to F.",
                "2222" to "G steps up to A. Every upper voice held or stepped.",
            )),
            tasks = listOf(
                Task("C major to F major. Which tenor note?", chords("E5 G4 C4 C3", "F5 A4 C4 F3"), Voice.TENOR, listOf(
                    opt("C4", Verdict.BEST, "Holds the shared C."),
                    opt("F4", Verdict.OK, "Fine, but it leaps when C could stay."),
                    opt("A3", Verdict.WRONG, "Leaps, and doubles the third."),
                )),
                Task("F major to G major, no shared note. Which tenor note?", chords("A4 F4 C4 F3", "G4 D4 B3 G3"), Voice.TENOR, listOf(
                    opt("B3", Verdict.BEST, "Steps down to the third."),
                    opt("D4", Verdict.WRONG, "A step, but the chord has no third."),
                    opt("G3", Verdict.WRONG, "A leap, and the chord has no third."),
                )),
                Task("A minor to F major. Which alto note?", chords("C5 E4 A3 A2", "C5 F4 A3 F2"), Voice.ALTO, listOf(
                    opt("F4", Verdict.BEST, "Steps up; the others hold."),
                    opt("C4", Verdict.OK, "Fine, but it leaps when F was a step away."),
                    opt("A4", Verdict.WRONG, "Leaps, and doubles the third."),
                )),
            ),
            find = chords("G4 E4 C4 C3", "A4 F4 C4 F3", "G4 D4 B3 G2", "C5 G4 E4 C3"),
            findNote = "Two spots break this lesson's rules. Tap a note in each, then Reveal.",
            apply = "Add soprano, alto and tenor over the bass (I, ii, IV, V, vi). Hold shared notes, step to near ones.",
            chords = setOf("I", "ii", "IV", "V", "vi"),
        ),
    )
}
