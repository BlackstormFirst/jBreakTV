package org.jellyfin.androidtv.util.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class CustomUpdateInfo(
	@SerialName("version_name")
	val versionName: String,
	@SerialName("download_url")
	val downloadUrl: String,
	@SerialName("release_notes")
	val releaseNotes: String? = null
)

class CustomUpdateChecker(private val context: Context) {

	private val json = Json { ignoreUnknownKeys = true }

	suspend fun checkForUpdate(serverJsonUrl: String): UpdateResult = withContext(Dispatchers.IO) {
		try {
			val url = URL(serverJsonUrl)
			val connection = url.openConnection() as HttpURLConnection
			connection.requestMethod = "GET"
			connection.connectTimeout = 5000
			connection.readTimeout = 5000

			if (connection.responseCode == 200) {
				val responseText = connection.inputStream.bufferedReader().use { it.readText() }
				val updateInfo = json.decodeFromString<CustomUpdateInfo>(responseText)

				val currentVersion = getAppVersionName(context)
				val latestVersion = updateInfo.versionName.trim()

				if (isVersionNewer(currentVersion, latestVersion)) {
					UpdateResult.Available(
						newVersion = latestVersion,
						downloadUrl = updateInfo.downloadUrl,
						releaseNotes = updateInfo.releaseNotes ?: ""
					)
				} else {
					UpdateResult.UpToDate
				}
			} else {
				UpdateResult.Error("Code HTTP : ${connection.responseCode}")
			}
		} catch (e: Exception) {
			UpdateResult.Error(e.localizedMessage ?: "Erreur réseau")
		}
	}

	private fun getAppVersionName(context: Context): String {
		return try {
			context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
		} catch (e: Exception) {
			"0.0.0"
		}
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
