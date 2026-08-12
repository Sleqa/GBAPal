package com.gbapal.companion.memory

import android.content.Context
import org.json.JSONObject

/** One entry from assets/game_profiles.json -- a bundled memory map and the ROM crc32s it applies to. */
private data class GameProfileEntry(
    val id: String,
    val displayName: String,
    val asset: String,
    val crc32s: List<Long>,
)

/** A game profile with its memory map already loaded, ready to use. */
data class ResolvedGameProfile(
    val id: String,
    val displayName: String,
    val memoryMap: MemoryMap,
)

/**
 * Bundled per-game memory-map profiles, resolved by the ROM's crc32 (see
 * RetroArchClient.getStatus / parseGetStatusResponse). Unlike a game's internal
 * header (title/game code), crc32 is a checksum of the exact ROM file, so it
 * tells hacks apart from their base game and from each other -- at the cost of
 * being version-exact: every new patch needs its crc32 added here once seen.
 * Until a crc32 is known, [default] is used, so the app still works exactly
 * as before profiles existed.
 */
object GameProfiles {
    private var cachedEntries: List<GameProfileEntry>? = null
    private var cachedDefaultId: String? = null
    private val memoryMapCache = mutableMapOf<String, MemoryMap>()

    private fun loadIndex(context: Context): Pair<List<GameProfileEntry>, String> {
        cachedEntries?.let { entries -> return entries to cachedDefaultId!! }

        val json = context.assets.open("game_profiles.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val defaultId = root.getString("defaultProfileId")
        val arr = root.getJSONArray("profiles")
        val entries = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val crcArr = p.getJSONArray("crc32s")
            val crcs = (0 until crcArr.length()).map { j -> crcArr.getString(j).removePrefix("0x").toLong(16) }
            GameProfileEntry(
                id = p.getString("id"),
                displayName = p.getString("displayName"),
                asset = p.getString("asset"),
                crc32s = crcs,
            )
        }

        cachedEntries = entries
        cachedDefaultId = defaultId
        return entries to defaultId
    }

    private fun resolve(context: Context, entry: GameProfileEntry): ResolvedGameProfile {
        val map = memoryMapCache.getOrPut(entry.id) { MemoryMap.load(context, entry.asset) }
        return ResolvedGameProfile(entry.id, entry.displayName, map)
    }

    /**
     * The profile used until/unless a live crc32 match resolves a different one.
     *
     * Marked [MemoryMap.matchesLoadedRom] = false: this is a guess to have
     * *something* to show before the ROM is even identified, not a claim that
     * whatever game is actually loaded is this one. See that flag's doc for why
     * the distinction matters.
     */
    fun default(context: Context): ResolvedGameProfile {
        val (entries, defaultId) = loadIndex(context)
        val entry = entries.firstOrNull { it.id == defaultId } ?: entries.first()
        val resolved = resolve(context, entry)
        return resolved.copy(memoryMap = resolved.memoryMap.copy(matchesLoadedRom = false))
    }

    /** Looks up the profile whose crc32 list contains [crc32], or null if none match yet. */
    fun forCrc32(context: Context, crc32: Long): ResolvedGameProfile? {
        val (entries, _) = loadIndex(context)
        val entry = entries.firstOrNull { crc32 in it.crc32s } ?: return null
        return resolve(context, entry)
    }
}
