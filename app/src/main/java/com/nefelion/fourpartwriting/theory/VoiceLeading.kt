package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Interval
import com.nefelion.fourpartwriting.model.IntervalQuality
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Slice
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.abs
import kotlin.math.sign

enum class Severity { ERROR, WARNING }

/** Checks the user can switch on or off, most important first. */
/** [why]: what the rule protects against. */
enum class Rule(val label: String, val desc: String, val why: String) {
    PARALLEL_OCTAVES("Parallel octaves", "Two voices an octave or unison apart must not move to another octave or unison.",
        "Octaves and unisons fuse into one sound, so two voices moving in parallel merge into one and the texture loses a part."),
    PARALLEL_FIFTHS("Parallel fifths", "Two voices a fifth apart must not move to another fifth.",
        "Fifths fuse almost as strongly as octaves; a pair of them sounds hollow and takes away the independence of the two voices."),
    LEADING_TONE("Leading tone", "In V–I the leading tone rises by step to the tonic.",
        "The leading tone sits a half step below the tonic and the ear expects it to rise; leaving it hanging weakens the cadence."),
    SEVENTH("Seventh", "The chordal seventh resolves down by step.",
        "The seventh is a dissonance; stepping down to the nearest chord tone releases its tension smoothly."),
    CROSSING("Voice crossing", "A lower voice must not sound above a higher one.",
        "The ear follows the highest and lowest pitch, not the singer, so crossed voices blur which line is which."),
    MISSING_THIRD("Missing third", "Every chord needs its third.",
        "The third decides major or minor; without it the chord sounds empty and its quality is unclear."),
    DOUBLING("Doubling", "Never double the leading tone or the seventh; in major triads double the root.",
        "A doubled tendency tone must resolve the same way in both voices, which makes parallel octaves; the root is the most stable note to reinforce."),
    SPACING("Spacing", "Keep upper neighboring voices within an octave; tenor and bass within a twelfth.",
        "Wide gaps in the upper voices make the chord sound hollow and split apart; the bass may sit further away, as the low overtones are spread wide too."),
    MELODIC("Melodic interval", "No augmented intervals, sevenths or leaps beyond an octave within a voice.",
        "Every part must be singable; awkward intervals are hard to pitch and break the line."),
    OVERLAP("Voice overlap", "A voice must not move past the previous note of its neighbor.",
        "When a voice passes its neighbor's previous note, the ear hears the line jump into the other voice, almost like crossing."),
    DIRECT("Direct fifths/octaves", "Outer voices may not leap in similar motion into a fifth or octave.",
        "The outer voices are heard most clearly; reaching a perfect interval by similar motion with a leap exposes it like a hidden parallel."),
    TENDENCY("Tendency tones", "Chromatic notes keep their pull: raised notes rise, lowered notes fall.",
        "A chromatic note acts as a temporary leading tone; moving against its pull sounds like a wrong note."),
    UNRESOLVED_NCT("Non-chord tones", "A dissonance must be a passing, neighbor, suspension or other standard figure.",
        "A dissonance makes sense only when the ear hears where it comes from and where it goes; otherwise it sounds like a mistake."),
    CADENCE("Final cadence", "End on the tonic in root position after V or IV.",
        "The ending must confirm the key; V–I or IV–I in root position gives the strongest sense of arrival."),
    NAMED_CHORD("Named chords", "The notes play the chord you named.",
        "Choosing the chord first and then voicing it is the core of four-part writing; a note outside it changes the harmony you meant."),
    FIGURES("Figured bass", "The upper voices play the notes the bass figures ask for.",
        "The figures are a shorthand for the harmony over each bass note; any other note changes the chord they ask for."),
    PROGRESSION("Chord progression", "Move tonic → predominant → dominant → tonic; avoid going backwards.",
        "Functional order builds tension and releases it; going backwards undoes the tension before it can resolve."),
    SIX_FOUR("Six-four chords", "Use a ⁶₄ chord only as cadential, passing, pedal or arpeggiated.",
        "The fourth over the bass is unstable, so a ⁶₄ chord sounds unfinished unless its context explains it."),
    RANGE("Range", "Keep each voice inside its usual range.",
        "Real singers have limits; notes outside them strain the voice or vanish in the texture."),
    LEAPS("Leap handling", "After a big leap turn back by step; no tritone outlines.",
        "A big leap leaves a gap the ear wants filled, and stepping back balances the line; tritone outlines sound awkward and are hard to sing."),
    CROSS_RELATION("Cross relation", "A note and its chromatic version should not appear in different voices in a row.",
        "A note clashing with its chromatic version in another voice sounds like a wrong note and blurs the key."),
    UNEQUAL_FIFTHS("Unequal fifths", "A diminished fifth over the bass should not move to a perfect fifth.",
        "The diminished fifth wants to close to a third; turning it into a perfect fifth skips that resolution and sounds like parallel fifths."),
    HARMONIC_RHYTHM("Harmonic rhythm", "Do not repeat a chord from a weak beat to the downbeat.",
        "A chord held from a weak beat into the downbeat makes the downbeat bring nothing new, so the meter seems to shift."),
    SIMILAR_MOTION("All voices in one direction", "Keep at least one voice moving against or holding against the others.",
        "When all voices move together the texture lurches as one block and parallels creep in; contrary motion keeps the parts independent."),
    MISSING_VOICE("Missing voice", "Every voice should sound in every chord.",
        "Four-part writing needs four lines; a gap thins the harmony and breaks a melody."),
    UNRECOGNIZED("Chord", "The notes should form a recognizable chord.",
        "Each sonority should have a clear harmonic function; an unclear chord leaves the listener without bearings."),
    STRUCTURE("Structure", "All voices equal in length; the last bar complete.",
        "Unequal voices or an incomplete bar leave the piece unfinished and chords cannot line up between the parts."),
}

/** A rule violation. [notes] are (slice index, voice) pairs to highlight. */
data class Issue(
    val severity: Severity,
    val slice: Int,
    val rule: Rule,
    val message: String,
    val notes: List<Pair<Int, Voice>>,
)

