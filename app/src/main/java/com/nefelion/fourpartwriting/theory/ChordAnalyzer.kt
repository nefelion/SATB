package com.nefelion.fourpartwriting.theory

import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.pcInterval

/**
 * Chord types as exact maps of generic interval above the root (0..6 = 1st..7th, so 1 = 9th,
 * 3 = 11th/4th, 5 = 13th/6th) to semitones. Spelling decides everything, so enharmonic chords
 * (Ger+6 vs dominant 7th, C♯ vs D♭) can never be confused. List order is match priority.
 */
enum class Quality(
    val members: Map<Int, Int>,
    val symbol: String,
    val upper: Boolean,
    val romanMark: String,
    val seventh: Boolean = false,
    val incomplete: Boolean = false,
) {
    MAJOR(mapOf(2 to 4, 4 to 7), "", true, ""),
    MINOR(mapOf(2 to 3, 4 to 7), "m", false, ""),
    DIM(mapOf(2 to 3, 4 to 6), "dim", false, "°"),
    AUG(mapOf(2 to 4, 4 to 8), "+", true, "+"),
    DOM7(mapOf(2 to 4, 4 to 7, 6 to 10), "7", true, "", seventh = true),
    MAJ7(mapOf(2 to 4, 4 to 7, 6 to 11), "maj7", true, "M", seventh = true),
    MIN7(mapOf(2 to 3, 4 to 7, 6 to 10), "m7", false, "", seventh = true),
    HALF_DIM7(mapOf(2 to 3, 4 to 6, 6 to 10), "m7♭5", false, "ø", seventh = true),
    DIM7(mapOf(2 to 3, 4 to 6, 6 to 9), "dim7", false, "°", seventh = true),
    MIN_MAJ7(mapOf(2 to 3, 4 to 7, 6 to 11), "m(maj7)", false, "M", seventh = true),
    AUG_MAJ7(mapOf(2 to 4, 4 to 8, 6 to 11), "+maj7", true, "+M", seventh = true),
    AUG7(mapOf(2 to 4, 4 to 8, 6 to 10), "+7", true, "+", seventh = true),
    // Augmented sixths: "root" is the lower note of the augmented sixth (normally ♭6̂).
    IT6(mapOf(2 to 4, 5 to 10), "It+6", true, ""),
    FR6(mapOf(2 to 4, 3 to 6, 5 to 10), "Fr+6", true, ""),
    GER6(mapOf(2 to 4, 4 to 7, 5 to 10), "Ger+6", true, ""),
    SW6(mapOf(2 to 4, 3 to 7, 5 to 10), "Ger+6 (♯2̂)", true, ""),
    DOM9(mapOf(2 to 4, 6 to 10, 1 to 2), "9", true, "", seventh = true, incomplete = true),
    DOM7_FLAT9(mapOf(2 to 4, 6 to 10, 1 to 1), "7♭9", true, "", seventh = true, incomplete = true),
    MAJ9(mapOf(2 to 4, 6 to 11, 1 to 2), "maj9", true, "M", seventh = true, incomplete = true),
    MIN9(mapOf(2 to 3, 6 to 10, 1 to 2), "m9", false, "", seventh = true, incomplete = true),
    DOM13(mapOf(2 to 4, 6 to 10, 5 to 9), "13", true, "", seventh = true, incomplete = true),
    SUS4(mapOf(3 to 5, 4 to 7), "sus4", true, ""),
    DOM7_SUS4(mapOf(3 to 5, 4 to 7, 6 to 10), "7sus4", true, "", seventh = true),
    MAJOR_NO5(mapOf(2 to 4), "(no5)", true, "", incomplete = true),
    MINOR_NO5(mapOf(2 to 3), "m(no5)", false, "", incomplete = true),
    DOM7_NO5(mapOf(2 to 4, 6 to 10), "7(no5)", true, "", seventh = true, incomplete = true),
    MAJ7_NO5(mapOf(2 to 4, 6 to 11), "maj7(no5)", true, "M", seventh = true, incomplete = true),
    MIN7_NO5(mapOf(2 to 3, 6 to 10), "m7(no5)", false, "", seventh = true, incomplete = true),
    SUS2(mapOf(1 to 2, 4 to 7), "sus2", true, "");

    val isAug6 get() = this == IT6 || this == FR6 || this == GER6 || this == SW6
    val isNinth get() = this == DOM9 || this == DOM7_FLAT9 || this == MAJ9 || this == MIN9
    val hasThird get() = 2 in members
}

