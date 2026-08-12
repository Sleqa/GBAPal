package com.gbapal.companion.pokemon

import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

/**
 * Reads species/move/item/ability data live off an unrecognised CFRU/DPE-family
 * ROM, via the same boot-time header pointer table [FireRedHeaderPointers]
 * already uses for sprites.
 *
 * Exists for exactly one situation: the loaded ROM's crc32 isn't in
 * game_profiles.json, so [com.gbapal.companion.memory.MemoryMap.matchesLoadedRom]
 * is false and the only MemoryMap on hand describes a *different* game.
 * Reading that game's addresses against this ROM doesn't fail cleanly -- it
 * decodes whatever real bytes happen to be at that address as if they meant
 * something, which Gen3Text renders as a wall of '?'. That is what an
 * unregistered CFRU hack looked like after this class stopped existing.
 * Reading via the ROM's own header table instead needs no profile at all.
 *
 * Two structures are known to hold constant across every CFRU-family hack
 * checked (Unbound, Water Blue, vanilla FireRed): the 28-byte base-stats
 * struct and the 11-byte species-name entry. Two are NOT constant -- move and
 * ability name stride vary per hack (13 bytes in vanilla, 17 in Unbound) --
 * so those are self-detected once per table, by reading entry #1 at a few
 * candidate strides and keeping whichever decodes to the name it has to be
 * ("Pound", "Stench"). A wrong guess here would show a real name under the
 * wrong id, so skipping that check was not an option.
 *
 * One instance per profile/ROM; every result is cached, so a repeat lookup
 * (the same species shown again next poll) costs no network round trip.
 */
class FireRedLiveData(private val client: RetroArchClient) {

    // CFRU's TYPE_* byte values -> display name. Confirmed the same,
    // unshifted order across every CFRU-family hack checked -- unlike
    // pokeemerald-expansion forks, which do reorder this enum (see
    // GameData._build_expansion_type_names on the gbamap side for why that
    // family needs a different approach entirely).
    private val typeNames = arrayOf(
        "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel", "Mystery",
        "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark",
        null, null, null, null, null, "Fairy",
    )

    private data class NameTable(val address: Int, val stride: Int)

    private var baseStatsAddress: Int? = null
    private var speciesNamesAddress: Int? = null
    private var itemsAddress: Int? = null
    private var moveDataAddress: Int? = null
    private var moveNames: NameTable? = null
    private var abilityNames: NameTable? = null
    private var tablesResolved = false

    private val speciesCache = HashMap<Int, BaseStats.Entry?>()
    private val speciesNameCache = HashMap<Int, String?>()
    private val moveCache = HashMap<Int, MoveData.Entry?>()
    private val moveNameCache = HashMap<Int, String?>()
    private val itemNameCache = HashMap<Int, String?>()
    private val abilityNameCache = HashMap<Int, String?>()

    private suspend fun resolveTables() {
        // Only latches once baseStatsAddress -- the one every other lookup
        // ultimately depends on for a species to mean anything -- actually
        // resolved. A single dropped UDP reply here used to permanently wedge
        // this instance into "no live data" for the rest of the session (this
        // ran once at startup and never again), silently falling everything
        // through to the bundled tier-3 tables keyed by a *different* game's
        // species numbering -- which is how a wrong species ends up on screen
        // rather than just a missing name.
        if (tablesResolved) return
        baseStatsAddress = FireRedHeaderPointers.resolve(client, FireRedHeaderPointers.BASE_STATS_HEADER_PTR)
        tablesResolved = baseStatsAddress != null
        speciesNamesAddress = FireRedHeaderPointers.resolve(client, FireRedHeaderPointers.SPECIES_NAMES_HEADER_PTR)
        itemsAddress = FireRedHeaderPointers.resolve(client, FireRedHeaderPointers.ITEMS_HEADER_PTR)
        moveDataAddress = FireRedHeaderPointers.resolve(client, FireRedHeaderPointers.MOVE_DATA_HEADER_PTR)

        val moveNamesBase = FireRedHeaderPointers.resolve(client, FireRedHeaderPointers.MOVE_NAMES_HEADER_PTR)
        moveNames = moveNamesBase?.let { detectNameStride(it, expectedFirst = "Pound") }

        // No header slot for ability names (CFRU doesn't route them through
        // this table), so the vanilla FireRed address is tried as a starting
        // guess, then proven or discarded the same way: it is never trusted
        // unless entry #1 actually decodes to "Stench".
        abilityNames = detectNameStride(VANILLA_ABILITY_NAMES_GUESS, expectedFirst = "Stench")
    }

