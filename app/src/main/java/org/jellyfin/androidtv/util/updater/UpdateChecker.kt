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
			// Récupération des dernières releases (inclut les pré-releases)
			val response = fetchFromApi("https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=10")
				?: return@withContext UpdateResult.NoApkFound

			val releases = json.decodeFromString<List<GitHubRelease>>(response)

			// Sélection de la version pré-release la plus récente
			val latestPreRelease = releases.firstOrNull { it.prerelease }
				?: return@withContext UpdateResult.NoApkFound

			return@withContext processRelease(latestPreRelease)

		} catch (e: Exception) {
			UpdateResult.Error(e.localizedMessage ?: "Erreur réseau inconnue")
		}
	}

	private fun fetchFromApi(urlString: String): String? {
		val url = URL(urlString)
		val connection = url.openConnection() as HttpURLConnection
		connection.requestMethod = "GET"
		connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
		connection.connectTimeout = 5000
		connection.readTimeout = 5000

		return if (connection.responseCode == 200) {
			connection.inputStream.bufferedReader().use { it.readText() }
		} else {
			null
		}
	}

	private fun processRelease(release: GitHubRelease): UpdateResult {
		val currentVersion = getAppVersionName(context)
		val latestVersion = release.tagName.removePrefix("v").trim()

		if (isVersionNewer(currentVersion, latestVersion)) {
			val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
			return if (apkAsset != null) {
				UpdateResult.Available(
					newVersion = latestVersion,
					downloadUrl = apkAsset.downloadUrl,
					releaseNotes = release.body ?: ""
				)
			} else {
				UpdateResult.NoApkFound
			}
		} else {
			return UpdateResult.UpToDate
		}
	}

	private fun getAppVersionName(context: Context): String {
		return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
	}

	private fun isVersionNewer(current: String, latest: String): Boolean {
		// Isoler le numéro de version de base avant un éventuel tiret (ex: "1.2.0-debug" -> "1.2.0")
		val currentClean = current.split("-").first()
		val latestClean = latest.split("-").first()

		val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }
		val latestParts = latestClean.split(".").mapNotNull { it.toIntOrNull() }

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
