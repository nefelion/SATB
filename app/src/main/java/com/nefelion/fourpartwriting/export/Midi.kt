package com.nefelion.fourpartwriting.export

import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Score
import com.nefelion.fourpartwriting.model.Voice
import java.io.ByteArrayOutputStream

/** Standard MIDI file (type 1): a tempo track, then one track per voice S A T B on its own channel. */
object Midi {
    private const val PPQ = 480

    fun export(score: Score): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); int32(out, 6); int16(out, 1); int16(out, 1 + Voice.entries.size); int16(out, PPQ)
        val usPerQuarter = (60_000_000 / score.qpm).toLong()
        track(out, listOf(0L to byteArrayOf(0xFF.toByte(), 0x51, 3, (usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte())))
        for (v in Voice.entries) {
            val ch = v.ordinal
            val evs = ArrayList<Pair<Long, ByteArray>>()
            val name = v.name.lowercase().replaceFirstChar { it.uppercase() }.toByteArray()
            evs += 0L to (byteArrayOf(0xFF.toByte(), 0x03, name.size.toByte()) + name)
            var t = Fraction.ZERO
            for (e in score.voice(v)) {
                val end = t + e.length
                e.pitch?.let { p ->
                    // Note-offs sort before note-ons at the same tick, so repeated notes restart cleanly
                    evs += ticks(t) to byteArrayOf((0x90 or ch).toByte(), p.midi.toByte(), 90)
                    evs += ticks(end) to byteArrayOf((0x80 or ch).toByte(), p.midi.toByte(), 0)
                }
                t = end
            }
            track(out, evs.sortedWith(compareBy({ it.first }, { if (it.second[0].toInt() and 0xF0 == 0x80) 0 else 1 })))
        }
        return out.toByteArray()
    }

    /** Whole notes to ticks. */
    private fun ticks(t: Fraction) = Math.round(t.toDouble() * 4 * PPQ)

    private fun track(out: ByteArrayOutputStream, events: List<Pair<Long, ByteArray>>) {
        val body = ByteArrayOutputStream()
        var last = 0L
        for ((at, data) in events) { varLen(body, at - last); body.write(data); last = at }
        varLen(body, 0); body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
        out.write("MTrk".toByteArray()); int32(out, body.size()); body.writeTo(out)
    }

    private fun varLen(out: ByteArrayOutputStream, v: Long) {
        var buf = v and 0x7F
        var x = v shr 7
        while (x > 0) { buf = (buf shl 8) or 0x80 or (x and 0x7F); x = x shr 7 }
        while (true) { out.write((buf and 0xFF).toInt()); if (buf and 0x80 != 0L) buf = buf shr 8 else break }
    }

    private fun int32(out: ByteArrayOutputStream, v: Int) { for (s in intArrayOf(24, 16, 8, 0)) out.write(v shr s and 0xFF) }
    private fun int16(out: ByteArrayOutputStream, v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
}
