package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceMachineTest {
    @Test fun completesTurnAndResumesListening() {
        val machine = HandsFreeVoiceMachine()
        val started = machine.start()
        assertEquals(HandsFreePhase.Listening, started.phase)
        assertTrue(machine.captureEnded())
        assertTrue(machine.transcriptSubmitted())
        assertTrue(machine.responseCompleted(started.generation))
        assertTrue(machine.playbackCompleted(started.generation))
        assertEquals(HandsFreePhase.Listening, machine.state.phase)
    }

    @Test fun stopFencesLateTranscriptionResponseAndPlayback() {
        val machine = HandsFreeVoiceMachine()
        val generation = machine.start().generation
        machine.captureEnded()
        machine.transcriptSubmitted()
        machine.stop()
        assertFalse(machine.responseCompleted(generation))
        assertFalse(machine.playbackCompleted(generation))
        assertFalse(machine.accepts(generation))
        assertEquals(HandsFreePhase.Stopped, machine.state.phase)
    }

    @Test fun staleGenerationCannotAdvanceNewTurn() {
        val machine = HandsFreeVoiceMachine()
        val old = machine.start().generation
        machine.captureEnded(); machine.transcriptSubmitted()
        machine.stop()
        val fresh = machine.start().generation
        machine.captureEnded(); machine.transcriptSubmitted()
        assertFalse(machine.responseCompleted(old))
        assertTrue(machine.responseCompleted(fresh))
    }
}
