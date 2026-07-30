package com.gbapal.companion.pokemon

import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

// CFRU's DexNav state, EWRAM addresses from BPRE.ld / include/new/ram_locs.h
// (github.com/Skeli789/Complete-Fire-Red-Upgrade). Unlike the ROM tables in
// RomSpeciesData, these are fixed RAM addresses baked into CFRU's own build,
// not resolvable via a ROM header pointer -- a fork that shifted CFRU's RAM
// layout would break these, so treat them as CFRU-baseline until confirmed
// against a specific hack.
private const val DEXNAV_HUD_PTR = 0x0203E038
private const val DEXNAV_CHAIN = 0x0203E051

// struct DexnavHudData (include/new/dexnav_data.h), first 16 bytes:
// u16 species; u16 moveId[4]; u16 heldItem; u8 ability; u8 potential;
// u8 searchLevel; u8 pokemonLevel; ...
private const val HUD_DATA_READ_SIZE = 16
private const val OFF_SPECIES = 0x00
private const val OFF_HELD_ITEM = 0x0A
private const val OFF_ABILITY = 0x0C
private const val OFF_SEARCH_LEVEL = 0x0E
private const val OFF_POKEMON_LEVEL = 0x0F

/** A live DexNav scan target, read straight from CFRU's HUD data struct. */
data class DexNavScan(
    val speciesId: Int,
    val heldItemId: Int,
    val abilityId: Int,
    val searchLevel: Int,
    val pokemonLevel: Int,
)

/**
 * Reads CFRU's DexNav state and computes the odds it rolls from, per the
 * probability tables in include/new/dexnav_config.h.
 */
object DexNavData {
    /**
     * The current scan target, or null if no scan is active. [inBattle] must
     * be passed in from the caller's own battle-state tracking -- the HUD
     * pointer's address is reused for the battle struct while a battle is
     * running, so reading it mid-battle would return garbage, not "no scan."
     */
    suspend fun activeScan(client: RetroArchClient, inBattle: Boolean): DexNavScan? {
        if (inBattle) return null

        val ptrBytes = (client.readCoreMemory(DEXNAV_HUD_PTR, 4) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        if (ptrBytes.size < 4) return null
        val ptr = (ptrBytes[0].toInt() and 0xFF) or
            ((ptrBytes[1].toInt() and 0xFF) shl 8) or
            ((ptrBytes[2].toInt() and 0xFF) shl 16) or
            ((ptrBytes[3].toInt() and 0xFF) shl 24)
        if (ptr !in 0x02000000..0x0203FFFF) return null

        val data = (client.readCoreMemory(ptr, HUD_DATA_READ_SIZE) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        if (data.size < HUD_DATA_READ_SIZE) return null

        fun u16(offset: Int) = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        val speciesId = u16(OFF_SPECIES)
        if (speciesId == 0) return null

        return DexNavScan(
            speciesId = speciesId,
            heldItemId = u16(OFF_HELD_ITEM),
            abilityId = data[OFF_ABILITY].toInt() and 0xFF,
            searchLevel = data[OFF_SEARCH_LEVEL].toInt() and 0xFF,
            pokemonLevel = data[OFF_POKEMON_LEVEL].toInt() and 0xFF,
        )
    }

    /** Current chain/streak counter (resets to 0 on a loss/flee, per end_battle.c). */
    suspend fun chain(client: RetroArchClient): Int? {
        val bytes = (client.readCoreMemory(DEXNAV_CHAIN, 1) as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?: return null
        return bytes.firstOrNull()?.let { it.toInt() and 0xFF }
    }

    // Percent chance bands from dexnav_config.h, indexed by search-level band:
    // <5, <10, <25, <50, <100, >=100.
    private fun band(searchLevel: Int) = when {
        searchLevel < 5 -> 0
        searchLevel < 10 -> 1
        searchLevel < 25 -> 2
        searchLevel < 50 -> 3
        searchLevel < 100 -> 4
        else -> 5
    }

    private val HIDDEN_ABILITY_PERCENT = intArrayOf(0, 0, 5, 15, 20, 23)
    private val EGG_MOVE_PERCENT = intArrayOf(0, 21, 46, 58, 63, 83)
    private val ONE_STAR_PERCENT = intArrayOf(0, 14, 17, 17, 15, 8)
    private val TWO_STAR_PERCENT = intArrayOf(0, 1, 9, 16, 17, 24)
    private val THREE_STAR_PERCENT = intArrayOf(0, 0, 1, 7, 6, 12)

    /** % chance the encounter carries its hidden ability (species must also already be caught, per dexnav.c -- not checked here). */
    fun hiddenAbilityPercent(searchLevel: Int): Int = HIDDEN_ABILITY_PERCENT[band(searchLevel)]

    /** % chance the encounter knows an egg move. */
    fun eggMovePercent(searchLevel: Int): Int = EGG_MOVE_PERCENT[band(searchLevel)]

    /** % chance of at least 1/2/3 guaranteed-perfect IVs (mutually exclusive bands). */
    fun ivPotentialPercents(searchLevel: Int): Triple<Int, Int, Int> {
        val b = band(searchLevel)
        return Triple(ONE_STAR_PERCENT[b], TWO_STAR_PERCENT[b], THREE_STAR_PERCENT[b])
    }

    /**
     * Overall shiny probability for this encounter (0.0-1.0), from
     * dexnav.c's shiny-roll math: a rarity value derived from search level
     * (capped/rescaled the same way CFRU does, with the SL=255 special case
     * mapped to ORAS's max of 999), rolled against Random32() % 10000 once
     * per "check." Chain gives bonus checks at 50/100; there's also a
     * separate random 4% chance of +4 checks that isn't modeled here (shown
     * as a note in the UI instead), so this is the odds ignoring that one
     * lucky roll.
     */
    fun shinyProbability(searchLevel: Int, chain: Int): Double {
        val value = when {
            searchLevel == 255 -> 999
            else -> {
                val band1 = minOf(searchLevel, 100) * 6
                val band2 = maxOf(0, minOf(searchLevel, 200) - 100) * 2
                val band3 = maxOf(0, searchLevel - 200)
                band1 + band2 + band3
            }
        }
        val perCheckProbability = value / 10000.0
        val chainBonusChecks = if (chain >= 100) 10 else if (chain >= 50) 5 else 0
        val numChecks = 1 + chainBonusChecks
        return 1.0 - Math.pow(1.0 - perCheckProbability, numChecks.toDouble())
    }
}
