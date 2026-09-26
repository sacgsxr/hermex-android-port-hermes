package com.uzairansar.hermex.ui.settings

import com.uzairansar.hermex.core.model.ModelSummary
import com.uzairansar.hermex.core.model.ModelsLiveResponse
import com.uzairansar.hermex.core.model.ProfileSummary
import com.uzairansar.hermex.core.model.ProfilesResponse
import com.uzairansar.hermex.core.model.ProviderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPickerModelsTest {
    @Test
    fun profileNameRulesMatchIosCreateProfileForm() {
        assertTrue(ProfileNameRules.isValid("research_1"))
        assertTrue(ProfileNameRules.isValid("work-profile"))
        assertFalse(ProfileNameRules.isValid("Work"))
        assertFalse(ProfileNameRules.isValid("-work"))
        assertFalse(ProfileNameRules.isValid("work profile"))
        assertTrue(ProfileNameRules.isValidBaseUrl("http://localhost:11434"))
        assertTrue(ProfileNameRules.isValidBaseUrl("https://api.example.com"))
        assertFalse(ProfileNameRules.isValidBaseUrl("localhost:11434"))
    }

    @Test
    fun effectiveDefaultProfileMirrorsIosPriority() {
        val activeResponse = ProfilesResponse(
            profiles = listOf(ProfileSummary(name = "default", isDefault = true), ProfileSummary(name = "work", isActive = true)),
            active = null,
        )
        val defaultResponse = ProfilesResponse(
            profiles = listOf(ProfileSummary(name = "default", isDefault = true), ProfileSummary(name = "work")),
            active = "",
        )

        assertEquals("work", activeResponse.effectiveDefaultProfileName())
        assertEquals("default", defaultResponse.effectiveDefaultProfileName())
        assertEquals("Default", defaultResponse.displayNameForProfile("default"))
    }

    @Test
    fun overlayLiveModelsReplacesOnlyMatchingProvider() {
        val catalog = listOf(
            ModelSummary(id = "gpt-5", provider = "openai", label = "GPT-5"),
            ModelSummary(id = "claude", provider = "anthropic", label = "Claude"),
        )
        val merged = overlayLiveModels(
            catalog,
            ModelsLiveResponse(
                provider = "openai",
                models = listOf(ModelSummary(id = "gpt-5.5", label = "GPT-5.5")),
                count = 1,
            ),
        )

        assertEquals(listOf("claude", "gpt-5.5"), merged.map { it.id })
        assertEquals("openai", merged.last().provider)
    }

    @Test
    fun openAiLiveModelsAreReducedToTenCurrentChatAndCodingAliases() {
        val ids = listOf(
            "gpt-6", "gpt-6-pro", "gpt-6-codex", "gpt-6-mini", "gpt-6-nano",
            "gpt-5.6", "gpt-5.6-pro", "gpt-5.6-codex", "gpt-5.6-mini", "gpt-5.6-nano",
            "gpt-5.5", "gpt-5.4", "o4-mini", "text-embedding-4-large", "gpt-image-2",
            "gpt-6-2026-09-01", "ft:gpt-6:example",
        )

        val selected = selectCurrentOpenAiModels(
            models = ids.map { ModelSummary(id = it, provider = "openai") },
            limit = 10,
        ).mapNotNull { it.id }

        assertEquals(10, selected.size)
        assertTrue("gpt-6" in selected)
        assertTrue("gpt-5.6-nano" in selected)
        assertFalse("gpt-5.5" in selected)
        assertFalse("text-embedding-4-large" in selected)
        assertFalse("gpt-image-2" in selected)
        assertFalse("gpt-6-2026-09-01" in selected)
        assertFalse("ft:gpt-6:example" in selected)
    }

    @Test
    fun openAiSelectionRetainsTheConfiguredModelOutsideTheCurrentTen() {
        val models = (1..12).map { minor -> ModelSummary(id = "gpt-5.$minor", provider = "openai") }
        val retained = ModelSummary(id = "gpt-4.1", provider = "openai")

        val selected = selectCurrentOpenAiModels(models, retainedModel = retained).mapNotNull { it.id }

        assertEquals(11, selected.size)
        assertEquals("gpt-4.1", selected.last())
    }

    @Test
    fun otherProvidersAreAlsoCappedAtTenAndRetainTheConfiguredModel() {
        val models = (1..12).map { index -> ModelSummary(id = "claude-$index", provider = "anthropic") }
        val retained = ModelSummary(id = "claude-legacy", provider = "anthropic")

        val selected = selectCurrentProviderModels("anthropic", models, retained).mapNotNull { it.id }

        assertEquals(11, selected.size)
        assertEquals((1..10).map { "claude-$it" }, selected.take(10))
        assertEquals("claude-legacy", selected.last())
    }

    @Test
    fun defaultModelGroupsUseProviderNamesAndSearch() {
        val groups = defaultModelPickerGroups(
            models = listOf(
                ModelSummary(id = "gpt-5", provider = "openai", label = "GPT-5"),
                ModelSummary(id = "claude-sonnet", provider = "anthropic", label = "Claude Sonnet"),
            ),
            providers = listOf(ProviderSummary(id = "anthropic", name = "Anthropic")),
            query = "claude",
        )

        assertEquals(1, groups.size)
        assertEquals("Anthropic", groups.single().title)
        assertEquals("claude-sonnet", groups.single().models.single().id)
    }
}
