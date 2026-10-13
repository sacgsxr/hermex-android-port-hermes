package com.uzairansar.hermex.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

@Serializable
internal data class LocalKokoroSpeechRequest(
    val model: String = "kokoro",
    val input: String,
    val voice: String,
    val response_format: String = "mp3",
    val speed: Float,
)

internal object LocalKokoroTtsPolicy {
    const val MaximumCharacters = 5_000

    fun endpointIsValid(endpoint: String): Boolean {
        val url = endpoint.trim().toHttpUrlOrNull() ?: return false
        return url.scheme in setOf("http", "https") && url.host.isNotBlank() &&
            url.username.isEmpty() && url.password.isEmpty()
    }

    fun shouldUse(text: String, endpoint: String, voice: String): Boolean =
        text.isNotBlank() && text.length <= MaximumCharacters &&
            endpointIsValid(endpoint) && voice.isNotBlank()
}

internal class LocalKokoroTtsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { encodeDefaults = true },
) {
    suspend fun synthesize(text: String, endpoint: String, voice: String, speed: Float): ByteArray =
        request(text, endpoint, voice, speed)

    suspend fun test(endpoint: String, voice: String, speed: Float): ByteArray =
        request("Hermex local speech test.", endpoint, voice, speed)

    private suspend fun request(text: String, endpoint: String, voice: String, speed: Float): ByteArray =
        withContext(Dispatchers.IO) {
            require(LocalKokoroTtsPolicy.shouldUse(text, endpoint, voice)) { "Enter a valid local TTS URL and voice." }
            val base = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
                ?: error("Enter a valid local TTS URL.")
            val url = base.newBuilder()
                .addPathSegment("audio")
                .addPathSegment("speech")
                .build()
            val body = json.encodeToString(
                LocalKokoroSpeechRequest(
                    input = text,
                    voice = voice.trim(),
                    speed = speed.coerceIn(0.25f, 4.0f),
                ),
            ).toRequestBody("application/json".toMediaType())
            // This client is intentionally independent from HermesApiClient: no Hermes bearer,
            // cookies, or custom headers are ever forwarded to the local TTS origin.
            client.newCall(
                Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Accept", "audio/mpeg")
                    .build(),
            ).execute().use { response ->
                check(response.isSuccessful) { "Local Kokoro returned HTTP ${response.code}." }
                val bytes = response.body?.bytes().orEmpty()
                check(bytes.isNotEmpty()) { "Local Kokoro returned empty audio." }
                bytes
            }
        }
}
