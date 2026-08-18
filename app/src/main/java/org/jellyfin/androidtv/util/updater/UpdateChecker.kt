package org.jellyfin.androidtv.util.updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class UpdateCheck(private val context: Context) {
	private val json = Json { ignoreUnknownKeys = true }

	suspend fun checkForUpdate(repoOwner: String, repoName: String): UpdateResult = withContext(Dispatchers.IO) {
		try {
			val url = URL("https://api/github.com/repos/$repoOwner/$repoName/release/latest")
			val connection = url.openConnection() as HttpURLConnection
			connection.requestMethod = "GET"
			connection.setRequestProperty("Accept", "application/vnd.github.v3+json")

			if (connection.responseCode == 200) {
				val responseText = connection.inputStream.bufferedReader().use { it.readText() }
				val release = json.decodeFromString<GitHubRelease>(responseText)

				val currentVersion = getAppVersionName(context)
				val latestVersion = release.tagName.removePrefix("v").trim()

				if (isVersionNewer(currentVersion, latestVersion)) {
					val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
					if (apkAsset != null) {
						UpdateResult.Available(
							newVersion = latestVersion,
							downloadUrl = apkAsset.downloadUrl,
							releaseNotes = release.body ?: ""
						)
					} else {
						UpdateResult.NoApkFound
					}
				} else {
					UpdateResult.UpToDate
				}
			} else {
				UpdateResult.Error("Code retour HTTP : ${connection.responseCode}")
			}
		} catch (e: Exception) {
			UpdateResult.Error(e.localizedMessage ?: "Erreur réseau inconnue")
		}
	}
	private fun getAppVersionName(context: Context): String {
		return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
	}

	private fun isVersionNewer(current: String, latest: String): Boolean {
		val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
		val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

		val length = maxOf(currentParts.size, latestParts.size)
		for (i in 0 until length) {
			val curr = currentParts.getOrElse(i) { 0 }
			val lat = latestParts.getOrElse(i) { 0 }
			if (lat > curr) return true
			if (lat < curr) return false
		}
		return false
	}
}

sealed class UpdateResult {
	data class Available(val newVersion: String, val downloadUrl: String, val releaseNotes: String) : UpdateResult()
	object UpToDate : UpdateResult()
	object NoApkFound : UpdateResult()
	data class Error(val message: String) : UpdateResult()
}
