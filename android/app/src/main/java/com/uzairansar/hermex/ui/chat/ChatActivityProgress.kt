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
    val identityAliases: Set<String> = emptySet(),
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
 * - Completions match running tools when any identity alias intersects; all server
 *   aliases are retained so replay events can match even when field precedence changes.
 * - ID-less fallback is allowed only for one anonymous running item with the same
 *   sanitized name. Ambiguous anonymous events are dropped.
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
     * Clears running tools for terminal/session boundaries. Reconnect is intentionally
     * a no-op: the same stream may replay a completion after its cursor is restored.
     * When [retainRecent] is true, safe recent items remain after a successful turn.
     */
    fun reset(current: ChatActivityProgressModel, reason: ChatActivityResetReason, retainRecent: Boolean = false): ChatActivityProgressModel =
        when {
            reason == ChatActivityResetReason.Reconnect -> current
            retainRecent -> current.copy(running = emptyList())
            else -> ChatActivityProgressModel()
        }

    private enum class ToolPhase { Started, Completed }

    private fun applyTool(phase: ToolPhase, event: ToolStreamEvent, current: ChatActivityProgressModel): ChatActivityProgressModel {
        val name = sanitizeName(event.name)
        val aliases = resolveIdentityAliases(event)
        val idKey = aliases.firstOrNull()
        return when (phase) {
            ToolPhase.Started -> startTool(idKey, aliases, name, current)
            ToolPhase.Completed -> completeTool(aliases, name, event.duration, event.isError, current)
        }
    }

    private fun startTool(idKey: String?, aliases: Set<String>, name: String, current: ChatActivityProgressModel): ChatActivityProgressModel {
        // Duplicate starts are idempotent: same identity means the tool is already
        // tracked and nothing changes. ID-keyed duplicates match by any retained alias;
        // anonymous ones match by visible name.
        val duplicate = if (aliases.isNotEmpty()) {
            current.running.any { it.identityAliases.intersect(aliases).isNotEmpty() } ||
                current.recent.any { it.identityAliases.intersect(aliases).isNotEmpty() }
        } else {
            current.running.any { it.identityAliases.isEmpty() && it.displayName == name }
        }
        if (duplicate) return current

        val running = current.running + ChatToolActivityItem(
            key = idKey ?: nextAnonymousKey(current),
            displayName = name,
            detail = null,
            status = ChatToolActivityStatus.Running,
            durationSeconds = null,
            identityAliases = aliases,
        )
        return current.copy(running = running)
    }

    private fun completeTool(
        aliases: Set<String>,
        name: String,
        durationSeconds: Double?,
        isError: Boolean?,
        current: ChatActivityProgressModel,
    ): ChatActivityProgressModel {
        val failed = isError == true
        val status = if (failed) ChatToolActivityStatus.Failed else ChatToolActivityStatus.Completed

        val index = when {
            aliases.isNotEmpty() -> current.running.indexOfFirst { it.identityAliases.intersect(aliases).isNotEmpty() }
            else -> matchFallbackIndex(name, current)
        }
        if (index < 0) return current

        val matched = current.running[index]
        // Duplicate completions are idempotent: an item that already finished with the
        // same outcome is left untouched instead of being pushed into history again.
        if (matched.status != ChatToolActivityStatus.Running && matched.status == status) return current

        val finished = matched.copy(
            detail = null,
            status = status,
            durationSeconds = durationSeconds?.takeIf { it.isFinite() && it >= 0.0 },
        )
        val running = current.running.filterIndexed { i, _ -> i != index }
        val recent = (listOf(finished) + current.recent).take(ChatActivityProgressModel.MAX_RECENT)
        return current.copy(running = running, recent = recent)
    }

    /** Retain every actual server identity alias; tid is a server tool identity. */
    private fun resolveIdentityAliases(event: ToolStreamEvent): Set<String> = listOfNotNull(
        event.callId,
        event.toolCallId,
        event.toolUseId,
        event.tid,
        event.id,
    ).mapNotNull { it.trim().takeIf(String::isNotBlank) }.take(MAX_IDENTITY_ALIASES).toSet()

    private fun matchFallbackIndex(name: String, current: ChatActivityProgressModel): Int {
        val matches = current.running.withIndex().filter { (_, item) ->
            item.identityAliases.isEmpty() && item.displayName == name
        }
        return matches.singleOrNull()?.index ?: -1
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
    private const val MAX_IDENTITY_ALIASES = 5

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