/**
 * [chords]: the chord in effect at every slice; [points]: slices where a chord is named;
 * [ncts]: non-chord tones by note.
 */
data class Analysis(
    val slices: List<Slice>,
    val chords: List<Chord>,
    val issues: List<Issue>,
    val points: List<Int> = slices.indices.toList(),
    val ncts: Map<com.nefelion.fourpartwriting.engrave.NoteRef, Nct> = emptyMap(),
)

object VoiceLeading {

    /** Common textbook ranges (MIDI, inclusive). */
    val RANGES = mapOf(
        Voice.SOPRANO to Pitch.parse("C4").midi..Pitch.parse("G5").midi,
        Voice.ALTO to Pitch.parse("G3").midi..Pitch.parse("D5").midi,
        Voice.TENOR to Pitch.parse("C3").midi..Pitch.parse("G4").midi,
        Voice.BASS to Pitch.parse("E2").midi..Pitch.parse("C4").midi,
    )

    private val V = Voice.entries

    /** Key for "ignore here": changes when the notes it names change, so an edited spot is checked again. */
    fun ignoreKey(issue: Issue, slices: List<Slice>) =
        "${issue.rule.name}@${slices.getOrNull(issue.slice)?.time}|${issue.message}"

    /** "Ignore here" mutes last only while their issue does: once an edit removes it, the mute is gone for good. */
    fun pruneMutes(sc: Score): Score {
        if (sc.ignored.isEmpty()) return sc
        val a = check(sc.copy(ignored = emptySet()))
        val live = a.issues.mapTo(HashSet()) { ignoreKey(it, a.slices) }
        return sc.copy(ignored = sc.ignored intersect live)
    }

