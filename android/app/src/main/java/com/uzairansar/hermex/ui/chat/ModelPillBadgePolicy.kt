package com.uzairansar.hermex.ui.chat

/**
 * Resolve the profile name to badge on the composer model pill.
 *
 * The badge used to show the model's execution location ("Local" / "Remote"),
 * but the gateway only ever marks `ollama` and `lmstudio` as self-hosted (see
 * `_SELF_HOSTED_PROVIDER_IDS` in the WebUI's `api/providers.py`). A local
 * llama.cpp router provider is therefore reported as `Remote` too, so the badge
 * read "Remote" in every profile regardless of where the model actually ran --
 * worse than showing no badge at all.
 *
 * The active profile is the genuinely useful fact here: it is what tells you
 * *which* budget, toolset, and model default you are talking to, and it was the
 * one thing the pill could not previously tell you. Execution location is still
 * available, and more accurate, in the model picker's own provider rows, so
 * nothing is lost by dropping it from the pill.
 *
 * [selectedProfileName] is the profile the composer is pointed at (which may
 * differ from the server's active profile mid-switch); [activeProfileName] is
 * the server's `profiles.active`. The selected profile wins because that is
 * the one the next message will actually use.
 *
 * Returns null when neither is usable, so the caller can skip the badge
 * entirely instead of rendering an empty pill.
 */
internal fun resolveModelPillProfileBadge(
    selectedProfileName: String?,
    activeProfileName: String?,
): String? {
    val candidate = selectedProfileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: activeProfileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return candidate
}
