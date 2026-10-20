package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.network.SseEvent
import com.uzairansar.hermex.core.network.ToolStreamEvent

/** Lifecycle state of a single tool activity item. */
enum class ChatToolActivityStatus {
    Running,
    Completed,
    Failed,
}

/**
 * One visible tool activity. [displayName] and [detail] are sanitized for display:
 * no raw arguments, results, payloads, or reasoning text is ever stored here.
 */
data class ChatToolActivityItem(
    val key: String,
    val displayName: String,
    val detail: String?,
    val status: ChatToolActivityStatus,
    val durationSeconds: Double?,
)

/**
 * Bounded snapshot of tool activity for the chat screen. [running] holds tools that
 * started but have not completed yet (in start order); [recent] holds finished items
 * newest-first and never exceeds [MAX_RECENT]. The model is intentionally free of
 * percentages, progress labels, or any other invented completion estimates.
 */
data class ChatActivityProgressModel(
    val running: List<ChatToolActivityItem> = emptyList(),
    val recent: List<ChatToolActivityItem> = emptyList(),
) {
    /** True while at least one tool is still in flight. */
    fun hasRunning(): Boolean = running.isNotEmpty()

    companion object {
        const val MAX_RECENT = 6
    }
}

/** Terminal conditions that clear the activity model. */
internal enum class ChatActivityResetReason {
    Done,
    Error,
    Cancelled,
    Reconnect,
    SessionSwitch,
}

/**
 * Pure reducer over [SseEvent.ToolStarted] / [SseEvent.ToolCompleted] events.
 *
 * Rules:
 * - Completions match running tools by call ID with precedence
 *   callId > toolCallId > toolUseId > tid > id (first non-blank wins).
 * - When neither the completion nor any running item has an ID, a stable fallback
 *   matches by name and then by start order. A named completion never falls back:
 *   if no running item shares its name it is dropped instead of claiming another tool.
 * - Duplicate starts and duplicate completions are idempotent. ID-less tools with
 *   identical visible identity collapse into one tracked item because they cannot be
 *   told apart without an ID.
 * - [recent] keeps at most [ChatActivityProgressModel.MAX_RECENT] items, newest first.
 */
internal object ChatActivityProgress {

    fun apply(start: SseEvent.ToolStarted, current: ChatActivityProgressModel): ChatActivityProgressModel =
        applyTool(ToolPhase.Started, start.event, current)

    fun apply(completed: SseEvent.ToolCompleted, current: ChatActivityProgressModel): ChatActivityProgressModel =
        applyTool(ToolPhase.Completed, completed.event, current)

    /**
     * Clears running tools. When [retainRecent] is false (done, error, cancel,
     * reconnect, session switch) the bounded recent history is dropped as well;
     * pass true to keep safe recent items after a turn ends.
     */
    fun reset(current: ChatActivityProgressModel, reason: ChatActivityResetReason, retainRecent: Boolean = false): ChatActivityProgressModel =
        if (retainRecent) {
            current.copy(running = emptyList())
        } else {
            ChatActivityProgressModel()
        }

    private enum class ToolPhase { Started, Completed }

    private fun applyTool(phase: ToolPhase, event: ToolStreamEvent, current: ChatActivityProgressModel): ChatActivityProgressModel {
        val name = sanitizeName(event.name)
        val detail = sanitizeDetail(event.preview)
        val idKey = resolveIdKey(event)
        return when (phase) {
            ToolPhase.Started -> startTool(idKey, name, detail, current)
            ToolPhase.Completed -> completeTool(idKey, name, detail, event.duration, event.isError, current)
        }
    }

    private fun startTool(idKey: String?, name: String, detail: String?, current: ChatActivityProgressModel): ChatActivityProgressModel {
        // Duplicate starts are idempotent: same identity means the tool is already
        // tracked and nothing changes. ID-keyed duplicates match by key; anonymous
        // ones match by visible name/detail.
        val duplicate = if (idKey != null) {
            current.running.any { it.key == idKey } || current.recent.any { it.key == idKey }
        } else {
            current.running.any { it.displayName == name && it.detail == detail }
        }
        if (duplicate) return current

        val running = current.running + ChatToolActivityItem(
            key = idKey ?: nextAnonymousKey(current),
            displayName = name,
            detail = detail,
            status = ChatToolActivityStatus.Running,
            durationSeconds = null,
        )
        return current.copy(running = running)
    }