    fun check(score: Score, rules: Set<Rule> = Rule.entries.toSet()): Analysis {
        val enabled = rules.filterTo(HashSet()) { it.name !in score.rulesOff }
        val slices = score.slices()
        val harmony = HarmonyAnalyzer.analyze(score, slices)
        val points = harmony.points
        // Context labels (cadential 6/4, Picardy) on the sequence of named chords
        val named = ChordAnalyzer.withContext(points.map { harmony.chordOf[it] }, score.key)
        val pointChord = points.indices.associate { points[it] to named[it] }
        var gov = 0
        val chords = slices.indices.map { i -> if (i in pointChord) gov = i; pointChord.getValue(gov) }
        val ncts = harmony.ncts
        val out = mutableListOf<Issue>()
        fun add(sev: Severity, i: Int, rule: Rule, msg: String, vararg n: Pair<Int, Voice>) {
            if (rule in enabled) out += Issue(sev, i, rule, msg, n.toList())
        }

        // Structure
        val lengths = V.map { score.length(it) }
        if (lengths.distinct().size > 1)
            add(Severity.WARNING, 0, Rule.STRUCTURE, "Voices have different lengths.")
        val measure = score.time.measure
        val total = score.length
        if (total > Fraction.ZERO && (total / measure).den != 1L)
            add(Severity.WARNING, slices.lastIndex.coerceAtLeast(0), Rule.STRUCTURE, "Last measure is incomplete.")

        // The voice sounding the bass of a slice (the bass part may rest)
        fun low(si: Int) = V.lastOrNull { slices[si].notes[it.ordinal] != null } ?: Voice.BASS

        // A held note may be a non-chord tone at one chord point and a chord tone at another (a pedal under ii° then VI)
        val chordAt = slices.indices.associate { slices[it].time to chords[it] }
        // (a note struck again shares the name of its earlier strikes)
        fun named(v: Voice, idx: Int): Boolean {
            val evs = score.voice(v)
            var k = idx
            while (com.nefelion.fourpartwriting.engrave.NoteRef(v, k) !in ncts) { if (k == 0 || evs[k - 1].pitch != evs[k].pitch) return false; k-- }
            return true
        }
        fun isNct(s: Slice, v: Voice) = s.eventIndex[v.ordinal] >= 0 && named(v, s.eventIndex[v.ordinal]) &&
            s.notes[v.ordinal]?.let { p -> chordAt[s.time]?.member(p.pc)?.let { it in setOf(0, 2, 4, 6) } } != true

        // Non-chord tones that are not explained by any standard pattern
        for ((ref, nct) in ncts) {
            val v = ref.voice
            val p = score.voice(v).getOrNull(ref.index)?.pitch ?: continue
            // A dissonance struck again is one dissonance
            val before = ncts[com.nefelion.fourpartwriting.engrave.NoteRef(v, ref.index - 1)]
            if (nct.type == NctType.UNRESOLVED && before?.type == NctType.UNRESOLVED && score.voice(v)[ref.index - 1].pitch == p) continue
            // The slice where it acts as a non-chord tone: a chord point it sounds at (and is foreign to), else its attack
            var last = ref.index
            while (score.voice(v).getOrNull(last + 1)?.pitch == p) last++
            val i = points.firstOrNull { slices[it].eventIndex[v.ordinal] in ref.index..last && chords[it].recognized && chords[it].member(p.pc) !in setOf(0, 2, 4, 6) }
                ?: slices.indexOfFirst { it.eventIndex[v.ordinal] == ref.index && it.attacked[v.ordinal] }
            if (i < 0) continue
            val s = slices[i]
            if (nct.type == NctType.UNRESOLVED)
                add(Severity.ERROR, i, Rule.UNRESOLVED_NCT,
                    "${v.label}: ${p.name} is a dissonance that is not a passing, neighbor, suspension or other standard non-chord tone.", i to v)
            if (nct.type == NctType.SUS && v != Voice.BASS && i in pointChord) {
                // The note of resolution should not sound in another voice against the suspension (the bass of a 9–8 or 9–6 excepted)
                // The step down: the next note, or the one after an ornament
                val res = score.voice(v).drop(s.eventIndex[v.ordinal] + 1).map { it.pitch }
                    .let { l -> l.filterIndexed { k, q -> k == 0 || q != l[k - 1] } }.take(2).filterNotNull()
                    .firstOrNull { p.diatonic - it.diatonic == 1 || p.diatonic == it.diatonic && it.midi < p.midi }
                val clash = V.filter { o -> o != v && s.notes[o.ordinal]?.pc == res?.pc && !(o == Voice.BASS && nct.label.startsWith("9–")) }
                if (res != null && clash.isNotEmpty())
                    add(Severity.WARNING, i, Rule.UNRESOLVED_NCT, "${v.label}: the note of resolution (${res.pc.name}) already sounds against the suspension.",
                        *(listOf(i to v) + clash.map { i to it }).toTypedArray())
            }
        }

        for ((k, i) in points.withIndex()) {
            val s = slices[i]
            val chord = chords[i]
            for (v in V) if (s.notes[v.ordinal] == null && s.pitches.isNotEmpty())
                add(Severity.WARNING, i, Rule.MISSING_VOICE, "${v.name.lowercase().cap()} has no note here.")
            // Doubling and construction ignore non-chord tones
            val chordSlice = s.copy(notes = V.map { v -> if (isNct(s, v)) null else s.notes[v.ordinal] })
            val prevI = points.getOrNull(k - 1)
            val nextI = points.getOrNull(k + 1)
            val prev = prevI?.let { chords[it] }
            if (s.pitches.size >= 3) chordRules(i, chordSlice, chord, prev, nextI?.let { chords[it] }, score.key, ::add)
            if (prevI != null) resolutionRules(score, prevI, i, slices, chords, chords[prevI], chord, ::isNct, ::add)
            if (prevI != null && i - prevI > 1) ornamentedParallels(prevI, i, slices, ::isNct, ::add)

            val q = chord.quality ?: continue
            // A ⁶₄ chord is cadential, passing (bass steps through), pedal (bass holds) or arpeggiated (same chord around it)
            if (q in setOf(Quality.MAJOR, Quality.MINOR, Quality.DIM, Quality.AUG) && chord.inversion == 2 && chord.roman != "Cad⁶₄") {
                fun bassAt(p: Int?) = p?.let { slices[it].notes[low(it).ordinal] }
                val pb = bassAt(prevI); val b = bassAt(i); val nb = bassAt(nextI)
                val passing = pb != null && b != null && nb != null && abs(b.diatonic - pb.diatonic) == 1 &&
                    nb.diatonic - b.diatonic == b.diatonic - pb.diatonic
                val pedal = b != null && pb == b && nb == b
                fun same(o: Chord?) = o != null && o.root == chord.root && o.quality == q
                if (!passing && !pedal && !same(prev) && !same(nextI?.let { chords[it] }))
                    add(Severity.WARNING, i, Rule.SIX_FOUR, "${chord.roman}: use a ⁶₄ chord only as a cadential, passing, pedal or arpeggiated ⁶₄.", i to low(i))
            }
            // The cadential ⁶₄ (from where it starts sounding) falls on a stronger beat than its V
            if (chord.roman == "Cad⁶₄" && nextI != null) {
                var start = k
                while (start > 0 && chords[points[start - 1]].let { it.root == chord.root && it.quality == q && it.bass == chord.bass }) start--
                if (weight(slices[points[start]].time, score.time) <= weight(slices[nextI].time, score.time))
                    add(Severity.WARNING, i, Rule.SIX_FOUR, "The cadential ⁶₄ should fall on a stronger beat than the V it resolves to.", i to low(i))
            }
            // Retrogressions: a dominant back to a predominant, ii back to I or IV
            if (prev != null && prev.root != chord.root && !prev.roman.contains("/") && !chord.roman.contains("/")) {
                val fromDominant = prev.target == score.key.tonic && prev.leadingTone != null
                val retro = (fromDominant && chord.degree in setOf(1, 3)) ||
                    (prev.degree == 1 && chord.degree == 3) ||
                    (prev.degree == 1 && chord.degree == 0 && chord.roman != "Cad⁶₄")
                if (retro) add(Severity.WARNING, i, Rule.PROGRESSION, "${prev.roman} → ${chord.roman} is a retrogression; move from predominant to dominant to tonic.")
            }
            // The same chord from a weak beat to the downbeat blurs the bar line (one held since the last downbeat does not)
            val ml = score.time.measure
            fun same(c: Chord) = c.root == chord.root && c.quality == q && c.bass == chord.bass
            // (an arpeggiating bass, I–I⁶–I⁶₄–I, keeps the harmony that began on the downbeat)
            var runStart = k - 1
            while (runStart > 0 && chords[points[runStart - 1]].let { it.root == chord.root && it.quality == q }) runStart--
            if (prevI != null && prev != null && same(prev) &&
                (s.time / ml).den == 1L && (slices[points[runStart]].time / ml).den != 1L
            ) add(Severity.WARNING, i, Rule.HARMONIC_RHYTHM, "${chord.roman} is repeated from a weak beat to the downbeat.")
        }

        // Final cadence: the tonic in root position, after V/vii° (authentic) or IV (plagal)
        val lastP = points.lastOrNull()
        val last = lastP?.let { chords[it] }
        if (lastP != null && last != null && last.recognized && points.count { slices[it].pitches.isNotEmpty() } >= 2) {
            if (last.degree != 0 || last.root != score.key.tonic)
                add(Severity.WARNING, lastP, Rule.CADENCE, "The piece does not end on the tonic (${last.roman}).")
            else {
                if (last.inversion != 0) add(Severity.WARNING, lastP, Rule.CADENCE, "End on the tonic in root position.", lastP to low(lastP))
                val pen = points.asReversed().map { chords[it] }.firstOrNull { it.root != last.root }
                if (pen != null && pen.recognized && !(pen.target == score.key.tonic && pen.leadingTone != null) && pen.degree != 3)
                    add(Severity.WARNING, lastP, Rule.CADENCE, "End with an authentic (V–I) or plagal (IV–I) cadence, not ${pen.roman}–${last.roman}.")
            }
        }

        for ((i, s) in slices.withIndex()) {
            val n = s.notes

            // Ranges
            for (v in V) {
                val p = n[v.ordinal] ?: continue
                if (p.midi !in RANGES.getValue(v))
                    add(Severity.WARNING, i, Rule.RANGE, "${v.name.lowercase().cap()} ${p.name} is out of its usual range.", i to v)
            }
            // Spacing and crossing, between neighboring sounding voices (a resting voice is skipped)
            for ((vu, vl) in V.filter { n[it.ordinal] != null }.zipWithNext()) {
                val up = n[vu.ordinal]!!
                val lo = n[vl.ordinal]!!
                if (lo.midi > up.midi)
                    add(Severity.ERROR, i, Rule.CROSSING, "${vl.label} (${lo.name}) is above ${vu.label} (${up.name}).", i to vu, i to vl)
                else if (vl != Voice.BASS && up.midi - lo.midi > 12)
                    add(Severity.ERROR, i, Rule.SPACING, "More than an octave between ${vu.label} and ${vl.label}.", i to vu, i to vl)
                else if (vl == Voice.BASS && Interval.between(lo, up).number > 12)
                    add(Severity.WARNING, i, Rule.SPACING, "More than a twelfth between ${vu.label} and B.", i to vu, i to vl)
            }

            if (i > 0) motionRules(i, slices[i - 1], s, ::add)
        }

        // Leaps: one larger than a fifth turns back by step; two in one direction stay within an octave, not a seventh
        for (v in V) {
            val evs = score.voice(v)
            fun sliceOf(idx: Int) = slices.indexOfFirst { it.eventIndex[v.ordinal] == idx && it.attacked[v.ordinal] }
            // Notes between rests, a note struck again counted once (it does not end a leap)
            val runs = mutableListOf(mutableListOf<Int>())
            for ((j, e) in evs.withIndex()) {
                val run = runs.last()
                if (e.pitch == null) { if (run.isNotEmpty()) runs += mutableListOf<Int>() }
                else if (run.isEmpty() || evs[run.last()].pitch != e.pitch) run += j
            }
            for (run in runs) for (k in 2 until run.size) {
                val p0 = evs[run[k - 2]].pitch!!
                val p1 = evs[run[k - 1]].pitch!!
                val p2 = evs[run[k]].pitch!!
                val d1 = p1.diatonic - p0.diatonic
                val d2 = p2.diatonic - p1.diatonic
                if (d1 == 0 || d2 == 0) continue
                val i0 = sliceOf(run[k - 2]); val i1 = sliceOf(run[k - 1]); val i2 = sliceOf(run[k])
                val stepBack = abs(d2) == 1 && d2.sign != d1.sign
                val leap = Interval.between(p0, p1)
                if (leap.quality == IntervalQuality.D && leap.number in 4..5 && !stepBack)
                    add(Severity.WARNING, i2, Rule.LEAPS, "${v.label}: after the diminished leap ${p0.name}–${p1.name}, move back inside by step.", i1 to v, i2 to v)
                else if (abs(d1) >= 5 && !stepBack && !(v == Voice.BASS && abs(d1) == 7))
                    add(Severity.WARNING, i2, Rule.LEAPS, "${v.label}: after the leap ${p0.name}–${p1.name}, turn back by step.", i1 to v, i2 to v)
                else if (d1.sign == d2.sign && abs(d1) >= 2 && abs(d2) >= 2) {
                    val span = Interval.between(p0, p2)
                    if (span.number > 8 || span.simpleNumber == 7)
                        add(Severity.WARNING, i2, Rule.LEAPS,
                            "${v.label}: two leaps in one direction span ${if (span.number > 8) "more than an octave" else "a seventh"} (${p0.name}–${p2.name}).",
                            i0 to v, i1 to v, i2 to v)
                }
            }
            // A line moving one way (2+ moves, between turning points or rests) should not outline a tritone
            var b = 0
            var dir = 0
            fun outline(from: Int, to: Int) {
                val p = evs[from].pitch ?: return
                val q = evs[to].pitch ?: return
                if ((from + 1..to).count { evs[it].pitch != evs[it - 1].pitch } < 2) return
                val iv = Interval.between(p, q)
                // (at the first strike of a note struck again)
                fun firstOf(k: Int): Int { var x = k; while (x > 0 && evs[x - 1].pitch == evs[x].pitch) x--; return x }
                if ((iv.number == 4 && iv.quality == IntervalQuality.A) || (iv.number == 5 && iv.quality == IntervalQuality.D))
                    add(Severity.WARNING, sliceOf(firstOf(to)), Rule.LEAPS, "${v.label}: the line ${p.name}–${q.name} outlines a tritone.",
                        sliceOf(firstOf(from)) to v, sliceOf(firstOf(to)) to v)
            }
            for (j in 1..evs.size) {
                val p = evs.getOrNull(j)?.pitch
                val prev = evs[j - 1].pitch
                if (p == null || prev == null) { outline(b, j - 1); b = j; dir = 0; continue }
                val d = (p.midi - prev.midi).sign
                if (d == 0) continue
                if (dir != 0 && d != dir) { outline(b, j - 1); b = j - 1 }
                dir = d
            }
        }
        // Figured bass: at each figured bass note, the upper voices against the notes the figures ask for
        // shortcut: one stack per bass note, so a 4–3 over one held note cannot be written; upgrade with figures at offsets
        for ((idx, e) in score.voice(Voice.BASS).withIndex()) {
            val b = e.pitch ?: continue
            if (e.figures.isEmpty()) continue
            val i = slices.indexOfFirst { it.eventIndex[Voice.BASS.ordinal] == idx && it.attacked[Voice.BASS.ordinal] }
            if (i < 0) continue
            val want = FiguredBass.realize(b, e.figures, score.key)
            val fig = FiguredBass.display(e.figures)
            val upper = V.filter { it != Voice.BASS && slices[i].notes[it.ordinal] != null }
            for (v in upper) {
                val p = slices[i].notes[v.ordinal]!!
                if (p.pc != b.pc && p.pc !in want.values)
                    add(Severity.ERROR, i, Rule.FIGURES, "${v.label}: $fig over ${b.pc.name} asks for ${want.values.joinToString(", ") { it.name }}, not ${p.pc.name}.", i to v, i to Voice.BASS)
            }
            val heard = upper.map { slices[i].notes[it.ordinal]!!.pc }.toSet()
            val missing = want.filter { (n, pc) -> pc !in heard && !FiguredBass.optional(n, e.figures) }.values
            if (upper.isNotEmpty() && missing.isNotEmpty())
                add(Severity.WARNING, i, Rule.FIGURES, "$fig over ${b.pc.name} also asks for ${missing.joinToString(", ") { it.name }}.", i to Voice.BASS)
        }

        // Named chords: the notes at a named note against the chord's notes (non-chord tones excepted)
        for (v0 in V) for ((idx, e) in score.voice(v0).withIndex()) {
            val name = e.chord ?: continue
            val i = slices.indexOfFirst { it.eventIndex[v0.ordinal] == idx && it.attacked[v0.ordinal] }
            if (i < 0) continue
            val spec = ChordText.parse(name, score.key)
            if (spec == null) { add(Severity.WARNING, i, Rule.NAMED_CHORD, "\"$name\" is not a chord name I can read (try C, Am7, G7/B, V65, ii6)."); continue }
            val s = slices[i]
            val sounding = V.filter { s.notes[it.ordinal] != null }
            for (v in sounding) {
                val p = s.notes[v.ordinal]!!
                if (p.pc !in spec.pcs && !isNct(s, v))
                    add(Severity.ERROR, i, Rule.NAMED_CHORD, "${v.label}: $name has no ${p.pc.name} (${spec.pcs.joinToString(", ") { it.name }}).", i to v)
            }
            val low = sounding.lastOrNull()
            if (spec.bass != null && low != null && s.notes[low.ordinal]!!.pc != spec.bass)
                add(Severity.ERROR, i, Rule.NAMED_CHORD, "$name needs ${spec.bass.name} in the bass.", i to low)
            val heard = sounding.map { s.notes[it.ordinal]!!.pc }.toSet()
            val fifth = spec.pcs.getOrNull(2)
            val missing = spec.pcs.filter { it !in heard && !(spec.pcs.size == 4 && it == fifth) }
            if (sounding.size == 4 && missing.isNotEmpty())
                add(Severity.WARNING, i, Rule.NAMED_CHORD, "$name is missing ${missing.joinToString(", ") { it.name }}.")
        }

        // Errors first, then warnings; each by position in the score (slices are in time order)
        out.removeAll { ignoreKey(it, slices) in score.ignored }
        return Analysis(slices, chords, out.sortedWith(compareBy({ it.severity }, { it.slice })), points, ncts)
    }

