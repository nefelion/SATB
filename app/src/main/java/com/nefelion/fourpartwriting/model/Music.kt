package com.nefelion.fourpartwriting.model

import kotlin.math.abs

/** Exact rational number for musical time (whole note = 1). */
data class Fraction(val num: Long, val den: Long) : Comparable<Fraction> {
    init { require(den > 0) }
    operator fun plus(o: Fraction) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Fraction) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Fraction) = of(num * o.num, den * o.den)
    operator fun div(o: Fraction) = of(num * o.den, den * o.num)
    override fun compareTo(other: Fraction) = (num * other.den).compareTo(other.num * den)
    fun toDouble() = num.toDouble() / den

    companion object {
        val ZERO = Fraction(0, 1)
        fun of(n: Long, d: Long): Fraction {
            require(d != 0L)
            val s = if (d < 0) -1 else 1
            val g = gcd(abs(n), abs(d)).coerceAtLeast(1)
            return Fraction(s * n / g, s * d / g)
        }
        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/** Semitones above C of each diatonic step C D E F G A B. */
private val STEP_SEMIS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val STEP_NAMES = "CDEFGAB"

fun alterSymbol(alter: Int) = when (alter) {
    -2 -> "𝄫"; -1 -> "♭"; 1 -> "♯"; 2 -> "𝄪"; else -> ""
}

/** Spelled pitch class: letter step 0..6 (C..B) + alteration. */
data class PitchClass(val step: Int, val alter: Int) {
    val semis get() = Math.floorMod(STEP_SEMIS[step] + alter, 12)
    val name get() = STEP_NAMES[step] + alterSymbol(alter)
    override fun toString() = name
}

/** Spelled pitch. Octave follows scientific pitch notation (C4 = middle C). */
data class Pitch(val step: Int, val alter: Int, val octave: Int) {
    init { require(step in 0..6 && alter in -2..2) }
    val diatonic get() = octave * 7 + step
    val midi get() = 12 * (octave + 1) + STEP_SEMIS[step] + alter
    val pc get() = PitchClass(step, alter)
    val name get() = pc.name + octave
    override fun toString() = name

    /** Moves by diatonic steps, keeping the alteration. */
    fun shiftDiatonic(n: Int): Pitch {
        val d = diatonic + n
        return Pitch(Math.floorMod(d, 7), alter, Math.floorDiv(d, 7))
    }

    companion object {
        fun fromDiatonic(d: Int, alter: Int) = Pitch(Math.floorMod(d, 7), alter, Math.floorDiv(d, 7))
        /** Parses "C4", "F#3", "Bb2", "Ex4" (double sharp), "Abb3". */
        fun parse(s: String): Pitch {
            val step = STEP_NAMES.indexOf(s[0].uppercaseChar())
            var i = 1
            var alter = 0
            while (i < s.length && !s[i].isDigit() && s[i] != '-') {
                alter += when (s[i]) { '#', '♯' -> 1; 'x' -> 2; 'b', '♭' -> -1; else -> error("bad pitch $s") }
                i++
            }
            return Pitch(step, alter, s.substring(i).toInt())
        }
    }
}

enum class IntervalQuality(val symbol: String) { DD("dd"), D("d"), MINOR("m"), PERFECT("P"), MAJOR("M"), A("A"), AA("AA") }

/**
 * Interval between two spelled pitches, computed from letter distance and semitones.
 * [number] is 1-based (1 = unison, 8 = octave), always >= 1; [descending] tells the direction.
 */
data class Interval(val number: Int, val semis: Int, val descending: Boolean) {
    val simpleNumber get() = (number - 1) % 7 + 1
    val isPerfectClass get() = simpleNumber in intArrayOf(1, 4, 5)

    val quality: IntervalQuality
        get() {
            val octaves = (number - 1) / 7
            val diff = semis - (STEP_SEMIS[simpleNumber - 1] + 12 * octaves)
            return if (isPerfectClass) when (diff) {
                0 -> IntervalQuality.PERFECT; 1 -> IntervalQuality.A; -1 -> IntervalQuality.D
                in 2..9 -> IntervalQuality.AA; else -> IntervalQuality.DD
            } else when (diff) {
                0 -> IntervalQuality.MAJOR; -1 -> IntervalQuality.MINOR; 1 -> IntervalQuality.A
                -2 -> IntervalQuality.D; in 2..9 -> IntervalQuality.AA; else -> IntervalQuality.DD
            }
        }

    val name get() = quality.symbol + number

    companion object {
        fun between(from: Pitch, to: Pitch): Interval {
            val d = to.diatonic - from.diatonic
            val s = to.midi - from.midi
            val desc = d < 0 || (d == 0 && s < 0)
            return Interval(abs(d) + 1, if (desc) -s else s, desc)
        }
    }
}

/** Generic (0..6) and semitone (0..11) distance from pitch class [a] up to [b]. */
fun pcInterval(a: PitchClass, b: PitchClass): Pair<Int, Int> =
    Math.floorMod(b.step - a.step, 7) to Math.floorMod(b.semis - a.semis, 12)

/** Note value: base 1 = whole, 2 = half ... 64 = sixty-fourth; up to 2 dots. */
data class Duration(val base: Int, val dots: Int = 0) {
    init { require(base in intArrayOf(1, 2, 4, 8, 16, 32, 64) && dots in 0..2) }
    val value: Fraction get() {
        // base value * (2 - 1/2^dots)
        val p = 1L shl dots
        return Fraction.of(2 * p - 1, base.toLong() * p)
    }
    /** Flags / beams count. */
    val flags get() = when (base) { 8 -> 1; 16 -> 2; 32 -> 3; 64 -> 4; else -> 0 }
}

enum class Mode { MAJOR, MINOR }

// Order of sharps (F C G D A E B) as diatonic steps; flats are the reverse.
val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6)
val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)

