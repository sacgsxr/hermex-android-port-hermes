package com.uzairansar.hermex.ui.chat

import com.uzairansar.hermex.core.model.ModelSummary
import com.uzairansar.hermex.core.model.ProviderSummary

enum class ModelExecutionLocation(val label: String) {
    Local("Local"),
    Remote("Remote"),
    Unverified("Unverified"),
}

object ModelExecutionLocationResolver {
    fun resolve(model: ModelSummary?, providers: List<ProviderSummary>): ModelExecutionLocation {
        val providerId = (model?.provider ?: model?.providerId)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ModelExecutionLocation.Unverified
        val matches = providers.filter { provider ->
            provider.id?.trim()?.equals(providerId, ignoreCase = true) == true
        }
        if (matches.size != 1) return ModelExecutionLocation.Unverified
        return when (matches.single().isSelfHosted) {
            true -> ModelExecutionLocation.Local
            false -> ModelExecutionLocation.Remote
            null -> ModelExecutionLocation.Unverified
        }
    }
}
