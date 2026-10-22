package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.export.MusicXml
import com.nefelion.fourpartwriting.model.Edit
import com.nefelion.fourpartwriting.model.Fraction
import com.nefelion.fourpartwriting.model.Pitch
import com.nefelion.fourpartwriting.model.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class MusicXmlTest {
    private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isValidating = false
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(xml.byteInputStream())

    private fun Element.child(name: String) = getElementsByTagName(name).item(0) as Element?

    @Test fun exampleExportsValidMeasures() {
        // Add a note that crosses a barline so ties are exported too
        val score = Edit.append(Edit.append(EXAMPLE, Voice.SOPRANO, Fraction.of(3, 4), Pitch.parse("C4")),
            Voice.SOPRANO, Fraction.of(1, 2), Pitch.parse("D4"))
        val doc = parse(MusicXml.export(score))
        val div = doc.getElementsByTagName("divisions").item(0).textContent.toInt()
        val barLen = 4 * div // 4/4
        val measures = doc.getElementsByTagName("measure")
        assertEquals(5, measures.length)
        for (m in 0 until measures.length) {
            val notes = (measures.item(m) as Element).getElementsByTagName("note")
            val perVoice = HashMap<String, Int>()
            for (i in 0 until notes.length) {
                val n = notes.item(i) as Element
                perVoice.merge(n.child("voice")!!.textContent, n.child("duration")!!.textContent.toInt(), Int::plus)
            }
            for ((v, total) in perVoice) assertTrue("bar ${m + 1} voice $v = $total", total <= barLen)
            if (m < 3) assertEquals("bar ${m + 1}", setOf(barLen), perVoice.values.toSet())
        }
        val first = doc.getElementsByTagName("note").item(0) as Element
        assertEquals("E", first.child("step")!!.textContent)
        assertEquals("4", first.child("octave")!!.textContent)
        assertEquals("quarter", first.child("type")!!.textContent)
        val starts = doc.getElementsByTagName("tie").let { l -> (0 until l.length).count { (l.item(it) as Element).getAttribute("type") == "start" } }
        val stops = doc.getElementsByTagName("tie").let { l -> (0 until l.length).count { (l.item(it) as Element).getAttribute("type") == "stop" } }
        assertEquals(1, starts)
        assertEquals(starts, stops)
    }
}