    private fun String.cap() = replaceFirstChar { it.uppercase() }

    /** Metric weight: 3 downbeat, 2 middle beat of an even meter, 1 other beat; off the beat negative, finer subdivisions lower. */
    internal fun weight(t: Fraction, ts: TimeSig): Int {
        val m = ts.measure
        val bar = t / m
        val pos = t - m * Fraction.of(bar.num / bar.den, 1)
        if (pos == Fraction.ZERO) return 3
        val b = pos / ts.beatLength
        if (b.den != 1L) return -b.den.toInt()
        val n = (m / ts.beatLength).let { it.num / it.den }
        return if (n % 2 == 0L && b.num == n / 2) 2 else 1
    }

    private fun voicesWith(s: Slice, pc: PitchClass) =
        V.filter { s.notes[it.ordinal]?.pc == pc }

    private fun chordRules(
        i: Int, s: Slice, c: Chord, prev: Chord?, next: Chord?, key: Key,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        if (!c.recognized) {
            if (c.symbol.contains("no 3rd")) add(Severity.ERROR, i, Rule.MISSING_THIRD, "The chord has no third: ${c.symbol}.", emptyArray())
            else add(Severity.WARNING, i, Rule.UNRECOGNIZED, "Unrecognized sonority: ${c.symbol}.", emptyArray())
            return
        }
        val q = c.quality!!
        // A sus chord whose 4th/2nd is not explained as a suspension (or other NCT) lacks its third
        if (!q.hasThird && s.pitches.any { c.member(it.pc) == 1 || c.member(it.pc) == 3 })
            add(Severity.ERROR, i, Rule.MISSING_THIRD, "The chord has no third: ${c.symbol}.", emptyArray())
        c.leadingTone?.let { lt ->
            val vs = voicesWith(s, lt)
            if (vs.size > 1) add(Severity.ERROR, i, Rule.DOUBLING, "Doubled leading tone (${lt.name}).", vs.map { i to it }.toTypedArray())
        }
        c.seventh?.let { sev ->
            val vs = voicesWith(s, sev)
            if (vs.size > 1) add(Severity.ERROR, i, Rule.DOUBLING, "Doubled chordal seventh (${sev.name}).", vs.map { i to it }.toTypedArray())
        }
        if (!q.seventh && c.inversion == 2 && voicesWith(s, c.bass!!).size < 2)
            add(Severity.WARNING, i, Rule.DOUBLING, "In a ⁶₄ chord, double the bass (${c.bass.name}).", emptyArray())
        // A leading-tone root is already reported as a doubled leading tone
        if (q == Quality.DIM && c.inversion in 0..1 && c.root != c.leadingTone && voicesWith(s, c.root!!).size > 1)
            add(Severity.WARNING, i, Rule.DOUBLING, "Do not double the root of a diminished triad; double the third.",
                voicesWith(s, c.root).map { i to it }.toTypedArray())
        // Root-position major triad: double the root, not the third (N, the deceptive V–vi and VI–V excepted)
        val third = c.third
        if ((q == Quality.MAJOR || q == Quality.MAJOR_NO5) && c.inversion == 0 && third != null && third != c.leadingTone && !c.roman.startsWith("N") &&
            !(c.degree == 5 && (prev?.degree == 4 || next?.degree == 4))
        ) {
            val vs = voicesWith(s, third)
            if (vs.size > 1) add(Severity.WARNING, i, Rule.DOUBLING, "Doubled third in a major triad (${third.name}); double the root.", vs.map { i to it }.toTypedArray())
        }
        // Chromatic notes are tendency tones
        for (pc in s.pitches.map { it.pc }.distinct()) {
            // The root of a chromatic chord (♭VI, ♭III ...) is its normal doubling; not ♭2̂ of the Neapolitan or ♭6̂ of an augmented sixth
            if (key.isDiatonic(pc) || pc == c.leadingTone || pc == c.seventh || pc == c.root && !c.roman.startsWith("N") && !q.isAug6) continue
            val vs = voicesWith(s, pc)
            if (vs.size > 1) add(Severity.WARNING, i, Rule.DOUBLING, "Doubled chromatic note (${pc.name}).", vs.map { i to it }.toTypedArray())
        }
    }

