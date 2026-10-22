package com.grothgarvc.fourpartwriting

import com.grothgarvc.fourpartwriting.engrave.BeamMark
import com.grothgarvc.fourpartwriting.engrave.chordLabels
import com.grothgarvc.fourpartwriting.engrave.Engraver
import com.grothgarvc.fourpartwriting.engrave.GlyphMark
import com.grothgarvc.fourpartwriting.engrave.LineMark
import com.grothgarvc.fourpartwriting.engrave.TextMark
import com.grothgarvc.fourpartwriting.engrave.TieMark
import com.grothgarvc.fourpartwriting.model.Arrangement
import com.grothgarvc.fourpartwriting.model.Clef
import com.grothgarvc.fourpartwriting.model.Duration
import com.grothgarvc.fourpartwriting.model.Event
import com.grothgarvc.fourpartwriting.model.Pitch
import com.grothgarvc.fourpartwriting.model.Key
import com.grothgarvc.fourpartwriting.model.Mode
import com.grothgarvc.fourpartwriting.model.Score
import com.grothgarvc.fourpartwriting.model.TimeSig
import com.grothgarvc.fourpartwriting.theory.VoiceLeading
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Dev-only visual check: renders layouts to PNG with the real Bravura font.
 * Runs only with -Dpreview.dir=<folder> (set via the PREVIEW_DIR env var below).
 */
class RenderPreview {
    private val out = System.getenv("PREVIEW_DIR")

    private fun line(s: String) = s.split(" ").map { tok ->
        val (p, d) = tok.split(":")
        Event(Duration(d.trimEnd('.').toInt(), d.count { it == '.' }), if (p == "r") null else Pitch.parse(p))
    }

    private fun render(score: Score, name: String) {
        val a = VoiceLeading.check(score)
        val l = Engraver.layout(score, chordLabels(a), a.ncts.mapValues { it.value.label })
        val sp = 16f
        val img = BufferedImage((l.width * sp).toInt(), (l.height * sp).toInt() + 10, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0x161616)
        val bravura = Font.createFont(Font.TRUETYPE_FONT, File("src/main/res/font/bravura.otf"))
        for (m in l.marks) when (m) {
            is LineMark -> { g.stroke = BasicStroke(m.width * sp, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER); g.draw(Line2D.Float(m.x1 * sp, m.y1 * sp, m.x2 * sp, m.y2 * sp)) }
            is GlyphMark -> { g.font = bravura.deriveFont(4f * sp * m.scale); g.drawString(m.c.toString(), m.x * sp, m.y * sp) }
            is BeamMark -> g.fill(Path2D.Float().apply {
                moveTo(m.x1 * sp, m.y1 * sp); lineTo(m.x2 * sp, m.y2 * sp); lineTo(m.x2 * sp, (m.y2 + m.h) * sp); lineTo(m.x1 * sp, (m.y1 + m.h) * sp); closePath()
            })
            is TieMark -> g.fill(Path2D.Float().apply {
                val w = m.x2 - m.x1; val s = if (m.above) -1f else 1f; val hh = minOf(0.25f + 0.06f * w, 0.9f)
                moveTo(m.x1 * sp, m.y * sp)
                curveTo((m.x1 + w * 0.25f) * sp, (m.y + s * hh * 1.33f) * sp, (m.x2 - w * 0.25f) * sp, (m.y + s * hh * 1.33f) * sp, m.x2 * sp, m.y * sp)
                curveTo((m.x2 - w * 0.25f) * sp, (m.y + s * (hh - 0.22f) * 1.33f) * sp, (m.x1 + w * 0.25f) * sp, (m.y + s * (hh - 0.22f) * 1.33f) * sp, m.x1 * sp, m.y * sp)
                closePath()
            })
            is TextMark -> {
                g.font = Font(Font.SERIF, Font.PLAIN, (m.size * sp).toInt())
                val w = g.fontMetrics.stringWidth(m.text)
                val x = if (m.style == com.grothgarvc.fourpartwriting.engrave.TextStyle.ROMAN || m.style == com.grothgarvc.fourpartwriting.engrave.TextStyle.SYMBOL) m.x * sp - w / 2 else m.x * sp
                g.drawString(m.text, x, m.y * sp)
            }
        }
        ImageIO.write(img, "png", File(out, "$name.png"))
    }

    @Test fun preview() {
        assumeTrue(out != null)
        render(EXAMPLE, "example")
        val tricky = Score(
            key = Key(-3, Mode.MAJOR), time = TimeSig(6, 8), tempo = 66,
            voices = listOf(
                line("G5:8 F5:8 Eb5:8 D5:8. C5:16 Bb4:8 Ab4:4 G4:8 r:4. r:2."),
                line("Eb5:8 D5:8 C5:8 F4:4. B4:4 C5:8 r:4. r:2."),
                line("Bb3:4. Ab3:4. F#3:4 G3:8 r:4. r:2."),
                line("Eb3:4. Bb2:4. D3:4 C3:8 r:4. C3:2."),
            ),
        )
        render(tricky, "tricky")
        render(Score(key = Key(5, Mode.MINOR), clefs = listOf(Clef.ALTO, Clef.TENOR), voices = EXAMPLE.voices), "clefs")
        for (a in Arrangement.entries) render(EXAMPLE.withArrangement(a), "arr_${a.label.replace('/', '_')}")
        // Ties and metric splitting: 4/4 syncopation, notes over barlines, dotted rhythms
        render(Score(voices = listOf(
            line("C5:4 D5:2 C5:4 B4:8 C5:4 D5:8 E5:2 G5:2. F5:2"),
            line("A4:2. G4:2 F4:2. E4:2 r:2"),
            line("E4:8 F4:8 G4:2 A4:4 G4:1 r:2 C4:2 r:4"),
            line("A3:1 E3:1 C3:2. G2:4 A2:2"),
        )), "ties")
        // Polyphonic: passing tones, 4-3 suspension, neighbour, anticipation
        render(Score(voices = listOf(
            line("E5:8 D5:8 C5:4 C5:4. B4:8 C5:8 D5:8 C5:2."),
            line("G4:4 A4:4 G4:4 G4:4 G4:8 F4:8 E4:2"),
            line("C4:4 C4:4 E4:4 D4:4 C4:2 G3:4"),
            line("C3:8 D3:8 E3:4 C3:4 G2:4 A2:4 F2:4 C3:2"),
        )), "poly")
    }
}
