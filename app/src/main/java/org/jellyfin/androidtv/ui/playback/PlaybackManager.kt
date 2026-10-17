package org.jellyfin.androidtv.ui.playback

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.compat.PlaybackException
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import timber.log.Timber

private fun createStreamInfo(
	api: ApiClient,
	options: VideoOptions,
	response: PlaybackInfoResponse,
): StreamInfo = StreamInfo().apply {
	val source = response.mediaSources.firstOrNull {
		options.mediaSourceId != null && it.id == options.mediaSourceId
	} ?: response.mediaSources.firstOrNull()

	itemId = options.itemId
	mediaSource = source
	runTimeTicks = source?.runTimeTicks
	playSessionId = response.playSessionId

	if (source == null) return@apply

	Timber.d("PlaybackManager: Creating stream info. Video stream index: ${options.videoStreamIndex}")

	val validVideoStreamIndex = options.videoStreamIndex?.takeIf { it >= 0 }

	if (options.enableDirectPlay && source.supportsDirectPlay) {
		playMethod = PlayMethod.DIRECT_PLAY
		container = source.container
		mediaUrl = when {
			source.isRemote && source.path != null -> source.path
			else -> api.videosApi.getVideoStreamUrl(
				itemId = itemId,
				container = container,
				mediaSourceId = source.id,
				static = true,
				tag = source.eTag,
				liveStreamId = source.liveStreamId,
				videoStreamIndex = validVideoStreamIndex,
			)
		}
	} else if (options.enableDirectStream && source.supportsDirectStream && !source.transcodingUrl.isNullOrBlank()) {
		playMethod = PlayMethod.DIRECT_STREAM
		container = source.transcodingContainer
		mediaUrl = api.createUrl(source.transcodingUrl!!, ignorePathParameters = true)
		if (validVideoStreamIndex != null) {
			mediaUrl += "&VideoStreamIndex=$validVideoStreamIndex"
		}
	} else if (source.supportsTranscoding && !source.transcodingUrl.isNullOrBlank()) {
		playMethod = PlayMethod.TRANSCODE
		container = source.transcodingContainer
		mediaUrl = api.createUrl(source.transcodingUrl!!, ignorePathParameters = true)
		if (validVideoStreamIndex != null) {
			mediaUrl += "&VideoStreamIndex=$validVideoStreamIndex"
		}
	} else {
		playMethod = PlayMethod.DIRECT_STREAM
		container = source.container
		mediaUrl = api.videosApi.getVideoStreamUrl(
			itemId = itemId,
			container = container,
			mediaSourceId = source.id,
			static = false,
			tag = source.eTag,
			liveStreamId = source.liveStreamId,
			videoStreamIndex = validVideoStreamIndex,
		)
	}

	Timber.d("PlaybackManager: Final media URL for $playMethod: $mediaUrl")
}

class PlaybackManager(
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val serverVersion: ServerVersion,
	private val context: Context,
) {
	fun getVideoStreamInfo(
		lifecycleOwner: LifecycleOwner,
		options: VideoOptions,
		startTimeTicks: Long,
		callback: Response<StreamInfo>,
	) = lifecycleOwner.lifecycleScope.launch {
		Timber.d("PlaybackManager: Requesting stream info for item ${options.itemId} at $startTimeTicks. Video index: ${options.videoStreamIndex}")
		getVideoStreamInfoInternal(options, startTimeTicks).fold(
			onSuccess = { callback.onResponse(it) },
			onFailure = { callback.onError(Exception(it)) },
		)
	}

	fun changeVideoStream(
		lifecycleOwner: LifecycleOwner,
		stream: StreamInfo,
		options: VideoOptions,
		startTimeTicks: Long,
		callback: Response<StreamInfo>
	) = lifecycleOwner.lifecycleScope.launch {
		Timber.d("PlaybackManager: Refreshing stream due to change. New video index: ${options.videoStreamIndex}")
		if (stream.playSessionId != null && stream.playMethod != PlayMethod.DIRECT_PLAY) {
			withContext(Dispatchers.IO) {
				api.hlsSegmentApi.stopEncodingProcess(api.deviceInfo.id, stream.playSessionId)
			}
		}

		getVideoStreamInfoInternal(options, startTimeTicks).fold(
			onSuccess = { callback.onResponse(it) },
			onFailure = { callback.onError(Exception(it)) },
		)
	}

	private suspend fun getVideoStreamInfoInternal(
		options: VideoOptions,
		startTimeTicks: Long
	) = runCatching {
		val deviceProfile = options.profile ?: createDeviceProfile(context, userPreferences, serverVersion)
		val validAudioStreamIndex = options.audioStreamIndex?.takeIf { it >= 0 }
		val validSubtitleStreamIndex = options.subtitleStreamIndex?.takeIf { it >= 0 }
		val validMediaSourceId = options.mediaSourceId?.takeIf { it.isNotBlank() }

		val response = withContext(Dispatchers.IO) {
			api.mediaInfoApi.getPostedPlaybackInfo(
				itemId = requireNotNull(options.itemId) { "Item id cannot be null" },
				data = PlaybackInfoDto(
					mediaSourceId = validMediaSourceId,
					startTimeTicks = startTimeTicks,
					deviceProfile = deviceProfile,
					enableDirectStream = options.enableDirectStream,
					enableDirectPlay = options.enableDirectPlay,
					enableTranscoding = true,
					maxAudioChannels = options.maxAudioChannels,
					audioStreamIndex = validAudioStreamIndex,
					subtitleStreamIndex = validSubtitleStreamIndex,
					allowVideoStreamCopy = true,
					allowAudioStreamCopy = true,
					autoOpenLiveStream = false,
					alwaysBurnInSubtitleWhenTranscoding = options.alwaysBurnInSubtitleWhenTranscoding,
				)
			).content
		}

		if (response.errorCode != null) {
			throw PlaybackException().apply {
				errorCode = response.errorCode!!
			}
		}

		createStreamInfo(api, options, response)
	}
}