/** Key signature: fifths -7..7 (negative = flats). */
data class Key(val fifths: Int, val mode: Mode) {
    init { require(fifths in -7..7) }

    /** Alteration the key signature gives to [step]. */
    fun sigAlter(step: Int): Int = when {
        fifths > 0 && SHARP_ORDER.indexOf(step) < fifths -> 1
        fifths < 0 && FLAT_ORDER.indexOf(step) < -fifths -> -1
        else -> 0
    }

    val tonic: PitchClass get() {
        val majorStep = Math.floorMod(4 * fifths, 7)
        val step = if (mode == Mode.MAJOR) majorStep else Math.floorMod(majorStep + 5, 7)
        return PitchClass(step, sigAlter(step))
    }

    /** Diatonic scale pitch class for degree 0..6 (natural minor in minor). */
    fun degree(d: Int): PitchClass {
        val step = Math.floorMod(tonic.step + d, 7)
        return PitchClass(step, sigAlter(step))
    }

    /** In minor, raised 6th/7th (melodic/harmonic) also count as diatonic. */
    fun isDiatonic(pc: PitchClass): Boolean {
        if (pc.alter == sigAlter(pc.step)) return true
        if (mode == Mode.MINOR) {
            val d = Math.floorMod(pc.step - tonic.step, 7)
            if ((d == 5 || d == 6) && pc.alter == sigAlter(pc.step) + 1) return true
        }
        return false
    }

    /** Leading tone: raised 7th in minor. */
    val leadingTone: PitchClass get() {
        val lt = degree(6)
        return if (mode == Mode.MINOR) PitchClass(lt.step, lt.alter + 1) else lt
    }

    val name get() = tonic.name + if (mode == Mode.MAJOR) " major" else " minor"
}