    private fun completeTool(
        idKey: String?,
        name: String,
        detail: String?,
        durationSeconds: Double?,
        isError: Boolean?,
        current: ChatActivityProgressModel,
    ): ChatActivityProgressModel {
        val failed = isError == true
        val status = if (failed) ChatToolActivityStatus.Failed else ChatToolActivityStatus.Completed

        val index = when {
            idKey != null -> current.running.indexOfFirst { it.key == idKey }
            // Stable fallback only when the completion carries no ID at all. A named
            // completion that matches no running name is dropped, never mis-matched.
            else -> matchFallbackIndex(name, detail, current)
        }
        if (index < 0) return current

        val matched = current.running[index]
        // Duplicate completions are idempotent: an item that already finished with the
        // same outcome is left untouched instead of being pushed into history again.
        if (matched.status != ChatToolActivityStatus.Running && matched.status == status) return current

        val finished = matched.copy(
            detail = matched.detail ?: detail,
            status = status,
            durationSeconds = durationSeconds?.takeIf { it.isFinite() && it >= 0.0 },
        )
        val running = current.running.filterIndexed { i, _ -> i != index }
        val recent = (listOf(finished) + current.recent).take(ChatActivityProgressModel.MAX_RECENT)
        return current.copy(running = running, recent = recent)
    }

    /** callId > toolCallId > toolUseId > tid > id; first non-blank wins. */
    private fun resolveIdKey(event: ToolStreamEvent): String? = listOfNotNull(
        event.callId,
        event.toolCallId,
        event.toolUseId,
        event.tid,
        event.id,
    ).firstOrNull { it.isNotBlank() }?.trim()

    private fun matchFallbackIndex(name: String, detail: String?, current: ChatActivityProgressModel): Int {
        val byName = current.running.indexOfFirst { it.displayName == name }
        if (byName >= 0) return byName
        if (name != DEFAULT_NAME) return -1
        // Anonymous completion with no ID: claim the oldest anonymous running tool.
        return current.running.indexOfFirst { it.key.startsWith(ANONYMOUS_KEY_PREFIX) }
    }

    private fun nextAnonymousKey(current: ChatActivityProgressModel): String {
        val taken = (current.running + current.recent).mapTo(HashSet()) { it.key }
        var slot = 0
        while (true) {
            val key = "$ANONYMOUS_KEY_PREFIX$slot"
            if (key !in taken) return key
            slot++
        }
    }

    private const val ANONYMOUS_KEY_PREFIX = "anon:"
    private const val DEFAULT_NAME = "Tool"

    // --- Sanitization ---------------------------------------------------------
    // Visible strings are short, single-line, and free of raw arguments/results.

    /** Collapses whitespace/newlines, strips control characters, truncates, falls back to [DEFAULT_NAME]. */
    internal fun sanitizeName(raw: String?): String {
        val cleaned = cleanForDisplay(raw) ?: return DEFAULT_NAME
        if (looksLikeUnsafePayload(cleaned)) return DEFAULT_NAME
        return truncate(cleaned, MAX_NAME_LENGTH)
    }

    /** Same cleaning as names but nullable; returns null when nothing safe remains. */
    internal fun sanitizeDetail(raw: String?): String? {
        val cleaned = cleanForDisplay(raw) ?: return null
        if (looksLikeUnsafePayload(cleaned)) return null
        return truncate(cleaned, MAX_DETAIL_LENGTH)
    }

    private const val MAX_NAME_LENGTH = 40
    internal const val MAX_DETAIL_LENGTH = 80

    private fun cleanForDisplay(raw: String?): String? {
        if (raw == null) return null
        // Drop C0/C1 control characters (keep none), collapse all whitespace runs to
        // single spaces, trim. This removes embedded newlines from multi-line payloads.
        val collapsed = buildString(raw.length) {
                for (ch in raw) append(if (ch.isISOControl()) ' ' else ch)
            }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)(token|password|secret|api[_-]?key|authorization)\\s*[:=]\\s*(?:Bearer\\s+)?(?:\\\"[^\\\"]*\\\"|[^ ,}]+)"), "[hidden]")
            .replace(Regex("(?i)\\\"(?:password|token|secret|api[_-]?key|authorization|private[_-]?key)\\\"\\s*:\\s*\\\"[^\\\"]*\\\""), "\"[hidden]\"")
            .replace(Regex("(?i)(https?://[^/\\s@]+):[^/\\s@]+@"), "$1:[hidden]@")
            .replace(Regex("(?i)([?&](?:token|password|secret|api[_-]?key|authorization)=)[^&\\s]+"), "$1[hidden]")
            .replace(Regex("(?i)\\bsk-[A-Za-z0-9_-]+\\b"), "[hidden]")
            .trim()
        return collapsed.ifBlank { null }
    }

    private fun looksLikeUnsafePayload(value: String): Boolean =
        value.contains("[hidden]") ||
        value.contains('{') ||
            value.contains('}') ||
            value.contains('[') ||
            value.contains(']') ||
            value.contains('`') ||
            value.contains("-----BEGIN", ignoreCase = true) ||
            value.contains("://") ||
            Regex("(?i)^(?:curl|wget|python|python3|bash|sh|node|powershell|git)\\b").containsMatchIn(value)

    private fun truncate(value: String, max: Int): String =
        if (value.length <= max) value else value.substring(0, max - 1).trimEnd() + '…'
}
