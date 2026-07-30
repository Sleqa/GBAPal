package com.gbapal.companion.pokemon

import org.junit.Assert.assertEquals
import org.junit.Test

class Gen3TextTest {

    @Test
    fun decodesNameWithTerminatorPadding() {
        val bytes = byteArrayOf(
            0xCC.toByte(), 0xED.toByte(), 0xD5.toByte(), 0xE2.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertEquals("Ryan", Gen3Text.decode(bytes))
    }

    @Test
    fun decodesDigitsAndUppercase() {
        val bytes = byteArrayOf(0xBB.toByte(), 0xA1.toByte(), 0xFF.toByte())
        assertEquals("A0", Gen3Text.decode(bytes))
    }

    @Test
    fun allZeroBytesDecodeToSpaces() {
        assertEquals("        ", Gen3Text.decode(ByteArray(8)))
    }
}
