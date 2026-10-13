package com.uzairansar.hermex.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun parsesProfilesSessionsGroupsAndNormalizesEpoch() {
        val payload = json.parseToJsonElement("""
            {"bot_mode_protocol":true,"profiles":[
              {"name":"lucy","display_name":"Lucy","model":"local","provider":"ollama",
               "ui_meta":{"hermes-bots":{"title":"Launch Lucy","hidden":true},
               "hermes-bots-groups":{"rooms":{"id:room":{"name":"Council","roomId":"r1",
                 "log":[{"text":"old"},{"text":"fresh"}]}}}},
               "is_default":true,"canonical_session":{"id":"stored","resolved_id":"tip","last_active":1777000000}}
            ],"unknown":{"future":true}}
        """.trimIndent()).jsonObject
        val roster = parseBotModeRosterPayload(payload)
        assertTrue(roster.protocolSupported)
        assertEquals("Launch Lucy", roster.profiles.single().displayName)
        assertTrue(roster.profiles.single().hidden)
        assertEquals("tip", roster.profiles.single().canonicalSession?.resolvedId)
        assertEquals(1_777_000_000_000L, roster.profiles.single().canonicalSession?.lastActiveAtMs)
        assertEquals("fresh", roster.groupRooms.single().latestText)
    }

    @Test fun malformedRowsAreDroppedAndUnknownFieldsDoNotMatter() {
        val roster = parseBotModeRosterPayload(json.parseToJsonElement("""
            {"profiles":[{}, {"name":"ok","canonical_session":{"preview":3}}, 4]}
        """.trimIndent()).jsonObject)
        assertEquals(listOf("ok"), roster.profiles.map { it.name })
        assertFalse(roster.protocolSupported)
        assertTrue(roster.groupRooms.isEmpty())
    }

    @Test fun canonicalRoutingPayloadsAreExactAndPreserveIds() {
        val route = CanonicalBotChatRoute("lucy", "stored", "tip")
        assertEquals(mapOf("profile" to "lucy", "title" to "Bot Chat", "include_hidden" to true, "limit" to 200), route.lookup)
        assertEquals("tip", route.title["session_id"])
        assertEquals(true, route.create["hidden"])
        assertEquals("android", route.create["source"])
    }
}
