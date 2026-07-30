package com.gbapal.companion.graphics

import org.junit.Assert.assertEquals
import org.junit.Test

class GbaTilesTest {

    @Test
    fun convertsPureRedColor() {
        // bits0-4 = red = 31 (max), green/blue = 0.
        val argb = GbaTiles.colorToArgb(0x001F)
        assertEquals(0xFFFF0000.toInt(), argb)
    }

    @Test
    fun convertsPureGreenColor() {
        // bits5-9 = green = 31 (max).
        val argb = GbaTiles.colorToArgb(0x03E0)
        assertEquals(0xFF00FF00.toInt(), argb)
    }

    @Test
    fun convertsPureBlueColor() {
        // bits10-14 = blue = 31 (max).
        val argb = GbaTiles.colorToArgb(0x7C00)
        assertEquals(0xFF0000FF.toInt(), argb)
    }

    @Test
    fun decodesPaletteFromLittleEndianPairs() {
        // Two colors: black (0x0000), then pure red (0x001F).
        val paletteBytes = byteArrayOf(0x00, 0x00, 0x1F, 0x00)
        val palette = GbaTiles.decodePalette(paletteBytes)
        assertEquals(2, palette.size)
        assertEquals(0xFF000000.toInt(), palette[0])
        assertEquals(0xFFFF0000.toInt(), palette[1])
    }

    @Test
    fun decodesSingleTileRespectingNibbleOrderAndTransparency() {
        // One 8x8 tile (32 bytes). Row 0: byte 0x10 -> low nibble=0 (index 0,
        // transparent) at x=0, high nibble=1 (index 1) at x=1. Remaining
        // bytes of the tile are 0 (both pixels palette index 0).
        val tile = ByteArray(32)
        tile[0] = 0x10 // low nibble 0 (x=0), high nibble 1 (x=1)
        val palette = intArrayOf(0x00000000, 0xFFFF0000.toInt())

        val pixels = GbaTiles.decode4bppSprite(tile, palette, widthPx = 8, heightPx = 8)

        assertEquals(0x00000000, pixels[0]) // (0,0) index 0 -> transparent
        assertEquals(0xFFFF0000.toInt(), pixels[1]) // (1,0) index 1 -> red
        assertEquals(0x00000000, pixels[2]) // (2,0) untouched byte -> index 0
    }

    @Test
    fun arrangesMultipleTilesRowMajor() {
        // 16x8 sprite = 2 tiles side by side. Second tile's first byte sets
        // its (0,0) pixel to palette index 1, which should land at x=8, y=0
        // in the final buffer, not overwrite the first tile.
        val tiles = ByteArray(64)
        tiles[32] = 0x01 // second tile, low nibble = index 1 at its local x=0
        val palette = intArrayOf(0x00000000, 0xFFFF0000.toInt())

        val pixels = GbaTiles.decode4bppSprite(tiles, palette, widthPx = 16, heightPx = 8)

        assertEquals(0x00000000, pixels[0]) // first tile's (0,0) untouched
        assertEquals(0xFFFF0000.toInt(), pixels[8]) // second tile's (0,0) -> global (8,0)
    }
}
