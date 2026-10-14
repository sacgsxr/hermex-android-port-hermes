package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalKokoroTtsPolicyTest {
    @Test
    fun acceptsOpenAiCompatibleLocalEndpointWithoutCredentials() {
        assertTrue(LocalKokoroTtsPolicy.endpointIsValid("http://192.168.1.20:8765/v1"))
        assertTrue(LocalKokoroTtsPolicy.shouldUse("invented harmless test", "http://localhost:8765/v1", "af_heart"))
    }

    @Test
    fun rejectsInvalidOrCredentialBearingEndpoint() {
        assertFalse(LocalKokoroTtsPolicy.endpointIsValid("not-a-url"))
        assertFalse(LocalKokoroTtsPolicy.endpointIsValid("https://user:secret@example.test/v1"))
        assertFalse(LocalKokoroTtsPolicy.shouldUse("", "http://localhost:8765/v1", "af_heart"))
        assertFalse(LocalKokoroTtsPolicy.shouldUse("hello", "http://localhost:8765/v1", ""))
    }
}