private val DOMINANT_QUALITIES = setOf(
    Quality.MAJOR, Quality.MAJOR_NO5, Quality.DOM7, Quality.DOM7_NO5, Quality.DOM9, Quality.DOM7_FLAT9,
)
private val LEADING_TONE_QUALITIES = setOf(Quality.DIM, Quality.DIM7, Quality.HALF_DIM7)

data class Chord(
    val notes: List<Pitch>,
    val root: PitchClass?,
    val quality: Quality?,
    val bass: PitchClass?,
    /** 0 = root position, 1..3 = inversions; -1 = bass is an extension or non-tertian. */
    val inversion: Int,
    val symbol: String,
    val roman: String,
    /** Scale degree 0..6 of the root, -1 when unknown. */
    val degree: Int = -1,
    /** For dominant-function chords: root of the chord they resolve to, and the leading tone. */
    val target: PitchClass? = null,
    val leadingTone: PitchClass? = null,
    /** Around a key change: the chord read in the other key ("G: IV"), shown faint under [roman]. */
    val alt: String? = null,
) {
    val recognized get() = quality != null
    /** Generic interval of [pc] above the root (0 root, 2 third, 4 fifth, 6 seventh...), -1 if none. */
    fun member(pc: PitchClass): Int =
        if (root == null || pc !in notes.map { it.pc }) -1 else pcInterval(root, pc).first
    val seventh: PitchClass? get() =
        if (quality?.seventh == true) notes.map { it.pc }.firstOrNull { member(it) == 6 } else null
    val third: PitchClass? get() = notes.map { it.pc }.firstOrNull { member(it) == 2 }
}

object ChordAnalyzer {

    /** Identifies one sonority. [notes] may be in any order; the lowest pitch is the bass. */
    fun analyze(notes: List<Pitch>, key: Key): Chord {
        if (notes.isEmpty()) return Chord(notes, null, null, null, -1, "—", "")
        val bass = notes.minWith(compareBy({ it.midi }, { it.diatonic })).pc
        val pcs = notes.map { it.pc }.distinct()
        if (pcs.size == 1) return unrecognized(notes, bass, "${bass.name} (unison/octave)")

        var best: Pair<PitchClass, Quality>? = null
        for (r in pcs) {
            val q = match(r, pcs) ?: continue
            // C–D–G is Csus2 over C but Gsus4 over G: between two sus readings the bass decides
            val better = if (best != null && !q.hasThird && !best.second.hasThird && (r == bass) != (best.first == bass)) r == bass
            else best == null || q.ordinal < best.second.ordinal
            if (better) best = r to q
        }
        if (best == null) {
            val hint = if (pcs.size == 2) {
                val (g, s) = pcInterval(pcs[0], pcs[1])
                if ((g == 4 && s == 7) || (g == 3 && s == 5)) " (open fifth, no 3rd)" else ""
            } else ""
            return unrecognized(notes, bass, pcs.joinToString("–") { it.name } + hint)
        }
        val (root, q) = best
        val bassG = pcInterval(root, bass).first
        val inversion = if (q.isAug6) (if (bassG == 0) 0 else -1) else when (bassG) {
            0 -> 0; 2 -> 1; 4 -> 2; 6 -> 3; else -> -1
        }
        val symbol = when {
            q.isAug6 -> "${q.symbol} on ${root.name}" + if (bass != root) " /${bass.name}" else ""
            else -> root.name + q.symbol + if (bass != root) "/${bass.name}" else ""
        }
        return romanize(Chord(notes, root, q, bass, inversion, symbol, ""), key)
    }

