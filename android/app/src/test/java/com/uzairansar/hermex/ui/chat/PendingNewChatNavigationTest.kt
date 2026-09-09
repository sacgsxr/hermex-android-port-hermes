package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.data.share.SharedAttachment
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingNewChatNavigationTest {
    @Test
    fun pendingIdentitiesAreUniqueAndRecognizable() {
        val first = newPendingNewChatSessionId()
        val second = newPendingNewChatSessionId()

        assertNotEquals(first, second)
        assertTrue(isPendingNewChatId(first))
        assertTrue(isPendingNewChatId(second))
        assertTrue(isPendingNewChatId(PENDING_NEW_CHAT_SESSION_ID))
    }

    @Test
    fun realSessionReplacesPendingRouteButPendingTargetsNeverDo() {
        val pending = newPendingNewChatSessionId()

        assertTrue(shouldReplacePendingChatRoute(pending, "session-42"))
        assertTrue(!shouldReplacePendingChatRoute(pending, newPendingNewChatSessionId()))
        assertTrue(!shouldReplacePendingChatRoute("session-41", "session-42"))
    }

    @Test
    fun pendingCleanupOwnsLocalAndDeferredSharedFilesAndClearsItsRegistry() {
        val key = "server\u0000${newPendingNewChatSessionId()}"
        val localPath = "/cache/local-upload.jpg"
        val sharedPath = "/cache/shared-upload.pdf"

        assertEquals(
            setOf(localPath, sharedPath),
            pendingComposerOwnedFilePaths(
                localUploads = listOf(PendingLocalAttachmentUpload(cachedPath = localPath)),
                sharedDraftRemainder = listOf(SharedAttachment("content://shared", cachedPath = sharedPath)),
            ),
        )
        QueuedDraftRegistry.save(key, listOf(QueuedDraft("draft", emptyList())))
        assertTrue(QueuedDraftRegistry.load(key).isNotEmpty())
        QueuedDraftRegistry.clear(key)
        assertTrue(QueuedDraftRegistry.load(key).isEmpty())
    }
}
