package com.gbapal.companion.pokemon

/**
 * Decodes Gen 3's proprietary character table (not ASCII) into readable text.
 * 0xFF is the string terminator. Covers the standard international charset
 * (digits, punctuation, upper/lowercase letters) used by trainer/Pokemon names.
 */
object Gen3Text {
    private const val TERMINATOR = 0xFF

    private val table: Map<Int, Char> = buildMap {
        put(0x00, ' ')
        // Accented Latin block, per CFRU/pokeemerald charmap.tbl. Sparse on
        // purpose -- the gaps (0x0A, 0x18, 0x1F, 0x2C) are genuinely unassigned
        // in the charmap. Without these, "POKé BALL" decoded as "POK? BALL",
        // which is both wrong on screen and unusable as a PokeApiClient lookup
        // key, since the name is what a description is fetched by.
        putAll(
            mapOf(
                0x01 to 'À', 0x02 to 'Á', 0x03 to 'Â', 0x04 to 'Ç', 0x05 to 'È',
                0x06 to 'É', 0x07 to 'Ê', 0x08 to 'Ë', 0x09 to 'Ì', 0x0B to 'Î',
                0x0C to 'Ï', 0x0D to 'Ò', 0x0E to 'Ó', 0x0F to 'Ô', 0x10 to 'Œ',
                0x11 to 'Ù', 0x12 to 'Ú', 0x13 to 'Û', 0x14 to 'Ñ', 0x15 to 'ß',
                0x16 to 'à', 0x17 to 'á', 0x19 to 'ç', 0x1A to 'è', 0x1B to 'é',
                0x1C to 'ê', 0x1D to 'ë', 0x1E to 'ì', 0x20 to 'î', 0x21 to 'ï',
                0x22 to 'ò', 0x23 to 'ó', 0x24 to 'ô', 0x25 to 'œ', 0x26 to 'ù',
                0x27 to 'ú', 0x28 to 'û', 0x29 to 'ñ', 0x2A to 'º', 0x2B to 'ª',
                0x2D to '&', 0x2E to '+',
            ),
        )
        ('0'..'9').forEachIndexed { i, c -> put(0xA1 + i, c) }
        put(0xAB, '!')
        put(0xAC, '?')
        put(0xAD, '.')
        put(0xAE, '-')
        put(0xB8, ',')
        put(0xB5, '♂') // ♂
        put(0xB6, '♀') // ♀
        ('A'..'Z').forEachIndexed { i, c -> put(0xBB + i, c) }
        ('a'..'z').forEachIndexed { i, c -> put(0xD5 + i, c) }
    }

    /** Decodes until 0xFF or the end of the array. Unmapped bytes render as '?'. */
    fun decode(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == TERMINATOR) break
            sb.append(table[v] ?: '?')
        }
        return sb.toString()
    }

    /**
     * Rough sanity check for a decoded name: a real Gen 3 name is never more
     * than an occasional unmapped byte. Reading from the wrong address --
     * e.g. [FireRedLiveData] guessing a table location that doesn't hold what
     * it expects on this particular ROM -- decodes mostly-garbage bytes,
     * which renders as a wall of '?'. This lets a caller tell that apart from
     * a real name and treat it as "no name" instead of showing the garbage.
     */
    fun looksLikeName(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.count { it == '?' } <= s.length / 3
    }
}
