package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack

import org.jellyfin.sdk.api.client.ApiClient

fun MediaInfo.getMediaStreamContainer() = MediaStreamContainer(
	format = requireNotNull(mediaSource.container)
)

fun MediaInfo.getTracks(api: ApiClient? = null) =
	mediaSource.mediaStreams
		.orEmpty()
		.mapNotNull { it.getMediaStreamTrack(api) }

fun MediaStream.getMediaStreamTrack(api: ApiClient? = null) = when (type) {
	MediaStreamType.AUDIO -> getAudioTrack(this)
	MediaStreamType.VIDEO -> getVideoTrack(this)
	MediaStreamType.SUBTITLE -> getSubtitleTrack(this, api)

	// Ignore other track types
	MediaStreamType.EMBEDDED_IMAGE,
	MediaStreamType.DATA,
	MediaStreamType.LYRIC -> null
}

private fun getAudioTrack(stream: MediaStream) = MediaStreamAudioTrack(
	codec = stream.codec.orEmpty(),
	index = stream.index,
	language = stream.language,
	title = stream.title,
	displayTitle = stream.displayTitle,
	bitrate = stream.bitRate ?: 0,
	channels = stream.channels ?: 1,
	sampleRate = stream.sampleRate ?: 0,
	isDefault = stream.isDefault,
)

private fun getVideoTrack(stream: MediaStream) = MediaStreamVideoTrack(
	codec = stream.codec.orEmpty(),
	bitrate = stream.bitRate ?: 0,
	width = stream.width ?: 0,
	height = stream.height ?: 0,
	videoRange = stream.videoRangeType.name,
)

private fun getSubtitleTrack(stream: MediaStream, api: ApiClient? = null) = MediaStreamSubtitleTrack(
	codec = stream.codec.orEmpty(),
	index = stream.index,
	language = stream.language,
	title = stream.title,
	displayTitle = stream.displayTitle ?: stream.title ?: stream.language,
	isExternal = stream.isExternal,
	isDefault = stream.isDefault,
	isForced = stream.isForced,
	deliveryUrl = stream.deliveryUrl?.let { url ->
		if (url.startsWith("http://") || url.startsWith("https://") || api == null) url
		else api.createUrl(url, ignorePathParameters = true)
	},
)


