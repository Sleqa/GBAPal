package com.gbapal.companion.pokemon

import org.junit.Assert.assertEquals
import org.junit.Test

class Gen3DecryptTest {

    private fun buildStruct(
        nickname: String = "TESTMON",
        otName: String = "OT",
        species: Int,
        heldItem: Int,
        experience: Long,
        friendship: Int,
        moves: IntArray,
        pp: IntArray,
    ): ByteArray {
        // The live CFRU party layout includes the Misc IV/ability-flags word at 0x48,
        // so the decoder requires the struct through byte 0x4B.
        val b = ByteArray(0x4C)
        fun putU16(offset: Int, v: Int) {
            b[offset] = (v and 0xFF).toByte()
            b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun putU32(offset: Int, v: Long) {
            putU16(offset, (v and 0xFFFF).toInt())
            putU16(offset + 2, ((v shr 16) and 0xFFFF).toInt())
        }
        fun putText(offset: Int, text: String) {
            text.forEachIndexed { i, c -> b[offset + i] = (0xBB + (c - 'A')).toByte() }
            b[offset + text.length] = 0xFF.toByte()
        }

        putText(0x08, nickname)
        putText(0x14, otName)
        putU16(0x20, species)
        putU16(0x22, heldItem)
        putU32(0x24, experience)
        b[0x29] = friendship.toByte()
        for (i in 0 until 4) putU16(0x2C + i * 2, moves[i])
        for (i in 0 until 4) b[0x34 + i] = pp[i].toByte()
        return b
    }

    @Test
    fun decodesPlaintextFields() {
        val struct = buildStruct(
            nickname = "GLIGAR",
            otName = "SLEQA",
            species = 207,
            heldItem = 5,
            experience = 12345,
            friendship = 70,
            moves = intArrayOf(1, 2, 3, 4),
            pp = intArrayOf(10, 20, 30, 40),
        )
        val decoded = Gen3Decrypt.decode(struct)!!

        assertEquals("GLIGAR", decoded.nickname)
        assertEquals("SLEQA", decoded.otName)
        assertEquals(207, decoded.speciesId)
        assertEquals(5, decoded.heldItemId)
        assertEquals(12345L, decoded.experience)
        assertEquals(70, decoded.friendship)
        assertEquals(listOf(1, 2, 3, 4), decoded.moves.toList())
        assertEquals(listOf(10, 20, 30, 40), decoded.pp.toList())
    }

    @Test
    fun tooShortReturnsNull() {
        assertEquals(null, Gen3Decrypt.decode(ByteArray(10)))
    }
}
