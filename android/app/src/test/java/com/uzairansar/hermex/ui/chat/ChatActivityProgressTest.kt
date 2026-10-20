package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.network.SseEvent
import com.uzairansar.hermex.core.network.ToolStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatActivityProgressTest {

    // --- Parallel tools and ID matching ---------------------------------------

    @Test
    fun parallelToolsWithDifferentIdsAreAllTrackedAndCompleteIndependently() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "read", callId = "b")), model)

        assertEquals(2, model.running.size)
        assertTrue(model.hasRunning())

        // The second tool finishes first; the first must stay running.
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(name = "read", callId = "b", duration = 0.5)), model)
        assertEquals(listOf("shell"), model.running.map { it.displayName })
        assertEquals(1, model.recent.size)
        assertEquals(ChatToolActivityStatus.Completed, model.recent[0].status)

        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(name = "shell", callId = "a")), model)
        assertFalse(model.hasRunning())
        assertEquals(listOf("read", "shell"), model.recent.map { it.displayName })
    }

    @Test
    fun idPrecedenceIsCallIdOverToolCallIdOverToolUseIdOverTidOverId() {
        // Start advertises several ID fields; the resolved key must be callId.
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(
            SseEvent.ToolStarted(tool(name = "shell", callId = "primary", toolCallId = "secondary")),
            model,
        )

        // A completion carrying only the lower-precedence field must NOT match.
        val unmatched = ChatActivityProgress.apply(
            SseEvent.ToolCompleted(tool(name = "shell", toolCallId = "secondary")),
            model,
        )
        assertEquals(model, unmatched)

        // The callId completion matches and removes the running item.
        val matched = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = "primary")), model)
        assertTrue(matched.running.isEmpty())
        assertEquals(1, matched.recent.size)
    }

    @Test
    fun lowerPrecedenceFieldsAreUsedWhenHigherOnesAreAbsent() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "grep", toolUseId = "tu-1")), model)
        val matched = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(toolUseId = "tu-1")), model)
        assertTrue(matched.running.isEmpty())

        model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "ls", id = "plain-id")), model)
        val matchedById = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(id = "plain-id")), model)
        assertTrue(matchedById.running.isEmpty())
    }

    @Test
    fun completionWithUnknownIdIsDroppedWithoutTouchingRunningTools() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)

        val afterOrphan = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = "unknown")), model)
        assertEquals(model, afterOrphan)
    }

    // --- Duplicate tolerance ---------------------------------------------------

    @Test
    fun duplicateStartsAreIdempotent() {
        var model = ChatActivityProgressModel()
        val start = SseEvent.ToolStarted(tool(name = "shell", callId = "a"))
        model = ChatActivityProgress.apply(start, model)
        assertEquals(model, ChatActivityProgress.apply(start, model))
        assertEquals(1, model.running.size)

        // Anonymous duplicate starts collapse the same way.
        val anonStart = SseEvent.ToolStarted(tool(name = "shell"))
        var anonModel = ChatActivityProgressModel()
        anonModel = ChatActivityProgress.apply(anonStart, anonModel)
        assertEquals(anonModel, ChatActivityProgress.apply(anonStart, anonModel))
        assertEquals(1, anonModel.running.size)
    }

    @Test
    fun duplicateCompletionsAreIdempotentAndDoNotDuplicateHistory() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        val completion = SseEvent.ToolCompleted(tool(callId = "a", duration = 1.0))
        model = ChatActivityProgress.apply(completion, model)
        assertEquals(model, ChatActivityProgress.apply(completion, model))
        assertEquals(1, model.recent.size)
    }

    @Test
    fun replayedStartAfterCompletionDoesNotReAddTheCompletedCall() {
        var model = ChatActivityProgressModel()
        val start = SseEvent.ToolStarted(tool(name = "shell", callId = "a", preview = "old"))
        model = ChatActivityProgress.apply(start, model)
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = "a")), model)
        model = ChatActivityProgress.apply(
            SseEvent.ToolStarted(tool(name = "shell", callId = "a", preview = "replayed")),
            model,
        )

        assertTrue(model.running.isEmpty())
        assertEquals(1, model.recent.size)
    }

    // --- Fallback matching without IDs ------------------------------------------

    @Test
    fun anonymousCompletionFallsBackToOldestAnonymousRunningTool() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "first")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "second")), model)

        // No IDs anywhere: the ID-less completion claims the oldest anonymous tool.
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool()), model)
        assertEquals(listOf("second"), model.running.map { it.displayName })
        assertEquals("first", model.recent[0].displayName)
    }

    @Test
    fun namedCompletionWithoutIdMatchesByVisibleName() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "read")), model)

        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(name = "read")), model)
        assertEquals(listOf("shell"), model.running.map { it.displayName })
        assertEquals("read", model.recent[0].displayName)
    }

    @Test
    fun namedCompletionWithoutIdDoesNotRequireThePreviewToMatch() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "read", preview = "starting")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(name = "read", preview = "finished")), model)

        assertTrue(model.running.isEmpty())
        assertEquals("read", model.recent.single().displayName)
    }

    @Test
    fun namedCompletionWithoutIdThatMatchesNothingIsDropped() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell")), model)

        val afterUnmatched = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(name = "web_search")), model)
        assertEquals(model, afterUnmatched)
    }

    // --- States and duration -----------------------------------------------------

    @Test
    fun isErrorCompletionMarksFailedAndDurationIsRetainedWhenSupplied() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        model = ChatActivityProgress.apply(
            SseEvent.ToolCompleted(tool(callId = "a", isError = true, duration = 2.5)),
            model,
        )

        val item = model.recent[0]
        assertEquals(ChatToolActivityStatus.Failed, item.status)
        assertEquals(2.5, item.durationSeconds!!, 0.0)
    }

    @Test
    fun missingOrInvalidDurationStaysNull() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = "a")), model)
        assertNull(model.recent[0].durationSeconds)

        var negative = ChatActivityProgressModel()
        negative = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "b")), negative)
        negative = ChatActivityProgress.apply(
            SseEvent.ToolCompleted(tool(callId = "b", duration = -1.0)),
            negative,
        )
        assertNull(negative.recent[0].durationSeconds)

        var nan = ChatActivityProgressModel()
        nan = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "c")), nan)
        nan = ChatActivityProgress.apply(
            SseEvent.ToolCompleted(tool(callId = "c", duration = Double.NaN)),
            nan,
        )
        assertNull(nan.recent[0].durationSeconds)
    }

    // --- Bounded history -----------------------------------------------------------

    @Test
    fun recentHistoryIsBoundedToSixNewestFirst() {
        var model = ChatActivityProgressModel()
        repeat(8) { i ->
            val id = "t$i"
            model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "tool$i", callId = id)), model)
            model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = id)), model)
        }

        assertEquals(6, model.recent.size)
        assertEquals(
            listOf("tool7", "tool6", "tool5", "tool4", "tool3", "tool2"),
            model.recent.map { it.displayName },
        )
    }

    // --- Terminal resets --------------------------------------------------------------

    @Test
    fun terminalResetsClearRunningAndRecentHistory() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        model = ChatActivityProgress.apply(SseEvent.ToolCompleted(tool(callId = "a")), model)

        for (reason in ChatActivityResetReason.values()) {
            val reset = ChatActivityProgress.reset(model, reason)
            assertEquals(ChatActivityProgressModel(), reset)
        }
    }

    @Test
    fun resetWithRetainRecentKeepsSafeHistoryAfterTurnEnd() {
        var model = ChatActivityProgressModel()
        model = ChatActivityProgress.apply(SseEvent.ToolStarted(tool(name = "shell", callId = "a")), model)
        model = ChatActivityProgress.apply(
            SseEvent.ToolCompleted(tool(callId = "a", duration = 0.25)),
            model,
        )

        val kept = ChatActivityProgress.reset(model, ChatActivityResetReason.Done, retainRecent = true)
        assertTrue(kept.running.isEmpty())
        assertEquals(1, kept.recent.size)
        assertEquals(ChatToolActivityStatus.Completed, kept.recent[0].status)
        assertEquals(0.25, kept.recent[0].durationSeconds!!, 0.0)
    }

    // --- Privacy sanitization -----------------------------------------------------------

    @Test
    fun longPreviewsAreTruncatedAndMultiLinePayloadsAreCollapsedToSingleLine() {
        val model = ChatActivityProgress.apply(
            SseEvent.ToolStarted(tool(name = "shell", preview = "a".repeat(200))),
            ChatActivityProgressModel(),
        )

        val detail = model.running[0].detail!!
        assertEquals(ChatActivityProgress.MAX_DETAIL_LENGTH, detail.length)
        assertFalse(detail.contains("\n"))
    }

    @Test
    fun controlCharactersAndBlankNamesAreSanitized() {
        assertNull(ChatActivityProgress.sanitizeDetail("secret=abc123\nmore\tstuff"))
        assertNull(ChatActivityProgress.sanitizeDetail("   \n  "))
        assertNull(ChatActivityProgress.sanitizeDetail(null))
        assertEquals("Tool", ChatActivityProgress.sanitizeName(null))
        assertEquals("Tool", ChatActivityProgress.sanitizeName("  \t "))
    }

    @Test
    fun credentialLikeAndRawPayloadPreviewsAreNeverShown() {
        val unsafePreviews = listOf(
            "Authorization: Bearer abc.def.ghi",
            "{\"password\":\"super-secret\"}",
            "https://user:password@example.test/?token=secret",
            "curl https://example.test -H 'Authorization: Bearer abc'",
            "-----BEGIN PRIVATE KEY-----",
            "sk-live-secret-value",
        )

        unsafePreviews.forEach { preview ->
            val model = ChatActivityProgress.apply(
                SseEvent.ToolStarted(tool(name = "shell", preview = preview)),
                ChatActivityProgressModel(),
            )
            assertNull("preview should be suppressed: $preview", model.running.single().detail)
        }
    }

    private fun tool(
        name: String? = null,
        preview: String? = null,
        duration: Double? = null,
        isError: Boolean? = null,
        callId: String? = null,
        toolCallId: String? = null,
        toolUseId: String? = null,
        tid: String? = null,
        id: String? = null,
    ): ToolStreamEvent = ToolStreamEvent(
        name = name,
        preview = preview,
        duration = duration,
        isError = isError,
        callId = callId,
        toolCallId = toolCallId,
        toolUseId = toolUseId,
        tid = tid,
        id = id,
    )
}
