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

    /**
     * Where to find one species' sprite or palette pointer: a base address, the
     * stride between species, and how far into each entry the pointer sits.
     */
    private data class PointerSource(val base: Int, val stride: Int, val offset: Int)

    /**
     * A profile whose sprite layout isn't the classic 8-byte pointer table is
     * describing something the header-pointer auto-resolution below cannot
     * express (pokeemerald-expansion keeps its sprite pointers inside
     * gSpeciesInfo), so in that case the profile has to win.
     */
    private fun PartyLayout?.isNonStandard(): Boolean =
        this != null && (slotStride != 8 || pointerOffset != 0)

    private suspend fun decodeRomFrontSprite(client: RetroArchClient, map: MemoryMap, speciesId: Int): ImageBitmap? {
        val front = map.frontSpriteTable
        val palette = map.paletteTable

        val spriteSource: PointerSource
        val paletteSource: PointerSource
        if (front.isNonStandard() || palette.isNonStandard()) {
            // A non-standard layout only ever comes from a profile that was
            // actually scanned for this shape (pokeemerald-expansion), so there
            // is no live-discovery equivalent to fall back to here.
            if (front == null || palette == null) return null
            spriteSource = PointerSource(front.firstSlotAddress, front.slotStride, front.pointerOffset)
            paletteSource = PointerSource(palette.firstSlotAddress, palette.slotStride, palette.pointerOffset)
        } else {
            // Standard Gen 3 pointer tables. The profile's own addresses win
            // only when the profile is confirmed to match the loaded ROM --
            // otherwise they are a different game's addresses, which read real
            // but wrong bytes rather than failing cleanly, so the live
            // header-pointer resolution has to take priority instead.
            val spriteAddress = front?.firstSlotAddress.takeIf { map.matchesLoadedRom }
                ?: FireRedHeaderPointers.frontSpriteTableAddress(client, map.engine)
                ?: front?.firstSlotAddress
                ?: return null
            val paletteAddress = palette?.firstSlotAddress.takeIf { map.matchesLoadedRom }
                ?: FireRedHeaderPointers.paletteTableAddress(client, map.engine)
                ?: palette?.firstSlotAddress
                ?: return null
            spriteSource = PointerSource(spriteAddress, 8, 0)
            paletteSource = PointerSource(paletteAddress, 8, 0)
        }

        val tileData = readCompressedBlock(client, spriteSource, speciesId, SPRITE_DECOMPRESSED_SIZE) ?: return null
        val paletteBytes = readCompressedBlock(client, paletteSource, speciesId, PALETTE_DECOMPRESSED_SIZE) ?: return null

        val colours = GbaTiles.decodePalette(paletteBytes)
        // Expansion stores two animation frames per front sprite, so the block
        // decompresses to twice the size of one 64x64 image; truncating to the
        // expected size keeps just the first frame, the Pokemon standing still.
        // A no-op on the classic single-frame format, where the sizes match.
        val firstFrame = tileData.copyOf(SPRITE_DECOMPRESSED_SIZE)
        val pixels = GbaTiles.decode4bppSprite(firstFrame, colours, SPRITE_WIDTH, SPRITE_HEIGHT)
        return Bitmap.createBitmap(pixels, SPRITE_WIDTH, SPRITE_HEIGHT, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * Reads the 4-byte ROM pointer for [speciesId] out of [source], follows it,
     * and decompresses the LZ77 block found there. Any size/tag fields
     * alongside the pointer are ignored -- they only matter for *identifying* a
     * table, not for loading from one. Falls back to treating the read as a raw
     * (uncompressed) block of [expectedSize] if it doesn't start with an LZ77
     * header, since some hacks store small blocks (like palettes) uncompressed.
     */
    private suspend fun readCompressedBlock(
        client: RetroArchClient,
        source: PointerSource,
        speciesId: Int,
        expectedSize: Int,
    ): ByteArray? {
        val entryAddress = source.base + speciesId * source.stride + source.offset
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
