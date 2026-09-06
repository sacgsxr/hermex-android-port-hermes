package com.uzairansar.hermex.data.update

import okhttp3.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    private val officialApk =
        "https://github.com/sacgsxr/hermex-android-port-hermes/releases/download/v1.2.0/hermex-v1.2.0.apk"
    private val hash = "A".repeat(64)

    @Test
    fun acceptsOnlyStrictlyHigherVersionCode() {
        val available = decodeAndValidateAppUpdateMetadata(metadata(versionCode = 35), 34, ExpectedPackage)
        assertTrue(available is AppUpdateCheckResult.UpdateAvailable)
        assertEquals(hash.lowercase(), (available as AppUpdateCheckResult.UpdateAvailable).metadata.sha256)

        assertEquals(
            AppUpdateCheckResult.UpToDate,
            decodeAndValidateAppUpdateMetadata(metadata(versionCode = 34), 34, ExpectedPackage),
        )
        assertEquals(
            AppUpdateCheckResult.UpToDate,
            decodeAndValidateAppUpdateMetadata(metadata(versionCode = 33), 34, ExpectedPackage),
        )
    }

    @Test
    fun checkResultsMapToUiStatesUsedByBothScreens() {
        assertEquals(AppUpdateUiState.UpToDate, AppUpdateCheckResult.UpToDate.toUiState())
        val metadata = (decodeAndValidateAppUpdateMetadata(metadata(), 34, ExpectedPackage) as
            AppUpdateCheckResult.UpdateAvailable).metadata
        assertEquals(
            AppUpdateUiState.UpdateAvailable(metadata),
            AppUpdateCheckResult.UpdateAvailable(metadata).toUiState(),
        )
    }

    @Test
    fun rejectsWrongPackageInvalidHashAndNonOfficialApkUrls() {
        assertInvalid(metadata(packageName = "example.attacker.app"))
        assertInvalid(metadata(sha256 = "abc123"))
        assertInvalid(metadata(apkUrl = "http://github.com/sacgsxr/hermex-android-port-hermes/releases/download/v1/app.apk"))
        assertInvalid(metadata(apkUrl = "https://github.com/other/hermex-android-port-hermes/releases/download/v1/app.apk"))
        assertInvalid(metadata(apkUrl = "https://objects.example/app.apk"))
        assertInvalid(metadata(apkUrl = "$officialApk?token=secret"))
    }

    @Test
    fun metadataRequestUsesOnlyFixedPublicGithubUrlAndNoClientCookies() {
        val request = appUpdateMetadataRequest()
        assertEquals(
            "https://github.com/sacgsxr/hermex-android-port-hermes/releases/latest/download/release.json",
            request.url.toString(),
        )
        assertFalse(request.headers.names().any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(request.headers.names().any { it.equals("Cookie", ignoreCase = true) })

        val client = buildAppUpdateHttpClient()
        assertTrue(client.cookieJar === CookieJar.NO_COOKIES)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(5_000, client.readTimeoutMillis)
        assertEquals(8_000, client.callTimeoutMillis)
    }

    @Test
    fun ignoresUnknownMetadataFieldsButRequiresEverySecurityField() {
        val withFutureField = metadata(versionCode = 35).dropLast(1) + ",\"notes\":\"hello\"}"
        assertTrue(
            decodeAndValidateAppUpdateMetadata(withFutureField, 34, ExpectedPackage) is
                AppUpdateCheckResult.UpdateAvailable,
        )
        assertInvalid("{\"versionCode\":35}")
    }

    private fun metadata(
        versionCode: Long = 35,
        packageName: String = ExpectedPackage,
        apkUrl: String = officialApk,
        sha256: String = hash,
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "1.2.0",
          "packageName": "$packageName",
          "apkUrl": "$apkUrl",
          "sha256": "$sha256"
        }
    """.trimIndent()

    private fun assertInvalid(encoded: String) {
        assertThrows(Exception::class.java) {
            decodeAndValidateAppUpdateMetadata(encoded, 34, ExpectedPackage)
        }
    }

    private companion object {
        const val ExpectedPackage = "com.uzairansar.hermex"
    }
}
