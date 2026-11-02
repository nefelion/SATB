package com.nefelion.fourpartwriting.theory

/**
 * A spot in a bundled study chorale (assets/study/[study].satb) where Bach keeps a rule ([breaks] false) or breaks it.
 * [bar] is the bar number as shown (a pickup is bar 0). The chorale's "why" comment on the rule in that bar explains it.
 * StudySpotsTest checks each one against the checker and the comments.
 */
class BachSpot(val study: String, val bar: Int, val breaks: Boolean)

/** The rule cards' "In Bach's chorales" lists: the spots whose comments teach the most. */
val IN_BACH: Map<Rule, List<BachSpot>> = mapOf(
    Rule.PARALLEL_FIFTHS to listOf(
        BachSpot("BWV 13.6", 3, true),
        BachSpot("BWV 26.6", 4, true),
        BachSpot("BWV 139.6", 4, true),
    ),
    Rule.PARALLEL_OCTAVES to listOf(
        BachSpot("BWV 104.6", 3, true),
        BachSpot("BWV 20.7", 1, true),
    ),
    Rule.LEADING_TONE to listOf(
        BachSpot("BWV 153.9", 16, false),
        BachSpot("BWV 139.6", 8, false),
        BachSpot("BWV 133.6", 6, true),
        BachSpot("BWV 104.6", 4, true),
    ),
    Rule.SEVENTH to listOf(
        BachSpot("BWV 140.7", 36, false),
        BachSpot("BWV 16.6", 7, true),
    ),
    Rule.DOUBLING to listOf(
        BachSpot("BWV 140.7", 14, true),
        BachSpot("BWV 113.8", 3, true),
        BachSpot("BWV 157.5", 2, true),
    ),
    Rule.SPACING to listOf(
        BachSpot("BWV 113.8", 2, true),
        BachSpot("BWV 40.3", 5, true),
    ),
    Rule.CROSSING to listOf(
        BachSpot("BWV 125.6", 8, true),
        BachSpot("BWV 135.6", 2, true),
    ),
    Rule.OVERLAP to listOf(
        BachSpot("BWV 126.6", 22, true),
        BachSpot("BWV 140.7", 34, true),
    ),
    Rule.MELODIC to listOf(
        BachSpot("BWV 245.26", 1, true),
        BachSpot("BWV 26.6", 9, true),
    ),
    Rule.LEAPS to listOf(
        BachSpot("BWV 11.6", 12, true),
        BachSpot("BWV 104.6", 10, true),
    ),
    Rule.RANGE to listOf(
        BachSpot("BWV 117.4", 5, true),
        BachSpot("BWV 245.40", 17, true),
    ),
    Rule.DIRECT to listOf(
        BachSpot("BWV 115.6", 5, true),
        BachSpot("BWV 244.15", 11, true),
    ),
    Rule.CROSS_RELATION to listOf(
        BachSpot("BWV 245.5", 12, true),
        BachSpot("BWV 245.26", 9, true),
    ),
    Rule.SIMILAR_MOTION to listOf(
        BachSpot("BWV 145.5", 15, true),
        BachSpot("BWV 117.4", 9, true),
    ),
    Rule.TENDENCY to listOf(
        BachSpot("BWV 245.22", 7, true),
    ),
    Rule.UNRESOLVED_NCT to listOf(
        BachSpot("BWV 20.7", 5, false),
        BachSpot("BWV 248.46", 11, true),
    ),
    Rule.MISSING_THIRD to listOf(
        BachSpot("BWV 26.6", 10, true),
        BachSpot("BWV 43.11", 22, true),
    ),
    Rule.SIX_FOUR to listOf(
        BachSpot("BWV 176.6", 18, false),
        BachSpot("BWV 174.5", 5, true),
    ),
    Rule.PROGRESSION to listOf(
        BachSpot("BWV 145.5", 15, true),
        BachSpot("BWV 113.8", 5, true),
    ),
    Rule.HARMONIC_RHYTHM to listOf(
        BachSpot("BWV 11.6", 6, true),
    ),
    Rule.CADENCE to listOf(
        BachSpot("BWV 20.7", 10, false),
        BachSpot("BWV 244.62", 12, true),
    ),
)
