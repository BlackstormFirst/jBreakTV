package org.jellyfin.androidtv.util.updater

import kotlinx.serialization.*

@Serializable
data class GitHubRelease(
	@SerialName("tag_name")
	val tagName: String,
	@SerialName("html_url")
	val htmlUrl: String,
	val body: String?,
	val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
	val name: String,
	@SerialName("browser_download_rl")
	val downloadUrl: String,
	val size: Long
)