data class TimeSig(val beats: Int, val beatUnit: Int, val symbol: Symbol = Symbol.NUMERIC) {
    enum class Symbol { NUMERIC, COMMON, CUT }
    val measure get() = Fraction.of(beats.toLong(), beatUnit.toLong())
    /** Compound meters (6/8, 9/8, 12/8, 6/4 ...) group by dotted beats. */
    val isCompound get() = beats % 3 == 0 && beats > 3 && beatUnit >= 4
    /** Beam/beat grouping unit. */
    val beatLength: Fraction get() = when {
        isCompound -> Fraction.of(3, beatUnit.toLong())
        // 2/2 and 4/2 beam in quarter groups is unusual; keep the written beat
        else -> Fraction.of(1, beatUnit.toLong())
    }
    val label get() = when (symbol) { Symbol.COMMON -> "C"; Symbol.CUT -> "¢"; else -> "$beats/$beatUnit" }
}

/**
 * Clef: sign pitch sits on [line] (1 = bottom line). Staff positions are counted in half
 * staff spaces from the bottom line (0 = bottom line, 8 = top line).
 */
enum class Clef(val label: String, val refPitch: Pitch, val line: Int, val octaveShift: Int) {
    TREBLE("Treble", Pitch(4, 0, 4), 2, 0),
    TREBLE_8VB("Treble 8vb", Pitch(4, 0, 4), 2, -1),
    TREBLE_8VA("Treble 8va", Pitch(4, 0, 4), 2, 1),
    FRENCH_VIOLIN("French violin", Pitch(4, 0, 4), 1, 0),
    SOPRANO("Soprano", Pitch(0, 0, 4), 1, 0),
    MEZZO("Mezzo-soprano", Pitch(0, 0, 4), 2, 0),
    ALTO("Alto", Pitch(0, 0, 4), 3, 0),
    TENOR("Tenor", Pitch(0, 0, 4), 4, 0),
    BARITONE_C("Baritone (C)", Pitch(0, 0, 4), 5, 0),
    BARITONE_F("Baritone (F)", Pitch(3, 0, 3), 3, 0),
    BASS("Bass", Pitch(3, 0, 3), 4, 0),
    BASS_8VB("Bass 8vb", Pitch(3, 0, 3), 4, -1),
    BASS_8VA("Bass 8va", Pitch(3, 0, 3), 4, 1),
    SUBBASS("Sub-bass", Pitch(3, 0, 3), 5, 0);

    val sign get() = when (refPitch.step) { 4 -> 'G'; 0 -> 'C'; else -> 'F' }
    private val refPos get() = (line - 1) * 2

    /** Written staff position of a sounding pitch. */
    fun position(p: Pitch) = p.diatonic - (refPitch.diatonic + 7 * octaveShift) + refPos
    /** Letter + octave (no alteration) at a staff position. */
    fun pitchAt(pos: Int, alter: Int = 0) =
        Pitch.fromDiatonic(refPitch.diatonic + 7 * octaveShift + pos - refPos, alter)

    /**
     * Staff positions of the key signature accidentals. Each accidental takes the only
     * octave inside a 6-step window; tenor sharps follow their own traditional zigzag.
     */
    fun keySigPositions(fifths: Int): List<Int> {
        if (fifths == 0) return emptyList()
        val sharps = fifths > 0
        val order = if (sharps) SHARP_ORDER else FLAT_ORDER
        if (sharps && this == TENOR) return listOf(2, 6, 3, 7, 4, 8, 5).take(fifths)
        if (sharps && this == SOPRANO) return listOf(3, 7, 4, 8, 5, 9, 6).take(fifths)
        // Window low end for an un-shifted clef, taken from the treble pattern (sharps 3..9,
        // flats 1..7) and moved by the clef's offset from treble.
        val trebleOffset = position(Pitch(4, 0, 4)) - 2 + 7 * octaveShift
        var lo = (if (sharps) 3 else 1) + trebleOffset
        // Keep signatures near the staff for clefs whose window falls outside it.
        while (lo < -1) lo += 7
        while (lo > 5) lo -= 7
        return order.take(abs(fifths)).map { step ->
            val base = clefFree(step)
            var pos = base
            while (pos < lo) pos += 7
            while (pos > lo + 6) pos -= 7
            pos
        }
    }

    // Position of the given step in octave 4, ignoring octave shift (signatures are written the same).
    private fun clefFree(step: Int) = Pitch(step, 0, 4).diatonic - refPitch.diatonic + refPos
}
