package com.gbapal.companion.pokemon

import android.content.Context
import com.gbapal.companion.ui.hub.HubMon
import org.json.JSONArray
import org.json.JSONObject

/**
 * One opponent team, kept after the battle that showed it is over.
 *
 * The point is losing: once a trainer beats you, their party is gone from
 * memory and there is nothing left to read, so the only way to study what just
 * happened is to have taken a copy first. That makes this a snapshot rather
 * than a live view -- the numbers are whatever they were at the moment it was
 * saved, HP included, and they never update again.
 *
 * Stored on disk rather than in memory so it survives the app being closed:
 * the team stays until it is explicitly cleared, which is what "saved" has to
 * mean to be worth pressing.
 *
 * Keyed per game profile, so each game keeps its own -- switching from Unbound
 * to Imperium must not show Unbound's saved trainer.
 */
object SavedTeam {

    private const val PREFS = "gbapal_saved_teams"

    fun load(context: Context, profileId: String): List<HubMon>? {
        val json = prefs(context).getString(key(profileId), null) ?: return null
        return runCatching { decode(JSONArray(json)) }
            // A team written by an older build with different fields is worth
            // less than a crash on startup; drop it and offer a fresh save.
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun save(context: Context, profileId: String, mons: List<HubMon>) {
        prefs(context).edit().putString(key(profileId), encode(mons).toString()).apply()
    }

    fun clear(context: Context, profileId: String) {
        prefs(context).edit().remove(key(profileId)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(profileId: String) = "team_$profileId"

    private fun encode(mons: List<HubMon>): JSONArray {
        val array = JSONArray()
        mons.forEach { mon ->
            array.put(
                JSONObject().apply {
                    put("partySlot", mon.partySlot)
                    put("speciesId", mon.speciesId)
                    put("nickname", mon.nickname)
                    put("level", mon.level)
                    put("currentHp", mon.currentHp)
                    put("maxHp", mon.maxHp)
                    put("attack", mon.attack)
                    put("defense", mon.defense)
                    put("spAttack", mon.spAttack)
                    put("spDefense", mon.spDefense)
                    put("speed", mon.speed)
                    put("heldItemId", mon.heldItemId)
                    put("abilityId", mon.abilityId)
                    put("moves", JSONArray(mon.moves))
                    put("pp", JSONArray(mon.pp))
                    // Stored by name rather than ordinal, so reordering the
                    // enum can never turn a saved burn into a freeze.
                    put("status", mon.status?.name)
                },
            )
        }
        return array
    }

    private fun decode(array: JSONArray): List<HubMon> = buildList {
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            add(
                HubMon(
                    partySlot = o.getInt("partySlot"),
                    speciesId = o.getInt("speciesId"),
                    nickname = o.getString("nickname"),
                    level = o.getInt("level"),
                    currentHp = o.getInt("currentHp"),
                    maxHp = o.getInt("maxHp"),
                    attack = o.getInt("attack"),
                    defense = o.getInt("defense"),
                    spAttack = o.getInt("spAttack"),
                    spDefense = o.getInt("spDefense"),
                    speed = o.getInt("speed"),
                    heldItemId = o.getInt("heldItemId"),
                    abilityId = o.getInt("abilityId"),
                    moves = o.getJSONArray("moves").toIntList(),
                    pp = o.getJSONArray("pp").toIntList(),
                    status = o.optString("status").takeIf { it.isNotEmpty() && it != "null" }
                        ?.let { name -> StatusCondition.entries.firstOrNull { it.name == name } },
                ),
            )
        }
    }

    private fun JSONArray.toIntList(): List<Int> = List(length()) { getInt(it) }

    /**
     * Encode then decode, with no Android storage in between, so the field
     * mapping can be tested without an instrumented test.
     */
    internal fun roundTripForTest(mons: List<HubMon>): List<HubMon> =
        decode(JSONArray(encode(mons).toString()))
}
