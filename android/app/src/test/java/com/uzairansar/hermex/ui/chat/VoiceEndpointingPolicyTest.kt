package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEndpointingPolicyTest {
    @Test
    fun silenceBeforeSpeechDoesNotEndCapture() {
        val policy = VoiceEndpointingPolicy(trailingSilenceMillis = 300L)
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.001f, 1_000L))
        assertFalse(policy.hasSpeech)
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.001f, 2_000L))
    }

    @Test
    fun speechThenTrailingSilenceEndsOnlyAfterWindow() {
        val policy = VoiceEndpointingPolicy(trailingSilenceMillis = 300L)
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.2f, 100L))
        assertTrue(policy.hasSpeech)
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.001f, 200L))
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.001f, 399L))
        assertEquals(VoiceEndpointDecision.TrailingSilence, policy.update(0.001f, 400L))
    }

    @Test
    fun resumedSpeechResetsTrailingSilence() {
        val policy = VoiceEndpointingPolicy(trailingSilenceMillis = 300L)
        policy.update(0.2f, 100L)
        policy.update(0.001f, 250L)
        policy.update(0.2f, 350L)
        assertEquals(VoiceEndpointDecision.Continue, policy.update(0.001f, 600L))
        assertEquals(VoiceEndpointDecision.TrailingSilence, policy.update(0.001f, 650L))
    }

    @Test
    fun hardMaximumEndsEvenWithoutSpeech() {
        val policy = VoiceEndpointingPolicy(maximumDurationMillis = 1_000L)
        assertEquals(VoiceEndpointDecision.MaximumDuration, policy.update(0f, 1_000L))
        assertFalse(policy.hasSpeech)
    }
}
