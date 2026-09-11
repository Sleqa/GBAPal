package com.gbapal.companion.pokemon

/**
 * Reads species/item/moves/nickname and friends out of a 100-byte Gen 3 party
 * Pokemon struct, in either of the two layouts real games use.
 *
 * Both layouts hold the same four 12-byte substructures -- Growth, Attacks,
 * EVs/Condition, Misc -- in the 48 bytes at 0x20-0x4F. They differ in whether
 * that block is protected:
 *
 * * [PartyFormat.PLAINTEXT] -- CFRU-based hacks (Pokemon Unbound). CFRU has its
 *   own in-RAM `struct Pokemon` that keeps the substructures in plain sight at
 *   fixed offsets: Growth always at 0x20, Attacks always at 0x2C. Confirmed on
 *   real hardware 2026-07-22, after a full standard-decryption implementation
 *   produced garbage here precisely because it was XORing already-plain data.
 * * [PartyFormat.ENCRYPTED] -- vanilla Gen 3 and pokeemerald-expansion builds
 *   (Emerald Imperium). The block is XORed with personality^otId and the four
 *   substructures are permuted into one of 24 orders chosen by personality % 24.
 *
 * Which one a ROM uses is detected from the data rather than configured, using
 * the checksum at 0x1C: it is the sum of the 24 halfwords of the *decrypted*
 * block, so a successful decrypt proves itself. See [detectFormat].
 */
object Gen3Decrypt {

    enum class PartyFormat { PLAINTEXT, ENCRYPTED }

    /** Where the substructure block starts, and how big it is. */
    private const val SUBSTRUCT_BASE = 0x20
    private const val SUBSTRUCT_SIZE = 48
    private const val SUBSTRUCT_COUNT = 4
    private const val SUBSTRUCT_LEN = 12

    private const val OFF_PERSONALITY = 0x00
    private const val OFF_OT_ID = 0x04
    private const val OFF_CHECKSUM = 0x1C

    /**
     * Offset of the PP bytes within a PLAINTEXT struct. Only meaningful for
     * that format -- on an encrypted struct the same bytes are ciphertext, so
     * writing PP there would corrupt the Pokemon. Use [buildPpWrite] instead of
     * this constant when writing.
     */
    internal const val OFF_PP = 0x34

    /**
     * pokeemerald-expansion declares Growth's species field as `u16 species:11`
     * and packs a second 5-bit field into the top of the same halfword -- a
     * nature override, 0 meaning "no override, use the one the personality
     * implies". Confirmed live in Emerald Imperium on 2026-08-22: three mons
     * whose nature had been changed read 27913, 6965 and 26895 where the plain
     * species is 1289 (Sprigatito), 821 (Rookidee) and 271 (Lombre), while two
     * untouched mons read their species with the top bits clear -- and those
     * two mons' stat spreads match their personality's nature, which is what
     * proves 0 means "unset" rather than Hardy.
     *
     * Masking matters far more than just getting the name right, because
     * [detectFormat] rejects an implausible species: left unmasked, one changed
     * nature made a correctly-encrypted mon look like a failed decrypt, and the
     * whole slot fell back to reading ciphertext as plaintext.
     *
     * Only applied to [PartyFormat.ENCRYPTED]. Vanilla Gen 3 tops out around
     * species 411 and expansion at 2047, so the mask never truncates a real id
     * there; CFRU's plaintext structs keep a full u16 and are left alone.
     */
    private const val SPECIES_MASK = 0x7FF

    // Offsets of each substructure once the block is in canonical G/A/E/M order.
    private const val GROWTH = 0
    private const val ATTACKS = 12
    private const val MISC = 36

    /**
     * Where pokeemerald-expansion keeps `abilityNum`: two bits at bit 29 of the
     * word at Misc+8, which vanilla Gen 3 used entirely for ribbons and
     * fatefulEncounter. 0 and 1 are the species' two normal slots, 2 its hidden
     * one.
     *
     * Vanilla Gen 3 instead stored a single ability bit at bit 31 of the IV
     * word, which is what this used to read -- wrong for every expansion ROM,
     * and the reason a Lombre with Rain Dish showed up as Swift Swim. Pinned
     * live on 2026-08-22 against exactly that Lombre: it was the only party
     * member with anything set here, reading 0x20000000, and bit 29 is the only
     * offset that turns that into its true slot (bit 28 would say Own Tempo).
     * Every supported ENCRYPTED profile is an expansion build, not true vanilla.
     */
    private const val ABILITY_NUM_WORD = MISC + 8
    private const val ABILITY_NUM_SHIFT = 29
    private const val ABILITY_NUM_MASK = 0x3L

