package com.uzairansar.hermex.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPillBadgePolicyTest {
    @Test
    fun selectedProfileWinsOverServerActiveProfile() {
        // Mid-switch the composer can point at a different profile than
        // profiles.active; the badge must name the one the next message uses.
        assertEquals(
            "finance",
            resolveModelPillProfileBadge(
                selectedProfileName = "finance",
                activeProfileName = "health",
            ),
        )
    }

    @Test
    fun fallsBackToActiveProfileWhenNoneSelected() {
        assertEquals("health", resolveModelPillProfileBadge(null, "health"))
        assertEquals("health", resolveModelPillProfileBadge("  ", "health"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("builder", resolveModelPillProfileBadge("  builder\n", null))
    }

    @Test
    fun noProfileAnywhereRendersNoBadge() {
        // A blank badge would be worse than none, so the pill drops it entirely.
        assertNull(resolveModelPillProfileBadge(null, null))
        assertNull(resolveModelPillProfileBadge("", "   "))
    }
}
