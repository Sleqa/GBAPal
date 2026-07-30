package com.gbapal.companion.pokemon

import android.content.Context
import org.json.JSONObject

/**
 * Species/move ID -> display name lookup, sourced from Dynamic Pokemon Expansion's
 * species.h/moves.h (github.com/DizzyEggg/Dynamic-Pokemon-Expansion, by Pokemon
 * Unbound's own author). Covers vanilla FireRed IDs plus DPE's large custom
 * expansion range (species up to ~1267, moves up to ~898) -- Unbound likely reuses
 * this same table for its own added Pokemon/moves, though that specific mapping is
 * NOT independently confirmed. IDs outside this table, or where a decoded ID doesn't
 * match what a live-verified move/species check would show, may be Unbound-specific
 * remaps this table doesn't know about.
 */
class NameTables private constructor(
    private val species: Map<Int, String>,
    private val moves: Map<Int, String>,
    private val items: Map<Int, String>,
    private val abilities: Map<Int, String>,
) {
    fun speciesName(id: Int): String = species[id] ?: "Unknown #$id"
    fun moveName(id: Int): String = moves[id] ?: "Unknown #$id"
    fun itemName(id: Int): String = if (id == 0) "None" else items[id] ?: "Unknown #$id"
    fun abilityName(id: Int): String = abilities[id] ?: "Unknown #$id"

    companion object {
        fun load(context: Context): NameTables {
            val species = loadTable(context, "species_names.json")
            val moves = loadTable(context, "move_names.json")
            val items = loadTable(context, "item_names.json")
            val abilities = loadTable(context, "ability_names.json")
            return NameTables(species, moves, items, abilities)
        }

        private fun loadTable(context: Context, assetName: String): Map<Int, String> {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<Int, String>(obj.length())
            obj.keys().forEach { key -> map[key.toInt()] = obj.getString(key) }
            return map
        }
    }
}
