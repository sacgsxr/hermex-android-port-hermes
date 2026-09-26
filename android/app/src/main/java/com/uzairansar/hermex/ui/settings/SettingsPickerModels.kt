package com.uzairansar.hermex.ui.settings

import com.uzairansar.hermex.core.model.ModelSummary
import com.uzairansar.hermex.core.model.ModelsLiveResponse
import com.uzairansar.hermex.core.model.ProfileSummary
import com.uzairansar.hermex.core.model.ProfilesResponse
import com.uzairansar.hermex.core.model.ProviderSummary
import com.uzairansar.hermex.data.preferences.displayModelTitle
import com.uzairansar.hermex.data.preferences.modelIdentifier
import com.uzairansar.hermex.data.preferences.normalizedProvider
import java.util.Locale

data class SettingsModelPickerGroup(
    val id: String,
    val title: String,
    val providerId: String?,
    val models: List<ModelSummary>,
)

object ProfileNameRules {
    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name.length > 64) return false
        val first = name.first()
        if (!first.isLowercaseAsciiLetterOrDigit()) return false
        return name.all { it.isLowercaseAsciiLetterOrDigit() || it == '-' || it == '_' }
    }

    fun isValidBaseUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")
}

fun overlayLiveModels(
    catalogModels: List<ModelSummary>,
    live: ModelsLiveResponse,
    selectedModelId: String? = null,
): List<ModelSummary> {
    val provider = live.provider?.trim()?.takeIf { it.isNotBlank() } ?: return catalogModels
    var liveModels = live.models.orEmpty()
        .map { model ->
            if (model.normalizedProvider == null) {
                model.copy(provider = provider)
            } else {
                model
            }
        }
        .filter { it.modelIdentifier != null }
    if (liveModels.isEmpty()) return catalogModels

    val providerKey = provider.lowercase(Locale.US)
    val selected = catalogModels.firstOrNull { model ->
        model.normalizedProvider?.lowercase(Locale.US) == providerKey &&
            model.modelIdentifier == selectedModelId
    }
    liveModels = selectCurrentProviderModels(providerKey, liveModels, selected)
    return catalogModels.filter { it.normalizedProvider?.lowercase(Locale.US) != providerKey } + liveModels
}

internal fun selectCurrentProviderModels(
    provider: String,
    models: List<ModelSummary>,
    retainedModel: ModelSummary? = null,
    limit: Int = 10,
): List<ModelSummary> {
    if (provider == "openai" || provider == "openai-codex") {
        return selectCurrentOpenAiModels(models, retainedModel, limit)
    }
    val selected = models
        .distinctBy { it.modelIdentifier?.lowercase(Locale.US) }
        .take(limit.coerceAtLeast(0))
        .toMutableList()
    val retainedId = retainedModel?.modelIdentifier
    if (retainedId != null && selected.none { it.modelIdentifier.equals(retainedId, ignoreCase = true) }) {
        selected += retainedModel
    }
    return selected
}

internal fun selectCurrentOpenAiModels(
    models: List<ModelSummary>,
    retainedModel: ModelSummary? = null,
    limit: Int = 10,
): List<ModelSummary> {
    val relevant = models
        .distinctBy { it.modelIdentifier?.lowercase(Locale.US) }
        .filter { model -> model.modelIdentifier?.isCurrentOpenAiChatModel() == true }
        .sortedWith(
            compareByDescending<ModelSummary> { it.modelIdentifier?.openAiVersionRank() ?: OpenAiVersionRank.Zero }
                .thenByDescending { it.modelIdentifier?.openAiVariantRank() ?: 0 }
                .thenBy { it.modelIdentifier.orEmpty() },
        )
        .take(limit.coerceAtLeast(0))
    val retainedId = retainedModel?.modelIdentifier
    return if (retainedId != null && relevant.none { it.modelIdentifier.equals(retainedId, ignoreCase = true) }) {
        relevant + retainedModel
    } else {
        relevant
    }
}

private val openAiSnapshotDate = Regex("(?:^|-)20\\d{2}-\\d{2}-\\d{2}(?:$|-)")
private val openAiVersion = Regex("^(?:gpt-|chatgpt-)?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")
private val openAiReasoningVersion = Regex("^o(\\d+)(?:[.-](\\d+))?")

