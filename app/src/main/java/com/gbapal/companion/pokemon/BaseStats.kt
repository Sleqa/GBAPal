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
    ) {
        /**
         * Mirrors CFRU's GetMonAbility() (src/build_pokemon.c): the hidden-ability
         * flag wins if the species actually has one, otherwise personality bit 0
         * picks ability1 vs ability2 (falling back to ability1 if there's no
         * ability2).
         *
         * [abilityNum] is the slot the Pokemon actually has stored, when the party
         * format records one (see Gen3Decrypt.Decoded.abilityNum). It takes
         * precedence over personality parity, which is only how the game *picks* a
         * slot at creation time -- breeding and in-game ability changes can leave
         * the stored slot disagreeing with the personality.
         */
        fun abilityId(personality: Long, hiddenAbilityFlag: Boolean, abilityNum: Int? = null): Int {
            if (hiddenAbilityFlag && hiddenAbility != 0) return hiddenAbility
            val secondSlot = abilityNum?.let { it != 0 } ?: ((personality and 1L) != 0L)
            return if (!secondSlot || ability2 == 0) ability1 else ability2
        }
    }

    fun entry(speciesId: Int): Entry? = bySpecies[speciesId]

    /** @see Entry.abilityId */
    fun abilityIdFor(
        speciesId: Int,
        personality: Long,
        hiddenAbilityFlag: Boolean,
        abilityNum: Int? = null,
    ): Int = bySpecies[speciesId]?.abilityId(personality, hiddenAbilityFlag, abilityNum) ?: 0

    companion object {
        /** Shared fallback table; per-game data comes from the ROM via GameData. */
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
