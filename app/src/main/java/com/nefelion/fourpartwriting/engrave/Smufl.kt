package com.nefelion.fourpartwriting.engrave

import com.nefelion.fourpartwriting.model.Clef

/**
 * SMuFL code points and Bravura metrics (from bravura_metadata, in staff spaces, y up).
 * The font em equals 4 staff spaces.
 */
object Smufl {
    const val BRACE = ''
    const val BRACKET_TOP = ''
    const val BRACKET_BOTTOM = ''
    const val G_CLEF = ''
    const val G_CLEF_8VB = ''
    const val G_CLEF_8VA = ''
    const val C_CLEF = ''
    const val F_CLEF = ''
    const val F_CLEF_8VB = ''
    const val F_CLEF_8VA = ''
    const val TIME_0 = ''
    const val TIME_COMMON = ''
    const val TIME_CUT = ''
    const val HEAD_WHOLE = ''
    const val HEAD_HALF = ''
    const val HEAD_BLACK = ''
    const val DOT = ''
    val FLAG_UP = charArrayOf('', '', '', '')
    val FLAG_DOWN = charArrayOf('', '', '', '')
    const val FLAT = ''
    const val NATURAL = ''
    const val SHARP = ''
    const val DOUBLE_SHARP = ''
    const val DOUBLE_FLAT = ''
    const val REST_WHOLE = ''
    const val REST_HALF = ''
    val RESTS = charArrayOf('', '', '', '', '', '', '')
    const val MET_QUARTER = ''
    // Palette glyphs: metronome notes whole..64th (stem up).
    val MET_NOTES = charArrayOf('', '', '', '', '', '', '')

    // engravingDefaults
    const val STAFF_LINE = 0.13f
    const val STEM = 0.12f
    const val LEDGER = 0.16f
    const val LEDGER_EXT = 0.4f
    const val BEAM = 0.5f
    const val BEAM_GAP = 0.25f
    const val THIN_BAR = 0.16f
    const val THICK_BAR = 0.5f
    const val BAR_SEP = 0.4f

    const val HEAD_W = 1.18f
    const val WHOLE_W = 1.688f
    /** stemUpSE / stemDownNW y offset on black and half noteheads. */
    const val STEM_ANCHOR_Y = 0.168f
    /** Flag stem attachment y (stemUpNW / stemDownSW) per flag count 1..4. */
    val FLAG_UP_Y = floatArrayOf(-0.04f, -0.088f, 0.376f, 1.172f)
    val FLAG_DOWN_Y = floatArrayOf(0.132f, 0.128f, -0.448f, -1.244f)
    const val FLAG_W = 1.1f
    const val DOT_W = 0.4f
    const val BRACE_H = 4.0f
    const val BRACE_W = 0.2774f
    const val CLEF_W = 2.8f

    fun accidental(alter: Int) = when (alter) {
        -2 -> DOUBLE_FLAT; -1 -> FLAT; 1 -> SHARP; 2 -> DOUBLE_SHARP; else -> NATURAL
    }
    fun accidentalWidth(alter: Int) = when (alter) {
        -2 -> 1.644f; -1 -> 0.904f; 1 -> 0.996f; 2 -> 0.988f; else -> 0.672f
    }
    /** Height above / below the glyph origin, for collision spacing of stacked accidentals. */
    fun accidentalTop(alter: Int) = when (alter) { -2, -1 -> 1.75f; 1 -> 1.4f; 2 -> 0.5f; else -> 1.36f }
    fun accidentalBottom(alter: Int) = when (alter) { -2, -1 -> 0.7f; 1 -> 1.39f; 2 -> 0.5f; else -> 1.34f }

    fun clefGlyph(c: Clef) = when (c) {
        Clef.TREBLE, Clef.FRENCH_VIOLIN -> G_CLEF
        Clef.TREBLE_8VB -> G_CLEF_8VB
        Clef.TREBLE_8VA -> G_CLEF_8VA
        Clef.SOPRANO, Clef.MEZZO, Clef.ALTO, Clef.TENOR, Clef.BARITONE_C -> C_CLEF
        Clef.BASS, Clef.BARITONE_F, Clef.SUBBASS -> F_CLEF
        Clef.BASS_8VB -> F_CLEF_8VB
        Clef.BASS_8VA -> F_CLEF_8VA
    }

    /** Time-signature digit glyph widths (timeSig0..9). */
    val DIGIT_W = floatArrayOf(1.8f, 1.256f, 1.704f, 1.604f, 1.8f, 1.532f, 1.656f, 1.684f, 1.664f, 1.656f)
}
