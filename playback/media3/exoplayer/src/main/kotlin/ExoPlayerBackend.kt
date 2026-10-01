package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.ViewGroup
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType

import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.playback.core.backend.BackendTrack
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.media3.exoplayer.support.AssFontManager
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import java.util.Locale

import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediatype.MediaType
import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats


import timber.log.Timber
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend() {
	companion object {
		const val TS_SEARCH_BYTES_LM = TsExtractor.TS_PACKET_SIZE * 1800
		const val TS_SEARCH_BYTES_HM = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
	}

	private var currentStream: PlayableMediaStream? = null
	private var subtitleView: SubtitleView? = null
	private val audioPipeline = ExoPlayerAudioPipeline().apply {
		enableNightMode = exoPlayerOptions.enableAudioNightMode
	}
	private val audioAttributeState = AudioAttributeState()
	private val timedEventState = TimedEventState()
	private var lastKnownDuration: Duration? = null

	private val assHandler by lazy {
		AssHandler(AssRenderType.OVERLAY_OPEN_GL)
	}

	private val exoPlayer by lazy {
		val dataSourceFactory = DefaultDataSource.Factory(
			context,
			exoPlayerOptions.baseDataSourceFactory,
		)
		val isLowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
		val extractorsFactory = DefaultExtractorsFactory().apply {
			setTsExtractorTimestampSearchBytes(
				when (isLowRamDevice) {
					true -> TS_SEARCH_BYTES_LM
					false -> TS_SEARCH_BYTES_HM
				}
			)
			setConstantBitrateSeekingEnabled(true)
			setConstantBitrateSeekingAlwaysEnabled(true)
		}

		val mediaSourceFactory = if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				setSubtitleParserFactory(assSubtitleParserFactory)
			}
		} else DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

		val renderersFactory = DefaultRenderersFactory(context).apply {
			setEnableDecoderFallback(true)
			setExtensionRendererMode(
				when (exoPlayerOptions.preferFfmpeg) {
					true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
					false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
				}
			)
		}.let { renderersFactory ->
			if (exoPlayerOptions.enableLibass) AssRenderersFactory(assHandler, renderersFactory)
			else renderersFactory
		}

		val minBufferMs = exoPlayerOptions.minBufferDuration?.inWholeMilliseconds?.toInt()
			?: if (isLowRamDevice) 15_000 else DefaultLoadControl.DEFAULT_MIN_BUFFER_MS
		val maxBufferMs = exoPlayerOptions.maxBufferDuration?.inWholeMilliseconds?.toInt()
			?: if (isLowRamDevice) 30_000 else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
		val bufferForPlaybackMs = exoPlayerOptions.bufferForPlaybackDuration?.inWholeMilliseconds?.toInt()
			?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
		val bufferForPlaybackAfterRebufferMs = exoPlayerOptions.bufferForPlaybackAfterRebufferDuration?.inWholeMilliseconds?.toInt()
			?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				minBufferMs,
				maxBufferMs,
				bufferForPlaybackMs,
				bufferForPlaybackAfterRebufferMs,
			)
			.setPrioritizeTimeOverSizeThresholds(true)
			.setBackBuffer(if (isLowRamDevice) 10_000 else 30_000, false)
			.build()

		ExoPlayer.Builder(context)
			.apply {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
					setVideoChangeFrameRateStrategy(2)
				}
			}
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setTunnelingEnabled(exoPlayerOptions.enableTunneling)
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
						}.build()
					)
					setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				})
			})
			.setMediaSourceFactory(mediaSourceFactory)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
				player.addListener(PlayerListener())

				if (exoPlayerOptions.enableDebugLogging) {
					player.addAnalyticsListener(EventLogger())
				}

				if (exoPlayerOptions.enableLibass) {
					assHandler.init(player)
				}
			}
	}

	private var pendingInitialSeekMs: Long? = null

	private var isBufferingState = false

	private fun updateBufferingState() {
		val newBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING
		if (isBufferingState != newBuffering) {
			isBufferingState = newBuffering
			listener?.onBufferingStateChange(newBuffering)
		}
	}

	override fun setListener(eventListener: PlayerBackendEventListener?) {
		super.setListener(eventListener)
		if (eventListener != null) {
			listener?.onBufferingStateChange(exoPlayer.playbackState == Player.STATE_BUFFERING)
		}
	}

	inner class PlayerListener : Player.Listener {
		private fun checkPendingInitialSeek() {
			val seekMs = pendingInitialSeekMs ?: return
			if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.duration > 0) {
				pendingInitialSeekMs = null
				if (exoPlayer.isCurrentMediaItemSeekable && abs(exoPlayer.currentPosition - seekMs) > 1000) {
					exoPlayer.seekTo(seekMs)
				}
			}
		}

		private fun updatePlayState() {
			val state = when {
				exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED -> PlayState.STOPPED
				exoPlayer.playWhenReady -> PlayState.PLAYING
				else -> PlayState.PAUSED
			}
			listener?.onPlayStateChange(state)
		}

		override fun onIsPlayingChanged(isPlaying: Boolean) {
			checkPendingInitialSeek()
			updatePlayState()
			updateBufferingState()
		}

		override fun onPlayerError(error: PlaybackException) {
			listener?.onPlayStateChange(PlayState.ERROR)
		}

		override fun onVideoSizeChanged(size: VideoSize) {
			if (size != VideoSize.UNKNOWN) {
				val frameRate = exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
				listener?.onVideoSizeChange(size.width, size.height, frameRate)
			}
		}

		override fun onEvents(player: Player, events: Player.Events) {
			checkPendingInitialSeek()
			if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED) || events.contains(Player.EVENT_TRACKS_CHANGED)) {
				val size = player.videoSize
				if (size != VideoSize.UNKNOWN) {
					val frameRate = exoPlayer.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
					listener?.onVideoSizeChange(size.width, size.height, frameRate)
				}
			}
		}

		override fun onCues(cueGroup: CueGroup) {
			subtitleView?.setCues(cueGroup.cues)
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			checkPendingInitialSeek()
			updatePlayState()
			updateBufferingState()
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
				listener?.onMediaStreamEnd(requireNotNull(currentStream))
			}
			updatePlayState()
			updateBufferingState()
		}

		override fun onAudioSessionIdChanged(audioSessionId: Int) {
			audioPipeline.setAudioSessionId(audioSessionId)
		}

		override fun onTracksChanged(tracks: Tracks) {
			listener?.onTracksChanged()
		}

		override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
			val queueEntry = mediaItem?.localConfiguration?.tag as? QueueEntry
			audioPipeline.normalizationGain = queueEntry?.normalizationGain
			exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverrides().build()
		}

		override fun onTimelineChanged(timeline: Timeline, reason: Int) {
			checkPendingInitialSeek()
			val duration = exoPlayer.duration.takeUnless { it == C.TIME_UNSET }?.milliseconds
			if (duration == lastKnownDuration) return
			timedEventState.onDurationChange(exoPlayer, duration)
			lastKnownDuration = duration
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			timedEventState.onSeek(oldPosition.positionMs.milliseconds, newPosition.positionMs.milliseconds, lastKnownDuration ?: Duration.ZERO)
		}
	}

	override fun supportsStream(
		stream: MediaStream
	): PlaySupportReport = exoPlayer.getPlaySupportReport(stream.toFormats())

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		exoPlayer.setVideoSurfaceView(surfaceView?.surface)
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {

		if (surfaceView != null) {
			if (subtitleView == null) {
				subtitleView = SubtitleView(surfaceView.context).apply {
					if (exoPlayerOptions.enableLibass) {
						addView(AssSubtitleView(surfaceView.context, assHandler))
					}
					exoPlayerOptions.configureSubtitleView?.invoke(this)
				}
			}

			surfaceView.addView(subtitleView)
		} else {
			(subtitleView?.parent as? ViewGroup)?.removeView(subtitleView)
			subtitleView = null
		}
	}

	override fun prepareItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)

		val subtitleConfigurations = stream.tracks
			.filterIsInstance<MediaStreamSubtitleTrack>()
			.filter { it.isExternal && !it.deliveryUrl.isNullOrEmpty() }
			.map { subTrack ->
				val uri = Uri.parse(subTrack.deliveryUrl)
				var flags = 0
				if (subTrack.isDefault) flags = flags or C.SELECTION_FLAG_DEFAULT
				if (subTrack.isForced) flags = flags or C.SELECTION_FLAG_FORCED

				MediaItem.SubtitleConfiguration.Builder(uri)
					.setId("JF_EXTERNAL:${subTrack.index}")
					.setMimeType(getFfmpegSubtitleMimeType(subTrack.codec))
					.setLanguage(subTrack.language)
					.setLabel(subTrack.displayTitle ?: subTrack.title ?: subTrack.language)
					.setSelectionFlags(flags)
					.build()
			}

		val mediaItem = MediaItem.Builder().apply {
			setTag(item)
			setMediaId(stream.hashCode().toString())
			setUri(stream.url)
			setSubtitleConfigurations(subtitleConfigurations)
		}.build()

		if (exoPlayerOptions.enableLibass) {
			CoroutineScope(Dispatchers.IO).launch {
				AssFontManager.preloadFontsForEntry(context, item, assHandler)
			}
		}

		// Remove any excessive items from the start
		while (exoPlayer.mediaItemCount > MEDIA_ITEM_COUNT_MAX - 1) exoPlayer.removeMediaItem(0)

		// Add new item to the end of the media item list
		exoPlayer.addMediaItem(mediaItem)

		// Instruct exoplayer to prepare
		exoPlayer.prepare()
	}


	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		currentStream = stream

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		// Prepare the item now if it doesn't exist yet
		if (preparedItemIndex == null) {
			prepareItem(item)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		val startTicks = stream.startPositionTicks
		val startPositionMs = startTicks / 10000L

		if (startPositionMs > 0) {
			pendingInitialSeekMs = startPositionMs
			exoPlayer.seekTo(preparedItemIndex, startPositionMs)
		} else {
			pendingInitialSeekMs = null
			// Seek to prepared media item
			when (preparedItemIndex) {
				exoPlayer.currentMediaItemIndex - 1 -> exoPlayer.seekToPreviousMediaItem()
				exoPlayer.currentMediaItemIndex + 1 -> exoPlayer.seekToNextMediaItem()
				exoPlayer.currentMediaItemIndex -> Unit
				else -> exoPlayer.seekTo(preparedItemIndex, 0)
			}
		}


		// Update audio attributes
		val contentType = when (item.mediaType) {
			MediaType.Video -> C.AUDIO_CONTENT_TYPE_MOVIE
			MediaType.Audio -> C.AUDIO_CONTENT_TYPE_MUSIC
			MediaType.Unknown -> C.AUDIO_CONTENT_TYPE_UNKNOWN
		}


		audioAttributeState.updateAudioAttributes(
			builder = {
				setContentType(contentType)
				setUsage(C.USAGE_MEDIA)
			},
			onChange = { audioAttributes ->
				exoPlayer.setAudioAttributes(audioAttributes, true)
			}
		)

		// Apply current playback speed
		if (currentSpeed != 1.0f) {
			exoPlayer.setPlaybackSpeed(currentSpeed)
		}

		// Enjoy!
		Timber.i("Playing ${item.mediaStream?.url}")
		exoPlayer.play()
	}

	override fun play() {
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
		exoPlayer.play()
	}

	override fun pause() {
		exoPlayer.pause()
	}

	override fun stop() {
		exoPlayer.stop()
		audioPipeline.release()
		currentStream = null
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !exoPlayer.isCurrentMediaItemSeekable) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		lastKnownPositionMs = position.inWholeMilliseconds
		exoPlayer.seekTo(position.inWholeMilliseconds)
	}

	override fun setScrubbing(scrubbing: Boolean) {
		exoPlayer.isScrubbingModeEnabled = scrubbing
	}

	private var currentSpeed: Float = 1.0f

	override fun setSpeed(speed: Float) {
		currentSpeed = speed
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
			Timber.w("Trying to change speed but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.setPlaybackSpeed(speed)
	}

	private var lastKnownPositionMs: Long = 0L
	private var lastKnownBufferMs: Long = 0L

	override fun getPositionInfo(): PositionInfo {
		val isMain = Looper.myLooper() == Looper.getMainLooper()
		if (isMain) {
			lastKnownPositionMs = runCatching { exoPlayer.currentPosition }.getOrDefault(lastKnownPositionMs)
			lastKnownBufferMs = runCatching { exoPlayer.bufferedPosition }.getOrDefault(lastKnownBufferMs)
		}
		return PositionInfo(
			active = lastKnownPositionMs.milliseconds,
			buffer = lastKnownBufferMs.milliseconds,
			duration = lastKnownDuration ?: Duration.ZERO,
		)
	}


	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		timedEventState.setTimedEvents(exoPlayer, timedEvents)
	}

	private fun normalizeLanguageCode(langCode: String): String {
		return when (val lower = langCode.lowercase(Locale.ROOT)) {
			"fre", "fra" -> "fr"
			"ger", "deu" -> "de"
			"eng" -> "en"
			"spa" -> "es"
			"ita" -> "it"
			"jpn" -> "ja"
			"chi", "zho" -> "zh"
			"rus" -> "ru"
			"por" -> "pt"
			"dut", "nld" -> "nl"
			"pol" -> "pl"
			"kor" -> "ko"
			"swe" -> "sv"
			"nor" -> "no"
			"fin" -> "fi"
			"dan" -> "da"
			"ara" -> "ar"
			"hin" -> "hi"
			"tur" -> "tr"
			"ukr" -> "uk"
			"cze", "ces" -> "cs"
			"gre", "ell" -> "el"
			"hun" -> "hu"
			"ron", "rum" -> "ro"
			else -> lower
		}
	}

	private fun getDisplayNameForLanguage(langCode: String?): String? {
		if (langCode.isNullOrBlank()) return null
		val norm = normalizeLanguageCode(langCode)
		val locale = Locale.forLanguageTag(norm)
		val display = locale.getDisplayLanguage(Locale.getDefault())
		return if (display.isNotBlank() && display != norm && display != langCode) {
			display.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
		} else {
			langCode
		}
	}

	override fun getTracks(trackType: Int): List<BackendTrack> {
		val trackList = mutableListOf<BackendTrack>()
		val currentTracks = exoPlayer.currentTracks

		val tracksForType = when (trackType) {
			C.TRACK_TYPE_AUDIO -> currentStream?.tracks?.filterIsInstance<MediaStreamAudioTrack>()
			C.TRACK_TYPE_TEXT -> currentStream?.tracks?.filterIsInstance<MediaStreamSubtitleTrack>()
			C.TRACK_TYPE_VIDEO -> currentStream?.tracks?.filterIsInstance<MediaStreamVideoTrack>()
			else -> null
		}

		val usedTrackIndices = mutableSetOf<Int>()

		for (groupInfo in currentTracks.groups) {
			if (groupInfo.type != trackType) continue
			val group = groupInfo.mediaTrackGroup
			for (i in 0 until group.length) {
				val format = group.getFormat(i)
				val isSelected = groupInfo.isTrackSelected(i)
				val trackIndexInType = trackList.size

				val lang = format.language?.lowercase(Locale.ROOT)

				var matchedTitle: String? = null
				var matchedIndex: Int? = null

				// 1. Check direct index match if language matches or is missing
				val candidateAtIndex = tracksForType?.getOrNull(trackIndexInType)
				if (candidateAtIndex != null && !usedTrackIndices.contains(trackIndexInType)) {
					val candLang = when (candidateAtIndex) {
						is MediaStreamAudioTrack -> candidateAtIndex.language
						is MediaStreamSubtitleTrack -> candidateAtIndex.language
						else -> null
					}?.lowercase(Locale.ROOT)

					if (lang.isNullOrBlank() || candLang.isNullOrBlank() || lang == candLang || normalizeLanguageCode(lang) == normalizeLanguageCode(candLang)) {
						matchedTitle = when (candidateAtIndex) {
							is MediaStreamAudioTrack -> candidateAtIndex.displayTitle ?: candidateAtIndex.title
							is MediaStreamSubtitleTrack -> candidateAtIndex.displayTitle ?: candidateAtIndex.title
							else -> null
						}
						matchedIndex = trackIndexInType
					}
				}

				// 2. Search by language among unused tracks if index match failed
				if (matchedTitle == null && tracksForType != null && !lang.isNullOrBlank()) {
					tracksForType.forEachIndexed { idx, trk ->
						if (matchedTitle == null && !usedTrackIndices.contains(idx)) {
							val trkLang = when (trk) {
								is MediaStreamAudioTrack -> trk.language
								is MediaStreamSubtitleTrack -> trk.language
								else -> null
							}?.lowercase(Locale.ROOT)

							if (trkLang != null && (lang == trkLang || normalizeLanguageCode(lang) == normalizeLanguageCode(trkLang))) {
								matchedTitle = when (trk) {
									is MediaStreamAudioTrack -> trk.displayTitle ?: trk.title
									is MediaStreamSubtitleTrack -> trk.displayTitle ?: trk.title
									else -> null
								}
								matchedIndex = idx
							}
						}
					}
				}

				if (matchedIndex != null) {
					usedTrackIndices.add(matchedIndex)
				}

				val matchedMediaStreamIndex = if (matchedIndex != null) {
					when (val trk = tracksForType?.getOrNull(matchedIndex)) {
						is MediaStreamAudioTrack -> trk.index
						is MediaStreamSubtitleTrack -> trk.index
						is MediaStreamVideoTrack -> trk.index
						else -> -1
					}
				} else -1

				val langDisplayName = format.language?.let { getDisplayNameForLanguage(it) }

				val label = matchedTitle
					?: format.label?.takeIf { it.isNotBlank() }
					?: langDisplayName
					?: format.language
					?: "Track ${trackIndexInType + 1}"

				trackList.add(
					BackendTrack(
						id = "${group.id}:$i",
						type = trackType,
						label = label,
						language = format.language,
						isSelected = isSelected,
						group = group,
						trackIndex = i,
						mediaStreamIndex = matchedMediaStreamIndex,
					)
				)
			}
		}
		return trackList
	}

	override fun selectTrack(trackType: Int, track: BackendTrack?) {
		val currentParams = exoPlayer.trackSelectionParameters
		val builder = currentParams.buildUpon()
		if (track == null) {
			builder.setTrackTypeDisabled(trackType, true)
		} else {
			builder.setTrackTypeDisabled(trackType, false)
			val group = track.group as? TrackGroup
			if (group != null) {
				builder.setOverrideForType(TrackSelectionOverride(group, track.trackIndex))
			}
		}
		val newParams = builder.build()
		if (currentParams != newParams) {
			exoPlayer.trackSelectionParameters = newParams
		}
	}
}

