package org.jellyfin.playback.jellyfin.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.util.isLocalPath
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QueueItem
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.sdk.model.api.RepeatMode as SdkRepeatMode

class PlaySessionService(
	private val api: ApiClient,
	private val onPlaybackStop: ((BaseItemDto) -> Unit)? = null,
) : PlayerService() {
	private var isSessionStarted = false
	private var lastActivePositionMs: Long = 0L
	private var activeEntry: QueueEntry? = null
	private var activeItem: BaseItemDto? = null

	private fun isLocalMedia(item: BaseItemDto): Boolean {
		//if (item.mediaSources?.firstOrNull()?.eTag != null || !item.serverId.isNullOrEmpty()) return false
		return isLocalPath(item.path)
	}

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			if (entry != null) {
				activeEntry = entry
				entry.baseItem?.let { activeItem = it }
			}
		}.launchIn(coroutineScope)

		state.playState.onEach { playState ->
			when (playState) {
				PlayState.PLAYING -> sendStreamStart()
				PlayState.STOPPED -> sendStreamStop()
				PlayState.PAUSED -> sendStreamUpdate()
				PlayState.ERROR -> sendStreamStop()
			}
		}.launchIn(coroutineScope)

		coroutineScope.launch {
			while (isActive) {
				delay(3.seconds)
				if (state.playState.value == PlayState.PLAYING) {
					sendStreamUpdate()
				}
			}
		}
	}

	private val MediaConversionMethod.playMethod
		get() = when (this) {
			MediaConversionMethod.None -> PlayMethod.DIRECT_PLAY
			MediaConversionMethod.Remux -> PlayMethod.DIRECT_STREAM
			MediaConversionMethod.Transcode -> PlayMethod.TRANSCODE
		}

	private val RepeatMode.remoteRepeatMode
		get() = when (this) {
			RepeatMode.NONE -> SdkRepeatMode.REPEAT_NONE
			RepeatMode.REPEAT_ENTRY_ONCE -> SdkRepeatMode.REPEAT_ONE
			RepeatMode.REPEAT_ENTRY_INFINITE -> SdkRepeatMode.REPEAT_ALL
		}

	suspend fun sendUpdateIfActive() {
		if (state.playState.value != PlayState.STOPPED) {
			coroutineScope.launch { sendStreamUpdate() }
		}
	}

	private suspend fun getQueue(): List<QueueItem> {
		return manager.queue
			.peekNext(15)
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id, playlistItemId = it.playlistItemId) }
	}

	private suspend fun getReportPositionMs(playableStream: PlayableMediaStream?): Long {
		val activeMs = withContext(Dispatchers.Main) {
			state.positionInfo.active.inWholeMilliseconds
		}
		if (activeMs > 0) {
			lastActivePositionMs = activeMs
			return activeMs
		}
		if (lastActivePositionMs > 0) {
			return lastActivePositionMs
		}
		val startPosMs = (playableStream?.startPositionTicks ?: 0L) / 10000L
		if (startPosMs > 0) {
			lastActivePositionMs = startPosMs
			return startPosMs
		}
		return 0L
	}

	private suspend fun sendStreamStart() {
		val entry = manager.queue.entry.value ?: activeEntry ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: activeItem ?: return
		if (isLocalMedia(item)) return

		val playableStream = stream as? PlayableMediaStream
		val mediaSourceId = playableStream?.mediaSourceId
			?: item.mediaSources?.firstOrNull { it.id == entry.mediaSourceId }?.id
			?: item.mediaSources?.firstOrNull()?.id
			?: entry.mediaSourceId

		val reportMs = getReportPositionMs(playableStream)

		withContext(Dispatchers.IO) {
			runCatching {
				api.playStateApi.reportPlaybackStart(
					PlaybackStartInfo(
						itemId = item.id,
						mediaSourceId = mediaSourceId,
						playSessionId = stream.identifier,
						playlistItemId = item.playlistItemId,
						canSeek = true,
						isMuted = state.volume.muted,
						volumeLevel = (state.volume.volume * 100).roundToInt(),
						isPaused = state.playState.value != PlayState.PLAYING,
						aspectRatio = state.videoSize.value.aspectRatio.toString(),
						positionTicks = reportMs * 10000L,
						playMethod = stream.conversionMethod.playMethod,
						repeatMode = state.repeatMode.value.remoteRepeatMode,
						nowPlayingQueue = getQueue(),
						playbackOrder = when (state.playbackOrder.value) {
							org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
							org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
							org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
						}
					)
				)
				isSessionStarted = true
				Timber.i("Reported playback start for ${item.name} at reportMs=$reportMs (mediaSourceId=$mediaSourceId, playSessionId=${stream.identifier})")
			}.onFailure { error -> Timber.w(error, "Failed to send playback start event") }
		}
	}

	private suspend fun sendStreamUpdate() {
		if (state.playState.value == PlayState.STOPPED) return
		val entry = manager.queue.entry.value ?: activeEntry ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: activeItem ?: return
		if (isLocalMedia(item)) return

		if (!isSessionStarted) {
			sendStreamStart()
			if (!isSessionStarted) return
		}

		val playableStream = stream as? PlayableMediaStream
		val mediaSourceId = playableStream?.mediaSourceId
			?: item.mediaSources?.firstOrNull { it.id == entry.mediaSourceId }?.id
			?: item.mediaSources?.firstOrNull()?.id
			?: entry.mediaSourceId

		val reportMs = getReportPositionMs(playableStream)

		withContext(Dispatchers.IO) {
			runCatching {
				api.playStateApi.reportPlaybackProgress(
					PlaybackProgressInfo(
						itemId = item.id,
						mediaSourceId = mediaSourceId,
						playSessionId = stream.identifier,
						playlistItemId = item.playlistItemId,
						canSeek = true,
						isMuted = state.volume.muted,
						volumeLevel = (state.volume.volume * 100).roundToInt(),
						isPaused = state.playState.value != PlayState.PLAYING,
						aspectRatio = state.videoSize.value.aspectRatio.toString(),
						positionTicks = reportMs * 10000L,
						playMethod = stream.conversionMethod.playMethod,
						repeatMode = state.repeatMode.value.remoteRepeatMode,
						nowPlayingQueue = getQueue(),
						playbackOrder = when (state.playbackOrder.value) {
							org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
							org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
							org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
						}
					)
				)
				Timber.d("Reported playback progress for ${item.name} at reportMs=$reportMs")
			}.onFailure { error -> Timber.w(error, "Failed to send playback update event") }
		}
	}

	private suspend fun sendStreamStop() {
		val entry = manager.queue.entry.value ?: activeEntry
		val item = entry?.baseItem ?: activeItem
		if (item != null) {
			onPlaybackStop?.invoke(item)
		}

		if (entry == null || item == null || isLocalMedia(item)) return
		val stream = entry.mediaStream ?: activeEntry?.mediaStream ?: return

		val playableStream = stream as? PlayableMediaStream
		val mediaSourceId = playableStream?.mediaSourceId
			?: item.mediaSources?.firstOrNull { it.id == entry.mediaSourceId }?.id
			?: item.mediaSources?.firstOrNull()?.id
			?: entry.mediaSourceId

		val activeMs = withContext(Dispatchers.Main) {
			state.positionInfo.active.inWholeMilliseconds
		}
		if (activeMs > 0) lastActivePositionMs = activeMs
		val reportMs = if (activeMs > 0) activeMs else lastActivePositionMs

		if (isSessionStarted) {
			withContext(Dispatchers.IO + NonCancellable) {
				runCatching {
					api.playStateApi.reportPlaybackStopped(
						PlaybackStopInfo(
							itemId = item.id,
							mediaSourceId = mediaSourceId,
							playSessionId = stream.identifier,
							playlistItemId = item.playlistItemId,
							positionTicks = reportMs * 10000L,
							failed = false,
							nowPlayingQueue = getQueue(),
						)
					)
					Timber.i("Reported playback stop for ${item.name} at reportMs=$reportMs")
				}.onFailure { error -> Timber.w(error, "Failed to send playback stop event") }
				isSessionStarted = false
				activeEntry = null
				activeItem = null
				lastActivePositionMs = 0L
			}
		}
	}
}
