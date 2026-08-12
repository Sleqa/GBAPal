package com.gbapal.companion.memory

import android.content.Context
import com.gbapal.companion.pokemon.RecordLayouts
import org.json.JSONObject

/** A single named byte anchor from the memory map. */
data class Anchor(
    val name: String,
    val address: Int,
    val size: Int,
    val confidence: String,
    val note: String?,
    val kind: String,
)

/**
 * Party layout: base address + stride, so slot N = firstSlotAddress + N * slotStride.
 *
 * Also describes the sprite/palette tables, where [pointerOffset] says how far
 * into each entry the 4-byte ROM pointer sits. That is 0 for the classic Gen 3
 * pointer tables (the pointer is the first field of an 8-byte entry), but
 * pokeemerald-expansion has no separate sprite table at all -- the pointers
 * live inside its much larger gSpeciesInfo entries, so both a bigger stride and
 * a non-zero offset are needed to reach them.
 */
data class PartyLayout(
    val firstSlotAddress: Int,
    val slotStride: Int,
    val slotCount: Int,
    val confidence: String,
    val note: String? = null,
    val pointerOffset: Int = 0,
)

data class MemoryMap(
    val version: String,
    val baseGame: String,
    /**
     * Free-text note on which codebase the ROM was built from, e.g.
     * "pokeemerald-expansion". Informational only -- how to *read* this game is
     * decided entirely by [dataTables] and the layouts they name, never by
     * branching on this string, so an unrecognised engine is not a problem.
     */
    val engine: String? = null,
    /**
     * The ROM tables this game's data lives in, keyed by the names in
     * GameData.Companion (speciesNames, speciesStats, moveData, ...).
     *
     * Describing the tables rather than bundling their contents is what keeps
     * adding a hack cheap: a profile is a few kilobytes of addresses and field
     * offsets, where a dump of the same data is several hundred. Anything not
     * described here falls back to the shared bundled tables.
     */
    val dataTables: Map<String, DataTable> = emptyMap(),
    /**
     * Type id -> display name for this ROM. Needed per-game because forks
     * renumber the type enum freely -- inserting one type shifts every id after
     * it, so a shared list silently mislabels every type in the game.
     */
    val typeNames: Map<Int, String> = emptyMap(),
    val party: PartyLayout,
    val enemyParty: PartyLayout,
    /**
     * Player/NPC overworld state, used to notice the player moving. Optional:
     * only a profile that has actually confirmed the address should carry it,
     * and features depending on it are skipped rather than fed a guess.
     */
    val overworldObjects: PartyLayout? = null,
    val scriptVars: PartyLayout? = null,
    /**
     * The live battle-engine struct array (`gBattleMons` in CFRU/pokeemerald),
     * one entry per active battler -- index 0 is the player's current
     * Pokemon, index 1 the opponent's, indices 2/3 the partner/second
     * opponent in a double battle. Unlike [party]/[enemyParty], this updates
     * the instant a Pokemon switches or faints into the next one, so it is
     * the right source for "what's out right now" rather than "what's on the
     * team". Only [slotStride] and enough of [firstSlotAddress] to reach the
     * species field (offset 0) are needed for that; a profile does not have
     * to map every other field in the struct just to use this.
     */
    val activeBattlers: PartyLayout? = null,
    val anchors: List<Anchor>,
    /**
     * Per-species front-sprite pointer table: address for species N is
     * firstSlotAddress + N * slotStride. Each 8-byte slot is
     * [4-byte little-endian ROM pointer to LZ77-compressed tile data]
     * [u16 decompressed size][u16 tag]. Null until discovered for a profile.
     */
    val frontSpriteTable: PartyLayout? = null,
    /**
     * Per-species palette pointer table: address for species N is
     * firstSlotAddress + N * slotStride. Each 8-byte slot is
     * [4-byte little-endian ROM pointer to LZ77-compressed 16-color
     * palette][u16 tag][u16 unused]. Null until discovered for a profile.
     */
    val paletteTable: PartyLayout? = null,
    /**
     * Whether this profile is confirmed to be *the ROM currently loaded*,
     * rather than just the app's starting default while nothing is confirmed
     * yet (see GameProfiles.default/forCrc32).
     *
     * This matters because every address here -- dataTables, sprite tables,
     * party -- was scanned for one specific ROM. Applying them to a different,
     * unrecognised ROM doesn't fail cleanly: it reads real bytes from the wrong
     * place and decodes them as if they meant something, which is how an
     * unregistered CFRU hack ended up showing literal '?' for every name --
     * Gen3Text renders undecodable bytes that way. When this is false, callers
     * should prefer ROM-agnostic live discovery (FireRedHeaderPointers) over
     * these addresses wherever the engine allows it.
     */
    val matchesLoadedRom: Boolean = true,
) {
    companion object {
        private fun parseLayout(obj: JSONObject): PartyLayout = PartyLayout(
            firstSlotAddress = obj.getString("firstSlotAddress").parseHex(),
            slotStride = obj.getInt("slotStride"),
            slotCount = obj.optInt("slotCount", 0),
            confidence = obj.getString("confidence"),
            note = if (obj.has("note")) obj.getString("note") else null,
            pointerOffset = if (obj.has("pointerOffset")) obj.getString("pointerOffset").parseHex() else 0,
        )

        /** Loads a bundled per-game memory map, e.g. "game_profiles/unbound.json". */
        fun load(context: Context, assetPath: String): MemoryMap {
            val json = context.assets.open(assetPath)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)

            val party = parseLayout(root.getJSONObject("party"))
            val enemyParty = parseLayout(root.getJSONObject("enemyParty"))
            val overworldObjects = root.optJSONObject("overworldObjects")?.let { parseLayout(it) }
            val scriptVars = root.optJSONObject("scriptVars")?.let { parseLayout(it) }
            val activeBattlers = root.optJSONObject("activeBattlers")?.let { parseLayout(it) }
            val frontSpriteTable = root.optJSONObject("frontSpriteTable")?.let { parseLayout(it) }
            val paletteTable = root.optJSONObject("paletteTable")?.let { parseLayout(it) }

            val dataTables = root.optJSONObject("dataTables")?.let { obj ->
                obj.keys().asSequence().mapNotNull { key ->
                    runCatching { key to DataTable.parse(obj.getJSONObject(key), RecordLayouts.PRESETS) }
                        .getOrNull()
                }.toMap()
            } ?: emptyMap()

            val typeNames = root.optJSONObject("typeNames")?.let { obj ->
                obj.keys().asSequence().mapNotNull { key ->
                    key.toIntOrNull()?.let { it to obj.getString(key) }
                }.toMap()
            } ?: emptyMap()

            val anchorsArr = root.getJSONArray("anchors")
            val anchors = (0 until anchorsArr.length()).map { i ->
                val a = anchorsArr.getJSONObject(i)
                Anchor(
                    name = a.getString("name"),
                    address = a.getString("address").parseHex(),
                    size = a.getInt("size"),
                    confidence = a.getString("confidence"),
                    note = if (a.has("note")) a.getString("note") else null,
                    kind = if (a.has("kind")) a.getString("kind") else "bytes",
                )
            }

            return MemoryMap(
                // "unboundVersion" is the original key name from when this app
                // only supported one game; still read so existing profiles load.
                version = root.optString("version").ifEmpty { root.optString("unboundVersion", "unknown") },
                baseGame = root.getString("baseGame"),
                engine = if (root.has("engine")) root.getString("engine") else null,
                dataTables = dataTables,
                typeNames = typeNames,
                party = party,
                enemyParty = enemyParty,
                overworldObjects = overworldObjects,
                scriptVars = scriptVars,
                activeBattlers = activeBattlers,
                anchors = anchors,
                frontSpriteTable = frontSpriteTable,
                paletteTable = paletteTable,
            )
        }
    }
}

/** Parses "0x02024284" or "02024284" into an Int. */
fun String.parseHex(): Int = removePrefix("0x").toLong(16).toInt()
