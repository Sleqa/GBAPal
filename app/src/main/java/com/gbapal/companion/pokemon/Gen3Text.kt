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
