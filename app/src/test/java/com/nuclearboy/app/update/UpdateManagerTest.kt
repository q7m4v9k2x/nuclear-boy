package com.nuclearboy.app.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateManagerTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val client = mockk<OkHttpClient>()
    private val stored = mutableMapOf<String, Any>()
    private var responseBody = ""
    private var responseCode = 200

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { context.getSharedPreferences("nuclear_update", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getLong(any(), any()) } answers { stored[firstArg()] as? Long ?: secondArg() }
        every { prefs.getString(any(), any()) } answers { stored[firstArg()] as? String ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } answers {
            stored[firstArg<String>()] = secondArg<Long>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            stored[firstArg<String>()] = secondArg<String>()
            editor
        }
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            mockk<Call> {
                every { execute() } answers {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message("test response")
                        .body(responseBody.toResponseBody())
                        .build()
                }
            }
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `release build selects release apk and retains integrity metadata`() = runTest {
        val releaseApk = asset("nuclear-boy-1.1.75-release.apk").copy(size = 123L, digest = "sha256:abc")
        serveRelease(asset("nuclear-boy-1.1.75-debug.apk"), asset("app.apk"), releaseApk)

        val result = manager().checkForUpdate(force = true) as UpdateManager.UpdateResult.Available

        assertEquals(releaseApk.browser_download_url, result.url)
        assertEquals(123L, result.expectedSize)
        assertEquals("sha256:abc", result.expectedDigest)
        assertTrue(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `debug build selects debug apk when both variants are published`() = runTest {
        val debugApk = asset("nuclear-boy-1.1.75-debug.apk")
        serveRelease(asset("nuclear-boy-1.1.75-release.apk"), asset("app.apk"), debugApk)

        val result = manager(debug = true).checkForUpdate(force = true) as UpdateManager.UpdateResult.Available

        assertEquals(debugApk.browser_download_url, result.url)
    }

    @Test
    fun `missing release apk is actionable and does not throttle the retry after upload`() = runTest {
        serveRelease(asset("nuclear-boy-1.1.75-debug.apk"))
        val manager = manager()

        val error = manager.checkForUpdate() as UpdateManager.UpdateResult.Error

        assertTrue(error.message.contains("v1.1.75"))
        assertTrue(error.message.contains("正式版（release）"))
        assertFalse(stored.containsKey("last_check_ms"))
        serveRelease(asset("nuclear-boy-1.1.75-debug.apk"), asset("nuclear-boy-1.1.75-release.apk"))
        assertTrue(manager.checkForUpdate() is UpdateManager.UpdateResult.Available)
        verify(exactly = 2) { client.newCall(any()) }
    }

    @Test
    fun `debug build cannot use a release only update`() = runTest {
        serveRelease(asset("nuclear-boy-1.1.75-release.apk"))

        val result = manager(debug = true).checkForUpdate(force = true) as UpdateManager.UpdateResult.Error

        assertTrue(result.message.contains("调试版（debug）"))
        assertFalse(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `already current version does not require an apk asset`() = runTest {
        serveRelease()

        val result = manager(currentVersion = "1.1.75").checkForUpdate(force = true)

        assertEquals(UpdateManager.UpdateResult.UpToDate, result)
        assertTrue(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `notification security failure does not hide a downloadable update`() = runTest {
        serveRelease(asset("app-release.apk"))
        val manager = manager(notificationSender = { throw SecurityException("Notification permission denied") })

        assertTrue(manager.checkForUpdate(force = true) is UpdateManager.UpdateResult.Available)
        assertTrue(stored.containsKey("last_check_ms"))
        assertFalse(stored.containsKey("last_known_version"))
    }

    @Test
    fun `disabled notifications can be retried and successful notification is not repeated`() = runTest {
        serveRelease(asset("app-release.apk"))
        var notificationsEnabled = false
        var notificationAttempts = 0
        val manager = manager(notificationSender = {
            notificationAttempts++
            notificationsEnabled
        })

        assertTrue(manager.checkForUpdate(force = true) is UpdateManager.UpdateResult.Available)
        assertFalse(stored.containsKey("last_known_version"))
        notificationsEnabled = true
        assertTrue(manager.checkForUpdate(force = true) is UpdateManager.UpdateResult.Available)
        assertEquals("v1.1.75", stored["last_known_version"])
        assertTrue(manager.checkForUpdate(force = true) is UpdateManager.UpdateResult.Available)
        assertEquals(2, notificationAttempts)
    }

    @Test
    fun `failed HTTP check is not cached as a successful check`() = runTest {
        responseCode = 503
        responseBody = "Service unavailable"

        val result = manager().checkForUpdate(force = true)

        assertEquals(UpdateManager.UpdateResult.Error("GitHub HTTP 503"), result)
        assertFalse(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `invalid JSON is not cached as a successful check`() = runTest {
        responseBody = "not JSON"

        assertTrue(manager().checkForUpdate(force = true) is UpdateManager.UpdateResult.Error)
        assertFalse(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `non HTTPS apk is never offered as an update`() = runTest {
        serveRelease(asset("app-release.apk").copy(browser_download_url = "http://example.test/app-release.apk"))

        assertTrue(manager().checkForUpdate(force = true) is UpdateManager.UpdateResult.Error)
        assertFalse(stored.containsKey("last_check_ms"))
    }

    @Test
    fun `legacy unnamed variant apk remains supported`() = runTest {
        val apk = asset("nuclear-boy.apk")
        serveRelease(apk)

        val result = manager().checkForUpdate(force = true) as UpdateManager.UpdateResult.Available

        assertEquals(apk.browser_download_url, result.url)
    }

    private fun manager(
        debug: Boolean = false,
        currentVersion: String = "1.1.74",
        notificationSender: (UpdateManager.UpdateResult.Available) -> Boolean = { true },
    ): UpdateManager {
        every { context.packageName } returns if (debug) "com.nuclearboy.app.debug" else "com.nuclearboy.app"
        val packageInfo = mockk<PackageInfo>().apply { versionName = currentVersion }
        val packageManager = mockk<PackageManager>()
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } returns packageInfo
        return UpdateManager(context, client, notificationSender)
    }

    private fun serveRelease(vararg assets: UpdateManager.GitHubAsset) {
        responseCode = 200
        responseBody = Json.encodeToString(
            UpdateManager.GitHubRelease(
                tag_name = "v1.1.75",
                html_url = "https://github.com/q7m4v9k2x/nuclear-boy/releases/tag/v1.1.75",
                assets = assets.toList(),
            ),
        )
    }

    private fun asset(name: String) = UpdateManager.GitHubAsset(
        name = name,
        browser_download_url = "https://github.com/q7m4v9k2x/nuclear-boy/releases/download/v1.1.75/$name",
    )
}
