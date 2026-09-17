package com.uzairansar.hermex.ui.kanban

import com.uzairansar.hermex.core.network.ApiError
import com.uzairansar.hermex.core.model.KanbanCompatibilityWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanCapabilityTest {
    @Test
    fun endpointMissingResponsesCloseTheScopedCapability() {
        assertTrue(isMissingKanbanCapability(ApiError.Http(405, null)))
        assertTrue(
            isMissingKanbanCapability(
                ApiError.Http(404, "{\"error\":\"Unknown Kanban endpoint; refresh the client\"}"),
            ),
        )
        assertTrue(isMissingKanbanCapability(ApiError.Http(404, "Kanban endpoint not found")))
        assertTrue(isMissingKanbanCapability(ApiError.Http(404, "Unsupported Kanban endpoint")))
    }

    @Test
    fun resourceNotFoundResponsesDoNotCloseTheCapability() {
        assertFalse(isMissingKanbanCapability(ApiError.Http(404, "{\"error\":\"task not found\"}")))
        assertFalse(isMissingKanbanCapability(ApiError.Http(404, null)))
        assertFalse(isMissingKanbanCapability(ApiError.Http(500, "Unknown Kanban endpoint")))
        assertFalse(isMissingKanbanCapability(ApiError.Http(501, null)))
    }

    @Test
    fun explicitUnsupportedOperationsCloseTheCapability() {
        assertTrue(isMissingKanbanCapability(UnsupportedOperationException("Kanban writes are unsupported")))
        assertFalse(isMissingKanbanCapability(UnsupportedOperationException("Other operation is unsupported")))
    }

    @Test
    fun compatibilityReasonExplainsReadOnlyAndMissingCapability() {
        assertEquals(
            "The server marked this Kanban surface read-only. The server did not advertise a verified Kanban write capability.",
            kanbanCompatibilityReason(
                listOf(
                    KanbanCompatibilityWarning.ReadOnly,
                    KanbanCompatibilityWarning.WriteCapabilityUnavailable,
                ),
            ),
        )
    }
}