    /**
     * The 24 substructure permutations, indexed by personality % 24. Each entry
     * lists which substructure occupies physical slot 0..3, where
     * G=Growth, A=Attacks, E=EVs/Condition, M=Misc. This is the standard Gen 3
     * table (the 24 orderings in lexicographic order over G<A<E<M).
     */
    private val ORDERS = arrayOf(
        "GAEM", "GAME", "GEAM", "GEMA", "GMAE", "GMEA",
        "AGEM", "AGME", "AEGM", "AEMG", "AMGE", "AMEG",
        "EGAM", "EGMA", "EAGM", "EAMG", "EMGA", "EMAG",
        "MGAE", "MGEA", "MAGE", "MAEG", "MEGA", "MEAG",
    )

    private fun substructIndex(kind: Char): Int = when (kind) {
        'G' -> 0
        'A' -> 1
        'E' -> 2
        else -> 3
    }

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
        /**
         * Which ability slot this Pokemon actually uses -- 0 or 1 for the two
         * normal ones, 2 for its hidden one -- or null when the format does not
         * record it. See [ABILITY_NUM_WORD] for where it lives. Prefer it over
         * deriving the slot from personality parity, since breeding, ability
         * capsules and hidden abilities all leave the two disagreeing.
         */
        val abilityNum: Int?,
        val format: PartyFormat,
    )

    /** A single byte-range write, relative to the start of the party slot. */
    data class Write(val offset: Int, val bytes: ByteArray)

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        putU16(offset, (value and 0xFFFF).toInt())
        putU16(offset + 2, ((value shr 16) and 0xFFFF).toInt())
    }

    /** Sum of the block's halfwords, truncated to 16 bits -- the Gen 3 checksum. */
    private fun checksumOf(canonical: ByteArray): Int {
        var sum = 0
        for (i in 0 until SUBSTRUCT_SIZE step 2) sum += canonical.u16(i)
        return sum and 0xFFFF
    }

    /**
     * XORs a 48-byte substructure block with [key], one 32-bit word at a time.
     * XOR is its own inverse, so this is both the encrypt and the decrypt step
     * -- only the permutation direction around it differs.
     */
    private fun xorSubstructBlock(source: ByteArray, sourceOffset: Int, key: Long): ByteArray {
        val out = ByteArray(SUBSTRUCT_SIZE)
        for (i in 0 until SUBSTRUCT_SIZE step 4) {
            out.putU32(i, source.u32(sourceOffset + i) xor key)
        }
        return out
    }

    /**
     * Decrypts and un-permutes the substructure block into canonical
     * G/A/E/M order. Returns null if [struct] is too short.
     */
    private fun decryptCanonical(struct: ByteArray): ByteArray? {
        if (struct.size < SUBSTRUCT_BASE + SUBSTRUCT_SIZE) return null
        val personality = struct.u32(OFF_PERSONALITY)
        val key = personality xor struct.u32(OFF_OT_ID)
        val plain = xorSubstructBlock(struct, SUBSTRUCT_BASE, key)

        // Undo the permutation: physical slot k holds substructure ORDERS[o][k].
        val order = ORDERS[(personality % 24).toInt()]
        val canonical = ByteArray(SUBSTRUCT_SIZE)
        for (slot in 0 until SUBSTRUCT_COUNT) {
            val target = substructIndex(order[slot]) * SUBSTRUCT_LEN
            plain.copyInto(canonical, target, slot * SUBSTRUCT_LEN, (slot + 1) * SUBSTRUCT_LEN)
        }
        return canonical
    }

    /** Re-applies the permutation and XOR, turning a canonical block back into stored bytes. */
    private fun encryptCanonical(struct: ByteArray, canonical: ByteArray): ByteArray {
        val personality = struct.u32(OFF_PERSONALITY)
        val key = personality xor struct.u32(OFF_OT_ID)

        val order = ORDERS[(personality % 24).toInt()]
        val permuted = ByteArray(SUBSTRUCT_SIZE)
        for (slot in 0 until SUBSTRUCT_COUNT) {
            val source = substructIndex(order[slot]) * SUBSTRUCT_LEN
            canonical.copyInto(permuted, slot * SUBSTRUCT_LEN, source, source + SUBSTRUCT_LEN)
        }
        return xorSubstructBlock(permuted, 0, key)
    }

    /**
     * Works out which layout [struct] uses.
     *
     * Decrypting and checking the result against the stored checksum is a
     * self-proving test: a wrong guess would have to produce 48 bytes that
     * happen to sum to the right value, roughly a 1-in-65536 accident. The
     * species/move sanity check on top makes a false positive effectively
     * impossible, which matters because mistaking one format for the other
     * shows the player nonsense.
     */
    fun detectFormat(struct: ByteArray): PartyFormat {
        val canonical = decryptCanonical(struct) ?: return PartyFormat.PLAINTEXT
        if (checksumOf(canonical) != struct.u16(OFF_CHECKSUM)) return PartyFormat.PLAINTEXT
        val species = canonical.u16(GROWTH) and SPECIES_MASK
        if (species == 0) return PartyFormat.PLAINTEXT
        for (i in 0 until 4) {
            if (canonical.u16(ATTACKS + i * 2) > 4000) return PartyFormat.PLAINTEXT
        }
        return PartyFormat.ENCRYPTED
    }

    /**
     * Returns the substructure block in canonical order for whichever layout
     * [struct] uses, or null if it is too short.
     */
    private fun canonicalBlock(struct: ByteArray, format: PartyFormat): ByteArray? = when (format) {
        PartyFormat.ENCRYPTED -> decryptCanonical(struct)
        PartyFormat.PLAINTEXT -> {
            if (struct.size < SUBSTRUCT_BASE + SUBSTRUCT_SIZE) null
            else struct.copyOfRange(SUBSTRUCT_BASE, SUBSTRUCT_BASE + SUBSTRUCT_SIZE)
        }
    }

    /**
     * Decodes a party slot. Pass [format] to force a layout; by default it is
     * detected per slot, so a single build reads both CFRU hacks and
     * expansion-based games with no configuration.
     */
    fun decode(struct: ByteArray, format: PartyFormat? = null): Decoded? {
        if (struct.size < SUBSTRUCT_BASE + SUBSTRUCT_SIZE) return null
        val resolved = format ?: detectFormat(struct)
        val block = canonicalBlock(struct, resolved) ?: return null

        val ivWord = block.u32(MISC + 4)
        // Bit 31 means different things per layout: CFRU repurposed it to flag a
        // hidden ability, whereas vanilla Gen 3 uses it for which of the two
        // normal ability slots applies.
        val bit31 = (ivWord and (1L shl 31)) != 0L

        return Decoded(
            nickname = Gen3Text.decode(struct.copyOfRange(0x08, 0x08 + 10)),
            otName = Gen3Text.decode(struct.copyOfRange(0x14, 0x14 + 7)),
            speciesId = when (resolved) {
                PartyFormat.ENCRYPTED -> block.u16(GROWTH) and SPECIES_MASK
                PartyFormat.PLAINTEXT -> block.u16(GROWTH)
            },
            heldItemId = block.u16(GROWTH + 2),
            experience = block.u32(GROWTH + 4),
            friendship = block[GROWTH + 9].toInt() and 0xFF,
            moves = IntArray(4) { block.u16(ATTACKS + it * 2) },
            pp = IntArray(4) { block[ATTACKS + 8 + it].toInt() and 0xFF },
            hiddenAbilityFlag = resolved == PartyFormat.PLAINTEXT && bit31,
            abilityNum = if (resolved == PartyFormat.ENCRYPTED) {
                ((block.u32(ABILITY_NUM_WORD) shr ABILITY_NUM_SHIFT) and ABILITY_NUM_MASK).toInt()
            } else {
                null
            },
            format = resolved,
        )
    }

    /**
     * Builds the writes needed to set a slot's PP, for either layout.
     *
     * On a plaintext struct this is a single four-byte poke. On an encrypted one
     * the whole block has to be re-encrypted *and* the checksum recomputed --
     * a struct whose checksum does not match its contents is what the games
     * turn into a Bad EGG, so writing the plain bytes and hoping is not an
     * option. Returns null if the struct cannot be decoded.
     */
    fun buildPpWrite(struct: ByteArray, pp: IntArray, format: PartyFormat? = null): List<Write>? {
        if (pp.size < 4) return null
        val resolved = format ?: detectFormat(struct)
        val block = canonicalBlock(struct, resolved) ?: return null

        val bytes = ByteArray(4) { pp[it].coerceIn(0, 255).toByte() }
        if (resolved == PartyFormat.PLAINTEXT) {
            return listOf(Write(OFF_PP, bytes))
        }

        bytes.copyInto(block, ATTACKS + 8)
        val reencrypted = encryptCanonical(struct, block)
        val checksum = ByteArray(2).also { it.putU16(0, checksumOf(block)) }
        return listOf(Write(OFF_CHECKSUM, checksum), Write(SUBSTRUCT_BASE, reencrypted))
    }
}
