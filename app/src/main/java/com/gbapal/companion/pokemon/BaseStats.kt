package com.gbapal.companion.pokemon

import android.content.Context
import org.json.JSONObject

/**
 * Per-species base stats/types/abilities, sourced from Dynamic Pokemon
 * Expansion's Base_Stats.c (gBaseStats[]). Used to derive a party Pokemon's
 * actual ability, which Gen 3 (and CFRU) does NOT store as a raw field --
 * see [abilityIdFor].
 */
class BaseStats private constructor(private val bySpecies: Map<Int, Entry>) {
    data class Entry(
        val hp: Int,
        val attack: Int,
        val defense: Int,
        val spAttack: Int,
        val spDefense: Int,
        val speed: Int,
        val type1: String?,
        val type2: String?,
        val ability1: Int,
        val ability2: Int,
        val hiddenAbility: Int,
    )

    fun entry(speciesId: Int): Entry? = bySpecies[speciesId]

    /**
     * Mirrors CFRU's GetMonAbility() (src/build_pokemon.c): the hidden-ability
     * flag wins if the species actually has one, otherwise personality bit 0
     * picks ability1 vs ability2 (falling back to ability1 if there's no
     * ability2).
     */
    fun abilityIdFor(speciesId: Int, personality: Long, hiddenAbilityFlag: Boolean): Int {
        val base = bySpecies[speciesId] ?: return 0
        if (hiddenAbilityFlag && base.hiddenAbility != 0) return base.hiddenAbility
        return if ((personality and 1L) == 0L || base.ability2 == 0) base.ability1 else base.ability2
    }

    companion object {
        fun load(context: Context): BaseStats {
            val json = context.assets.open("base_stats.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<Int, Entry>(obj.length())
            obj.keys().forEach { key ->
                val e = obj.getJSONObject(key)
                map[key.toInt()] = Entry(
                    hp = e.optInt("hp"),
                    attack = e.optInt("attack"),
                    defense = e.optInt("defense"),
                    spAttack = e.optInt("spAttack"),
                    spDefense = e.optInt("spDefense"),
                    speed = e.optInt("speed"),
                    type1 = e.optString("type1").ifEmpty { null },
                    type2 = e.optString("type2").ifEmpty { null },
                    ability1 = e.optInt("ability1"),
                    ability2 = e.optInt("ability2"),
                    hiddenAbility = e.optInt("hiddenAbility"),
                )
            }
            return BaseStats(map)
        }
    }
}
