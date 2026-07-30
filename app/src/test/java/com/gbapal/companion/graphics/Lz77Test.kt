package com.gbapal.companion.graphics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Lz77Test {

    @Test
    fun decompressesLiteralFollowedByBackReference() {
        // Decompressed target: "AAAAAAAA" (8 bytes).
        // Header: type=0x10, size=8 (LE 24-bit).
        // Block 1 (literal): 'A' (0x41).
        // Block 2 (back-ref): length=7 (nibble 4+3), disp=0 -> repeats the
        // last written byte, cascading to fill the remaining 7 bytes.
        // Flags byte 0x40 = 0b01000000: bit7=0 (literal), bit6=1 (back-ref).
        val compressed = byteArrayOf(
            0x10, 0x08, 0x00, 0x00,
            0x40,
            0x41,
            0x40, 0x00,
        )
        val result = Lz77.decompress(compressed, 0)
        assertArrayEquals("AAAAAAAA".toByteArray(), result)
    }

    @Test
    fun decompressesAllLiterals() {
        // "AB" with no compression: flags=0x00 (both literal).
        val compressed = byteArrayOf(0x10, 0x02, 0x00, 0x00, 0x00, 0x41, 0x42)
        val result = Lz77.decompress(compressed, 0)
        assertArrayEquals("AB".toByteArray(), result)
    }

    @Test
    fun rejectsWrongTypeByte() {
        val notLz77 = byteArrayOf(0x11, 0x02, 0x00, 0x00, 0x00, 0x41, 0x42)
        assertNull(Lz77.decompress(notLz77, 0))
    }

    @Test
    fun rejectsTruncatedInput() {
        // Declares 8 bytes of output but the input stream cuts off early.
        val truncated = byteArrayOf(0x10, 0x08, 0x00, 0x00, 0x00, 0x41)
        assertNull(Lz77.decompress(truncated, 0))
    }
}