    private fun motionRules(
        i: Int, a: Slice, b: Slice,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        val h = i - 1
        // An enharmonic respelling (G♯–A♭) is not motion
        fun moved(v: Voice) = b.attacked[v.ordinal] && a.notes[v.ordinal] != null && b.notes[v.ordinal] != null &&
            a.notes[v.ordinal]!!.midi != b.notes[v.ordinal]!!.midi
        fun both(v: Voice) = arrayOf(h to v, i to v)

        // Melodic intervals
        for (v in V) {
            if (!moved(v)) continue
            val iv = Interval.between(a.notes[v.ordinal]!!, b.notes[v.ordinal]!!)
            // A chromatic semitone (A1) in one voice is the correct way to write a chromatic change
            if ((iv.quality == IntervalQuality.A && iv.number != 1) || iv.quality == IntervalQuality.AA)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: augmented melodic interval (${iv.name}).", both(v))
            else if (iv.number > 8)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: leap larger than an octave (${iv.name}).", both(v))
            else if (iv.number == 8 && iv.quality != IntervalQuality.PERFECT)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: leap of a diminished or augmented octave (${iv.name}).", both(v))
            else if (iv.number == 7)
                add(Severity.ERROR, i, Rule.MELODIC, "${v.label}: leap of a seventh (${iv.name}).", both(v))
        }

        // All four voices moving the same way
        val dirs = V.map { v -> if (moved(v)) (b.notes[v.ordinal]!!.midi - a.notes[v.ordinal]!!.midi).sign else 0 }
        if (dirs.all { it == 1 } || dirs.all { it == -1 })
            add(Severity.WARNING, i, Rule.SIMILAR_MOTION, "All four voices move in the same direction.", V.flatMap { both(it).toList() }.toTypedArray())

        // Cross relation: a letter changes its accidental between two different voices
        for (y in V) {
            if (!b.attacked[y.ordinal]) continue
            val q = b.notes[y.ordinal] ?: continue
            if (a.notes[y.ordinal] == q) continue // struck again: nothing new arrives
            val x = V.firstOrNull { x -> x != y && a.notes[x.ordinal]?.let { it.step == q.step && it.alter != q.alter } == true } ?: continue
            val p = a.notes[x.ordinal]!!
            // Fine when some voice makes the chromatic step itself
            val inOneVoice = V.any { z -> a.notes[z.ordinal]?.pc == p.pc && b.notes[z.ordinal]?.pc == q.pc }
            if (!inOneVoice)
                add(Severity.WARNING, i, Rule.CROSS_RELATION, "Cross relation: ${p.name} in ${x.label}, then ${q.name} in ${y.label}.", arrayOf(h to x, i to y))
        }

        // Overlap
        for (k in 0 until 3) {
            val up = V[k]; val lo = V[k + 1]
            val upA = a.notes[k]; val loA = a.notes[k + 1]; val upB = b.notes[k]; val loB = b.notes[k + 1]
            if (upA == null || loA == null || upB == null || loB == null) continue
            if (loB.midi > upA.midi && moved(lo))
                add(Severity.ERROR, i, Rule.OVERLAP, "${lo.label} moves above the previous ${up.label} note.", arrayOf(h to up, i to lo))
            if (upB.midi < loA.midi && moved(up))
                add(Severity.ERROR, i, Rule.OVERLAP, "${up.label} moves below the previous ${lo.label} note.", arrayOf(h to lo, i to up))
        }

        // Parallels and direct perfect intervals
        for (x in 0 until 4) for (y in x + 1 until 4) {
            val vx = V[x]; val vy = V[y]
            val ax = a.notes[x]; val ay = a.notes[y]; val bx = b.notes[x]; val by = b.notes[y]
            if (ax == null || ay == null || bx == null || by == null) continue
            if (!moved(vx) || !moved(vy)) continue
            val ia = Interval.between(ay, ax)
            val ib = Interval.between(by, bx)
            val same = (bx.midi - ax.midi).sign == (by.midi - ay.midi).sign
            val names = "${vx.label}–${vy.label}"
            val hl = arrayOf(h to vx, h to vy, i to vx, i to vy)
            when {
                isP5(ia) && isP5(ib) -> add(Severity.ERROR, i, Rule.PARALLEL_FIFTHS,
                    if (same) "Parallel fifths between $names." else "Consecutive fifths by contrary motion between $names.", hl)
                isP8(ia) && isP8(ib) -> add(Severity.ERROR, i, Rule.PARALLEL_OCTAVES,
                    if (same) "Parallel ${if (ib.number == 1) "unisons" else "octaves"} between $names."
                    else "Consecutive octaves by contrary motion between $names.", hl)
                vy == Voice.BASS && isD5(ia) && isP5(ib) && same ->
                    add(Severity.WARNING, i, Rule.UNEQUAL_FIFTHS, "d5 → P5 between $names.", hl)
                x == 0 && vy == Voice.BASS && same && (isP5(ib) || isP8(ib)) &&
                    abs(bx.diatonic - ax.diatonic) > 1 ->
                    add(Severity.WARNING, i, Rule.DIRECT,
                        "Outer voices move in similar motion into a perfect ${if (isP5(ib)) "fifth" else "octave"} with a leap in the soprano.", hl)
            }
        }
    }

