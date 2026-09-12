package org.jellyfin.androidtv.util.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
	@SerialName("tag_name")
	val tagName: String,
	@SerialName("html_url")
	val htmlUrl: String,
	@SerialName("prerelease")
	val prerelease: Boolean = false,
	val body: String? = null,
	val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
	val name: String,
	@SerialName("browser_download_url")
	val downloadUrl: String,
	val size: Long
)
