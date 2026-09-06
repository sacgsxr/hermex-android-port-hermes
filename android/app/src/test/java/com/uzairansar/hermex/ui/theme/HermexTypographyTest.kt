package com.uzairansar.hermex.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class HermexTypographyTest {
    @Test
    fun everyMaterialTextStyleUsesBundledJetBrainsMono() {
        val styles = listOf(
            HermexTypography.displayLarge,
            HermexTypography.displayMedium,
            HermexTypography.displaySmall,
            HermexTypography.headlineLarge,
            HermexTypography.headlineMedium,
            HermexTypography.headlineSmall,
            HermexTypography.titleLarge,
            HermexTypography.titleMedium,
            HermexTypography.titleSmall,
            HermexTypography.bodyLarge,
            HermexTypography.bodyMedium,
            HermexTypography.bodySmall,
            HermexTypography.labelLarge,
            HermexTypography.labelMedium,
            HermexTypography.labelSmall,
        )

        styles.forEach { style ->
            assertEquals(JetBrainsMonoFontFamily, style.fontFamily)
        }
    }
}
