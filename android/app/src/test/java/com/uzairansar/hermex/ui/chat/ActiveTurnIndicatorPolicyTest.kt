package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.PendingApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /**
     * Regression: the floating ActiveTurnStatusPill and the composer's compact
     * controls row both render a spinner plus the same activity word in the same
     * vertical band, so during a thinking turn two "Thinking" labels drew on top
     * of each other. The pill must stand down whenever the controls row is
     * already showing a live status label.
     */
    @Test
    fun pillStandsDownWhenTheStatusRowAlreadyShowsTheLabel() {
        val state = ChatUiState(
            isStreaming = true,
            activeStreamId = "stream-1",
            liveReasoning = "The user is asking about the overlap",
        )
        assertNotNull(state.activeTurnIndicator())
        assertFalse(
            shouldShowFloatingActiveTurnPill(
                indicator = state.activeTurnIndicator(),
                compactControlsVisible = true,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun pillReturnsWhenTheControlsRowIsHidden() {
        assertTrue(
            shouldShowFloatingActiveTurnPill(
                indicator = ChatUiState(
                    isStreaming = true,
                    activeStreamId = "stream-1",
                    liveReasoning = "thinking",
                ).activeTurnIndicator(),
                compactControlsVisible = false,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun pillNeverShowsWithoutAnIndicator() {
        assertFalse(
            shouldShowFloatingActiveTurnPill(
                indicator = null,
                compactControlsVisible = true,
                isStreaming = true,
            ),
        )
    }

    /**
     * Approval and clarification are blocking states the user must act on, so
     * they keep their own floating indicator regardless of the controls row.
     */
    @Test
    fun blockingStatesKeepTheirPillEvenWithTheControlsRowVisible() {
        assertTrue(
            shouldShowFloatingActiveTurnPill(
                indicator = ChatUiState(
                    isStreaming = true,
                    pendingApproval = PendingApproval(id = "approval-1"),
                ).activeTurnIndicator(),
                compactControlsVisible = true,
                isStreaming = true,
            ),
        )
    }
}
