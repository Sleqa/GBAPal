package com.gbapal.companion.pokemon

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gbapal.companion.graphics.GbaTiles
import com.gbapal.companion.graphics.Lz77
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.memory.PartyLayout
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

private const val SPRITE_WIDTH = 64
private const val SPRITE_HEIGHT = 64
private const val SPRITE_DECOMPRESSED_SIZE = SPRITE_WIDTH * SPRITE_HEIGHT / 2 // 4bpp
private const val PALETTE_DECOMPRESSED_SIZE = 32 // 16 colors x 2 bytes

object SpriteAssets {
    private val romCache = HashMap<Pair<MemoryMap, Int>, ImageBitmap?>()

    /**
     * Live front sprite decoded straight from the loaded ROM via [client],
     * using [map]'s confirmed frontSpriteTable/paletteTable -- no bundled
     * copyrighted art involved. Returns null if the profile has no tables
     * yet, or if any read/decompress step fails; caller should fall back to
     * a generic placeholder, not bundled art, so failures stay visible.
     */
    suspend fun romFrontSprite(client: RetroArchClient, map: MemoryMap, speciesId: Int): ImageBitmap? {
        val key = map to speciesId
        if (romCache.containsKey(key)) return romCache[key]

        val bitmap = decodeRomFrontSprite(client, map, speciesId)
        romCache[key] = bitmap
        return bitmap
    }

    private suspend fun decodeRomFrontSprite(client: RetroArchClient, map: MemoryMap, speciesId: Int): ImageBitmap? {
        val spriteTable = map.frontSpriteTable ?: return null
        val paletteTable = map.paletteTable ?: return null

        val tileData = readCompressedBlock(client, spriteTable, speciesId, SPRITE_DECOMPRESSED_SIZE) ?: return null
        val paletteBytes = readCompressedBlock(client, paletteTable, speciesId, PALETTE_DECOMPRESSED_SIZE) ?: return null

        val palette = GbaTiles.decodePalette(paletteBytes)
        val pixels = GbaTiles.decode4bppSprite(tileData, palette, SPRITE_WIDTH, SPRITE_HEIGHT)
        return Bitmap.createBitmap(pixels, SPRITE_WIDTH, SPRITE_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * Reads [table]'s entry for [speciesId] (just the leading 4-byte pointer
     * -- size/tag aren't needed to load, only to identify the table), follows
     * it, and decompresses the LZ77 block found there. Falls back to treating
     * the read as a raw (uncompressed) block of [expectedSize] if it doesn't
     * start with an LZ77 header, since some hacks store small blocks (like
     * palettes) uncompressed.
     */
    private suspend fun readCompressedBlock(
        client: RetroArchClient,
        table: PartyLayout,
        speciesId: Int,
        expectedSize: Int,
    ): ByteArray? {
        val entryAddress = table.firstSlotAddress + speciesId * table.slotStride
        val entryBytes = (client.readCoreMemory(entryAddress, 4) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        if (entryBytes.size < 4) return null
        val pointer = (entryBytes[0].toInt() and 0xFF) or
            ((entryBytes[1].toInt() and 0xFF) shl 8) or
            ((entryBytes[2].toInt() and 0xFF) shl 16) or
            ((entryBytes[3].toInt() and 0xFF) shl 24)
        if (pointer == 0) return null

        // Generous buffer past the pointer -- LZ77 can't expand data by more
        // than ~12.5% (one flag byte per 8 literal bytes), so this comfortably
        // covers the compressed size without needing to know it up front.
        val bufferLength = expectedSize + expectedSize / 8 + 16
        val rawBytes = (client.readCoreMemory(pointer, bufferLength) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null

        Lz77.decompress(rawBytes, 0)?.let { return it }
        return if (rawBytes.size >= expectedSize) rawBytes.copyOf(expectedSize) else null
    }
}