private fun String.isCurrentOpenAiChatModel(): Boolean {
    val id = lowercase(Locale.US)
    if (openAiSnapshotDate.containsMatchIn(id) || id.startsWith("ft:")) return false
    if (listOf(
            "embedding", "moderation", "whisper", "tts", "audio", "realtime",
            "transcrib", "image", "dall-e", "sora", "search-preview", "computer-use",
            "babbage", "davinci",
        ).any(id::contains)
    ) return false
    return id.startsWith("gpt-") || id.startsWith("chatgpt-") ||
        openAiReasoningVersion.containsMatchIn(id) || id.startsWith("codex-")
}

private data class OpenAiVersionRank(val major: Int, val minor: Int, val patch: Int) : Comparable<OpenAiVersionRank> {
    override fun compareTo(other: OpenAiVersionRank): Int =
        compareValuesBy(this, other, OpenAiVersionRank::major, OpenAiVersionRank::minor, OpenAiVersionRank::patch)

    companion object {
        val Zero = OpenAiVersionRank(0, 0, 0)
    }
}

private fun String.openAiVersionRank(): OpenAiVersionRank {
    val id = lowercase(Locale.US)
    val match = openAiVersion.find(id)
    if (match != null) {
        return OpenAiVersionRank(
            major = match.groupValues[1].toIntOrNull() ?: 0,
            minor = match.groupValues[2].toIntOrNull() ?: 0,
            patch = match.groupValues[3].toIntOrNull() ?: 0,
        )
    }
    val reasoning = openAiReasoningVersion.find(id)
    return OpenAiVersionRank(
        major = reasoning?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
        minor = reasoning?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
        patch = 0,
    )
}

private fun String.openAiVariantRank(): Int {
    val id = lowercase(Locale.US)
    return when {
        id.endsWith("-latest") -> 6
        "pro" in id -> 5
        "codex" in id -> 4
        "chat" in id -> 3
        "mini" in id -> 2
        "nano" in id -> 1
        else -> 7
    }
}

fun defaultModelPickerGroups(
    models: List<ModelSummary>,
    providers: List<ProviderSummary>,
    query: String,
): List<SettingsModelPickerGroup> {
    val providerNames = providers.associate { provider ->
        provider.id.orEmpty().lowercase(Locale.US) to (provider.name?.trim()?.takeIf { it.isNotBlank() } ?: provider.id.orEmpty())
    }
    val grouped = linkedMapOf<String, MutableList<ModelSummary>>()
    val providerIds = linkedMapOf<String, String?>()

    models.forEach { model ->
        if (!model.matchesDefaultModelQuery(query)) return@forEach
        val providerId = model.normalizedProvider
        val key = providerId?.lowercase(Locale.US) ?: "default"
        grouped.getOrPut(key) { mutableListOf() } += model
        providerIds.putIfAbsent(key, providerId)
    }

    return grouped.map { (key, groupModels) ->
        val providerId = providerIds[key]
        SettingsModelPickerGroup(
            id = "model-group-$key",
            title = providerId?.let { providerNames[it.lowercase(Locale.US)] ?: it } ?: "Models",
            providerId = providerId,
            models = groupModels,
        )
    }
}

fun ModelSummary.matchesDefaultModelQuery(query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return true
    return listOfNotNull(displayModelTitle, modelIdentifier, normalizedProvider)
        .any { it.contains(normalizedQuery, ignoreCase = true) }
}

fun ProfileSummary.normalizedProfileName(): String? =
    name?.trim()?.takeIf { it.isNotBlank() }

fun ProfileSummary.settingsDisplayName(): String {
    displayName?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val profileName = normalizedProfileName() ?: return "Profile"
    return if (profileName == "default") "Default" else profileName
}

fun ProfileSummary.settingsDetails(): String? {
    val details = buildList {
        model?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        provider?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        skillCount?.let { add("$it skills") }
    }
    return details.takeIf { it.isNotEmpty() }?.joinToString(" - ")
}

fun ProfilesResponse.effectiveDefaultProfileName(): String? {
    active?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    profiles.orEmpty().firstOrNull { it.isActive == true }?.normalizedProfileName()?.let { return it }
    profiles.orEmpty().firstOrNull { it.isDefault == true }?.normalizedProfileName()?.let { return it }
    return profiles.orEmpty().firstNotNullOfOrNull { it.normalizedProfileName() }
}

fun ProfilesResponse.displayNameForProfile(profileName: String?): String? {
    val normalized = profileName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return profiles.orEmpty().firstOrNull { it.normalizedProfileName() == normalized }?.settingsDisplayName()
        ?: if (normalized == "default") "Default" else normalized
}

private fun Char.isLowercaseAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in '0'..'9'
