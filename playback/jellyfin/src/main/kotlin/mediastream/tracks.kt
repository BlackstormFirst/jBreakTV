package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaStreamAttachmentTrack
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

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

	MediaStreamType.EMBEDDED_IMAGE,
	MediaStreamType.DATA,
	MediaStreamType.LYRIC -> {
		val codecLower = codec?.lowercase() ?: ""
		val titleLower = title?.lowercase() ?: ""
		val displayTitleLower = displayTitle?.lowercase() ?: ""
		if (codecLower.contains("ttf") || codecLower.contains("otf") || codecLower.contains("font") ||
			titleLower.endsWith(".ttf") || titleLower.endsWith(".otf") || titleLower.endsWith(".ttc") ||
			displayTitleLower.endsWith(".ttf") || displayTitleLower.endsWith(".otf") || displayTitleLower.endsWith(".ttc")
		) {
			getAttachmentTrack(this, api)
		} else {
			null
		}
	}
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
	index = stream.index,
	bitrate = stream.bitRate ?: 0,
	width = stream.width ?: 0,
	height = stream.height ?: 0,
	videoRange = stream.videoRangeType.name,
	title = stream.title,
	displayTitle = stream.displayTitle ?: stream.title,
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
		if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith("content://") || url.startsWith("/") || api == null) url
		else api.createUrl(url, ignorePathParameters = true)
	},
)

private fun getAttachmentTrack(stream: MediaStream, api: ApiClient? = null) = MediaStreamAttachmentTrack(
	codec = stream.codec.orEmpty(),
	index = stream.index,
	fileName = stream.title ?: stream.displayTitle,
	mimeType = stream.comment,
	deliveryUrl = stream.deliveryUrl?.let { url ->
		if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") || url.startsWith("content://") || url.startsWith("/") || api == null) url
		else api.createUrl(url, ignorePathParameters = true)
	},
)
