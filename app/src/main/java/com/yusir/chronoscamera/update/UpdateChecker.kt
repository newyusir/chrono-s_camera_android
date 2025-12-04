package com.yusir.chronoscamera.update
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_API_URL = "https:
    private const val RELEASES_URL = "https:
    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val downloadUrl: String,
        val releasePageUrl: String = RELEASES_URL
    )
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersion = getCurrentVersion(context) ?: return@withContext null
            val latestRelease = fetchLatestRelease() ?: return@withContext null
            val latestVersion = latestRelease.first
            val downloadUrl = latestRelease.second
            val hasUpdate = isNewerVersion(latestVersion, currentVersion)
            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion, HasUpdate: $hasUpdate")
            UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                downloadUrl = downloadUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for update", e)
            null
        }
    }
    private fun getCurrentVersion(context: Context): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current version", e)
            null
        }
    }
    private fun fetchLatestRelease(): Pair<String, String>? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "ChronosCamera-Android")
                connectTimeout = 10000
                readTimeout = 10000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                return null
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val tagName = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isEmpty()) {
                apkUrl = RELEASES_URL
            }
            return Pair(tagName, apkUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch latest release", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
            val maxLength = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLength) {
                val latestPart = latestParts.getOrElse(i) { 0 }
                val currentPart = currentParts.getOrElse(i) { 0 }
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
            return false 
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compare versions", e)
            return false
        }
    }
}