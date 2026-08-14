package com.gbapal.companion.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/** Which PokeAPI collection a lookup targets. [path] is the REST path segment. */
enum class DexKind(val path: String) {
    ABILITY("ability"),
    ITEM("item"),
    MOVE("move"),
    // "pokemon-species" rather than "pokemon" -- the species endpoint is the
    // one that carries flavor text and the evolution_chain link; "pokemon"
    // is stats/sprites/moves, none of which this needs.
    SPECIES("pokemon-species"),
}

sealed class DexResult {
    /** A real description, already whitespace-normalised for display. */
    data class Found(val text: String) : DexResult()

    /**
     * The API has no such entry. Expected and normal, not a malfunction: PokeAPI
     * only covers the official games, so a ROM hack's custom content lands here
     * (Radical Red's "Feline Prowess", for one). Cached like a real answer --
     * it will still be missing next time, so re-asking would just be a slow
     * repeat of the same 404.
     */
    data object NotFound : DexResult()

    /** Network/parse failure. Never cached -- see [PokeApiClient.description]. */
    data class Error(val message: String) : DexResult()
}

/**
 * Looks up ability/item/move descriptions from PokeAPI (pokeapi.co), keyed by
 * the *name* already read out of the ROM rather than by any numeric id.
 *
 * Keying on the name is the whole point: every ROM hack renumbers its internal
 * ability/item/move ids freely, so an id means nothing outside the ROM it came
 * from, but "Simple" is "Simple" in all of them. That makes one integration
 * cover every bundled profile with no per-ROM work -- unlike reading
 * descriptions out of each ROM, which would need a fresh table hunt per hack.
 *
 * The tradeoff is that only official content exists upstream. Anything a hack
 * invented resolves to [DexResult.NotFound], and an ability a hack silently
 * *rebalanced* will return the official text, which may not match what that
 * hack actually does.
 *
 * Descriptions are immutable, so both tiers of cache are permanent: an
 * in-memory map for the session, and a small file per entry under [cacheDir]
 * so a lookup survives an app restart and works offline afterwards. Failures
 * are deliberately not cached, matching RomDataReader's reasoning -- one
 * dropped request must not poison an entry for good.
 */
