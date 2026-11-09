package com.nefelion.fourpartwriting.export

import com.nefelion.fourpartwriting.engrave.Notation
import com.nefelion.fourpartwriting.engrave.Seg
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Mode
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.TimeSig
import com.nefelion.fourpartwriting.model.Voice
import com.nefelion.fourpartwriting.model.Arrangement
import com.nefelion.fourpartwriting.model.Change
import com.nefelion.fourpartwriting.model.Event
import com.nefelion.fourpartwriting.model.Key
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.PitchClass
import com.nefelion.fourpartwriting.model.Tuplet
import com.nefelion.fourpartwriting.theory.FiguredBass
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.max

/** MusicXML 4.0 partwise export: one part, one staff per arrangement staff, voices 1–4. */
object MusicXml {
    private val TYPES = mapOf(1 to "whole", 2 to "half", 4 to "quarter", 8 to "eighth", 16 to "16th", 32 to "32nd", 64 to "64th")
    private val STEPS = "CDEFGAB"

    private class W(val voice: Voice, val index: Int, val seg: Seg, val segIndex: Int, val last: Boolean)

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    fun export(score: Score): String {
        val written = Voice.entries.flatMap { v ->
            Notation.splitVoice(score.voice(v), score).flatMapIndexed { i, segs ->
                segs.mapIndexed { k, s -> W(v, i, s, k, k == segs.lastIndex) }
            }
        }
        // divisions per quarter: every segment length must be a whole number of divisions
        var div = 1L
        for (w in written) {
            val q = w.seg.length * Fraction.of(4, 1)
            div = div / gcd(div, q.den) * q.den
        }
        val barList = score.bars(score.length)
        for (b in barList) div = max(div, ((b.end - b.start) * Fraction.of(4, 1)).den)
        fun dur(f: Fraction) = (f * Fraction.of(4 * div, 1)).let { it.num / it.den }

        val bars = barList.size
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN" "http://www.musicxml.org/dtds/partwise.dtd">
<score-partwise version="4.0">
  <identification><encoding><software>SATB</software></encoding></identification>
  <part-list><score-part id="P1"><part-name>SATB</part-name></score-part></part-list>
  <part id="P1">
""")
        for (b in 0 until bars) {
            val bar = barList[b]
            val start = bar.start
            val end = bar.end
            val key = score.keyOfBar(b)
            // A pickup is measure 0, marked implicit (not counted)
            sb.append("    <measure number=\"${score.barNumber(start)}\"" + (if (b == 0 && score.pickup != null) " implicit=\"yes\"" else "") + ">\n")
            val change = score.changes[b]
            if (b > 0 && change != null) {
                sb.append("      <attributes>\n")
                change.key?.let { k -> sb.append("        <key><fifths>${k.fifths}</fifths><mode>${if (k.mode == Mode.MAJOR) "major" else "minor"}</mode></key>\n") }
                change.time?.let { t ->
                    val sym = when (t.symbol) { TimeSig.Symbol.COMMON -> " symbol=\"common\""; TimeSig.Symbol.CUT -> " symbol=\"cut\""; else -> "" }
                    sb.append("        <time$sym><beats>${t.beats}</beats><beat-type>${t.beatUnit}</beat-type></time>\n")
                }
                sb.append("      </attributes>\n")
            }
            if (b == 0) {
                sb.append("      <attributes>\n        <divisions>$div</divisions>\n")
                sb.append("        <key><fifths>${score.key.fifths}</fifths><mode>${if (score.key.mode == Mode.MAJOR) "major" else "minor"}</mode></key>\n")
                val sym = when (score.time.symbol) { TimeSig.Symbol.COMMON -> " symbol=\"common\""; TimeSig.Symbol.CUT -> " symbol=\"cut\""; else -> "" }
                if (score.time.isFree) sb.append("        <time><senza-misura/></time>\n")
                else sb.append("        <time$sym><beats>${score.time.beats}</beats><beat-type>${score.time.beatUnit}</beat-type></time>\n")
                sb.append("        <staves>${score.arrangement.staves}</staves>\n")
                for ((i, c) in score.clefs.withIndex()) {
                    sb.append("        <clef number=\"${i + 1}\"><sign>${c.sign}</sign><line>${c.line}</line>")
                    if (c.octaveShift != 0) sb.append("<clef-octave-change>${c.octaveShift}</clef-octave-change>")
                    sb.append("</clef>\n")
                }
                sb.append("      </attributes>\n")
                val t = score.time
                val unit = when (if (t.isCompound) t.beatUnit / 2 else t.beatUnit) {
                    1 -> "whole"; 2 -> "half"; 4 -> "quarter"; 8 -> "eighth"; 16 -> "16th"; 32 -> "32nd"; else -> "64th"
                }
                val qpm = "%.4f".format(java.util.Locale.ROOT, score.qpm).trimEnd('0').trimEnd('.')
                // <sound tempo> is always quarter notes per minute
                sb.append("      <direction placement=\"above\"><direction-type><metronome><beat-unit>$unit</beat-unit>" +
                    (if (t.isCompound) "<beat-unit-dot/>" else "") +
                    "<per-minute>${score.tempo}</per-minute></metronome></direction-type><sound tempo=\"$qpm\"/></direction>\n")
            }
            // Accidental display state per staff, as the engraver shows it
            val state = HashMap<Pair<Int, Int>, Int>()
            val inBar = written.filter { it.seg.onset >= start && it.seg.onset < end }.sortedBy { it.seg.onset }
            val accOf = HashMap<W, Int>()
            for (w in inBar) {
                val p = score.voice(w.voice)[w.index].pitch ?: continue
                if (w.segIndex > 0) continue
                val staff = score.staffOf(w.voice)
                val cur = state[staff to p.diatonic] ?: key.sigAlter(p.step)
                // Free meter has no bar to carry an accidental: every one off the key is shown
                if (p.alter != cur || score.time.isFree && p.alter != key.sigAlter(p.step)) accOf[w] = p.alter
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
                // Neighboring values of one tuplet group
                fun joined(a: W?, b: W?) = a != null && b != null && a.seg.tuplet && a.seg.group == b.seg.group &&
                    a.seg.onset + a.seg.length == b.seg.onset
                for ((j, w) in notes.withIndex()) {
                    if (w.seg.onset > t) { // gap in this voice: forward
                        sb.append("      <forward><duration>${dur(w.seg.onset - t)}</duration><voice>${v.ordinal + 1}</voice><staff>${score.staffOf(v) + 1}</staff></forward>\n")
                    }
                    val tup = if (!w.seg.tuplet) null else
                        (if (!joined(notes.getOrNull(j - 1), w)) "start" else "") + (if (!joined(w, notes.getOrNull(j + 1))) " stop" else "")
                    note(sb, score, w, accOf[w], ::dur, tup)
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

    /** [tup]: null for a plain value; else which ends of a tuplet run this note is ("start", " stop", both or none). */
    private fun note(sb: StringBuilder, score: Score, w: W, acc: Int?, dur: (Fraction) -> Long, tup: String? = null) {
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
        w.seg.group?.let { g -> sb.append("<time-modification><actual-notes>${g.n}</actual-notes><normal-notes>${g.m}</normal-notes></time-modification>") }
        val stem = score.arrangement.stems()[w.voice.ordinal]
        if (p != null && stem != 0 && w.seg.dur.base > 1) sb.append("<stem>${if (stem > 0) "up" else "down"}</stem>")
        sb.append("<staff>${score.staffOf(w.voice) + 1}</staff>")
        val tupStart = tup?.contains("start") == true
        val tupStop = tup?.contains("stop") == true
        val fermata = p != null && w.last && e.fermata
        if (tieStart || tieStop || tupStart || tupStop || fermata) {
            sb.append("<notations>")
            // Below the lower voice of a shared staff, like the editor
            if (fermata) sb.append(if (stem < 0) "<fermata type=\"inverted\"/>" else "<fermata type=\"upright\"/>")
            if (tieStop) sb.append("<tied type=\"stop\"/>")
            if (tieStart) sb.append("<tied type=\"start\"/>")
            if (tupStart) sb.append(if (w.seg.group!!.label.contains(':')) "<tuplet type=\"start\" show-number=\"both\"/>" else "<tuplet type=\"start\"/>")
            if (tupStop) sb.append("<tuplet type=\"stop\"/>")
            sb.append("</notations>")
        }
        sb.append("</note>\n")
    }

    // ---------- import ----------

    /** A read file: the score, its title if the file has one, and a note for the user about what was left out. */
    class Imported(val score: Score, val title: String?, val note: String?)

    /** One note as written, before voices are assigned: [part], [staff] and [voice] say which line it is on. */
    private data class N(
        val part: Int, val staff: Int, val voice: Int, val onset: Fraction, val length: Fraction, val pitch: Pitch,
        val tieStart: Boolean, val tieStop: Boolean, val fermata: Boolean, val tuplet: Tuplet?,
    )

    /** Figures at [at] under note [pc], so they only go onto a bass note of the same name. */
    private data class F(val at: Fraction, val pc: PitchClass, val lines: List<String>)

    private fun Element.kids(name: String) = childNodes.let { l -> (0 until l.length).map { l.item(it) }.filterIsInstance<Element>().filter { it.tagName == name } }
    private fun Element.kid(name: String) = kids(name).firstOrNull()
    private fun Element.text(name: String) = kid(name)?.textContent?.trim()

    /**
     * Reads a MusicXML file (.musicxml/.xml, or compressed .mxl) into four voices. Parts, staves and voices are taken
     * top to bottom: the first four lines become S, A, T, B (one line is a soprano, two are soprano and bass). With fewer
     * than four lines, chords are split into their notes, top one first. Null when the file is not partwise MusicXML
     * or holds no notes.
     */
    fun read(bytes: ByteArray): Imported? = runCatching { readXml(unzip(bytes) ?: bytes) }.getOrNull()

    /** The score inside a compressed .mxl (a zip), or null when [bytes] is not a zip. */
    private fun unzip(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return null
        val entries = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            var total = 0L
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val data = z.readBytes()
                total += data.size
                require(total < 50_000_000) // a zip bomb is not a score
                entries[e.name] = data
            }
        }
        val root = entries["META-INF/container.xml"]?.let { parse(it).getElementsByTagName("rootfile").item(0) as Element? }?.getAttribute("full-path")
        return entries[root] ?: entries.entries.firstOrNull { (n, _) -> !n.startsWith("META-INF") && (n.endsWith(".xml") || n.endsWith(".musicxml")) }?.value
    }

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isValidating = false
        isNamespaceAware = false
        // No DTD downloads or external entities (not every platform parser knows every feature)
        for (f in listOf("http://apache.org/xml/features/nonvalidating/load-external-dtd", "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities")) runCatching { setFeature(f, false) }
    }.newDocumentBuilder().parse(bytes.inputStream()).documentElement

    private fun readXml(bytes: ByteArray): Imported? {
        val root = parse(bytes)
        if (root.tagName != "score-partwise") return null
        val notes = ArrayList<N>()
        val figs = ArrayList<F>()
        // Per measure: length (longest part), whether it is marked as not counted, and key / time set in it
        val lengths = ArrayList<Fraction>()
        val implicit = ArrayList<Boolean>()
        val keys = HashMap<Int, Int>()
        val modes = HashMap<Int, Mode>()
        val times = HashMap<Int, TimeSig>()
        var qpm: Double? = null
        // Times are first kept as (measure, offset in it): parts are put on common bar lines once all are read
        val at = ArrayList<Int>()
        val figAt = ArrayList<Int>()
        for ((pi, part) in root.kids("part").withIndex()) {
            var div = 1L
            for ((mi, m) in part.kids("measure").withIndex()) {
                if (mi >= lengths.size) { lengths += Fraction.ZERO; implicit += m.getAttribute("implicit") == "yes" }
                var pos = Fraction.ZERO
                var last = Fraction.ZERO
                var end = Fraction.ZERO
                // Figures before a note belong to it; several over one note each say how long they last
                val pendingFigs = ArrayList<Pair<Fraction, List<String>>>()
                var figOff = Fraction.ZERO
                fun len(e: Element) = Fraction.of(e.text("duration")?.toLongOrNull() ?: 0L, 4 * div)
                for (e in m.childNodes.let { l -> (0 until l.length).map { l.item(it) }.filterIsInstance<Element>() }) {
                    when (e.tagName) {
                        "attributes" -> {
                            e.text("divisions")?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }?.let { div = it }
                            e.kid("key")?.let { k ->
                                k.text("fifths")?.toIntOrNull()?.let { keys.putIfAbsent(mi, it.coerceIn(-7, 7)) }
                                when (k.text("mode")) { "major" -> modes.putIfAbsent(mi, Mode.MAJOR); "minor" -> modes.putIfAbsent(mi, Mode.MINOR) }
                            }
                            e.kid("time")?.let { t ->
                                val beats = t.text("beats")?.toIntOrNull()
                                val unit = t.text("beat-type")?.toIntOrNull()
                                if (beats != null && unit != null && beats > 0 && unit > 0) times.putIfAbsent(mi, TimeSig(beats, unit, when (t.getAttribute("symbol")) {
                                    "common" -> TimeSig.Symbol.COMMON; "cut" -> TimeSig.Symbol.CUT; else -> TimeSig.Symbol.NUMERIC }))
                                else if (t.kid("senza-misura") != null) times.putIfAbsent(mi, TimeSig(4, 4, TimeSig.Symbol.FREE))
                            }
                        }
                        "backup" -> pos = maxOf(Fraction.ZERO, pos - len(e))
                        "forward" -> pos += len(e)
                        "direction", "sound" -> if (qpm == null) (if (e.tagName == "sound") e else e.kid("sound"))?.getAttribute("tempo")?.toDoubleOrNull()?.let { qpm = it }
                        "figured-bass" -> {
                            val lines = e.kids("figure").mapNotNull(::figure)
                            if (lines.isNotEmpty()) pendingFigs += figOff to lines
                            figOff += len(e)
                        }
                        "note" -> {
                            val chord = e.kid("chord") != null
                            if (e.kid("grace") != null) continue
                            val onset = if (chord) last else pos
                            val d = len(e)
                            if (!chord) { last = pos; pos += d }
                            end = maxOf(end, pos)
                            val p = e.kid("pitch")
                            val figsHere = pendingFigs.toList()
                            pendingFigs.clear(); figOff = Fraction.ZERO
                            if (p == null || e.kid("cue") != null || d <= Fraction.ZERO) continue
                            val step = "CDEFGAB".indexOf(p.text("step") ?: "")
                            val octave = p.text("octave")?.toIntOrNull() ?: continue
                            if (step < 0) continue
                            val pitch = Pitch(step, (p.text("alter")?.toDoubleOrNull() ?: 0.0).toInt().coerceIn(-2, 2), octave)
                            val ties = e.kids("tie").map { it.getAttribute("type") } + e.kids("notations").flatMap { it.kids("tied") }.map { it.getAttribute("type") }
                            val tup = e.kid("time-modification")?.let { tm ->
                                val n = tm.text("actual-notes")?.toIntOrNull(); val mm = tm.text("normal-notes")?.toIntOrNull()
                                val base = TYPES.entries.firstOrNull { it.value == (tm.text("normal-type") ?: e.text("type")) }?.key
                                if (n != null && mm != null && base != null && n > 0 && mm > 0 && n != mm) Tuplet(n, mm, Fraction.of(1, base.toLong())) else null
                            }
                            notes += N(pi, e.text("staff")?.toIntOrNull() ?: 1, e.text("voice")?.toIntOrNull() ?: 1, onset, d, pitch,
                                "start" in ties, "stop" in ties, e.kids("notations").any { it.kid("fermata") != null }, tup)
                            at += mi
                            for ((o, l) in figsHere) { figs += F(onset + o, pitch.pc, l); figAt += mi }
                        }
                    }
                    end = maxOf(end, pos)
                }
                lengths[mi] = maxOf(lengths[mi], end)
            }
        }
        if (notes.isEmpty()) return null
        // Each measure starts where the longest part's previous measure ended
        val starts = lengths.runningFold(Fraction.ZERO) { a, l -> a + l }
        return build(notes.mapIndexed { i, n -> n.copy(onset = starts[at[i]] + n.onset) }, figs.mapIndexed { i, f -> f.copy(at = starts[figAt[i]] + f.at) },
            lengths, implicit, keys, modes, times, qpm, root)
    }

    /** One <figure> as a figure line ("6", "#6", "b", "n7"); null for an extension line or anything not a figure. */
    private fun figure(f: Element): String? {
        fun acc(s: String?) = when (s) { "sharp", "plus", "backslash", "slash" -> "#"; "flat" -> "b"; "natural" -> "n"; else -> "" }
        val num = f.text("figure-number")?.filter { it.isDigit() }.orEmpty()
        val line = (acc(f.text("prefix")).ifEmpty { acc(f.text("suffix")) }) + num
        return line.takeIf { FiguredBass.valid(it) }
    }

    private fun build(
        notes: List<N>, figs: List<F>, rawLengths: List<Fraction>, implicit: List<Boolean>,
        keys: Map<Int, Int>, modes: Map<Int, Mode>, times: Map<Int, TimeSig>, qpm: Double?, root: Element,
    ): Imported {
        // Lines top to bottom: part, then staff, then voice number
        val lines = notes.groupBy { Triple(it.part, it.staff, it.voice) }.toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).values.toList()
        var note: String? = null
        // Too few lines: split chords into their notes, top first (a shorter chord repeats its lowest note)
        val split = if (lines.size >= 4) lines.map { l -> l.groupBy { it.onset }.values.map { c -> listOf(c.maxBy { it.pitch.midi }) } }
        else lines.flatMap { l ->
            val chords = l.groupBy { it.onset }.values.map { c -> c.sortedByDescending { it.pitch.midi } }
            val k = chords.maxOf { it.size }
            (0 until k).map { r -> chords.map { c -> listOf(c[minOf(r, c.lastIndex)]) } }
        }
        if (split.size > 4) note = "Only the top four voices were read; ${split.size - 4} more ${if (split.size == 5) "was" else "were"} left out."
        val chosen = split.take(4).map { it.flatten() }
        val voiceOf = when (chosen.size) { 1 -> listOf(0); 2 -> listOf(0, 3); 3 -> listOf(0, 1, 3); else -> listOf(0, 1, 2, 3) }

        // Measures: a short measure followed by an uncounted one (a repeat sign mid-bar) are one bar
        val lengths = ArrayList<Fraction>(); val firstOf = ArrayList<Int>()
        var mi = 0
        while (mi < rawLengths.size) {
            val ts = times.entries.filter { it.key <= mi }.maxByOrNull { it.key }?.value ?: TimeSig(4, 4)
            if (mi > 0 && mi + 1 < rawLengths.size && implicit[mi + 1] && rawLengths[mi] + rawLengths[mi + 1] == ts.measure && rawLengths[mi] < ts.measure) {
                lengths += ts.measure; firstOf += mi; mi += 2
            } else { lengths += rawLengths[mi]; firstOf += mi; mi++ }
        }

        val time0 = times[0] ?: TimeSig(4, 4)
        val fifths0 = keys[0] ?: 0
        val pickup = lengths.firstOrNull()?.takeIf { !time0.isFree && it > Fraction.ZERO && it < time0.measure && lengths.size > 1 }
        // Bars of an odd length get their own time signature, and the old one comes back after them
        val changes = HashMap<Int, Change>()
        var cur = time0
        // Free meter has no bars, so nothing can change at one
        for (b in 1 until if (time0.isFree) 1 else lengths.size) {
            val m = firstOf[b]

            val set = times[m]
            if (set != null && set != cur) { cur = set; changes[b] = Change(time = set) }
            val len = lengths[b]
            val last = b == lengths.lastIndex
            if (len != cur.measure && len > Fraction.ZERO && !last) {
                val odd = timeFor(len, cur)
                changes[b] = (changes[b] ?: Change()).copy(time = odd)
                if (times[firstOf[b + 1]] == null) changes[b + 1] = Change(time = cur)
            }
            keys[m]?.takeIf { m > 0 }?.let { f -> changes[b] = (changes[b] ?: Change()).copy(key = Key(f, modes[m] ?: Mode.MAJOR)) }
        }

        // Voices: ties joined, gaps as rests, notes overlapping the previous one dropped
        val voices = MutableList(4) { emptyList<Event>() }
        var dropped = 0
        for ((i, line) in chosen.withIndex()) {
            val evs = ArrayList<Event>(); var t = Fraction.ZERO
            var lastPitch: Pitch? = null
            for (n in line.sortedBy { it.onset }) {
                if (n.onset < t) { dropped++; continue }
                if (n.tieStop && n.onset == t && lastPitch == n.pitch && evs.isNotEmpty()) {
                    val p = evs.removeAt(evs.lastIndex)
                    evs += p.copy(length = p.length + n.length, fermata = p.fermata || n.fermata, tuplet = p.tuplet.takeIf { it == n.tuplet })
                } else {
                    if (n.onset > t) evs += Event(n.onset - t, null)
                    evs += Event(n.length, n.pitch, n.tuplet, fermata = n.fermata)
                }
                t = n.onset + n.length
                lastPitch = n.pitch
            }
            voices[voiceOf[i]] = evs
        }
        if (dropped > 0) note = listOfNotNull(note, "$dropped overlapping note${if (dropped == 1) " was" else "s were"} left out.").joinToString(" ")

        // Figures go onto bass notes that start with them and have the same name as the note they were written under
        if (chosen.size > 1) {
            val bass = voices[3]
            var t = Fraction.ZERO
            val onsets = bass.map { e -> t.also { t += e.length } }
            val withFigs = bass.mapIndexed { i, e ->
                val f = figs.firstOrNull { it.at == onsets[i] && e.pitch != null && it.pc.semis == e.pitch.pc.semis }
                if (f != null) e.copy(figures = f.lines) else e
            }
            voices[3] = withFigs
        }

        val mode0 = modes[0] ?: guessMode(fifths0, voices)
        val staffKeys = chosen.map { it.first().part to it.first().staff }
        val distinct = staffKeys.distinct()
        val staffOf = if (chosen.size == 4) staffKeys.map { distinct.indexOf(it) } else null
        val arr = staffOf?.let { runCatching { Arrangement(it) }.getOrNull() } ?: Arrangement.CLOSED
        val title = root.kid("work")?.text("work-title")?.ifEmpty { null } ?: root.text("movement-title")?.ifEmpty { null }
        val sc = Score(
            key = Key(fifths0, mode0), time = time0,
            tempo = qpm?.let { Math.round(it / (time0.beatLength.toDouble() * 4)).toInt() }?.coerceIn(20, 300) ?: 80,
            arrangement = arr, voices = voices, pickup = pickup, changes = changes,
        )
        return Imported(sc, title, note)
    }

    /** A time signature [len] long, in the beat unit of [like] when it fits ("3/4" for a short bar of 4/4). */
    private fun timeFor(len: Fraction, like: TimeSig): TimeSig {
        val beats = len * Fraction.of(like.beatUnit.toLong(), 1)
        return if (beats.den == 1L) TimeSig(beats.num.toInt(), like.beatUnit) else TimeSig(len.num.toInt(), len.den.toInt())
    }

    /**
     * Minor when the bass ends on the minor tonic of the signature or its dominant (a Phrygian close), or starts on the
     * minor tonic and does not end on the major one.
     */
    private fun guessMode(fifths: Int, voices: List<List<Event>>): Mode {
        val bass = voices.lastOrNull { v -> v.any { it.pitch != null } } ?: return Mode.MAJOR
        val first = bass.firstOrNull { it.pitch != null }?.pitch?.pc?.semis
        val last = bass.lastOrNull { it.pitch != null }?.pitch?.pc?.semis
        val major = Key(fifths, Mode.MAJOR).tonic.semis
        val minor = Key(fifths, Mode.MINOR).tonic.semis
        return if (last == minor || last == (minor + 7) % 12 || (last != major && first == minor)) Mode.MINOR else Mode.MAJOR
    }
}
