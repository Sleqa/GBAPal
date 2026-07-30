package com.gbapal.companion.pokemon

import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

// CFRU/vanilla FireRed wild-encounter addresses (github.com/Skeli789/
// Complete-Fire-Red-Upgrade). Like DexNavData's addresses, these are fixed
// RAM/ROM locations baked into CFRU's own build (0x08082990 is read by
// CFRU's own GetCurrentMapWildMonHeaderId, so any CFRU hack that relocated
// its wild table had to keep this word in sync or its own encounters would
// break -- but it's still CFRU-baseline, not ROM-header-portable the way
// RomSpeciesData's tables are).
private const val SAVE_BLOCK_1_PTR = 0x03005008
private const val LOCATION_MAP_GROUP_OFFSET = 0x04
private const val LOCATION_MAP_NUM_OFFSET = 0x05

private const val CACHED_HEADER_PTR = 0x0203E080
private const val CACHED_MAP_GROUP = 0x0203E084
private const val CACHED_MAP_NUM = 0x0203E085
private const val WILD_MON_HEADERS_PTR = 0x08082990

// struct WildPokemonHeader, 20 bytes (include/wild_encounter.h).
private const val HEADER_SIZE = 20
private const val HEADER_OFF_MAP_GROUP = 0x00
private const val HEADER_OFF_MAP_NUM = 0x01
private const val HEADER_OFF_LAND_INFO = 0x04
private const val HEADER_OFF_WATER_INFO = 0x08
private const val HEADER_OFF_ROCK_INFO = 0x0C
private const val HEADER_OFF_FISHING_INFO = 0x10
private const val MAP_GROUP_SENTINEL = 0xFF

// struct WildPokemonInfo: u8 encounterRate, 3 bytes padding, then a pointer
// to a struct WildPokemon[] array of {u8 minLevel, u8 maxLevel, u16 species}.
private const val INFO_SIZE = 8
private const val INFO_OFF_MONS_PTR = 0x04
private const val SLOT_SIZE = 4

private const val LAND_SLOT_COUNT = 12
private const val WATER_SLOT_COUNT = 5
private const val ROCK_SLOT_COUNT = 5
private const val FISHING_SLOT_COUNT = 10

// Max headers to linear-scan looking for the current map, if the CFRU cache
// (below) doesn't already have it. Vanilla FireRed has a few hundred maps
// with wild data; this is a generous safety cap, not an expected case.
private const val MAX_HEADER_SCAN = 2000
private const val HEADER_SCAN_BATCH = 50

/** One encounter slot: a species and its level range, plus its % chance within that method. */
data class WildEncounterSlot(val speciesId: Int, val minLevel: Int, val maxLevel: Int, val percent: Int)

/** All wild encounters for one map, grouped by method. Any list may be empty if that method isn't available there. */
data class WildEncounters(
    val land: List<WildEncounterSlot>,
    val water: List<WildEncounterSlot>,
    val rockSmash: List<WildEncounterSlot>,
    val fishing: List<WildEncounterSlot>,
)

// Per-slot % chance, vanilla Gen 3 rates (src/dexnav.c). Rock Smash uses the
// same 5-slot distribution as Surfing per standard Gen 3 mechanics.
private val LAND_RATES = intArrayOf(20, 20, 10, 10, 10, 10, 5, 5, 4, 4, 1, 1)
private val WATER_RATES = intArrayOf(60, 30, 5, 4, 1)
private val ROCK_RATES = WATER_RATES
private val FISHING_RATES = intArrayOf(70, 30, 60, 20, 20, 40, 40, 15, 4, 1)

object WildEncounterData {
    /** The player's current mapGroup/mapNum, read from the live save block. */
    suspend fun currentMapKey(client: RetroArchClient): Pair<Int, Int>? {
        val saveBlockPtr = readU32(client, SAVE_BLOCK_1_PTR) ?: return null
        if (saveBlockPtr !in 0x02000000..0x0203FFFF) return null
        val bytes = readBytes(client, saveBlockPtr + LOCATION_MAP_GROUP_OFFSET, 2) ?: return null
        return (bytes[0].toInt() and 0xFF) to (bytes[1].toInt() and 0xFF)
    }

