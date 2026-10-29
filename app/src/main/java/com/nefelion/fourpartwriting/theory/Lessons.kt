package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice

/**
 * A guided lesson on one idea of voice leading, in seven steps: hear it, why, watch it done, try it,
 * find it in a passage, see it in Bach, apply it in an exercise. LessonsTest checks every passage against the checker.
 */
class Lesson(
    val title: String,
    /** One line on the lessons list. */
    val idea: String,
    val hear: Hear,
    val why: String,
    /** Rules this lesson adds; a lesson's check uses these and those of the lessons before it. */
    val rules: List<Rule>,
    val watch: Watch,
    val tasks: List<Task>,
    /** A passage with mistakes of this lesson to find yourself, and the message shown with it. */
    val find: Score, val findNote: String,
    /** What the exercise asks for, and its chords (names as in [Exercises]). */
    val apply: String, val chords: Set<String>,
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

    /** Whole-note chords "S A T B", one per bar of 4/4, in C major. */
    private fun chords(vararg cs: String) = Score(voices = Voice.entries.map { v ->
        cs.map { c -> c.split(" ")[v.ordinal].let { Event(Fraction.of(1, 1), if (it == "r") null else Pitch.parse(it)) } }
    })

    private fun opt(note: String, v: Verdict, text: String) = Option(Pitch.parse(note), v, text)

    val ALL: List<Lesson> = listOf(
        Lesson(
            title = "One chord, four singers",
            idea = "Three notes, four voices: which note to double and where each voice goes.",
            hear = Hear(
                "Which chord sounds full?",
                good = chords("E5 G4 C4 C3"), bad = chords("C5 G4 G3 C3"), goodFirst = false,
                answer = "B. Chord A has only C and G, no E: an open, hollow sound. E is the third of the chord, and the third decides whether a chord is major or minor, so every chord needs it.",
            ),
            why = "Four voices share a chord of three notes, so one note sounds twice. Double the root, the steadiest note, and never leave out the third. " +
                "Keep soprano, alto and tenor close, each within an octave of the next, so the chord blends; the bass may lie further down. " +
                "Keep every voice in its range and in its place: soprano on top, then alto, tenor, and the bass at the bottom.",
            rules = listOf(Rule.MISSING_THIRD, Rule.DOUBLING, Rule.SPACING, Rule.RANGE, Rule.CROSSING),
            watch = Watch(chords("D5 G4 B3 G3"), listOf(
                "0001" to "Start with the bass. Here it is G, the root of the G major chord (G, B, D).",
                "1001" to "Then the soprano, the melody on top. Any note of the chord in its range works; here D, the fifth.",
                "1011" to "The third, B, must sound: it makes the chord major. The tenor takes it.",
                "1111" to "Four voices, three notes: one sounds twice. Double the root, G, the steadiest note. The alto takes it, close to soprano and tenor.",
            )),
            tasks = listOf(
                Task("The tenor is missing. Which note completes the F major chord (F, A, C)?", chords("C5 A4 F4 F3"), Voice.TENOR, listOf(
                    opt("F4", Verdict.BEST, "F doubles the root: the chord sounds full and steady."),
                    opt("C4", Verdict.OK, "This works: doubling the fifth is allowed. But the root, F, is the steadier note to double."),
                    opt("A3", Verdict.WRONG, "This doubles the third, A, which the alto already sings. A doubled third in a major chord sticks out; double the root."),
                )),
                Task("The tenor is missing in this G major chord (G, B, D). Which note?", chords("D5 G4 B3 G2"), Voice.TENOR, listOf(
                    opt("B3", Verdict.BEST, "B, the third, makes the chord major. Now every note of the chord sounds."),
                    opt("D4", Verdict.WRONG, "Only G and D: without its third the chord sounds hollow, neither major nor minor."),
                    opt("G3", Verdict.WRONG, "Three G's and a D: the third, B, is missing, so the chord sounds empty."),
                )),
                Task("The tenor is missing in this C major chord (C, E, G). Which note?", chords("E5 G4 C4 C3"), Voice.TENOR, listOf(
                    opt("C4", Verdict.BEST, "C doubles the root, and alto and tenor stay close: the chord blends."),
                    opt("G3", Verdict.OK, "Possible: alto and tenor are still within an octave. But it doubles the fifth; the root, C, is steadier."),
                    opt("E3", Verdict.WRONG, "More than an octave between alto and tenor: the chord falls apart in the middle. It also doubles the third."),
                )),
                Task("The alto is missing in this G major chord (G, B, D). Which note?", chords("D5 G4 B3 G2"), Voice.ALTO, listOf(
                    opt("G4", Verdict.BEST, "G sits between soprano and tenor and doubles the root."),
                    opt("D4", Verdict.OK, "Possible: D sits between soprano and tenor. But it doubles the fifth, so the root G sounds only in the bass."),
                    opt("G5", Verdict.WRONG, "G5 is above the soprano: the alto would cross it, and it is too high for an alto. The ear would hear it as the melody."),
                )),
            ),
            find = chords("E5 G4 C4 C3", "F5 A4 C4 F3", "D5 G4 D4 G2", "C5 G4 E3 C3"),
            findNote = "Two chords here break a rule of this lesson. Tap a note of each, then Reveal. Stuck? Ask which bars to look in.",
            apply = "Write soprano, alto and tenor over the given bass. Every chord is I, IV or V in root position, so each bass note is the root of its chord. " +
                "Voice each chord well; how chords connect comes in the next lesson.",
            chords = setOf("I", "IV", "V"),
        ),
        Lesson(
            title = "Two chords: hold and step",
            idea = "Each voice is a melody: keep the notes chords share, move the others to the nearest note.",
            hear = Hear(
                "Which version can you sing as four separate melodies?",
                good = chords("G4 E4 C4 C3", "A4 F4 C4 F3", "G4 D4 B3 G3", "G4 E4 C4 C3"),
                bad = chords("G4 E4 C4 C3", "F5 C4 A3 F3", "B4 G4 D4 G3", "E5 G4 C4 C3"),
                goodFirst = true,
                answer = "A. The chords are the same, but in A each upper voice holds its note or moves by step, so every line is a melody you could sing. " +
                    "In B the soprano leaps a seventh and the alto jumps up and down: the lines break apart and you only hear chords changing.",
            ),
            why = "Each voice is a melody. Between two chords, a voice keeps a note both chords share, a common tone, and the others move to the nearest note of the new chord, by step when they can. " +
                "Only the bass leaps freely, from root to root. Smooth lines are easy to sing and keep each voice clear. " +
                "A big leap in an upper voice sticks out, may pass where a neighbor just was (an overlap), and leaps such as sevenths or augmented intervals are hard to sing.",
            rules = listOf(Rule.MELODIC, Rule.LEAPS, Rule.OVERLAP),
            watch = Watch(chords("G4 E4 C4 C3", "A4 F4 C4 F3"), listOf(
                "1112" to "C major to F major. The bass moves from root to root, C to F. Now the upper voices.",
                "1122" to "C is in both chords. The tenor keeps it: a common tone, held.",
                "1222" to "E moves to F, the nearest note of F major: one step up.",
                "2222" to "G moves to A, also one step. Each upper voice held or stepped, and the new chord has its third and a doubled root.",
            )),
            tasks = listOf(
                Task("C major to F major. Which note for the tenor?", chords("E5 G4 C4 C3", "F5 A4 C4 F3"), Voice.TENOR, listOf(
                    opt("C4", Verdict.BEST, "C is in both chords: the tenor simply holds it."),
                    opt("F4", Verdict.OK, "F is a note of the chord, but the tenor leaps a fourth when C was there to hold."),
                    opt("A3", Verdict.WRONG, "A leap down, and the third, A, now sounds twice (alto and tenor). Hold C instead."),
                )),
                Task("F major to G major: no note is shared. Which note for the tenor?", chords("A4 F4 C4 F3", "G4 D4 B3 G3"), Voice.TENOR, listOf(
                    opt("B3", Verdict.BEST, "C down to B: one step, against the rising bass. And B is the third the chord needs."),
                    opt("D4", Verdict.WRONG, "C up to D is a step too, but D is already in the alto and the chord has no third, B."),
                    opt("G3", Verdict.WRONG, "A leap down a fourth onto the bass's note, and the chord still has no third."),
                )),
                Task("A minor to F major: two notes are shared. Which note for the alto?", chords("C5 E4 A3 A2", "C5 F4 A3 F2"), Voice.ALTO, listOf(
                    opt("F4", Verdict.BEST, "E to F: one step up. Soprano and tenor hold the shared notes, C and A."),
                    opt("C4", Verdict.OK, "C is a note of the chord, but the alto leaps a third when F was a step away, and the root F then sounds only in the bass."),
                    opt("A4", Verdict.WRONG, "A leap of a fourth, and it doubles A, the third, which the tenor already sings."),
                )),
            ),
            find = chords("G4 E4 C4 C3", "A4 F4 C4 F3", "G4 D4 B3 G2", "C5 G4 E4 C3"),
            findNote = "Two spots here break rules of this lesson. Tap a note of each, then Reveal. Stuck? Ask which bars to look in.",
            apply = "Write soprano, alto and tenor over the given bass. The chords are I, ii, IV, V and vi in root position. " +
                "From chord to chord, hold the shared notes and move the other voices to the nearest note.",
            chords = setOf("I", "ii", "IV", "V", "vi"),
        ),
    )
}