    /**
     * Tries a short list of plausible strides at [base], keeping the first one
     * where entry #1 decodes to [expectedFirst]. Returns null if none do --
     * meaning this ROM's table isn't at this address/shape, so nothing here
     * should be trusted.
     */
    private suspend fun detectNameStride(base: Int, expectedFirst: String): NameTable? {
        for (stride in intArrayOf(13, 17, 14, 18, 19, 20)) {
            val entry = readBytes(base + stride, stride) ?: continue
            if (Gen3Text.decode(entry).equals(expectedFirst, ignoreCase = true)) {
                return NameTable(base, stride)
            }
        }
        return null
    }

    private suspend fun readBytes(address: Int, length: Int): ByteArray? {
        val result = client.readCoreMemory(address, length)
        return (result as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?.takeIf { it.size >= length }
    }

    suspend fun baseStats(speciesId: Int): BaseStats.Entry? {
        if (speciesCache.containsKey(speciesId)) return speciesCache[speciesId]
        resolveTables()
        val table = baseStatsAddress
        val entry = if (table == null) null else readBytes(table + speciesId * 0x1C, 0x1C)?.let { e ->
            val type1 = typeNames.getOrNull(e[0x06].toInt() and 0xFF)
            val type2 = typeNames.getOrNull(e[0x07].toInt() and 0xFF).takeIf { it != type1 }
            BaseStats.Entry(
                hp = e[0x00].toInt() and 0xFF,
                attack = e[0x01].toInt() and 0xFF,
                defense = e[0x02].toInt() and 0xFF,
                spAttack = e[0x04].toInt() and 0xFF,
                spDefense = e[0x05].toInt() and 0xFF,
                speed = e[0x03].toInt() and 0xFF,
                type1 = type1,
                type2 = type2,
                ability1 = e[0x16].toInt() and 0xFF,
                ability2 = e[0x17].toInt() and 0xFF,
                hiddenAbility = e[0x1A].toInt() and 0xFF,
            )
        }
        speciesCache[speciesId] = entry
        return entry
    }

    suspend fun speciesName(speciesId: Int): String? {
        if (speciesNameCache.containsKey(speciesId)) return speciesNameCache[speciesId]
        resolveTables()
        val table = speciesNamesAddress
        val name = table?.let { readBytes(it + speciesId * 11, 11) }
            ?.let { Gen3Text.decode(it) }?.takeIf(Gen3Text::looksLikeName)
        speciesNameCache[speciesId] = name
        return name
    }

    suspend fun moveData(moveId: Int): MoveData.Entry? {
        if (moveCache.containsKey(moveId)) return moveCache[moveId]
        resolveTables()
        val table = moveDataAddress
        val entry = if (table == null) null else readBytes(table + moveId * 0x0C, 0x0C)?.let { e ->
            val power = e[1].toInt() and 0xFF
            val typeId = e[2].toInt() and 0xFF
            val type = typeNames.getOrNull(typeId)
            val splitByte = e[10].toInt() and 0xFF
            val category = when {
                splitByte <= 2 -> arrayOf("Physical", "Special", "Status")[splitByte]
                power == 0 -> "Status"
                typeId < 9 -> "Physical" // vanilla rule: no split byte means category follows type
                else -> "Special"
            }
            MoveData.Entry(
                power = power,
                accuracy = e[3].toInt() and 0xFF,
                ppMax = e[4].toInt() and 0xFF,
                type = type,
                category = category,
            )
        }
        moveCache[moveId] = entry
        return entry
    }

    suspend fun moveName(moveId: Int): String? {
        if (moveNameCache.containsKey(moveId)) return moveNameCache[moveId]
        resolveTables()
        val table = moveNames
        val name = table?.let { readBytes(it.address + moveId * it.stride, it.stride) }
            ?.let { Gen3Text.decode(it) }?.takeIf(Gen3Text::looksLikeName)
        moveNameCache[moveId] = name
        return name
    }

    suspend fun abilityName(abilityId: Int): String? {
        if (abilityNameCache.containsKey(abilityId)) return abilityNameCache[abilityId]
        resolveTables()
        val table = abilityNames
        val name = table?.let { readBytes(it.address + abilityId * it.stride, it.stride) }
            ?.let { Gen3Text.decode(it) }?.takeIf(Gen3Text::looksLikeName)
        abilityNameCache[abilityId] = name
        return name
    }

    suspend fun itemName(itemId: Int): String? {
        if (itemNameCache.containsKey(itemId)) return itemNameCache[itemId]
        resolveTables()
        val table = itemsAddress
        val name = table?.let { readBytes(it + itemId * 0x2C, 14) }
            ?.let { Gen3Text.decode(it) }?.takeIf(Gen3Text::looksLikeName)
        itemNameCache[itemId] = name
        return name
    }

    companion object {
        /**
         * The stock vanilla FireRed address, used only as a starting guess for
         * [detectNameStride] to prove or discard -- never trusted outright.
         */
        private const val VANILLA_ABILITY_NAMES_GUESS = 0x0824FC40
    }
}