    /** All wild encounters for [mapKey] (mapGroup, mapNum), or null if that map has no wild data. */
    suspend fun encountersFor(client: RetroArchClient, mapKey: Pair<Int, Int>): WildEncounters? {
        val (mapGroup, mapNum) = mapKey
        val headerAddress = findCachedHeader(client, mapGroup, mapNum)
            ?: scanForHeader(client, mapGroup, mapNum)
            ?: return null

        val headerBytes = readBytes(client, headerAddress, HEADER_SIZE) ?: return null
        return WildEncounters(
            land = readSlots(client, headerBytes, HEADER_OFF_LAND_INFO, LAND_SLOT_COUNT, LAND_RATES),
            water = readSlots(client, headerBytes, HEADER_OFF_WATER_INFO, WATER_SLOT_COUNT, WATER_RATES),
            rockSmash = readSlots(client, headerBytes, HEADER_OFF_ROCK_INFO, ROCK_SLOT_COUNT, ROCK_RATES),
            fishing = readSlots(client, headerBytes, HEADER_OFF_FISHING_INFO, FISHING_SLOT_COUNT, FISHING_RATES),
        )
    }

    /** CFRU's own memoized "current map's header" cache -- fast path, avoids scanning the table. */
    private suspend fun findCachedHeader(client: RetroArchClient, mapGroup: Int, mapNum: Int): Int? {
        val cachedKey = readBytes(client, CACHED_MAP_GROUP, 2) ?: return null
        if ((cachedKey[0].toInt() and 0xFF) != mapGroup || (cachedKey[1].toInt() and 0xFF) != mapNum) return null
        val header = readU32(client, CACHED_HEADER_PTR) ?: return null
        return header.takeIf { it in 0x08000000..0x09FFFFFF }
    }

    private suspend fun scanForHeader(client: RetroArchClient, mapGroup: Int, mapNum: Int): Int? {
        val tableAddress = readU32(client, WILD_MON_HEADERS_PTR)?.takeIf { it in 0x08000000..0x09FFFFFF } ?: return null

        var offset = 0
        while (offset < MAX_HEADER_SCAN * HEADER_SIZE) {
            val batchBytes = readBytes(client, tableAddress + offset, HEADER_SCAN_BATCH * HEADER_SIZE) ?: return null
            for (i in 0 until HEADER_SCAN_BATCH) {
                val entryOffset = i * HEADER_SIZE
                if (entryOffset + HEADER_SIZE > batchBytes.size) return null
                val entryMapGroup = batchBytes[entryOffset + HEADER_OFF_MAP_GROUP].toInt() and 0xFF
                if (entryMapGroup == MAP_GROUP_SENTINEL) return null
                val entryMapNum = batchBytes[entryOffset + HEADER_OFF_MAP_NUM].toInt() and 0xFF
                if (entryMapGroup == mapGroup && entryMapNum == mapNum) return tableAddress + offset + entryOffset
            }
            offset += HEADER_SCAN_BATCH * HEADER_SIZE
        }
        return null
    }

    private suspend fun readSlots(
        client: RetroArchClient,
        headerBytes: ByteArray,
        infoOffset: Int,
        slotCount: Int,
        rates: IntArray,
    ): List<WildEncounterSlot> {
        val infoPtr = u32(headerBytes, infoOffset)
        if (infoPtr !in 0x08000000..0x09FFFFFF) return emptyList()

        val infoBytes = readBytes(client, infoPtr, INFO_SIZE) ?: return emptyList()
        val monsPtr = u32(infoBytes, INFO_OFF_MONS_PTR)
        if (monsPtr !in 0x08000000..0x09FFFFFF) return emptyList()

        val monsBytes = readBytes(client, monsPtr, slotCount * SLOT_SIZE) ?: return emptyList()
        return (0 until slotCount).map { i ->
            val offset = i * SLOT_SIZE
            WildEncounterSlot(
                minLevel = monsBytes[offset].toInt() and 0xFF,
                maxLevel = monsBytes[offset + 1].toInt() and 0xFF,
                speciesId = (monsBytes[offset + 2].toInt() and 0xFF) or ((monsBytes[offset + 3].toInt() and 0xFF) shl 8),
                percent = rates.getOrElse(i) { 0 },
            )
        }
    }

    private suspend fun readU32(client: RetroArchClient, address: Int): Int? {
        val bytes = readBytes(client, address, 4) ?: return null
        return u32(bytes, 0)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private suspend fun readBytes(client: RetroArchClient, address: Int, length: Int): ByteArray? =
        (client.readCoreMemory(address, length) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?.takeIf { it.size >= length }
}
