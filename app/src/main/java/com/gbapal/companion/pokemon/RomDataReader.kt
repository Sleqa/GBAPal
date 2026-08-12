package com.gbapal.companion.pokemon

import com.gbapal.companion.memory.DataTable
import com.gbapal.companion.memory.FieldSpec
import com.gbapal.companion.memory.TextSource
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse

private const val ROM_START = 0x08000000
private const val ROM_END = 0x09FFFFFF

/**
 * Reads records out of ROM tables described by a profile's [DataTable]s.
 *
 * Knows nothing about which game it is reading -- it follows the descriptor.
 * That is what lets a new ROM hack ship as a profile with no app changes, and
 * why the app no longer bundles a copy of each game's Pokemon data.
 *
 * Every record and string is cached once successfully read. Game data is
 * immutable for a given ROM, so the cache never needs invalidating within a
 * session, and a party poll after the first costs no reads at all. A failed
 * read is not cached -- see [record] -- so a dropped reply just costs a retry
 * on the next poll rather than a permanently wrong answer. One reader belongs
 * to one profile; swap profiles, build a new reader.
 */
class RomDataReader(
    private val client: RetroArchClient,
    private val tables: Map<String, DataTable>,
) {
    private val records = HashMap<Long, ByteArray?>()
    private val strings = HashMap<Long, String?>()

    fun has(table: String): Boolean = tables.containsKey(table)

    fun table(table: String): DataTable? = tables[table]

    /** Number of entries [table] holds, or 0 if this profile does not describe it. */
    fun count(table: String): Int = tables[table]?.count ?: 0

    private fun cacheKey(table: String, id: Int): Long =
        (table.hashCode().toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)

    private suspend fun read(address: Int, length: Int): ByteArray? {
        val result = client.readCoreMemory(address, length)
        return (result as? RetroArchClient.Result.Success)
            ?.let { parseReadCoreMemoryResponse(it.response) }
            ?.takeIf { it.size >= length }
    }

    /**
     * The raw bytes of one record, or null if unreadable / not described.
     *
     * Only a successful read is cached. A null here is usually a transient
     * network hiccup rather than "this record doesn't exist" -- the table
     * and id were already validated by the caller -- so caching it would
     * permanently poison that id for the rest of the session on nothing more
     * than one dropped UDP reply, silently falling back to bundled data
     * forever instead of getting it right on the next poll.
     */
    suspend fun record(table: String, id: Int): ByteArray? {
        val descriptor = tables[table] ?: return null
        if (id < 0) return null
        val key = cacheKey(table, id)
        records[key]?.let { return it }

        val bytes = read(descriptor.recordAddress(id), descriptor.stride)
        if (bytes != null) records[key] = bytes
        return bytes
    }

    /**
     * One record's display name, following a pointer into the shared string
     * pool when the descriptor says the name is stored that way.
     */
    suspend fun text(table: String, id: Int): String? {
        val descriptor = tables[table] ?: return null
        val spec = descriptor.text ?: return null
        val key = cacheKey(table, id)
        strings[key]?.let { return it }

        val decoded = when (spec.via) {
            TextSource.INLINE -> {
                val bytes = record(table, id)
                bytes?.let {
                    val end = minOf(spec.offset + spec.length, it.size)
                    if (spec.offset >= end) null
                    else Gen3Text.decode(it.copyOfRange(spec.offset, end)).ifEmpty { null }
                }
            }

            TextSource.POINTER -> {
                val bytes = record(table, id)
                val pointer = bytes?.takeIf { spec.offset + 4 <= it.size }?.let { intAt(it, spec.offset) }
                if (pointer == null || pointer !in ROM_START..ROM_END) {
                    null
                } else {
                    read(pointer, spec.length)?.let { Gen3Text.decode(it).ifEmpty { null } }
                }
            }
        }
        if (decoded != null) strings[key] = decoded
        return decoded
    }

    /** Reads a named field out of an already-fetched record. */
    fun field(record: ByteArray, spec: FieldSpec?): Int? {
        if (spec == null) return null
        if (spec.bit != null && spec.width != null) return bits(record, spec.bit, spec.width)
        if (spec.offset + spec.size > record.size) return null
        var value = 0
        for (i in 0 until spec.size) {
            value = value or ((record[spec.offset + i].toInt() and 0xFF) shl (i * 8))
        }
        return value
    }

    /** Reads a named field by looking its spec up in the table's layout. */
    fun field(table: String, record: ByteArray, name: String): Int? =
        field(record, tables[table]?.fields?.get(name))

    private fun bits(record: ByteArray, bit: Int, width: Int): Int? {
        var value = 0
        for (i in 0 until width) {
            val index = (bit + i) / 8
            if (index >= record.size) return null
            val set = (record[index].toInt() shr ((bit + i) % 8)) and 1
            value = value or (set shl i)
        }
        return value
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
