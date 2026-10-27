package com.nefelion.fourpartwriting

import com.nefelion.fourpartwriting.model.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ScoreLibraryTest {
    private val lib = ScoreLibrary(Files.createTempDirectory("scores").toFile())

    @Test fun writeReadRenameDelete() {
        assertTrue(lib.write("A", EXAMPLE))
        assertEquals(EXAMPLE, lib.read("A"))
        assertTrue(lib.write("B", Score()))
        assertFalse(lib.rename("A", "B")) // never overwrites another score
        assertTrue(lib.rename("A", "C"))
        assertNull(lib.read("A"))
        assertEquals(setOf("B", "C"), lib.names().toSet())
        assertTrue(lib.delete("C"))
        assertEquals(listOf("B"), lib.names())
    }

    @Test fun freeNames() {
        assertEquals("Untitled", lib.freeName("  "))
        assertEquals("ab", lib.freeName("a/b"))
        assertEquals("x", lib.freeName("..x"))
        lib.write("Song", Score())
        lib.write("Song (2)", Score())
        assertEquals("Song (3)", lib.freeName("Song"))
    }

    @Test fun titleTravelsInFile() {
        val text = com.nefelion.fourpartwriting.model.ScoreCodec.encode(EXAMPLE, "Chorale in G")
        assertEquals("Chorale in G", com.nefelion.fourpartwriting.model.ScoreCodec.title(text))
        assertEquals(EXAMPLE, com.nefelion.fourpartwriting.model.ScoreCodec.decode(text))
        assertNull(com.nefelion.fourpartwriting.model.ScoreCodec.title(com.nefelion.fourpartwriting.model.ScoreCodec.encode(EXAMPLE)))
    }

    @Test fun badFileReadsAsNull() {
        lib.write("ok", Score())
        assertNull(lib.read("missing"))
    }
}
