package com.gbapal.companion.pokemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyDecoderTest {

    private fun buildSlot(
        personality: Long = 0x11223344,
        otId: Long = 0x55667788,
        level: Int = 45,
        curHp: Int = 120,
        maxHp: Int = 130,
        attack: Int = 80,
        defense: Int = 70,
        speed: Int = 90,
        spAttack: Int = 100,
        spDefense: Int = 85,
    ): ByteArray {
        val b = ByteArray(100)
        fun putU16(offset: Int, v: Int) {
            b[offset] = (v and 0xFF).toByte()
            b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun putU32(offset: Int, v: Long) {
            putU16(offset, (v and 0xFFFF).toInt())
            putU16(offset + 2, ((v shr 16) and 0xFFFF).toInt())
        }
        putU32(0, personality)
        putU32(4, otId)
        b[0x54] = level.toByte()
        putU16(0x56, curHp)
        putU16(0x58, maxHp)
        putU16(0x5A, attack)
        putU16(0x5C, defense)
        putU16(0x5E, speed)
        putU16(0x60, spAttack)
        putU16(0x62, spDefense)
        return b
    }

    @Test
    fun decodesUnencryptedFields() {
        val slot = PartyDecoder.decode(buildSlot())!!
        assertEquals(0x11223344L, slot.personality)
        assertEquals(0x55667788L, slot.otId)
        assertEquals(45, slot.level)
        assertEquals(120, slot.currentHp)
        assertEquals(130, slot.maxHp)
        assertEquals(80, slot.attack)
        assertEquals(70, slot.defense)
        assertEquals(90, slot.speed)
        assertEquals(100, slot.spAttack)
        assertEquals(85, slot.spDefense)
        assertTrue(slot.looksValid)
    }

    @Test
    fun emptySlotIsInvalid() {
        val slot = PartyDecoder.decode(ByteArray(100))!!
        assertFalse(slot.looksValid)
    }

    @Test
    fun tooShortReturnsNull() {
        assertEquals(null, PartyDecoder.decode(ByteArray(50)))
    }

    @Test
    fun currentHpAboveMaxIsInvalid() {
        val slot = PartyDecoder.decode(buildSlot(curHp = 200, maxHp = 130))!!
        assertFalse(slot.looksValid)
    }
}
