package com.gbapal.companion.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetroArchClientTest {

    @Test
    fun parsesPlayingStatusWithCrc32() {
        val status = parseGetStatusResponse("GET_STATUS PLAYING gba,Pokemon Unbound.gba,crc32=a1b2c3d4")
        assertEquals(true, status?.playing)
        assertEquals("gba", status?.systemId)
        assertEquals("Pokemon Unbound.gba", status?.gameBasename)
        assertEquals(0xA1B2C3D4L, status?.crc32)
    }

    @Test
    fun parsesPausedStatus() {
        val status = parseGetStatusResponse("GET_STATUS PAUSED gba,game.gba,crc32=deadbeef")
        assertEquals(false, status?.playing)
        assertEquals(0xDEADBEEFL, status?.crc32)
    }

    @Test
    fun handlesBasenameContainingCommas() {
        val status = parseGetStatusResponse("GET_STATUS PLAYING gba,my,weird,rom.gba,crc32=00000001")
        assertEquals("my,weird,rom.gba", status?.gameBasename)
    }

    @Test
    fun contentlessReturnsNull() {
        assertNull(parseGetStatusResponse("GET_STATUS CONTENTLESS"))
    }

    @Test
    fun unrecognisedReplyReturnsNull() {
        assertNull(parseGetStatusResponse("VERSION 1.19.0"))
        assertNull(parseGetStatusResponse("GET_STATUS PLAYING onlyonefield"))
        assertNull(parseGetStatusResponse("GET_STATUS PLAYING gba,game.gba,nocrcfield"))
    }
}
