package com.gbapal.companion.pokemon

import android.content.Context
import org.json.JSONObject

/** Per-move power/accuracy/max PP/type/category, sourced from CFRU's battle_moves.c. */
class MoveData private constructor(private val byMove: Map<Int, Entry>) {
    data class Entry(
        val power: Int,
        val accuracy: Int,
        val ppMax: Int,
        val type: String?,
        val category: String,
    )

    fun entry(moveId: Int): Entry? = byMove[moveId]
    fun ppMax(moveId: Int): Int = byMove[moveId]?.ppMax ?: 0
    fun type(moveId: Int): String = byMove[moveId]?.type ?: "Normal"
    fun category(moveId: Int): String = byMove[moveId]?.category ?: "Status"

    companion object {
        fun load(context: Context): MoveData {
            val json = context.assets.open("move_data.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<Int, Entry>(obj.length())
            obj.keys().forEach { key ->
                val e = obj.getJSONObject(key)
                map[key.toInt()] = Entry(
                    power = e.optInt("power"),
                    accuracy = e.optInt("accuracy"),
                    ppMax = e.optInt("pp"),
                    type = e.optString("type").ifEmpty { null },
                    category = e.optString("category", "Status"),
                )
            }
            return MoveData(map)
        }
    }
}
