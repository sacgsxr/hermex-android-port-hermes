package com.uzairansar.hermex.data.update

import com.uzairansar.hermex.BuildConfig
import com.uzairansar.hermex.core.network.HermesJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

private const val ReleaseMetadataUrl =
    "https://github.com/sacgsxr/hermex-android-port-hermes/releases/latest/download/release.json"
private const val OfficialReleasePathPrefix =
    "/sacgsxr/hermex-android-port-hermes/releases/download/"
private const val MaximumMetadataCharacters = 64 * 1024

@Serializable
data class AppUpdateMetadata(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val metadata: AppUpdateMetadata) : AppUpdateCheckResult
    data object UpToDate : AppUpdateCheckResult
}

sealed interface AppUpdateUiState {
    data object NotChecked : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class UpdateAvailable(val metadata: AppUpdateMetadata) : AppUpdateUiState
    data class Error(val message: String) : AppUpdateUiState
}

internal fun AppUpdateCheckResult.toUiState(): AppUpdateUiState = when (this) {
    is AppUpdateCheckResult.UpdateAvailable -> AppUpdateUiState.UpdateAvailable(metadata)
    AppUpdateCheckResult.UpToDate -> AppUpdateUiState.UpToDate
}

fun interface AppUpdateSource {
    suspend fun check(): AppUpdateCheckResult
}

class AppUpdateChecker(
    private val client: OkHttpClient = buildAppUpdateHttpClient(),
    private val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val expectedPackageName: String = BuildConfig.APPLICATION_ID,
) : AppUpdateSource {
    override suspend fun check(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        client.newCall(appUpdateMetadataRequest()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub returned HTTP ${response.code}.")
            }
            val encoded = response.body.charStream().use { reader ->
                val buffer = CharArray(4 * 1024)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MaximumMetadataCharacters) {
                        throw IOException("Update metadata is too large.")
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            decodeAndValidateAppUpdateMetadata(encoded, currentVersionCode, expectedPackageName)
        }
    }
}

internal fun buildAppUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(8, TimeUnit.SECONDS)
    .build()

internal fun appUpdateMetadataRequest(): Request = Request.Builder()
    .url(ReleaseMetadataUrl)
    .get()
    .build()

internal fun decodeAndValidateAppUpdateMetadata(
    encoded: String,
    currentVersionCode: Long,
    expectedPackageName: String,
): AppUpdateCheckResult {
    val metadata = runCatching { HermesJson.decodeFromString<AppUpdateMetadata>(encoded) }
        .getOrElse { throw IOException("Update metadata is invalid.", it) }
    require(metadata.versionCode > 0) { "Update versionCode must be positive." }
    require(metadata.versionName.isNotBlank() && metadata.versionName.length <= 100) {
        "Update versionName is invalid."
    }
    require(metadata.packageName == expectedPackageName) { "Update packageName does not match Hermex." }
    require(Regex("^[0-9a-fA-F]{64}$").matches(metadata.sha256)) { "Update sha256 is invalid." }
    require(isAllowedOfficialApkUrl(metadata.apkUrl)) { "Update APK URL is not an official Hermex release URL." }

    if (metadata.versionCode <= currentVersionCode) return AppUpdateCheckResult.UpToDate
    return AppUpdateCheckResult.UpdateAvailable(metadata.copy(sha256 = metadata.sha256.lowercase()))
}

internal fun isAllowedOfficialApkUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" &&
        url.host == "github.com" &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        url.query == null &&
        url.fragment == null &&
        url.encodedPath.startsWith(OfficialReleasePathPrefix) &&
        url.encodedPath.endsWith(".apk", ignoreCase = true) &&
        url.encodedPath.length > OfficialReleasePathPrefix.length + 4
}
