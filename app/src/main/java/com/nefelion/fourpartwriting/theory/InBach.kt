package com.nefelion.fourpartwriting.theory

/**
 * A spot in a bundled study chorale (assets/study/[study].satb) where Bach keeps a rule ([breaks] false) or breaks it.
 * [bar] is the bar number as shown (a pickup is bar 0). StudySpotsTest checks each one against the checker.
 */
class BachSpot(val study: String, val bar: Int, val breaks: Boolean, val note: String)

/** The rule cards' "In Bach's chorales" lists. */
val IN_BACH: Map<Rule, List<BachSpot>> = mapOf(
    Rule.PARALLEL_FIFTHS to listOf(
        BachSpot("BWV 13.6", 3, true, "Soprano and tenor go from a twelfth to a fifth in opposite directions. Strict exercises forbid this too, but the contrary motion keeps the two lines apart."),
        BachSpot("BWV 26.6", 4, true, "Soprano and tenor fall by step together on a weak eighth note. The fifths pass between the beats, so they are hardly heard."),
    ),
    Rule.PARALLEL_OCTAVES to listOf(
        BachSpot("BWV 104.6", 3, true, "Tenor and bass step from A to G♯ an octave apart on a weak eighth note. For a moment the bass is simply doubled; in exercises, keep the voices apart."),
    ),
    Rule.LEADING_TONE to listOf(
        BachSpot("BWV 153.9", 16, false, "At the final cadence the leading tone B rises to C in the soprano."),
        BachSpot("BWV 139.6", 8, false, "The alto's leading tone D♯ drops to B, the fifth of the last chord, so that chord has all its notes. The soprano still ends on the tonic."),
        BachSpot("BWV 104.6", 4, true, "The leading tone G♯ in the bass skips to C♯ first and reaches A only an eighth later."),
    ),
    Rule.SEVENTH to listOf(
        BachSpot("BWV 140.7", 36, false, "In the final V⁷ the tenor's A♭, the seventh, falls by step to G."),
    ),
    Rule.DOUBLING to listOf(
        BachSpot("BWV 113.8", 3, true, "In the D major chord (III in B minor) Bach doubles the third, F♯. F♯ is the dominant of the home key, a strong note to double."),
        BachSpot("BWV 157.5", 2, true, "The leading tone C♯ is doubled in soprano and tenor, but only for an eighth: the tenor moves on to B at once."),
    ),
    Rule.SPACING to listOf(
        BachSpot("BWV 113.8", 2, true, "The bass falls to a low F♯ for the dominant, two octaves under the tenor's F♯, which gives the chord a deep, full sound."),
    ),
    Rule.CROSSING to listOf(
        BachSpot("BWV 102.7", 4, true, "The alto dips to B, a neighbor note, under the tenor's C for one eighth. The crossing is so short that both lines stay clear."),
    ),
    Rule.OVERLAP to listOf(
        BachSpot("BWV 244.3", 8, true, "The bass leaps up an octave on the same chord, past where the tenor just was. The tenor has moved up too, so the lines do not get confused."),
    ),
    Rule.MELODIC to listOf(
        BachSpot("BWV 245.26", 1, true, "The bass leaps down a diminished seventh, A♭ to B, then steps up to C. An expressive leap in the St John Passion, settled by the step after it."),
    ),
    Rule.DIRECT to listOf(
        BachSpot("BWV 244.15", 11, true, "The soprano leaps up to B while the bass steps up to B: hidden octaves going into the dominant of the cadence. The bass moves by step, which softens them."),
    ),
    Rule.CROSS_RELATION to listOf(
        BachSpot("BWV 245.5", 12, true, "G in the soprano, then G♯ in the bass. The G♯ is the bass's leading tone into A, so the ear hears it as part of the bass line."),
    ),
    Rule.SIMILAR_MOTION to listOf(
        BachSpot("BWV 113.8", 6, true, "All four voices fall into the B minor chord: the seventh E falls to D and the bass to the tonic, so every pull points down."),
    ),
    Rule.SIX_FOUR to listOf(
        BachSpot("BWV 176.6", 18, false, "A cadential ⁶₄ on the downbeat over the bass G resolves to V: E♭ to D in the soprano, C to B in the tenor."),
    ),
    Rule.CADENCE to listOf(
        BachSpot("BWV 20.7", 10, false, "An authentic cadence: V to I, both in root position, the soprano falling from G to the tonic F."),
        BachSpot("BWV 244.62", 12, true, "The melody is in the Phrygian mode and ends on E, so Bach ends on the E major chord, which in A minor is V."),
    ),
)