    /**
     * Fifths or octaves between the chord tones of two chord points, hidden by notes in between
     * (E–D–C over A–F). Ones that also sound right before [i] are already reported as plain parallels.
     */
    private fun ornamentedParallels(
        h: Int, i: Int, slices: List<Slice>, isNct: (Slice, Voice) -> Boolean,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        val a = slices[h]; val b = slices[i]; val m = slices[i - 1]
        for (x in 0 until 4) for (y in x + 1 until 4) {
            val vx = V[x]; val vy = V[y]
            val ax = a.notes[x]; val ay = a.notes[y]; val bx = b.notes[x]; val by = b.notes[y]
            if (ax == null || ay == null || bx == null || by == null || ax.midi == bx.midi || ay.midi == by.midi) continue
            if (!b.attacked[x] || !b.attacked[y] || isNct(a, vx) || isNct(a, vy) || isNct(b, vx) || isNct(b, vy)) continue
            val ia = Interval.between(ay, ax)
            val ib = Interval.between(by, bx)
            val im = m.notes[x]?.let { mx -> m.notes[y]?.let { Interval.between(it, mx) } }
            val (rule, name) = when {
                isP5(ia) && isP5(ib) && (im == null || !isP5(im)) -> Rule.PARALLEL_FIFTHS to "fifths"
                isP8(ia) && isP8(ib) && (im == null || !isP8(im)) -> Rule.PARALLEL_OCTAVES to "octaves"
                else -> continue
            }
            val same = (bx.midi - ax.midi).sign == (by.midi - ay.midi).sign
            add(Severity.ERROR, i, rule, "${if (same) "Parallel" else "Consecutive"} $name between ${vx.label}–${vy.label} (notes in between do not hide them).",
                arrayOf(h to vx, h to vy, i to vx, i to vy))
        }
    }

