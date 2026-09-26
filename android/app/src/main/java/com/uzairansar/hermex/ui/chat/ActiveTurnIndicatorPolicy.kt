package com.uzairansar.hermex.ui.chat

internal enum class ActiveTurnIndicatorActivity {
    Starting,
    WaitingForApproval,
    WaitingForInput,
    SubmittingPromptResponse,
    Checking,
    Reconnecting,
    RunningTool,
    Thinking,
    Responding,
    Working,
}

internal data class ActiveTurnIndicator(
    val activity: ActiveTurnIndicatorActivity,
    val label: String,
    val toolName: String? = null,
)

internal fun ChatUiState.activeTurnIndicator(): ActiveTurnIndicator? {
    if (!isStreaming) return null

    if (isRespondingToPendingPrompt) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.SubmittingPromptResponse,
            label = "Responding",
        )
    }
    if (pendingApproval != null) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.WaitingForApproval,
            label = "Waiting for approval",
        )
    }
    if (pendingClarification != null) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.WaitingForInput,
            label = "Clarification Required",
        )
    }

    when (activeStreamRecoveryState) {
        ActiveStreamRecoveryState.Checking -> return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.Checking,
            label = activeStreamRecoveryState.label,
        )
        ActiveStreamRecoveryState.Reconnecting -> return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.Reconnecting,
            label = activeStreamRecoveryState.label,
        )
        ActiveStreamRecoveryState.Idle -> Unit
    }

    liveToolActivity?.takeIf { it.isNotBlank() }?.let { activity ->
        val toolName = activity.safeToolName()
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.RunningTool,
            label = if (toolName == null) "Running command" else "Running",
            toolName = toolName,
        )
    }
    if (messages.any { message ->
            message.role == "assistant" && message.id == "streaming" && message.displayText.isNotBlank()
        }
    ) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.Responding,
            label = "Responding",
        )
    }
    if (liveReasoning.isNotBlank()) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.Thinking,
            label = "Thinking",
        )
    }
    if (activeStreamId.isNullOrBlank()) {
        return ActiveTurnIndicator(
            activity = ActiveTurnIndicatorActivity.Starting,
            label = "Starting response",
        )
    }
    return ActiveTurnIndicator(
        activity = ActiveTurnIndicatorActivity.Working,
        label = "Hermes is working",
    )
}

/**
 * Whether the floating active-turn pill should render above the composer.
 *
 * The pill and the composer's compact controls row both draw a spinner plus the
 * activity word, and they occupy the same vertical band, so rendering both
 * stacks two "Thinking" labels on top of each other. The controls row is the
 * richer readout (it also carries the context gauge and params button), so it
 * wins when it is on screen.
 *
 * Blocking states the user must act on are exempt: approval, clarification, and
 * recovery need to be unmissable, so they keep their own indicator.
 */
internal fun shouldShowFloatingActiveTurnPill(
    indicator: ActiveTurnIndicator?,
    compactControlsVisible: Boolean,
    isStreaming: Boolean,
): Boolean {
    if (indicator == null) return false
    if (!compactControlsVisible || !isStreaming) return true
    return when (indicator.activity) {
        ActiveTurnIndicatorActivity.WaitingForApproval,
        ActiveTurnIndicatorActivity.WaitingForInput,
        ActiveTurnIndicatorActivity.Reconnecting,
        ActiveTurnIndicatorActivity.Checking,
        -> true

        else -> false
    }
}

private fun String.safeToolName(): String? {
    if (trim().equals("Tool running", ignoreCase = true)) return null
    val firstToken = trim()
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.substringBefore(' ')
        ?.substringBefore('\t')
        ?.trim('"', '\'', '`')
        ?.take(64)
        .orEmpty()
    return firstToken.takeIf { token ->
        token.isNotBlank() && token.all { character ->
            character.isLetterOrDigit() || character in "._:/-"
        }
    }
}
