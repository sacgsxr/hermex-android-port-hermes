package com.uzairansar.hermex.ui.chat

import java.util.UUID

const val PENDING_NEW_CHAT_SESSION_ID = "pending-new-chat"
private const val PENDING_NEW_CHAT_SESSION_PREFIX = "$PENDING_NEW_CHAT_SESSION_ID-"

/** Returns a route-safe identity for one pending composer instance. */
fun newPendingNewChatSessionId(): String = "$PENDING_NEW_CHAT_SESSION_PREFIX${UUID.randomUUID()}"

/** Accepts the legacy test identity as well as nonce-bearing pending identities. */
fun isPendingNewChatId(sessionId: String?): Boolean =
    sessionId == PENDING_NEW_CHAT_SESSION_ID || sessionId?.startsWith(PENDING_NEW_CHAT_SESSION_PREFIX) == true

/** A real session must replace, not stack above, its pending composer route. */
fun shouldReplacePendingChatRoute(currentSessionId: String?, targetSessionId: String?): Boolean =
    isPendingNewChatId(currentSessionId) && !targetSessionId.isNullOrBlank() && !isPendingNewChatId(targetSessionId)