class PokeApiClient(
    cacheDir: File,
    private val baseUrl: String = "https://pokeapi.co/api/v2",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) {
    private val diskCache = File(cacheDir, "dex").apply { mkdirs() }
    private val memoryCache = HashMap<String, DexResult>()

    suspend fun description(kind: DexKind, name: String): DexResult {
        val slug = slugify(name)
        if (slug.isEmpty()) return DexResult.NotFound
        val key = "${kind.path}-$slug"

        synchronized(memoryCache) { memoryCache[key] }?.let { return it }
        readDisk(key)?.let { cached ->
            synchronized(memoryCache) { memoryCache[key] = cached }
            return cached
        }

        val result = fetch(kind, slug)
        // Only a definitive answer is worth keeping. An Error is usually just
        // "no wifi right now", and caching that would leave the entry
        // permanently blank long after the connection came back.
        if (result !is DexResult.Error) {
            synchronized(memoryCache) { memoryCache[key] = result }
            writeDisk(key, result)
        }
        return result
    }

    private suspend fun fetch(kind: DexKind, slug: String): DexResult = withContext(Dispatchers.IO) {
        when (val species = getJson("$baseUrl/${kind.path}/$slug/")) {
            is Fetched.Ok ->
                if (kind == DexKind.SPECIES) {
                    DexResult.Found(buildSpeciesText(species.json))
                } else {
                    parseDescription(species.json)?.let { DexResult.Found(it) } ?: DexResult.NotFound
                }
            Fetched.NotFound -> DexResult.NotFound
            is Fetched.Failed -> DexResult.Error(species.message)
        }
    }

    /**
     * Description plus how this species evolves, e.g. "A strange seed...
     *
     * Evolves into Ivysaur (Level 16)." The evolution half is fetched as a
     * second request (the chain lives at its own URL, shared by every species
     * in it) and is deliberately non-fatal on its own: a description the
     * player can read is worth more than nothing at all just because the
     * second request happened to drop.
     */
    private fun buildSpeciesText(species: JSONObject): String {
        val description = parseDescription(species) ?: "No description available."
        val chainUrl = species.optJSONObject("evolution_chain")?.optString("url")
        val slug = species.optString("name")
        val evolutionText = (chainUrl?.let { getJson(it) } as? Fetched.Ok)
            ?.json?.optJSONObject("chain")
            ?.let { findEvolutionNode(it, slug, null, null) }
            ?.let { describeEvolution(it) }
            ?: "unknown (couldn't reach the evolution data)."
        return "$description\n\nEvolution: $evolutionText"
    }

    /** Outcome of one GET, distinguishing "no such entry" from "request failed" -- see [getJson]. */
    private sealed class Fetched {
        data class Ok(val json: JSONObject) : Fetched()
        data object NotFound : Fetched()
        data class Failed(val message: String) : Fetched()
    }

    /**
     * GETs and parses one URL, whichever kind of PokeAPI object it happens to
     * be -- a species, an evolution chain, or an ability/item/move. The three-
     * way result exists because the evolution-chain follow-up in
     * [buildSpeciesText] needs to tell "no chain" apart from "the connection
     * dropped" without spilling mutable state across what may be concurrent
     * lookups (two Dex taps in quick succession each run their own [fetch]).
     */
    private fun getJson(url: String): Fetched {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // PokeAPI asks callers to identify themselves and cache; both done.
                setRequestProperty("User-Agent", "GBAPal (github.com/Sleqa/GBAPal)")
                setRequestProperty("Accept", "application/json")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    Fetched.Ok(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                HttpURLConnection.HTTP_NOT_FOUND -> Fetched.NotFound
                else -> Fetched.Failed("Lookup failed (HTTP $code)")
            }
        } catch (e: Exception) {
            Fetched.Failed(e.message ?: "No connection")
        } finally {
            connection?.disconnect()
        }
    }

    /** One matched species' relationship to its evolution chain. */
    private class EvolutionNode(
        val parentName: String?,
        val parentCondition: JSONObject?,
        val children: List<Pair<String, JSONObject?>>,
    )

    /**
     * Walks a chain link recursively to find [target], carrying along the name
     * of whatever species is one level up ([parentName]) and the condition
     * that reaches the current link ([parentCondition]) so the match can
     * report both directions without a second pass over the tree.
     */
    private fun findEvolutionNode(
        link: JSONObject,
        target: String,
        parentName: String?,
        parentCondition: JSONObject?,
    ): EvolutionNode? {
        val name = link.optJSONObject("species")?.optString("name") ?: return null
        val childLinks = link.optJSONArray("evolves_to") ?: JSONArray()

        if (name == target) {
            val children = (0 until childLinks.length()).mapNotNull { i ->
                val child = childLinks.optJSONObject(i) ?: return@mapNotNull null
                val childName = child.optJSONObject("species")?.optString("name") ?: return@mapNotNull null
                childName to representativeCondition(child.optJSONArray("evolution_details"))
            }
            return EvolutionNode(parentName, parentCondition, children)
        }

        for (i in 0 until childLinks.length()) {
            val child = childLinks.optJSONObject(i) ?: continue
            val condition = representativeCondition(child.optJSONArray("evolution_details"))
            findEvolutionNode(child, target, name, condition)?.let { return it }
        }
        return null
    }

    /**
     * A branch's evolution_details can hold several near-duplicate entries --
     * one per game version the branch was introduced or re-styled in (Leafeon
     * alone carries six, mostly "stand next to a mossy rock in this region").
     * PokeAPI marks exactly one per branch is_default, which is the
     * current/simplest way to get it (an item in modern games, where one
     * exists) -- that's the one worth showing instead of every historical
     * variant.
     */
    private fun representativeCondition(details: JSONArray?): JSONObject? {
        if (details == null || details.length() == 0) return null
        for (i in 0 until details.length()) {
            val entry = details.optJSONObject(i)
            if (entry?.optBoolean("is_default", false) == true) return entry
        }
        return details.optJSONObject(0)
    }

    private fun describeEvolution(node: EvolutionNode): String {
        val lines = mutableListOf<String>()
        node.parentName?.let { parent ->
            val condition = node.parentCondition?.let { formatCondition(it) } ?: "special condition"
            lines += "Evolved from ${humanize(parent)} ($condition)."
        }
        if (node.children.isEmpty()) {
            lines += if (node.parentName != null) "Does not evolve further." else "Does not evolve."
        } else {
            node.children.forEach { (childName, condition) ->
                val conditionText = condition?.let { formatCondition(it) } ?: "special condition"
                lines += "Evolves into ${humanize(childName)} ($conditionText)."
            }
        }
        return lines.joinToString(" ")
    }

    /**
     * Turns one evolution_details object into a short human clause, e.g.
     * "Level 16" or "Use Water Stone" or "Trade holding King's Rock". Every
     * field PokeAPI defines for this object is a possible additional
     * condition on top of the trigger, so this reads more of them than any
     * single evolution actually uses -- most clauses will be empty for a
     * given entry, which is expected.
     */
    private fun formatCondition(d: JSONObject): String {
        val clauses = mutableListOf<String>()
        val trigger = d.optJSONObject("trigger")?.optString("name").orEmpty()

        when (trigger) {
            "level-up" -> {
                val level = d.optInt("min_level", -1)
                if (level > 0) clauses += "Level $level"
            }

            "trade" -> {
                clauses += "Trade"
                d.optJSONObject("trade_species")?.optString("name")
                    ?.let { clauses += "for ${humanize(it)}" }
            }

            "use-item" -> {
                d.optJSONObject("item")?.optString("name")?.let { clauses += "Use ${humanize(it)}" }
            }

            // Anything else (three-critical-hits, take-damage, spin, the
            // Legends: Arceus move-style triggers, ...) has no bespoke
            // wording here, so the trigger's own name stands in.
            else -> if (trigger.isNotEmpty()) clauses += humanize(trigger)
        }

        d.optJSONObject("held_item")?.optString("name")?.let { clauses += "holding ${humanize(it)}" }
        d.optString("time_of_day").takeIf { it.isNotBlank() }?.let { clauses += "during ${humanize(it)}" }
        if (d.optInt("min_happiness", -1) > 0) clauses += "high friendship"
        if (d.optInt("min_beauty", -1) > 0) clauses += "high beauty"
        if (d.optInt("min_affection", -1) > 0) clauses += "high affection"
        d.optJSONObject("known_move")?.optString("name")?.let { clauses += "knows ${humanize(it)}" }
        d.optJSONObject("known_move_type")?.optString("name")
            ?.let { clauses += "knows a ${humanize(it)}-type move" }
        d.optJSONObject("location")?.optString("name")?.let { clauses += "at ${humanize(it)}" }
        d.optJSONObject("party_species")?.optString("name")?.let { clauses += "with ${humanize(it)} in party" }
        d.optJSONObject("party_type")?.optString("name")
            ?.let { clauses += "with a ${humanize(it)}-type Pokemon in party" }
        if (d.optBoolean("needs_overworld_rain")) clauses += "while raining"
        if (d.optBoolean("turn_upside_down")) clauses += "console upside down"
        when (if (d.isNull("relative_physical_stats")) null else d.optInt("relative_physical_stats")) {
            1 -> clauses += "Attack > Defense"
            -1 -> clauses += "Attack < Defense"
            0 -> clauses += "Attack = Defense"
        }

        return clauses.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "special condition"
    }

    /** PokeAPI slug -> display text: hyphens to spaces, each word capitalised. */
    private fun humanize(slug: String): String =
        slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /**
     * Pulls the most useful English text out of a PokeAPI entry.
     *
     * Prefers effect_entries.short_effect (one or two sentences, written to
     * explain the mechanic) and falls back to the longer effect, then to
     * flavor_text_entries for the entries that carry no effect text at all.
     * Filtering on language matters: entries ship every translation in one
     * array and English is not reliably first -- an item lookup came back
     * French-first during testing.
     */
    private fun parseDescription(root: JSONObject): String? {
        val effects = root.optJSONArray("effect_entries")
        if (effects != null) {
            for (i in 0 until effects.length()) {
                val entry = effects.optJSONObject(i) ?: continue
                if (entry.optJSONObject("language")?.optString("name") != EN) continue
                val text = entry.optString("short_effect").ifBlank { entry.optString("effect") }
                if (text.isNotBlank()) return normalise(substituteChance(text, root))
            }
        }

        val flavor = root.optJSONArray("flavor_text_entries")
        if (flavor != null) {
            // Walked backwards so the newest game's wording wins -- the array is
            // ordered oldest-first, and older entries are terser and likelier to
            // describe pre-Gen-6 behaviour.
            for (i in flavor.length() - 1 downTo 0) {
                val entry = flavor.optJSONObject(i) ?: continue
                if (entry.optJSONObject("language")?.optString("name") != EN) continue
                val text = entry.optString("flavor_text").ifBlank { entry.optString("text") }
                if (text.isNotBlank()) return normalise(text)
            }
        }
        return null
    }

    /** Moves write their proc rate as a "$effect_chance" placeholder in the text. */
    private fun substituteChance(text: String, root: JSONObject): String {
        if (!text.contains(EFFECT_CHANCE)) return text
        val chance = root.opt("effect_chance") as? Int ?: return text.replace(EFFECT_CHANCE, "a")
        return text.replace(EFFECT_CHANCE, chance.toString())
    }

    /** Collapses the newlines and form feeds PokeAPI text is wrapped with. */
    private fun normalise(text: String): String =
        text.replace('\u000C', ' ').replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * ROM display name -> PokeAPI slug: accents folded, lowercase, punctuation
     * dropped, spaces hyphenated. Covers the awkward real names --
     * "Farfetch'd" -> "farfetchd", "Mr. Mime" -> "mr-mime",
     * "King's Rock" -> "kings-rock", "Poké Ball" -> "poke-ball".
     *
     * The accent fold is load-bearing, not decorative: Gen 3 stores the e-acute
     * in "Poké Ball" as its own byte, so simply stripping non-ASCII would
     * yield "pok-ball" and 404 on an item the player is very likely to be
     * holding. NFD splits the accent into a separate combining mark, which the
     * character-class filter below then drops, leaving the bare letter.
     */
    private fun slugify(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
            .lowercase()
            .replace(Regex("[^a-z0-9 -]"), "")
            .replace(Regex("[ _]+"), "-")
            .trim('-')

    private fun cacheFile(key: String) = File(diskCache, "$key.txt")

    private fun readDisk(key: String): DexResult? = try {
        val file = cacheFile(key)
        if (!file.exists()) {
            null
        } else {
            val content = file.readText()
            when {
                content.startsWith(MARKER_NOT_FOUND) -> DexResult.NotFound
                content.startsWith(MARKER_FOUND) -> DexResult.Found(content.removePrefix(MARKER_FOUND))
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeDisk(key: String, result: DexResult) {
        try {
            val content = when (result) {
                is DexResult.Found -> MARKER_FOUND + result.text
                DexResult.NotFound -> MARKER_NOT_FOUND
                is DexResult.Error -> return
            }
            cacheFile(key).writeText(content)
        } catch (e: Exception) {
            // A cache that cannot be written is not worth failing a lookup over;
            // the in-memory tier still covers the rest of the session.
        }
    }

    private companion object {
        const val EN = "en"
        const val EFFECT_CHANCE = "\$effect_chance"
        const val MARKER_FOUND = "1\n"
        const val MARKER_NOT_FOUND = "0"
    }
}
