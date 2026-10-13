package com.uzairansar.hermex.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Pure projection of Relay's optional Gateway profiles.list Bot Mode payload. */
data class BotModeProfile(
    val name: String,
    val displayName: String,
    val title: String = "",
    val hidden: Boolean = false,
    val model: String = "",
    val provider: String = "",
    val canonicalSession: BotModeSession? = null,
    val workerSession: BotModeSession? = null,
    val lastSession: BotModeSession? = null,
)

data class BotModeSession(
    val id: String,
    val resolvedId: String,
    val title: String = "",
    val preview: String = "",
    val lastActiveAtMs: Long = 0,
)

data class BotModeRosterPayload(
    val profiles: List<BotModeProfile> = emptyList(),
    val groupRooms: List<BotModeGroupRoom> = emptyList(),
    val protocolSupported: Boolean = false,
)

data class BotModeGroupRoom(
    val key: String,
    val name: String,
    val roomId: String? = null,
    val latestText: String? = null,
)

data class CanonicalBotChatRoute(
    val profile: String,
    val storedSessionId: String? = null,
    val resolvedSessionId: String? = null,
) {
    val lookup: Map<String, Any?> get() = mapOf(
        "profile" to profile,
        "title" to BOT_CHAT_TITLE,
        "include_hidden" to true,
        "limit" to 200,
    )
    val create: Map<String, Any?> get() = mapOf(
        "cols" to DEFAULT_BOT_CHAT_COLS,
        "source" to "android",
        "profile" to profile,
        "title" to BOT_CHAT_TITLE,
        "hidden" to true,
    )
    val title: Map<String, Any?> get() = mapOf(
        "session_id" to (resolvedSessionId ?: storedSessionId),
        "title" to BOT_CHAT_TITLE,
    )

    init { require(profile.isNotBlank()) { "profile must not be blank" } }
}

const val BOT_CHAT_TITLE = "Bot Chat"
const val DEFAULT_BOT_CHAT_COLS = 80

fun parseBotModeRosterPayload(payload: JsonObject): BotModeRosterPayload {
    val rows = (payload["profiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val profiles = rows.mapNotNull { row ->
        val name = row.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val ui = (row["ui_meta"] as? JsonObject)
        val botMeta = ui?.get("hermes-bots") as? JsonObject
        BotModeProfile(
            name = name,
            displayName = botMeta?.string("title")?.takeIf { it.isNotBlank() }
                ?: row.string("display_name")?.takeIf { it.isNotBlank() } ?: name,
            title = botMeta?.string("title")?.orEmpty().orEmpty().take(128),
            hidden = botMeta?.boolean("hidden") == true,
            model = row.string("model").orEmpty(),
            provider = row.string("provider").orEmpty(),
            canonicalSession = parseBotModeSession(row["canonical_session"] as? JsonObject),
            workerSession = parseBotModeSession(row["worker_session"] as? JsonObject),
            lastSession = parseBotModeSession(row["last_session"] as? JsonObject),
        )
    }
    val default = rows.firstOrNull { it.boolean("is_default") == true }
        ?: rows.firstOrNull { it.string("name") == "default" }
    val rooms = (((default?.get("ui_meta") as? JsonObject)?.get("hermes-bots-groups") as? JsonObject)
        ?.get("rooms") as? JsonObject).orEmpty().entries.take(64).mapNotNull { (key, raw) ->
            val room = raw as? JsonObject ?: return@mapNotNull null
            val name = room.string("name")?.takeIf { it.isNotBlank() } ?: key.substringAfter(':')
            val latest = (room["log"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .mapNotNull { it.string("text")?.takeIf(String::isNotBlank) }.lastOrNull()?.take(1200)
            BotModeGroupRoom(key.take(256), name.take(128), room.string("roomId")?.take(128), latest)
        }
    return BotModeRosterPayload(profiles, rooms, payload.boolean("bot_mode_protocol") == true)
}

private fun parseBotModeSession(row: JsonObject?): BotModeSession? {
    val id = row?.string("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return BotModeSession(
        id = id,
        resolvedId = row.string("resolved_id")?.takeIf { it.isNotBlank() } ?: id,
        title = row.string("title").orEmpty().take(256),
        preview = row.string("preview").orEmpty().take(512),
        lastActiveAtMs = normalizeBotEpoch(row.long("last_active")),
    )
}

private fun normalizeBotEpoch(value: Long): Long = when {
    value <= 0L -> 0L
    value < 10_000_000_000L -> value * 1_000L
    else -> value
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull
    ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
