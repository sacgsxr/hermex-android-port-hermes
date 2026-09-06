package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ModelSummary
import com.uzairansar.hermex.core.model.ProviderSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelExecutionLocationTest {
    private val model = ModelSummary(id = "model", provider = "ollama")

    @Test
    fun explicitSelfHostedProviderIsLocal() {
        assertEquals(
            ModelExecutionLocation.Local,
            ModelExecutionLocationResolver.resolve(
                model,
                listOf(ProviderSummary(id = "OLLAMA", isSelfHosted = true)),
            ),
        )
    }

    @Test
    fun explicitNonSelfHostedProviderIsRemote() {
        assertEquals(
            ModelExecutionLocation.Remote,
            ModelExecutionLocationResolver.resolve(
                model,
                listOf(ProviderSummary(id = "ollama", isSelfHosted = false)),
            ),
        )
    }

    @Test
    fun missingNullableOrAmbiguousProviderEvidenceIsUnverified() {
        assertEquals(ModelExecutionLocation.Unverified, ModelExecutionLocationResolver.resolve(model, emptyList()))
        assertEquals(
            ModelExecutionLocation.Unverified,
            ModelExecutionLocationResolver.resolve(model, listOf(ProviderSummary(id = "ollama"))),
        )
        assertEquals(
            ModelExecutionLocation.Unverified,
            ModelExecutionLocationResolver.resolve(
                model,
                listOf(
                    ProviderSummary(id = "ollama", isSelfHosted = true),
                    ProviderSummary(id = "OLLAMA", isSelfHosted = true),
                ),
            ),
        )
        assertEquals(
            ModelExecutionLocation.Unverified,
            ModelExecutionLocationResolver.resolve(
                ModelSummary(id = "model", provider = "friendly-name"),
                listOf(ProviderSummary(id = "provider-id", name = "friendly-name", isSelfHosted = true)),
            ),
        )
    }
}
