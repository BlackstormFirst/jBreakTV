package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackIndexManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.RefreshRateHelper
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.BackendTrack
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.MediaStreamService
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoSize
import org.jellyfin.playback.core.queue.preferredVideoIndex
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.File
import java.time.Instant

private data class TrackSelectionResult(
	val audioIndex: Int?,
	val subIndex: Int?,
	val videoIndex: Int?,
	val targetVideoTrack: BackendTrack?,
	val targetAudioTrack: BackendTrack?,
	val targetSubTrack: BackendTrack?,
)

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"

		@Volatile
		private var activeInstanceHash: Int? = null
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private val navigationRepository by inject<NavigationRepository>()
	private val interactionTrackerViewModel by inject<InteractionTrackerViewModel>()
	private val dataRefreshService by inject<DataRefreshService>()

	private var refreshRateHelper: RefreshRateHelper? = null
	private var playerEventListener: PlayerBackendEventListener? = null
	private var lastAutoSelectedEntryId: String? = null

	private var playbackRetries = 0
	private var lastPlaybackErrorTime = 0L
	private var isRetrying = false
	private var isNavigatingToNextUp = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		activeInstanceHash = this.hashCode()
		Timber.d("PlayerDebug [VideoPlayerFragment.onCreate]: Created fragment instance hash=${this.hashCode()}")

		refreshRateHelper = RefreshRateHelper(requireActivity(), userPreferences)

		val items = videoQueueManager.getCurrentVideoQueue().toMutableList()
		val currentMediaPosition = videoQueueManager.getCurrentMediaPosition()

		if (savedInstanceState == null && currentMediaPosition in items.indices) {
			// Clear playbackPositionTicks for all queue items EXCEPT the initial currentMediaPosition
			items.indices.forEach { index ->
				if (index != currentMediaPosition) {
					val item = items[index]
					val clearedUserData = item.userData?.copy(playbackPositionTicks = 0L)
					if (clearedUserData != null) {
						items[index] = item.copy(userData = clearedUserData)
					}
				}
			}

			val targetItem = items[currentMediaPosition]
			val resumeSubtractSeconds = userPreferences[UserPreferences.resumeSubtractDuration].toLongOrNull() ?: 0L
			val resumeSubtractTicks = resumeSubtractSeconds * 10000000L

			val targetTicks = if (arguments?.containsKey(EXTRA_POSITION) == true) {
				val startPositionMs = arguments?.getInt(EXTRA_POSITION, 0) ?: 0
				val startPositionTicks = startPositionMs.toLong() * 10000L
				if (startPositionTicks > 0L) {
					(startPositionTicks - resumeSubtractTicks).coerceAtLeast(0L)
				} else {
					0L
				}
			} else {
				val serverTicks = targetItem.userData?.playbackPositionTicks ?: 0L
				if (serverTicks > 0L) {
					(serverTicks - resumeSubtractTicks).coerceAtLeast(0L)
				} else {
					0L
				}
			}

			val updatedUserData = targetItem.userData?.copy(playbackPositionTicks = targetTicks)
				?: UserItemDataDto(
					itemId = targetItem.id,
					playbackPositionTicks = targetTicks,
					playCount = 0,
					isFavorite = false,
					played = false,
					key = targetItem.id.toString(),
				)
			items[currentMediaPosition] = targetItem.copy(userData = updatedUserData)
			videoQueueManager.setCurrentVideoQueue(items)
		}

		val currentItem = items.getOrNull(currentMediaPosition)
		if (currentItem != null) {
			interactionTrackerViewModel.notifyStartSession(currentItem, items)
			interactionTrackerViewModel.notifyStart(currentItem)
		}

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, items, false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, currentMediaPosition)

		// Sync current media position with VideoQueueManager as playback advances in the queue
		lifecycleScope.launch {
			playbackManager.queue.entryIndex.collect { index ->
				if (index >= 0) {
					videoQueueManager.setCurrentMediaPosition(index)
					lastAutoSelectedEntryId = null
					isNavigatingToNextUp = false

					val rawItem = videoQueueManager.getCurrentVideoQueue().getOrNull(index)
						?: playbackManager.queue.entry.value?.baseItem
					if (rawItem != null) {
						val currentItem = rawItem.withUpdatedPlaybackUserData()
						dataRefreshService.lastPlayback = Instant.now()
						dataRefreshService.lastPlayedItem = currentItem
						when (currentItem.type) {
							BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
							BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
							else -> Unit
						}
					}

					// Clear explicit stream index overrides from previous item so next item uses smart matching
					videoQueueManager.setSelectedAudioStreamIndex(null)
					videoQueueManager.setSelectedSubtitleStreamIndex(null)
					videoQueueManager.setSelectedVideoStreamIndex(null)

					// Reset playbackPositionTicks and invalidate cached mediaStream for all non-active items in the queue
					// so returning to a previous episode starts from position 0 instead of resuming at the end
					val entries = playbackManager.queue.entries.value
					entries.forEachIndexed { i, entry ->
						if (i != index && (entry.mediaStream != null || (entry.baseItem?.userData?.playbackPositionTicks ?: 0L) > 0L)) {
							val item = entry.baseItem
							if (item != null && (item.userData?.playbackPositionTicks ?: 0L) > 0L) {
								val clearedUserData = item.userData?.copy(playbackPositionTicks = 0L)
								entry.baseItem = item.copy(userData = clearedUserData)
							}
							entry.mediaStream = null
						}
					}
				}
			}
		}

		// Observe video size and queue entry to apply refresh rate switching
		lifecycleScope.launch {
			playbackManager.state.videoSize.collect { videoSize ->
				applyRefreshRate(videoSize)
			}
		}

		lifecycleScope.launch {
			playbackManager.queue.entry.collect { entry ->
				val item = entry?.baseItem
				if (item != null) {
					dataRefreshService.lastPlayback = Instant.now()
					dataRefreshService.lastPlayedItem = item
					when (item.type) {
						BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
						BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
						else -> Unit
					}
					if (LiveTvPlayerHelper.isLiveTv(item)) {
						TvManager.setLastLiveTvChannel(item.id)
					}
				}
				val videoSize = playbackManager.state.videoSize.value
				applyRefreshRate(videoSize)
			}
		}

		// Observe play state to navigate back when playback is stopped after starting
		lifecycleScope.launch {
			var hasStarted = false
			playbackManager.state.playState.collect { playState ->
				when (playState) {
					PlayState.PLAYING, PlayState.PAUSED -> {
						hasStarted = true
						isRetrying = false
					}
					PlayState.STOPPED -> {
						val hasNextItem = videoQueueManager.getCurrentMediaPosition() < videoQueueManager.getCurrentVideoQueue().size - 1
						if (hasStarted && !isRetrying && !isNavigatingToNextUp && !hasNextItem && isAdded && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
							navigationRepository.goBack()
						}
					}
					else -> Unit
				}
			}
		}

		// Listen for track changes to apply best video/audio/subtitle selections
		playerEventListener = object : PlayerBackendEventListener() {
			private var isApplyingAutoTracks = false

			override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
				lifecycleScope.launch {
					val currentIndex = videoQueueManager.getCurrentMediaPosition()
					val queueItems = videoQueueManager.getCurrentVideoQueue()
					val currentItem = queueItems.getOrNull(currentIndex)
					val nextIndex = currentIndex + 1
					val nextItem = queueItems.getOrNull(nextIndex)

					val stillWatchingBehavior = userPreferences[UserPreferences.stillWatchingBehavior]
					val nextUpBehavior = userPreferences[UserPreferences.nextUpBehavior]

					val stillWatchingEnabled = stillWatchingBehavior.enabled
					val nextUpEnabled = nextUpBehavior != NextUpBehavior.DISABLED

					val showStillWatching = stillWatchingEnabled && interactionTrackerViewModel.getShowStillWatching()
					val showNextUp = nextUpEnabled && !showStillWatching

					if (nextItem != null && currentItem != null && currentItem.type != BaseItemKind.TRAILER && (showStillWatching || showNextUp)) {
						isNavigatingToNextUp = true
						interactionTrackerViewModel.onEpisodeWatched()
						videoQueueManager.setCurrentMediaPosition(nextIndex)
						playbackManager.state.stop()

						if (showStillWatching) {
							navigationRepository.navigate(Destinations.stillWatching(nextItem.id), true)
						} else {
							navigationRepository.navigate(Destinations.nextUp(nextItem.id), true)
						}
					}
				}
			}

			override fun onTracksChanged() {
				if (isApplyingAutoTracks) return

				val entry = playbackManager.queue.entry.value ?: return
				val baseItem = entry.baseItem ?: return
				val entryId = baseItem.id.toString()
				if (lastAutoSelectedEntryId == entryId) {
					// Initial auto-selection for this media item was already applied.
					// Do not override user's manual track selection!
					return
				}

				isApplyingAutoTracks = true
				lifecycleScope.launch {
					try {
						val baseItem = entry.baseItem ?: return@launch
						val backend = playbackManager.backend

						val videoTracks = backend.getTracks(2)
						val audioTracks = backend.getTracks(1)
						val subTracks = backend.getTracks(3)

						if (videoTracks.isEmpty() && audioTracks.isEmpty() && subTracks.isEmpty()) {
							// Tracks not loaded yet in ExoPlayer, retry on next onTracksChanged
							return@launch
						}

						val isLocal = LocalVideoManager.isLocalItem(baseItem)
						val mediaSource = baseItem.mediaSources?.firstOrNull() ?: MediaSourceInfo(
							protocol = if (isLocal) MediaProtocol.FILE else MediaProtocol.HTTP,
							id = baseItem.id.toString(),
							path = baseItem.path,
							type = MediaSourceType.DEFAULT,
							isRemote = !isLocal,
							mediaStreams = baseItem.mediaStreams,
							supportsDirectPlay = true,
							supportsDirectStream = true,
							supportsTranscoding = false,
							supportsProbing = false,
							requiresOpening = false,
							requiresClosing = false,
							requiresLooping = false,
							isInfiniteStream = false,
							ignoreIndex = false,
							ignoreDts = false,
							genPtsInput = false,
							readAtNativeFramerate = false,
							hasSegments = false,
							transcodingSubProtocol = MediaStreamProtocol.HTTP,
						)

						val playbackIndexManager: PlaybackIndexManager by inject()
						val ctx = context

						// Perform track index calculation on Dispatchers.Default (background thread pool)
						val trackSelection = withContext(Dispatchers.Default) {
							val audioIndex = playbackIndexManager.getBestAudioIndex(mediaSource)
							val activeAudioLang = baseItem.mediaStreams?.firstOrNull { it.index == audioIndex }?.language
							val subIndex = playbackIndexManager.getBestSubtitleIndex(mediaSource, ctx, activeAudioLang)
							val videoIndex = playbackIndexManager.getBestVideoIndex(mediaSource)

							val targetVideoTrack = if (videoIndex != null) {
								videoTracks.firstOrNull { it.mediaStreamIndex == videoIndex } ?: videoTracks.firstOrNull()
							} else null

							val targetAudioTrack = if (audioIndex != null) {
								audioTracks.firstOrNull { it.mediaStreamIndex == audioIndex }
									?: audioTracks.firstOrNull { it.language.equals(activeAudioLang, ignoreCase = true) }
							} else null

							val targetSubTrack = if (subIndex != null && subIndex >= 0) {
								val targetStream = baseItem.mediaStreams?.firstOrNull { it.index == subIndex }
								subTracks.firstOrNull { it.mediaStreamIndex == subIndex }
									?: subTracks.firstOrNull { it.language.equals(targetStream?.language, ignoreCase = true) }
							} else null

							TrackSelectionResult(
								audioIndex = audioIndex,
								subIndex = subIndex,
								videoIndex = videoIndex,
								targetVideoTrack = targetVideoTrack,
								targetAudioTrack = targetAudioTrack,
								targetSubTrack = targetSubTrack,
							)
						}

						// Double check fragment is active and playing the same item before mutating track selections
						if (!isAdded || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
						if (playbackManager.state.playState.value == PlayState.STOPPED) return@launch
						val activeEntry = playbackManager.queue.entry.value ?: return@launch
						if (activeEntry.baseItem?.id != baseItem.id) return@launch

						// Apply track selections atomically back on the main thread
						backend.selectTracks(
							videoTrack = if (trackSelection.videoIndex != null) trackSelection.targetVideoTrack else null,
							audioTrack = if (trackSelection.audioIndex != null) trackSelection.targetAudioTrack else null,
							subtitleTrack = if (trackSelection.subIndex != null && trackSelection.subIndex >= 0) trackSelection.targetSubTrack else null,
						)

						// Save active audio/subtitle/video properties to VideoQueueManager for queue persistence across episodes
						val selectedAudioStream = baseItem.mediaStreams?.firstOrNull { it.index == trackSelection.audioIndex }
						if (selectedAudioStream != null) {
							selectedAudioStream.language?.let { videoQueueManager.setLastPlayedAudioLanguageIsoCode(it) }
							selectedAudioStream.codec?.let { videoQueueManager.setLastPlayedAudioCodec(it) }
							videoQueueManager.setLastPlayedAudioTitle(selectedAudioStream.title)
							videoQueueManager.setLastPlayedAudioDefaultState(selectedAudioStream.isDefault)
							videoQueueManager.setLastPlayedAudioHearingImpairedState(selectedAudioStream.isHearingImpaired)
						}

						val selectedSubStream = if (trackSelection.subIndex != null && trackSelection.subIndex >= 0) {
							baseItem.mediaStreams?.firstOrNull { it.index == trackSelection.subIndex }
						} else null
						if (selectedSubStream != null) {
							selectedSubStream.language?.let { videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(it) }
							selectedSubStream.codec?.let { videoQueueManager.setLastPlayedSubtitleCodec(it) }
							videoQueueManager.setLastPlayedSubtitleTitle(selectedSubStream.title)
							videoQueueManager.setLastPlayedSubtitleDefaultState(selectedSubStream.isDefault)
							videoQueueManager.setLastPlayedSubtitleForcedState(selectedSubStream.isForced)
							videoQueueManager.setLastPlayedSubtitleHearingImpairedState(selectedSubStream.isHearingImpaired)
						} else if (trackSelection.subIndex == null || trackSelection.subIndex < 0) {
							videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
							videoQueueManager.setLastPlayedSubtitleTitle(null)
							videoQueueManager.setLastPlayedSubtitleCodec(null)
						}

						if (trackSelection.videoIndex != null) {
							entry.preferredVideoIndex = trackSelection.videoIndex
							val selectedVideoStream = baseItem.mediaStreams?.firstOrNull { it.index == trackSelection.videoIndex }
							if (selectedVideoStream != null) {
								selectedVideoStream.codec?.let { videoQueueManager.setLastPlayedVideoCodec(it) }
								videoQueueManager.setLastPlayedVideoTitle(selectedVideoStream.title)
								videoQueueManager.setLastPlayedVideoDefaultState(selectedVideoStream.isDefault)
								val videoTypeIndex = baseItem.mediaStreams?.filter { it.type == MediaStreamType.VIDEO }?.indexOf(selectedVideoStream) ?: -1
								if (videoTypeIndex >= 0) videoQueueManager.setLastPlayedVideoIndexInType(videoTypeIndex)
							}
						}

						lastAutoSelectedEntryId = entryId
					} catch (e: Exception) {
						Timber.e(e, "Error during automatic track selection")
					} finally {
						isApplyingAutoTracks = false
					}
				}
			}

			override fun onError(error: Throwable) {
				lifecycleScope.launch {
					handlePlayerError(error)
				}
			}

			override fun onSurfaceAttached(surfaceView: PlayerSurfaceView) {
				val videoSize = playbackManager.state.videoSize.value
				applyRefreshRate(videoSize)
			}
		}.also { listener ->
			playbackManager.addListener(listener)
		}
	}

	private fun handlePlayerError(error: Throwable?) {
		if (!isAdded || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

		val entry = playbackManager.queue.entry.value
		if (entry == null || playbackManager.state.playState.value == PlayState.STOPPED) {
			Timber.w("Ignoring player error because playback is already stopped or queue entry is null")
			return
		}
		val currentItem = entry.baseItem

		// USB Protection: If current item is a disconnected local file, close player cleanly
		if (currentItem != null && LocalVideoManager.isLocalItem(currentItem)) {
			val path = currentItem.path
			if (path != null) {
				val file = File(path)
				if (!file.exists() || !file.canRead()) {
					Timber.w("UsbDebug: Local file inaccessible on error (%s). Closing player.", path)
					Toast.makeText(requireContext(), "Périphérique USB déconnecté", Toast.LENGTH_SHORT).show()
					playbackManager.state.stop()
					navigationRepository.goBack()
					return
				}
			}
		}

		// Reset retry count if it's been more than 30s since previous error
		val now = System.currentTimeMillis()
		if (playbackRetries > 0 && now - lastPlaybackErrorTime > 30000) {
			Timber.i("Playback error: playback stabilized - retry count reset to 0 from $playbackRetries")
			playbackRetries = 0
		}

		playbackRetries++
		lastPlaybackErrorTime = now

		if (playbackRetries < 3) {
			val isLiveTv = currentItem != null && LiveTvPlayerHelper.isLiveTv(currentItem)
			val msgRes = if (isLiveTv) R.string.msg_error_live_stream else R.string.player_error
			Toast.makeText(requireContext(), getString(msgRes), Toast.LENGTH_SHORT).show()
			Timber.i("Player error encountered (attempt $playbackRetries/3) - retrying")

			isRetrying = true
			lifecycleScope.launch {
				playbackManager.getService<MediaStreamService>()?.reloadStream(entry)
			}
		} else {
			isRetrying = false
			Toast.makeText(requireContext(), getString(R.string.too_many_errors), Toast.LENGTH_LONG).show()
			Timber.e(error, "Too many playback errors ($playbackRetries retries), closing player")

			lifecycleScope.launch(Dispatchers.IO) {
				runCatching {
					val report = buildString {
						appendLine("---")
						appendLine("client: jBreakTV for Android TV")
						appendLine("client_version: ${BuildConfig.VERSION_NAME}")
						appendLine("type: playback_error")
						appendLine("format: markdown")
						appendLine("---")
						appendLine()
						appendLine("## Playback Error Report")
						appendLine("- Item: ${currentItem?.name} (ID: ${currentItem?.id})")
						appendLine("- Error: ${error?.message}")
						appendLine("- Cause: ${error?.cause?.message}")
						appendLine("- Retries: $playbackRetries")
						if (error != null) {
							appendLine()
							appendLine("```log")
							appendLine(error.stackTraceToString())
							appendLine("```")
						}
					}
					api.clientLogApi.logFile(report)
				}.onFailure { e -> Timber.w(e, "Failed to upload playback error log to server") }
			}

			playbackManager.state.stop()
			navigationRepository.goBack()
		}
	}

	private fun applyRefreshRate(videoSize: VideoSize) {
		val entry = playbackManager.queue.entry.value
		val baseItem = entry?.baseItem
		val mediaStreams = baseItem?.mediaStreams?.takeIf { it.isNotEmpty() }
			?: baseItem?.mediaSources?.firstOrNull()?.mediaStreams
		val preferredIndex = entry?.preferredVideoIndex
		val videoStream = if (preferredIndex != null) {
			mediaStreams?.firstOrNull { it.index == preferredIndex }
		} else {
			mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
		}

		val mediaStreamFrameRate = videoStream?.realFrameRate?.takeIf { it > 0f }
			?: videoStream?.averageFrameRate?.takeIf { it > 0f }

		val frameRate = when {
			videoSize.frameRate > 0f -> videoSize.frameRate
			mediaStreamFrameRate != null -> mediaStreamFrameRate
			else -> null
		}

		if (frameRate == null || frameRate <= 0f) {
			Timber.d("No valid frame rate found to apply refresh rate switching (videoSize=${videoSize.frameRate}, mediaStream=$mediaStreamFrameRate)")
			return
		}

		val videoWidth = if (videoSize.width > 0) videoSize.width else (videoStream?.width ?: 0)
		val videoHeight = if (videoSize.height > 0) videoSize.height else (videoStream?.height ?: 0)

		val surfaceView = playbackManager.backendService.surfaceView?.surface

		val changed = refreshRateHelper?.updateRefreshRate(
			frameRate = frameRate,
			videoWidth = videoWidth,
			videoHeight = videoHeight,
			surfaceView = surfaceView,
		) == true

		if (changed) {
			lifecycleScope.launch {
				// Pause briefly to allow display refresh rate switch / HDMI handshake to complete smoothly
				val wasPlaying = playbackManager.state.playState.value == PlayState.PLAYING
				if (wasPlaying) {
					playbackManager.state.pause()
				}
				kotlinx.coroutines.delay(1200L)
				if (isAdded && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
					if (wasPlaying || playbackManager.state.playState.value == PlayState.PAUSED) {
						playbackManager.state.unpause()
					}
				}
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		Timber.d("PlayerDebug [VideoPlayerFragment.onCreateView]: Creating view for fragment hash=${this.hashCode()}")
		BaseScreen {
			VideoPlayerScreen()
		}
	}

	override fun onPause() {
		super.onPause()
		Timber.d("PlayerDebug [VideoPlayerFragment.onPause]: Pausing fragment hash=${this.hashCode()}")

		if (activeInstanceHash == this.hashCode()) {
			playbackManager.state.pause()
		}
	}

	override fun onResume() {
		super.onResume()
		Timber.d("PlayerDebug [VideoPlayerFragment.onResume]: Resuming fragment hash=${this.hashCode()}, playState=${playbackManager.state.playState.value}")

		if (activeInstanceHash == this.hashCode()) {
			if (playbackManager.state.playState.value == PlayState.STOPPED) {
				val currentEntry = playbackManager.queue.entry.value
				if (currentEntry?.mediaStream != null) {
					playbackManager.state.play()
				}
			} else {
				playbackManager.state.unpause()
			}
		}
	}

	private fun BaseItemDto.withUpdatedPlaybackUserData(): BaseItemDto {
		val positionMs = playbackManager.state.positionInfo.active.inWholeMilliseconds
		val durationMs = playbackManager.state.positionInfo.duration.inWholeMilliseconds
		val positionTicks = positionMs * 10000L
		val durationTicks = durationMs * 10000L
		val isPlayed = durationMs > 0 && positionMs >= (durationMs * 0.9)

		val currentUserData = userData
		val updatedUserData = currentUserData?.copy(
			playbackPositionTicks = if (isPlayed) 0L else positionTicks,
			played = isPlayed || (currentUserData.played == true),
			playedPercentage = if (durationTicks > 0) (positionTicks.toDouble() / durationTicks * 100) else currentUserData.playedPercentage
		) ?: UserItemDataDto(
			itemId = id,
			playbackPositionTicks = if (isPlayed) 0L else positionTicks,
			played = isPlayed,
			playCount = 1,
			isFavorite = false,
			key = id.toString(),
		)

		return copy(userData = updatedUserData)
	}

	override fun onDestroy() {
		super.onDestroy()
		val isRemoving = isRemoving || requireActivity().isFinishing
		Timber.d("PlayerDebug [VideoPlayerFragment.onDestroy]: Destroying fragment hash=${this.hashCode()}, isRemoving=$isRemoving")

		playerEventListener?.let { listener ->
			playbackManager.removeListener(listener)
		}
		playerEventListener = null

		if (isRemoving && activeInstanceHash == this.hashCode()) {
			val rawItem = playbackManager.queue.entry.value?.baseItem
				?: videoQueueManager.getCurrentVideoQueue().getOrNull(videoQueueManager.getCurrentMediaPosition())
			if (rawItem != null) {
				val currentItem = rawItem.withUpdatedPlaybackUserData()
				dataRefreshService.lastPlayback = Instant.now()
				dataRefreshService.lastPlayedItem = currentItem
				when (currentItem.type) {
					BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
					BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
					else -> Unit
				}
			}
			playbackManager.state.stop()

			val surfaceView = playbackManager.backendService.surfaceView?.surface
			refreshRateHelper?.resetRefreshRate(surfaceView)
		}
	}
}
