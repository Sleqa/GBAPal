package com.gbapal.companion.pokemon

/**
 * Reads species/item/experience/moves/nickname/OT-name from a party Pokemon
 * struct at 0x02024284 in Pokemon Unbound.
 *
 * This is NOT standard Gen 3 encryption. Vanilla FireRed encrypts+shuffles the
 * 48-byte substructure block at 0x20-0x4F (personality-dependent order, XOR key
 * = personality^otId). We implemented that in full, verified the shuffle table
 * against PKHeX's real source, and it STILL produced garbage species/moves.
 *
 * Root cause, confirmed on real hardware 2026-07-22: species at raw offset 0x20
 * (no decryption, no reordering) is correct for every party member regardless
 * of personality value. CFRU (Unbound's engine base) has its own `struct
 * Pokemon` type for the live in-RAM party -- distinct from the save-file
 * `struct BoxPokemon` -- with species/item/experience/moves/pp stored in
 * PLAINTEXT at the classic fixed offsets (Growth always at 0x20, Attacks
 * always at 0x2C, etc), never encrypted or shuffled. Our "decryption" was
 * corrupting already-plain data by XORing it with a key that doesn't apply
 * here. This file just reads the fields directly.
 */
object Gen3Decrypt {
    /** Offset of the first of 4 consecutive 1-byte PP fields (one per move slot). */
    internal const val OFF_PP = 0x34

    data class Decoded(
        val nickname: String,
        val otName: String,
        val speciesId: Int,
        val heldItemId: Int,
        val experience: Long,
        val friendship: Int,
        val moves: IntArray,
        val pp: IntArray,
        val hiddenAbilityFlag: Boolean,
    )

    /**
     * [struct] must be at least 0x4C bytes -- through the Misc substructure's IV/flags
     * word at 0x48, which packs the 6 IVs plus isEgg/hiddenAbility as the top two bits
     * (per CFRU's `struct Pokemon` in include/pokemon.h). hiddenAbilityFlag is bit 31.
     */
    fun decode(struct: ByteArray): Decoded? {
        if (struct.size < 0x4C) return null

        return Decoded(
            nickname = Gen3Text.decode(struct.copyOfRange(0x08, 0x08 + 10)),
            otName = Gen3Text.decode(struct.copyOfRange(0x14, 0x14 + 7)),
            speciesId = struct.u16(0x20),
            heldItemId = struct.u16(0x22),
            experience = struct.u32(0x24),
            friendship = struct[0x29].toInt() and 0xFF,
            moves = intArrayOf(struct.u16(0x2C), struct.u16(0x2E), struct.u16(0x30), struct.u16(0x32)),
            pp = intArrayOf(
                struct[OFF_PP].toInt() and 0xFF, struct[OFF_PP + 1].toInt() and 0xFF,
                struct[OFF_PP + 2].toInt() and 0xFF, struct[OFF_PP + 3].toInt() and 0xFF,
            ),
            hiddenAbilityFlag = (struct.u32(0x48) and (1L shl 31)) != 0L,
        )
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
}
