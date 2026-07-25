package com.gbapal.companion.memory

import android.content.Context
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

/** Party layout: base address + stride, so slot N = firstSlotAddress + N * slotStride. */
data class PartyLayout(
    val firstSlotAddress: Int,
    val slotStride: Int,
    val slotCount: Int,
    val confidence: String,
    val note: String? = null,
)

data class MemoryMap(
    val unboundVersion: String,
    val baseGame: String,
    val party: PartyLayout,
    val enemyParty: PartyLayout,
    val overworldObjects: PartyLayout,
    val scriptVars: PartyLayout,
    val anchors: List<Anchor>,
    /**
     * Per-species front-sprite pointer table: address for species N is
     * firstSlotAddress + N * slotStride. Each 8-byte slot is
     * [4-byte little-endian ROM pointer to LZ77-compressed tile data]
     * [u16 decompressed size][u16 tag]. Null until discovered for a profile.
     */
    val frontSpriteTable: PartyLayout? = null,
) {
    companion object {
        private fun parseLayout(obj: JSONObject): PartyLayout = PartyLayout(
            firstSlotAddress = obj.getString("firstSlotAddress").parseHex(),
            slotStride = obj.getInt("slotStride"),
            slotCount = obj.optInt("slotCount", 0),
            confidence = obj.getString("confidence"),
            note = if (obj.has("note")) obj.getString("note") else null,
        )

        /** Loads a bundled per-game memory map, e.g. "game_profiles/unbound.json". */
        fun load(context: Context, assetPath: String): MemoryMap {
            val json = context.assets.open(assetPath)
                .bufferedReader().use { it.readText() }
            val root = JSONObject(json)

            val party = parseLayout(root.getJSONObject("party"))
            val enemyParty = parseLayout(root.getJSONObject("enemyParty"))
            val overworldObjects = parseLayout(root.getJSONObject("overworldObjects"))
            val scriptVars = parseLayout(root.getJSONObject("scriptVars"))
            val frontSpriteTable = root.optJSONObject("frontSpriteTable")?.let { parseLayout(it) }

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
                unboundVersion = root.getString("unboundVersion"),
                baseGame = root.getString("baseGame"),
                party = party,
                enemyParty = enemyParty,
                overworldObjects = overworldObjects,
                scriptVars = scriptVars,
                anchors = anchors,
                frontSpriteTable = frontSpriteTable,
            )
        }
    }
}

/** Parses "0x02024284" or "02024284" into an Int. */
fun String.parseHex(): Int = removePrefix("0x").toLong(16).toInt()
