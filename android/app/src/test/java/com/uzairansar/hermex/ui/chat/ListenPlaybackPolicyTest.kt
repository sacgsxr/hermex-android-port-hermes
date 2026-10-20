package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenPlaybackPolicyTest {
    @Test
    fun serverTtsHonorsTheServerCharacterLimit() {
        assertTrue(ServerTtsPolicy.shouldUseServer("Hello"))
        assertTrue(ServerTtsPolicy.shouldUseServer("x".repeat(ServerTtsPolicy.MaximumCharacters)))
        assertFalse(ServerTtsPolicy.shouldUseServer("x".repeat(ServerTtsPolicy.MaximumCharacters + 1)))
        assertFalse(ServerTtsPolicy.shouldUseServer("  "))
    }

    @Test
    fun playbackSpeedRestoresOnlySupportedValues() {
        assertEquals(ListenPlaybackSpeed.Half, ListenPlaybackSpeed.fromStoredRate(0.5f))
        assertEquals(ListenPlaybackSpeed.OneAndHalf, ListenPlaybackSpeed.fromStoredRate(1.5f))
        assertEquals(ListenPlaybackSpeed.Normal, ListenPlaybackSpeed.fromStoredRate(1.25f))
    }

}
