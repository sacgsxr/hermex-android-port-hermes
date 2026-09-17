package com.uzairansar.hermex.ui.kanban

import com.uzairansar.hermex.core.network.ApiError
import com.uzairansar.hermex.core.model.KanbanCompatibilityWarning

internal fun isMissingKanbanCapability(error: Throwable): Boolean {
    if (error is UnsupportedOperationException && error.message?.contains("Kanban", ignoreCase = true) == true) {
        return true
    }
    val http = error as? ApiError.Http ?: return false
    if (http.statusCode == 405) return true
    if (http.statusCode != 404) return false

    val body = http.body?.lowercase().orEmpty()
    return "unknown kanban endpoint" in body ||
        "kanban endpoint not found" in body ||
        "unsupported kanban endpoint" in body
}

internal fun kanbanCompatibilityReason(warnings: List<KanbanCompatibilityWarning>): String? {
    val reasons = buildList {
        if (warnings.contains(KanbanCompatibilityWarning.ReadOnly)) {
            add("The server marked this Kanban surface read-only.")
        }
        if (warnings.contains(KanbanCompatibilityWarning.WriteCapabilityUnavailable)) {
            add("The server did not advertise a verified Kanban write capability.")
        }
    }
    return reasons.joinToString(" ").takeIf(String::isNotEmpty)
}
