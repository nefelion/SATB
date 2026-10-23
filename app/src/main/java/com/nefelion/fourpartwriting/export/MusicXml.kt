package com.nefelion.fourpartwriting.export

import com.nefelion.fourpartwriting.engrave.Notation
import com.nefelion.fourpartwriting.engrave.Seg
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import kotlin.math.ceil
import kotlin.math.max

/** MusicXML 4.0 partwise export: one part, one staff per arrangement staff, voices 1–4. */
object MusicXml {
    private val TYPES = mapOf(1 to "whole", 2 to "half", 4 to "quarter", 8 to "eighth", 16 to "16th", 32 to "32nd", 64 to "64th")
    private val STEPS = "CDEFGAB"

    private class W(val voice: Voice, val index: Int, val seg: Seg, val segIndex: Int, val last: Boolean)

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    fun export(score: Score): String {
        val ml = score.time.measure
        val written = Voice.entries.flatMap { v ->
            var t = Fraction.ZERO
            score.voice(v).flatMapIndexed { i, e ->
                val segs = Notation.split(t, e.length, score.time, e.pitch == null)
                t += e.length
                segs.mapIndexed { k, s -> W(v, i, s, k, k == segs.lastIndex) }
            }
        }
        // divisions per quarter: every segment length must be a whole number of divisions
        var div = 1L
        for (w in written) {
            val q = w.seg.length * Fraction.of(4, 1)
            div = div / gcd(div, q.den) * q.den
        }
        div = max(div, ml.let { (it * Fraction.of(4, 1)).den })
        fun dur(f: Fraction) = (f * Fraction.of(4 * div, 1)).let { it.num / it.den }

        val bars = max(1, ceil(score.length.toDouble() / ml.toDouble() - 1e-9).toInt())
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0">
  <identification><encoding><software>SATB</software></encoding></identification>
  <part-list><score-part id="P1"><part-name>SATB</part-name></score-part></part-list>
  <part id="P1">
""")
        for (b in 0 until bars) {
            val start = ml * Fraction.of(b.toLong(), 1)
            val end = start + ml
            sb.append("    <measure number=\"${b + 1}\">\n")
            if (b == 0) {
                sb.append("      <attributes>\n        <divisions>$div</divisions>\n")
                sb.append("        <key><fifths>${score.key.fifths}</fifths><mode>${if (score.key.mode == Mode.MAJOR) "major" else "minor"}</mode></key>\n")
                val sym = when (score.time.symbol) { TimeSig.Symbol.COMMON -> " symbol=\"common\""; TimeSig.Symbol.CUT -> " symbol=\"cut\""; else -> "" }
                sb.append("        <time$sym><beats>${score.time.beats}</beats><beat-type>${score.time.beatUnit}</beat-type></time>\n")
                sb.append("        <staves>${score.arrangement.staves}</staves>\n")
                for ((i, c) in score.clefs.withIndex()) {
                    sb.append("        <clef number=\"${i + 1}\"><sign>${c.sign}</sign><line>${c.line}</line>")
                    if (c.octaveShift != 0) sb.append("<clef-octave-change>${c.octaveShift}</clef-octave-change>")
                    sb.append("</clef>\n")
                }
                sb.append("      </attributes>\n")
                sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>quarter</beat-unit>" +
                    "<per-minute>${score.tempo}</per-minute></metronome></direction-type><sound tempo=\"${score.tempo}\"/></direction>\n")
            }
            // Accidental display state per staff, as the engraver shows it
            val state = HashMap<Pair<Int, Int>, Int>()
            val inBar = written.filter { it.seg.onset >= start && it.seg.onset < end }.sortedBy { it.seg.onset }
            val accOf = HashMap<W, Int>()
            for (w in inBar) {
                val p = score.voice(w.voice)[w.index].pitch ?: continue
                if (w.segIndex > 0) continue
                val staff = score.staffOf(w.voice)
                val cur = state[staff to p.diatonic] ?: score.key.sigAlter(p.step)
                if (p.alter != cur) accOf[w] = p.alter
                state[staff to p.diatonic] = p.alter
            }
            var first = true
            var lastVoiceEnd = start
            for (v in Voice.entries) {
                val notes = inBar.filter { it.voice == v }
                if (notes.isEmpty()) continue
                if (!first) {
                    // back to the bar start (voices may be shorter than the bar)
                    sb.append("      <backup><duration>${dur(lastVoiceEnd - start)}</duration></backup>\n")
                }
                first = false
                var t = start
                for (w in notes) {
                    if (w.seg.onset > t) { // gap in this voice: forward
                        sb.append("      <forward><duration>${dur(w.seg.onset - t)}</duration><voice>${v.ordinal + 1}</voice><staff>${score.staffOf(v) + 1}</staff></forward>\n")
                    }
                    note(sb, score, w, accOf[w], ::dur)
                    t = w.seg.onset + w.seg.length
                }
                lastVoiceEnd = t
            }
            if (b == bars - 1) sb.append("      <barline location=\"right\"><bar-style>light-heavy</bar-style></barline>\n")
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n</score-partwise>\n")
        return sb.toString()
    }

    private fun note(sb: StringBuilder, score: Score, w: W, acc: Int?, dur: (Fraction) -> Long) {
        val e = score.voice(w.voice)[w.index]
        val p = e.pitch
        val tieStart = p != null && !w.last
        val tieStop = p != null && w.segIndex > 0
        sb.append("      <note>")
        if (p == null) sb.append(if (w.seg.fullBar) "<rest measure=\"yes\"/>" else "<rest/>")
        else {
            sb.append("<pitch><step>${STEPS[p.step]}</step>")
            if (p.alter != 0) sb.append("<alter>${p.alter}</alter>")
            sb.append("<octave>${p.octave}</octave></pitch>")
        }
        sb.append("<duration>${dur(w.seg.length)}</duration>")
        if (tieStop) sb.append("<tie type=\"stop\"/>")
        if (tieStart) sb.append("<tie type=\"start\"/>")
        sb.append("<voice>${w.voice.ordinal + 1}</voice>")
        if (!w.seg.fullBar) {
            sb.append("<type>${TYPES.getValue(w.seg.dur.base)}</type>")
            repeat(w.seg.dur.dots) { sb.append("<dot/>") }
        }
        if (acc != null) sb.append("<accidental>${when (acc) { -2 -> "flat-flat"; -1 -> "flat"; 1 -> "sharp"; 2 -> "double-sharp"; else -> "natural" }}</accidental>")
        val stem = score.arrangement.stems()[w.voice.ordinal]
        if (p != null && stem != 0 && w.seg.dur.base > 1) sb.append("<stem>${if (stem > 0) "up" else "down"}</stem>")
        sb.append("<staff>${score.staffOf(w.voice) + 1}</staff>")
        if (tieStart || tieStop) {
            sb.append("<notations>")
            if (tieStop) sb.append("<tied type=\"stop\"/>")
            if (tieStart) sb.append("<tied type=\"start\"/>")
            sb.append("</notations>")
        }
        sb.append("</note>\n")
    }
}
