package com.gbapal.companion.memory

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Finds the per-game profiles the app can use, by discovery rather than
 * registration.
 *
 * Adding a game is exactly one file: drop `<game>.json` into
 * `assets/game_profiles/` (bundled) or into the app's external
 * `game_profiles/` folder on the device (drop-in, see [externalDir]). Nothing
 * else references it -- the profile names itself and lists the ROM CRC32s it
 * applies to, so there is no index to edit, no id to register, and no shared
 * file that two people adding two different games could conflict in.
 *
 * A drop-in file beats a bundled one with the same id, which is what makes it
 * possible to iterate on a profile for one game -- or fix a wrong address in a
 * shipped one -- without rebuilding the app or touching any other game.
 */
object GameProfiles {
    private const val TAG = "GameProfiles"
    private const val DIR = "game_profiles"

    private var cached: List<MemoryMap>? = null

    /**
     * Where drop-in profiles live: `Android/data/<package>/files/game_profiles`
     * on the device's shared storage. App-scoped, so it needs no storage
     * permission, and it survives app updates.
     */
    fun externalDir(context: Context): File? = context.getExternalFilesDir(DIR)

    /** Every discovered profile, drop-in files taking precedence over bundled ones. */
    fun all(context: Context): List<MemoryMap> = cached ?: buildList {
        addAll(loadBundled(context))
        // Added second and de-duplicated by id below, so a drop-in file
        // shadows the bundled game it shares an id with.
        addAll(loadExternal(context))
    }.reversed().distinctBy { it.id }.also { cached = it }

    /** Forgets discovered profiles so a newly dropped-in file is picked up. */
    fun refresh() {
        cached = null
    }

    /**
     * The profile for the ROM currently loaded, or null if its CRC32 matches
     * nothing -- callers should prefer ROM-agnostic discovery over another
     * game's addresses in that case (see [MemoryMap.matchesLoadedRom]).
     */
    fun forCrc32(context: Context, crc32: Long): MemoryMap? =
        all(context).firstOrNull { crc32 in it.crc32s }

    /**
     * What to use until a ROM is identified. Flagged [MemoryMap.matchesLoadedRom]
     * false: it is a guess so the hub has something to show at launch, not a
     * claim about which game is loaded.
     */
    fun default(context: Context): MemoryMap? {
        val profiles = all(context)
        val fallback = profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
        return fallback?.copy(matchesLoadedRom = false)
    }

    private fun loadBundled(context: Context): List<MemoryMap> =
        runCatching { context.assets.list(DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(SUFFIX) }
            .mapNotNull { name ->
                parseOrWarn(name) {
                    context.assets.open("$DIR/$name").bufferedReader().use { it.readText() }
                }
            }

    private fun loadExternal(context: Context): List<MemoryMap> =
        externalDir(context)
            ?.let { dir -> runCatching { dir.listFiles() }.getOrNull() }
            ?.filter { it.isFile && it.name.endsWith(SUFFIX) }
            ?.mapNotNull { file -> parseOrWarn(file.name) { file.readText() } }
            ?: emptyList()

    /**
     * One malformed profile must not take the app down with it -- most likely
     * it is a drop-in someone is still writing, and the other games should
     * keep working while they fix it.
     */
    private inline fun parseOrWarn(fileName: String, readJson: () -> String): MemoryMap? =
        runCatching { MemoryMap.parse(readJson(), fileName.removeSuffix(SUFFIX)) }
            .onFailure { Log.w(TAG, "Skipping unreadable profile '$fileName'", it) }
            .getOrNull()

    private const val SUFFIX = ".json"
}
