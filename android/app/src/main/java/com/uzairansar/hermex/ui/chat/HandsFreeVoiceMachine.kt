package com.uzairansar.hermex.ui.chat

/** Pure lifecycle for hands-free voice; Android capture/playback stays outside this class. */
enum class HandsFreePhase { Stopped, Listening, Transcribing, WaitingForResponse, Speaking }

data class HandsFreeVoiceState(
    val phase: HandsFreePhase = HandsFreePhase.Stopped,
    val generation: Long = 0,
    val submittedGeneration: Long? = null,
) {
    val isActive: Boolean get() = phase != HandsFreePhase.Stopped
}

class HandsFreeVoiceMachine {
    var state: HandsFreeVoiceState = HandsFreeVoiceState()
        private set

    fun start(): HandsFreeVoiceState {
        state = HandsFreeVoiceState(HandsFreePhase.Listening, state.generation + 1)
        return state
    }

    fun stop(): HandsFreeVoiceState {
        state = HandsFreeVoiceState(HandsFreePhase.Stopped, state.generation + 1)
        return state
    }

    fun captureEnded(): Boolean = transition(HandsFreePhase.Transcribing)
    fun transcriptSubmitted(): Boolean {
        if (state.phase != HandsFreePhase.Transcribing) return false
        state = state.copy(phase = HandsFreePhase.WaitingForResponse, submittedGeneration = state.generation)
        return true
    }

    fun responseCompleted(generation: Long): Boolean =
        if (state.phase == HandsFreePhase.WaitingForResponse && state.submittedGeneration == generation) {
            state = state.copy(phase = HandsFreePhase.Speaking)
            true
        } else false

    fun playbackCompleted(generation: Long): Boolean =
        if (state.phase == HandsFreePhase.Speaking && state.generation == generation) {
            state = state.copy(phase = HandsFreePhase.Listening, submittedGeneration = null)
            true
        } else false

    fun accepts(generation: Long): Boolean = state.isActive && state.generation == generation

    private fun transition(next: HandsFreePhase): Boolean {
        if (!state.isActive || (next == HandsFreePhase.Transcribing && state.phase != HandsFreePhase.Listening)) return false
        state = state.copy(phase = next)
        return true
    }
}