    /** Analyzes a progression and applies context labels (cadential 6/4, Picardy third). */
    fun analyzeAll(slices: List<List<Pitch>>, key: Key): List<Chord> = withContext(slices.map { analyze(it, key) }, key)

    /** Context labels on a chord sequence: cadential 6/4, Picardy third. */
    fun withContext(list: List<Chord>, key: Key): List<Chord> = withContext(list, list.map { key })

    /** [keys]: the key of each chord (a piece may modulate). */
    fun withContext(list: List<Chord>, keys: List<Key>): List<Chord> {
        val chords = list.toMutableList()
        for (i in chords.indices) {
            val c = chords[i]
            val key = keys[i]
            // The chord the context comes from: the next one that is not this chord struck again
            val next = list.drop(i + 1).firstOrNull { it.root != c.root || it.quality != c.quality || it.bass != c.bass }
            // I6/4 resolving to V (root position) is a cadential 6/4.
            if (c.degree == 0 && c.inversion == 2 && c.quality in setOf(Quality.MAJOR, Quality.MINOR) &&
                next != null && next.degree == 4 && next.inversion == 0 && next.target == key.tonic
            ) chords[i] = c.copy(roman = "Cad⁶₄")
            // A major tonic ending a minor-key piece is a Picardy third, not V/iv.
            if (list.drop(i + 1).all { it.root == c.root && it.quality == c.quality } && key.mode == Mode.MINOR && c.root == key.tonic &&
                c.quality in setOf(Quality.MAJOR, Quality.MAJOR_NO5)
            ) chords[i] = c.copy(roman = "I" + triadFigure(c.inversion) + " (Picardy)", target = null, leadingTone = null)
        }
        return chords
    }

    private fun unrecognized(notes: List<Pitch>, bass: PitchClass, text: String) =
        Chord(notes, null, null, bass, -1, text, "?")

    private fun match(root: PitchClass, pcs: List<PitchClass>): Quality? {
        val m = HashMap<Int, Int>()
        for (pc in pcs) {
            if (pc == root) continue
            val (g, s) = pcInterval(root, pc)
            if (g == 0 || m.put(g, s) != null) return null // altered root or split member
        }
        return Quality.entries.firstOrNull { it.members == m }
    }

    // ---- Roman numerals ----

    private val NUMERALS = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")

    private fun triadFigure(inv: Int) = when (inv) { 1 -> "⁶"; 2 -> "⁶₄"; else -> "" }
    private fun seventhFigure(inv: Int) = when (inv) { 1 -> "⁶₅"; 2 -> "⁴₃"; 3 -> "⁴₂"; else -> "⁷" }

    private fun figure(q: Quality, inv: Int) = when {
        q.isNinth -> "⁹"
        q == Quality.DOM13 -> "¹³"
        q == Quality.SUS4 -> "sus4"
        q == Quality.SUS2 -> "sus2"
        q == Quality.DOM7_SUS4 -> "⁷sus4"
        q.seventh -> seventhFigure(inv)
        else -> triadFigure(inv)
    }

    /** Numeral with case and quality mark, no figure (e.g. "vii°", "IVM", "♭VI"). */
    private fun numeral(root: PitchClass, q: Quality, key: Key): Pair<String, Int> {
        val degree = Math.floorMod(root.step - key.tonic.step, 7)
        val diff = root.alter - key.sigAlter(root.step)
        val raisedInMinor = key.mode == Mode.MINOR && (degree == 5 || degree == 6) && diff == 1
        val prefix = when {
            raisedInMinor && degree == 6 -> ""
            raisedInMinor -> "♯"
            else -> when (diff) { -2 -> "𝄫"; -1 -> "♭"; 1 -> "♯"; 2 -> "𝄪"; else -> "" }
        }
        val n = NUMERALS[degree].let { if (q.upper) it else it.lowercase() }
        return prefix + n + q.romanMark to degree
    }