    /**
     * Leading tone and chordal seventh, from chord point [h] to the next chord point [i].
     * A voice's resolution is its next note (so ornaments between chords do not hide it).
     */
    private fun resolutionRules(
        score: Score, h: Int, i: Int, slices: List<Slice>, chords: List<Chord>, ca: Chord, cb: Chord, isNct: (Slice, Voice) -> Boolean,
        add: (Severity, Int, Rule, String, Array<out Pair<Int, Voice>>) -> Unit,
    ) {
        val a = slices[h]
        // Slices since the chord on [ca]'s root began (V⁷ moving on to V or V⁶ is one span), latest first
        var start = h
        while (start > 0 && chords[start - 1].root == ca.root) start--
        val span = (i - 1 downTo start)
        // A voice's note in chord [h]; after a non-chord tone there, the chord tone it reaches (B of a C–B 4–3),
        // else the last chord tone it had in this chord (B of B–A on beats 3–4 of V)
        val srcIdx = IntArray(4) { a.eventIndex[it] }
        for (v in V) if (isNct(a, v)) span.firstOrNull { slices[it].notes[v.ordinal] != null && !isNct(slices[it], v) }
            ?.let { srcIdx[v.ordinal] = slices[it].eventIndex[v.ordinal] }
        fun note(v: Voice) = score.voice(v).getOrNull(srcIdx[v.ordinal])?.pitch
        val resIdx = IntArray(4) { -1 }
        /** Struck again within this chord only delays the resolution; [skipRepeats]: also struck again in the next chord. */
        fun nextNote(v: Voice, skipRepeats: Boolean = false): Pitch? {
            val idx = srcIdx[v.ordinal]
            val p = note(v) ?: return null
            val evs = score.voice(v)
            val on = score.onsets(v)
            var j = idx + 1
            while (evs.getOrNull(j)?.pitch == p && (skipRepeats || on[j] < slices[i].time)) j++
            resIdx[v.ordinal] = j
            return evs.getOrNull(j)?.pitch
        }
        /** The voice's note at [i]; after a non-chord tone there, the note it resolves to (B–D–C over I: D is an appoggiatura). */
        fun arrival(v: Voice): Pitch? {
            val idx = slices[i].eventIndex[v.ordinal]
            val evs = score.voice(v)
            if (idx < 0 || idx == srcIdx[v.ordinal]) return null
            if (!isNct(slices[i], v)) return evs[idx].pitch
            var j = idx + 1
            while (evs.getOrNull(j)?.pitch == evs[idx].pitch) j++
            return evs.getOrNull(j)?.pitch
        }
        // Highlights the note and the note it moves to (which may sound before [i])
        fun both(v: Voice): Array<Pair<Int, Voice>> {
            fun sliceOf(idx: Int) = slices.indexOfFirst { it.eventIndex[v.ordinal] == idx && it.attacked[v.ordinal] }
            val from = if (srcIdx[v.ordinal] == a.eventIndex[v.ordinal]) h else sliceOf(srcIdx[v.ordinal])
            val at = sliceOf(resIdx[v.ordinal])
            return arrayOf((if (from >= 0) from else h) to v, (if (at >= 0) at else i) to v)
        }

        val lt = ca.leadingTone
        val target = ca.target
        val kept = srcIdx.copyOf()
        // A leading tone the voice reaches later in the chord (D–B over V) must resolve too
        if (lt != null) for (v in V) if (note(v)?.pc != lt) span.firstOrNull { slices[it].notes[v.ordinal] != null && !isNct(slices[it], v) }
            ?.let { j -> if (slices[j].notes[v.ordinal]?.pc == lt) srcIdx[v.ordinal] = slices[j].eventIndex[v.ordinal] }
        if (lt != null && target != null && cb.root != null && cb.notes.any { it.pc == target }) {
            // The cadential ⁶₄ stands for V, so a leading tone of V still rises into it
            val toTarget = cb.root == target || cb.root.step == (target.step + 5) % 7 ||
                (cb.roman == "Cad⁶₄" && target == score.key.degree(4))
            if (toTarget) for (v in V) {
                val p = note(v) ?: continue
                val q = nextNote(v, skipRepeats = true) ?: continue
                if (p.pc != lt || (q.pc == target && q.diatonic == p.diatonic + 1)) continue
                // Ornamented resolution: the voice reaches the tonic as the chord changes (B–D–C, B–G–C)
                if (arrival(v)?.let { it.pc == target && it.diatonic == p.diatonic + 1 } == true) continue
                // Chromatic descent into the seventh of the next chord (V⁷/V → V⁷: F♯ → F♮)
                if (q.step == p.step && q.alter == p.alter - 1 && q.pc == cb.seventh) continue
                if (v == Voice.SOPRANO || v == Voice.BASS)
                    add(Severity.ERROR, i, Rule.LEADING_TONE, "${v.label}: leading tone ${lt.name} should resolve up to ${target.name}.", both(v))
                // An inner voice may drop a third to the fifth of the chord (frustrated leading tone), also through a passing tone
                else if ((q.diatonic != p.diatonic - 2 || cb.member(q.pc) != 4) &&
                    arrival(v)?.let { it.diatonic == p.diatonic - 2 && cb.member(it.pc) == 4 } != true)
                    add(Severity.WARNING, i, Rule.LEADING_TONE, "${v.label}: leading tone ${lt.name} should resolve up to ${target.name} or drop to the fifth.", both(v))
            }
        }

        kept.copyInto(srcIdx)

        // Augmented sixth: both notes move outward by step to the octave. Diminished/half-diminished 7th: the fifth falls by step.
        val qa = ca.quality
        if (qa != null && cb.recognized && cb.root != ca.root) for (v in V) {
            val p = note(v) ?: continue
            val q = nextNote(v) ?: continue
            val m = ca.member(p.pc)
            val step = q.diatonic - p.diatonic
            if (qa.isAug6 && ((m == 0 && step != -1) || (m == 5 && step != 1)))
                add(Severity.ERROR, i, Rule.TENDENCY, "${v.label}: ${p.name} of the augmented sixth should move outward by step to the octave.", both(v))
            if ((qa == Quality.DIM7 || qa == Quality.HALF_DIM7) && ca.target != null && cb.root == ca.target && m == 4 && step != -1)
                add(Severity.WARNING, i, Rule.TENDENCY, "${v.label}: the fifth of ${ca.roman} (${p.name}) should fall by step.", both(v))
        }

        // Chromatic chord tones: the Neapolitan ♭2̂ moves down; other raised notes rise, lowered notes fall, by step.
        // Leading tones, sevenths and augmented sixths have their own rules above; so an applied chord (V/iii) has no others.
        val key = score.key
        if (qa != null && !qa.isAug6 && !ca.roman.contains("/") && cb.recognized && cb.root != ca.root) for (v in V) {
            val p = note(v) ?: continue
            if (key.isDiatonic(p.pc) || ca.member(p.pc) < 0 || p.pc == ca.leadingTone || p.pc == ca.seventh) continue
            val q = nextNote(v) ?: continue
            val step = q.diatonic - p.diatonic
            if (cb.notes.any { it.pc == p.pc }) continue // kept as a common tone
            if (ca.roman.startsWith("N") && p.pc == ca.root) {
                if (step !in -2..-1)
                    add(Severity.WARNING, i, Rule.TENDENCY, "${v.label}: ${p.name} (♭2̂) of the Neapolitan should move down to the leading tone or the tonic.", both(v))
                continue
            }
            val dir = if (p.pc.alter > key.sigAlter(p.pc.step)) 1 else -1
            // Free when the next chord (with its triad letters, an omitted fifth too) has no note a step that way,
            // as E♭ of ♭VI going to iv; turning back chromatically (A♭–A♮) is never free
            val letters = cb.notes.map { it.step } + listOf(0, 2, 4).map { (cb.root!!.step + it) % 7 }
            val reversal = q.step == p.step && (q.alter - p.alter).sign == -dir
            if (step != dir && (reversal || Math.floorMod(p.step + dir, 7) in letters))
                add(Severity.WARNING, i, Rule.TENDENCY, "${v.label}: chromatic ${p.name} should ${if (dir > 0) "rise" else "fall"} by step.", both(v))
        }

        val sev = ca.seventh ?: span.firstNotNullOfOrNull { chords[it].seventh }
        if (sev != null && cb.recognized && cb.root != ca.root) {
            val passing43 = ca.inversion == 2 && ca.degree == 4 && cb.degree == 0 && cb.inversion == 1
            for (v in V) {
                // The voice's last seventh in this chord: leaping away to another chord tone (F–D over V⁷) does not free it,
                // rising by step to the root within the chord (a passing 7–8) does
                span.firstOrNull { slices[it].notes[v.ordinal]?.pc == sev && !isNct(slices[it], v) }?.let { j ->
                    val idx = slices[j].eventIndex[v.ordinal]
                    val evs = score.voice(v)
                    var k = idx + 1
                    while (evs.getOrNull(k)?.pitch == evs[idx].pitch) k++
                    val up = evs.getOrNull(k)?.pitch?.let { it.diatonic == evs[idx].pitch!!.diatonic + 1 } == true &&
                        score.onsets(v)[k] < slices[i].time
                    if (!up) srcIdx[v.ordinal] = idx
                }
                val p = note(v) ?: continue
                if (p.pc != sev) continue
                // Handed to another voice that still has it as the chord changes (V⁷ → V⁶₅)
                if (slices[i - 1].notes[v.ordinal]?.pc != sev && V.any { slices[i - 1].notes[it.ordinal]?.pc == sev }) continue
                val held = slices[i].notes[v.ordinal] == p && !slices[i].attacked[v.ordinal]
                if (held && cb.notes.any { it.pc == sev }) continue // prolonged into the next chord
                val q = nextNote(v) ?: continue
                val step = q.diatonic - p.diatonic
                // Ornamented resolution: the step down arrives with the next chord (F–D–E)
                val ok = step == -1 || (step == 0 && q == p) || (passing43 && step == 1) ||
                    arrival(v)?.let { it.diatonic == p.diatonic - 1 } == true
                if (!ok) add(Severity.ERROR, i, Rule.SEVENTH, "${v.label}: chordal seventh ${sev.name} should resolve down by step.", both(v))
            }
        }
    }

    private fun isP5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.PERFECT
    private fun isD5(iv: Interval) = iv.simpleNumber == 5 && iv.quality == IntervalQuality.D
    private fun isP8(iv: Interval) = iv.simpleNumber == 1 && iv.quality == IntervalQuality.PERFECT
}
