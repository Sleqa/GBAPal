package com.gbapal.companion.graphics

/**
 * Decodes GBA graphics primitives: 4bpp (16-color) indexed tile data and
 * 15-bit BGR555 palettes. Both are fixed hardware formats (GBATEK),
 * identical across every GBA game -- these functions don't depend on which
 * ROM/hack the data came from, only on where that data lives in the ROM,
 * which is decided per game profile elsewhere.
 */
object GbaTiles {
    /** Converts a 15-bit GBA color (bit0-4 red, 5-9 green, 10-14 blue, bit15 unused) to 0xAARRGGBB. */
    fun colorToArgb(color16: Int): Int {
        fun expand(c5: Int) = (c5 shl 3) or (c5 shr 2)
        val r = expand(color16 and 0x1F)
        val g = expand((color16 shr 5) and 0x1F)
        val b = expand((color16 shr 10) and 0x1F)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Parses a raw palette blob (pairs of little-endian 15-bit colors) into ARGB ints. */
    fun decodePalette(paletteBytes: ByteArray): IntArray = IntArray(paletteBytes.size / 2) { i ->
        val lo = paletteBytes[i * 2].toInt() and 0xFF
        val hi = paletteBytes[i * 2 + 1].toInt() and 0xFF
        colorToArgb(lo or (hi shl 8))
    }

    /**
     * Decodes 4bpp tile pixel data (as decompressed from ROM) into an ARGB
     * pixel buffer of size [widthPx] x [heightPx]. Tiles are 8x8 pixels (32
     * bytes each, two pixels per byte), arranged row-major left-to-right,
     * top-to-bottom -- the "1D mapping" layout Pokemon Gen3 front/back
     * sprites use. Palette index 0 is transparent.
     */
    fun decode4bppSprite(tileData: ByteArray, palette: IntArray, widthPx: Int, heightPx: Int): IntArray {
        val out = IntArray(widthPx * heightPx)
        val tilesPerRow = widthPx / 8
        val tileRows = heightPx / 8
        var tileIndex = 0
        for (tileRow in 0 until tileRows) {
            for (tileCol in 0 until tilesPerRow) {
                val tileOffset = tileIndex * 32
                for (py in 0 until 8) {
                    for (px in 0 until 8) {
                        val byteIndex = tileOffset + py * 4 + px / 2
                        if (byteIndex >= tileData.size) continue
                        val byte = tileData[byteIndex].toInt() and 0xFF
                        val paletteIndex = if (px % 2 == 0) byte and 0x0F else (byte shr 4) and 0x0F
                        val destX = tileCol * 8 + px
                        val destY = tileRow * 8 + py
                        out[destY * widthPx + destX] =
                            if (paletteIndex == 0) 0x00000000 else palette.getOrElse(paletteIndex) { 0xFF000000.toInt() }
                    }
                }
                tileIndex++
            }
        }
        return out
    }
}