    private fun romanize(c: Chord, key: Key): Chord {
        val root = c.root!!
        val q = c.quality!!
        val inv = c.inversion
        val fig = figure(q, inv)
        val diatonic = c.notes.all { key.isDiatonic(it.pc) }

        if (q.isAug6) {
            val name = when (q) { Quality.IT6 -> "It"; Quality.FR6 -> "Fr"; else -> "Ger" } + "⁺⁶"
            val flat6 = lowered(key, 5)
            val flat6OfV = lowered(key, 2) // ♭6̂ of the dominant key = ♭3̂ of the tonic
            val roman = when (root) { flat6 -> name; flat6OfV -> "$name/V"; else -> "$name (on ${root.name})" }
            return c.copy(roman = roman)
        }

        val (num, degree) = numeral(root, q, key)

        // Neapolitan: major triad on ♭2̂.
        if (degree == 1 && root.alter == key.degree(1).alter - 1 && q in setOf(Quality.MAJOR, Quality.MAJOR_NO5)) {
            return c.copy(roman = "N" + triadFigure(inv), degree = degree)
        }

        // Home-key dominant function.
        if (degree == 4 && q in DOMINANT_QUALITIES && c.third == key.leadingTone)
            return c.copy(roman = num + fig, degree = degree, target = key.tonic, leadingTone = key.leadingTone)
        if (degree == 6 && q in LEADING_TONE_QUALITIES && root == key.leadingTone)
            return c.copy(roman = num + fig, degree = degree, target = key.tonic, leadingTone = key.leadingTone)

        if (!diatonic) secondary(c, key, fig)?.let { return it }

        return c.copy(roman = num + fig, degree = degree)
    }

    /** ♭ of scale degree d in major; the natural-minor degree in minor. */
    private fun lowered(key: Key, d: Int): PitchClass {
        val pc = key.degree(d)
        return if (key.mode == Mode.MAJOR) PitchClass(pc.step, pc.alter - 1) else pc
    }

    private fun secondary(c: Chord, key: Key, fig: String): Chord? {
        val q = c.quality!!
        val asV = q in DOMINANT_QUALITIES
        val asVii = q in LEADING_TONE_QUALITIES
        if (!asV && !asVii) return null
        val targets = if (key.mode == Mode.MAJOR) arrayOf(null, "ii", "iii", "IV", "V", "vi", null)
        else arrayOf(null, null, "III", "iv", "V", "VI", "VII")
        for (t in 1..6) {
            val label = targets[t] ?: continue
            val target = key.degree(t)
            if (asV && c.root == transpose(target, 4, 7) && c.third == transpose(target, 6, 11)) {
                val lt = transpose(target, 6, 11)
                return c.copy(roman = "V" + fig + "/" + label, degree = Math.floorMod(c.root!!.step - key.tonic.step, 7),
                    target = target, leadingTone = lt)
            }
            if (asVii && c.root == transpose(target, 6, 11)) {
                val mark = q.romanMark
                return c.copy(roman = "vii" + mark + fig + "/" + label, degree = Math.floorMod(c.root.step - key.tonic.step, 7),
                    target = target, leadingTone = c.root)
            }
        }
        return null
    }

    /** Pitch class [generic] letters and [semis] semitones above [pc]. */
    fun transpose(pc: PitchClass, generic: Int, semis: Int): PitchClass {
        val step = (pc.step + generic) % 7
        val natural = PitchClass(step, 0).semis
        var alter = Math.floorMod(pc.semis + semis - natural, 12)
        if (alter > 6) alter -= 12
        return PitchClass(step, alter.coerceIn(-2, 2))
    }
}
