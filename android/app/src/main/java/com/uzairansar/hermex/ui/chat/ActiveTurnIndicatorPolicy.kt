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
