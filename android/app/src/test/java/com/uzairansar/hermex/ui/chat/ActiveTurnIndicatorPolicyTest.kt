package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.PendingApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTurnIndicatorPolicyTest {
    @Test
    fun inactiveTurnNeverShowsAnIndicatorEvenWithStaleActivity() {
        assertNull(
            ChatUiState(
                isStreaming = false,
                activeStreamId = "finished-stream",
                liveToolActivity = "terminal secret-argument",
            ).activeTurnIndicator(),
        )
    }

    @Test
    fun actualBlockingAndRecoveryStatesTakePriority() {
        assertEquals(
            ActiveTurnIndicatorActivity.WaitingForApproval,
            ChatUiState(
                isStreaming = true,
                pendingApproval = PendingApproval(id = "approval-1"),
                activeStreamRecoveryState = ActiveStreamRecoveryState.Reconnecting,
                liveToolActivity = "terminal",
            ).activeTurnIndicator()?.activity,
        )
        assertEquals(
            ActiveTurnIndicatorActivity.Reconnecting,
            ChatUiState(
                isStreaming = true,
                activeStreamRecoveryState = ActiveStreamRecoveryState.Reconnecting,
                liveToolActivity = "terminal",
            ).activeTurnIndicator()?.activity,
        )
    }

    @Test
    fun runningToolUsesOnlyTheActualSanitizedToolName() {
        val indicator = ChatUiState(
            isStreaming = true,
            activeStreamId = "stream-1",
            liveToolActivity = "functions.browser_exec --token super-secret\n{\"password\":\"hidden\"}",
        ).activeTurnIndicator()

        assertEquals(ActiveTurnIndicatorActivity.RunningTool, indicator?.activity)
        assertEquals("functions.browser_exec", indicator?.toolName)
        assertFalse(indicator?.label.orEmpty().contains("secret", ignoreCase = true))
        assertFalse(indicator?.label.orEmpty().contains("password", ignoreCase = true))
    }

    @Test
    fun activeTurnFallsBackToTruthfulPhaseAndClearsAtCompletion() {
        assertEquals(
            ActiveTurnIndicatorActivity.Thinking,
            ChatUiState(isStreaming = true, activeStreamId = "stream-1", liveReasoning = "Considering").activeTurnIndicator()?.activity,
        )
        assertEquals(
            ActiveTurnIndicatorActivity.Responding,
            ChatUiState(
                isStreaming = true,
                activeStreamId = "stream-1",
                messages = listOf(com.uzairansar.hermex.core.model.ChatMessage(role = "assistant", id = "streaming", content = "Hello")),
            ).activeTurnIndicator()?.activity,
        )
        assertTrue(ChatUiState(isStreaming = true, activeStreamId = null).activeTurnIndicator()?.label == "Starting response")
        assertNull(ChatUiState(isStreaming = false).activeTurnIndicator())
    }
}
