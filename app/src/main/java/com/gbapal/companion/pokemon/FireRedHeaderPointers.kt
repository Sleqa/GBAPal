package com.gbapal.companion.pokemon

import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

/**
 * Resolves the CFRU/DPE-family boot-time header pointer table.
 *
 * FireRed keeps a table of pointers near the start of its ROM, and the CFRU /
 * Dynamic Pokemon Expansion toolchains rewrite those entries at build time to
 * point wherever they relocated each table. Following them finds the real
 * table in *any* CFRU hack, with no per-hack address hunt -- what makes
 * [FireRedLiveData] possible for a hack that has no profile of its own.
 *
 * Every offset below was confirmed by dereferencing it in two unrelated CFRU
 * hacks (Pokemon Unbound, Water Blue) and validating the data it points at.
 * There is no slot for ability names -- CFRU does not route them through this
 * table -- so [FireRedLiveData] locates that one differently.
 */
object FireRedHeaderPointers {
    const val FRONT_PIC_TABLE_HEADER_PTR = 0x08000128
    const val PALETTE_TABLE_HEADER_PTR = 0x08000130
    const val SPECIES_NAMES_HEADER_PTR = 0x08000144
    const val MOVE_NAMES_HEADER_PTR = 0x08000148
    const val BASE_STATS_HEADER_PTR = 0x080001BC
    const val ITEMS_HEADER_PTR = 0x080001C8
    const val MOVE_DATA_HEADER_PTR = 0x080001CC

    /** Engines whose ROM layout this shortcut is known to apply to. */
    private val SUPPORTED_ENGINES = setOf("firered-tables", "cfru")

    /** A null engine means a profile written before the field existed; those are all CFRU hacks. */
    fun appliesTo(engine: String?): Boolean = engine == null || engine in SUPPORTED_ENGINES

    suspend fun frontSpriteTableAddress(client: RetroArchClient, engine: String?): Int? =
        if (appliesTo(engine)) resolve(client, FRONT_PIC_TABLE_HEADER_PTR) else null

    suspend fun paletteTableAddress(client: RetroArchClient, engine: String?): Int? =
        if (appliesTo(engine)) resolve(client, PALETTE_TABLE_HEADER_PTR) else null

    /** Dereferences a header slot, returning it only if it lands in ROM space. */
    suspend fun resolve(client: RetroArchClient, headerAddress: Int): Int? {
        val bytes = (client.readCoreMemory(headerAddress, 4) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        if (bytes.size < 4) return null
        val pointer = (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
        return if (pointer in 0x08000000..0x09FFFFFF) pointer else null
    }
}
