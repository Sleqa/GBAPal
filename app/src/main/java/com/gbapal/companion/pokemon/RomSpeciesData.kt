package com.gbapal.companion.pokemon

import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

// FireRed's own boot-time pointer table near the start of ROM -- Dynamic
// Pokemon Expansion overwrites the value at each of these addresses to point
// at wherever it built the real table, at compile time. Same header
// addresses across every CFRU/DPE-based hack (confirmed: Unbound, Radical
// Red), so resolving through here works without any per-hack address hunt.
private const val FRONT_PIC_TABLE_HEADER_PTR = 0x08000128
private const val PALETTE_TABLE_HEADER_PTR = 0x08000130
private const val BASE_STATS_TABLE_HEADER_PTR = 0x080001BC
private const val SPECIES_NAMES_TABLE_HEADER_PTR = 0x08000144

private const val BASE_STATS_ENTRY_SIZE = 0x1C
private const val SPECIES_NAME_ENTRY_SIZE = 11

// CFRU's TYPE_* byte values -> display name (include/constants/pokemon.h).
// Indices 18-22 are reserved/special marker slots (e.g. TYPE_ROOSTLESS,
// TYPE_BLANK), never a real species' type1/type2.
private val GBA_TYPE_NAMES = arrayOf(
    "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "Mystery",
    "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
    null, null, null, null, null,
    "Fairy",
)

/**
 * Reads species base stats/types/abilities and species names straight from
 * the loaded ROM instead of a per-hack JSON dump. Works on any CFRU/Dynamic
 * Pokemon Expansion-based hack -- the actual fix for a hack (like Radical
 * Red) renumbering its expanded species IDs differently than the hack a
 * bundled JSON was built from, which otherwise shows wrong types/names for
 * whichever species collide with the wrong hack's ID at that slot.
 */
object RomSpeciesData {
    /**
     * Resolves a table's real address via [headerAddress]. Returns null if
     * the read fails or the pointer doesn't land in ROM space -- meaning
     * this isn't a DPE/CFRU-based hack, or it relocated this pointer table.
     */
    private suspend fun resolveTablePointer(client: RetroArchClient, headerAddress: Int): Int? {
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

    suspend fun frontSpriteTableAddress(client: RetroArchClient): Int? =
        resolveTablePointer(client, FRONT_PIC_TABLE_HEADER_PTR)

    suspend fun paletteTableAddress(client: RetroArchClient): Int? =
        resolveTablePointer(client, PALETTE_TABLE_HEADER_PTR)

    /** Reads one species' base stats/types/abilities straight from the ROM's gBaseStats[]. */
    suspend fun baseStats(client: RetroArchClient, speciesId: Int): BaseStats.Entry? {
        val tableAddress = resolveTablePointer(client, BASE_STATS_TABLE_HEADER_PTR) ?: return null
        val entryAddress = tableAddress + speciesId * BASE_STATS_ENTRY_SIZE
        val bytes = (client.readCoreMemory(entryAddress, BASE_STATS_ENTRY_SIZE) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        if (bytes.size < BASE_STATS_ENTRY_SIZE) return null

        val type1 = GBA_TYPE_NAMES.getOrNull(bytes[0x06].toInt() and 0xFF)
        val type2 = GBA_TYPE_NAMES.getOrNull(bytes[0x07].toInt() and 0xFF).takeIf { it != type1 }

        return BaseStats.Entry(
            hp = bytes[0x00].toInt() and 0xFF,
            attack = bytes[0x01].toInt() and 0xFF,
            defense = bytes[0x02].toInt() and 0xFF,
            spAttack = bytes[0x04].toInt() and 0xFF,
            spDefense = bytes[0x05].toInt() and 0xFF,
            speed = bytes[0x03].toInt() and 0xFF,
            type1 = type1,
            type2 = type2,
            ability1 = bytes[0x16].toInt() and 0xFF,
            ability2 = bytes[0x17].toInt() and 0xFF,
            hiddenAbility = bytes[0x1A].toInt() and 0xFF,
        )
    }

    /**
     * Mirrors CFRU's GetMonAbility() (see [BaseStats.abilityIdFor]), but
     * using ability IDs read live from ROM via [baseStats] -- a hack's
     * expanded species can have different ability IDs at the same species
     * ID than whichever hack a bundled JSON was built from. Returns null if
     * the ROM read fails, so callers should fall back to
     * [BaseStats.abilityIdFor].
     */
    suspend fun abilityIdFor(
        client: RetroArchClient,
        speciesId: Int,
        personality: Long,
        hiddenAbilityFlag: Boolean,
    ): Int? {
        val entry = baseStats(client, speciesId) ?: return null
        if (hiddenAbilityFlag && entry.hiddenAbility != 0) return entry.hiddenAbility
        return if ((personality and 1L) == 0L || entry.ability2 == 0) entry.ability1 else entry.ability2
    }

    /** Reads one species' name straight from the ROM's gSpeciesNames[]. */
    suspend fun speciesName(client: RetroArchClient, speciesId: Int): String? {
        val tableAddress = resolveTablePointer(client, SPECIES_NAMES_TABLE_HEADER_PTR) ?: return null
        val entryAddress = tableAddress + speciesId * SPECIES_NAME_ENTRY_SIZE
        val bytes = (client.readCoreMemory(entryAddress, SPECIES_NAME_ENTRY_SIZE) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        return Gen3Text.decode(bytes).ifEmpty { null }
    }
}
