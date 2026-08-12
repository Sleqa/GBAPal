package com.gbapal.companion.memory

import org.json.JSONObject

/**
 * Where one field sits inside a record.
 *
 * Two addressing modes, because Gen 3 games use both: byte-aligned fields
 * ([offset] + [size], little-endian) and bit-packed ones ([bit] + [width]).
 * pokeemerald-expansion packs a move's power, type and category into shared
 * words, while classic tables give each its own byte.
 */
data class FieldSpec(
    val offset: Int = 0,
    val size: Int = 1,
    val bit: Int? = null,
    val width: Int? = null,
) {
    companion object {
        fun parse(obj: JSONObject): FieldSpec = FieldSpec(
            offset = obj.optHexOrInt("offset", 0),
            size = obj.optInt("size", 1),
            bit = if (obj.has("bit")) obj.optHexOrInt("bit", 0) else null,
            width = if (obj.has("width")) obj.getInt("width") else null,
        )
    }
}

/** How to get a display name out of a record. */
enum class TextSource {
    /** The characters live inside the record itself, at a fixed offset. */
    INLINE,

    /**
     * The record holds a 4-byte ROM pointer to the string, which lives in a
     * shared pool elsewhere. How decomp-based games store their names.
     */
    POINTER,
}

data class TextSpec(
    val via: TextSource,
    val offset: Int = 0,
    val length: Int = 12,
)

/**
 * A table of fixed-size records somewhere in the ROM, described well enough
 * that the app can read it without knowing which game built it.
 *
 * This is the whole point of the profile format: a new ROM hack is a few
 * hundred bytes of addresses and field offsets, not a bundled copy of its
 * Pokemon data. Adding a game costs no app code and no meaningful download
 * size, and an engine nobody has seen yet is still expressible as long as its
 * records are fixed-stride -- which every Gen 3 base and every decomp fork of
 * one has been so far.
 */
data class DataTable(
    val address: Int,
    val stride: Int,
    val count: Int,
    val fields: Map<String, FieldSpec> = emptyMap(),
    val text: TextSpec? = null,
) {
    fun recordAddress(id: Int): Int = address + id * stride

    companion object {
        fun parse(obj: JSONObject, presets: Map<String, Map<String, FieldSpec>>): DataTable {
            // A named layout supplies the usual field offsets for a known
            // record shape; anything in "fields" overrides it. A profile can
            // therefore describe a brand-new layout with no preset at all.
            val preset = obj.optString("layout").takeIf { it.isNotEmpty() }?.let { presets[it] }
            val overrides = obj.optJSONObject("fields")?.let { fieldsObj ->
                fieldsObj.keys().asSequence().associateWith { key ->
                    FieldSpec.parse(fieldsObj.getJSONObject(key))
                }
            } ?: emptyMap()

            val text = obj.optJSONObject("text")?.let { textObj ->
                TextSpec(
                    via = if (textObj.optString("via") == "pointer") TextSource.POINTER else TextSource.INLINE,
                    offset = textObj.optHexOrInt("offset", 0),
                    length = textObj.optInt("length", 12),
                )
            }

            return DataTable(
                address = obj.getString("address").parseHex(),
                stride = obj.getInt("stride"),
                count = obj.optInt("count", 0),
                fields = (preset ?: emptyMap()) + overrides,
                text = text,
            )
        }
    }
}

/**
 * Reads a JSON number that may be written either as a hex string ("0x2C") or a
 * plain integer. Addresses and offsets read far better as hex in a profile,
 * but requiring it everywhere would be fussy.
 */
internal fun JSONObject.optHexOrInt(key: String, fallback: Int): Int {
    if (!has(key)) return fallback
    val raw = get(key)
    return if (raw is String) raw.parseHex() else optInt(key, fallback)
}
