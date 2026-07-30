package com.gbapal.companion.graphics

/**
 * GBA BIOS-compatible LZ77 (LZSS variant) decompression, as used for
 * compressed ROM assets like sprite tile data. This is a fixed,
 * hardware-documented format (GBATEK's LZ77UnCompCore) identical across
 * every GBA game or hack -- what varies per ROM is only where compressed
 * data lives, never this format itself.
 */
object Lz77 {
    /**
     * Decompresses an LZ77 block starting at [offset] in [data]. Expects the
     * standard 4-byte header: a type byte (must be 0x10) followed by a
     * 24-bit little-endian decompressed size. Returns null if the type byte
     * doesn't match, or if the input runs out before the declared size is
     * reached (e.g. [offset] didn't actually point at compressed data).
     */
    fun decompress(data: ByteArray, offset: Int): ByteArray? {
        if (offset < 0 || offset + 4 > data.size) return null
        val type = data[offset].toInt() and 0xFF
        if (type != 0x10) return null
        val size = (data[offset + 1].toInt() and 0xFF) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            ((data[offset + 3].toInt() and 0xFF) shl 16)

        val out = ByteArray(size)
        var outPos = 0
        var inPos = offset + 4

        while (outPos < size) {
            if (inPos >= data.size) return null
            val flags = data[inPos].toInt() and 0xFF
            inPos++

            for (bit in 7 downTo 0) {
                if (outPos >= size) break
                if (inPos >= data.size) return null

                if ((flags shr bit) and 1 == 0) {
                    // Literal block: one byte copied as-is.
                    out[outPos] = data[inPos]
                    outPos++
                    inPos++
                } else {
                    // Back-reference block: copy (length) bytes from
                    // (outPos - disp - 1). disp/length can overlap the bytes
                    // being written (e.g. disp=0 repeats the last byte), so
                    // this must copy one byte at a time, not via array copy.
                    if (inPos + 1 >= data.size) return null
                    val b1 = data[inPos].toInt() and 0xFF
                    val b2 = data[inPos + 1].toInt() and 0xFF
                    inPos += 2
                    val length = (b1 shr 4) + 3
                    val disp = ((b1 and 0x0F) shl 8) or b2
                    val copySource = outPos - disp - 1
                    if (copySource < 0) return null
                    for (i in 0 until length) {
                        if (outPos >= size) break
                        out[outPos] = out[copySource + i]
                        outPos++
                    }
                }
            }
        }
        return out
    }
}
