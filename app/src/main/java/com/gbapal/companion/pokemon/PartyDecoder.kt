package com.gbapal.companion.pokemon

/**
 * Decodes the UNENCRYPTED fields of a Gen 3 party Pokemon struct (100 bytes).
 *
 * Level, HP, and battle stats live at fixed offsets 0x54-0x63 in plaintext —
 * the encrypted 48-byte block (species, moves, IVs/EVs) at 0x20 is NOT touched
 * here. This is deliberately just enough to verify the party address is correct
 * for Unbound: read a slot, and if the level/HP match your actual party, the
 * anchor is confirmed. Full substructure decryption comes later.
 */
data class PartySlot(
    val personality: Long,
    val otId: Long,
    val level: Int,
    val currentHp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val speed: Int,
    val spAttack: Int,
    val spDefense: Int,
) {
    /** True if this looks like a real occupied slot (vs empty/garbage memory). */
    val looksValid: Boolean
        get() = personality != 0L && level in 1..100 && maxHp in 1..999 && currentHp <= maxHp

    val summary: String
        get() = if (looksValid) {
            "Lv $level  HP $currentHp/$maxHp  (Atk $attack Def $defense Spe $speed SpA $spAttack SpD $spDefense)"
        } else {
            "empty / invalid"
        }
}

object PartyDecoder {
    // Offsets within the 100-byte struct.
    private const val OFF_PERSONALITY = 0
    private const val OFF_OT_ID = 4
    // Standard Gen 3 status condition bitfield (sleep/poison/burn/freeze/paralysis),
    // stored as a plain u32 immediately before the encrypted substructures -- not
    // itself part of the encrypted block, so a zero write here clears it directly.
    internal const val OFF_STATUS = 0x50
    private const val OFF_LEVEL = 0x54
    internal const val OFF_CUR_HP = 0x56
    private const val OFF_MAX_HP = 0x58
    private const val OFF_ATTACK = 0x5A
    private const val OFF_DEFENSE = 0x5C
    private const val OFF_SPEED = 0x5E
    private const val OFF_SP_ATTACK = 0x60
    private const val OFF_SP_DEFENSE = 0x62

    /** [bytes] must be a 100-byte party slot. Returns null if too short. */
    fun decode(bytes: ByteArray): PartySlot? {
        if (bytes.size < 100) return null
        return PartySlot(
            personality = bytes.u32(OFF_PERSONALITY),
            otId = bytes.u32(OFF_OT_ID),
            level = bytes[OFF_LEVEL].toInt() and 0xFF,
            currentHp = bytes.u16(OFF_CUR_HP),
            maxHp = bytes.u16(OFF_MAX_HP),
            attack = bytes.u16(OFF_ATTACK),
            defense = bytes.u16(OFF_DEFENSE),
            speed = bytes.u16(OFF_SPEED),
            spAttack = bytes.u16(OFF_SP_ATTACK),
            spDefense = bytes.u16(OFF_SP_DEFENSE),
        )
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        (u16(offset).toLong()) or (u16(offset + 2).toLong() shl 16)
}
