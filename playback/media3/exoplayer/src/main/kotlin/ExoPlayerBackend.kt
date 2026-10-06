package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.ViewGroup
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType

import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.Format
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
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
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
import org.jellyfin.playback.core.LibassRenderMode
import org.jellyfin.playback.core.backend.BackendTrack
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.media3.exoplayer.support.AssFontManager
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
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
import org.jellyfin.playback.core.queue.preferredAudioIndex
import org.jellyfin.playback.core.queue.preferredSubtitleIndex
import org.jellyfin.playback.core.queue.preferredVideoIndex
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
		AssHandler(
			renderType = AssRenderType.OVERLAY_OPEN_GL,
			config = AssFontManager.defaultAssHandlerConfig,
		)
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
			val delegatingFactory = DelegatingSubtitleParserFactory(assSubtitleParserFactory)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				setSubtitleParserFactory(delegatingFactory)
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

		val backBufferMs = when {
			isLowRamDevice -> 10_000
			exoPlayerOptions.maxBufferDuration != null && exoPlayerOptions.maxBufferDuration.inWholeSeconds >= 200 -> 120_000
			else -> 30_000
		}
		val retainBackBuffer = exoPlayerOptions.maxBufferDuration != null && exoPlayerOptions.maxBufferDuration.inWholeSeconds >= 200

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				minBufferMs,
				maxBufferMs,
				bufferForPlaybackMs,
				bufferForPlaybackAfterRebufferMs,
			)
			.setPrioritizeTimeOverSizeThresholds(true)
			.setBackBuffer(backBufferMs, retainBackBuffer)
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
					if (exoPlayerOptions.libassRenderMode == LibassRenderMode.FRAME_ACCURATE) {
						player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
							assHandler.videoTimeCallback?.invoke(presentationTimeUs)
						}
					}
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
			applyPreferredTrackOverrides()
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

	private fun buildMediaItem(item: QueueEntry): MediaItem {
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

		return MediaItem.Builder().apply {
			setTag(item)
			setMediaId(stream.hashCode().toString())
			setUri(stream.url)
			setSubtitleConfigurations(subtitleConfigurations)
		}.build()
	}

	override fun prepareItem(item: QueueEntry) {
		val mediaItem = buildMediaItem(item)

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

		val mediaItem = buildMediaItem(item)

		if (exoPlayerOptions.enableLibass) {
			CoroutineScope(Dispatchers.IO).launch {
				AssFontManager.preloadFontsForEntry(context, item, assHandler)
			}
		}

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			val tag = exoPlayer.getMediaItemAt(index).localConfiguration?.tag as? QueueEntry
			tag == item || exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		if (preparedItemIndex != null) {
			exoPlayer.replaceMediaItem(preparedItemIndex, mediaItem)
		} else {
			while (exoPlayer.mediaItemCount > MEDIA_ITEM_COUNT_MAX - 1) exoPlayer.removeMediaItem(0)
			exoPlayer.addMediaItem(mediaItem)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		exoPlayer.prepare()

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

	private fun findBestEmbeddedTrackMatch(
		format: Format,
		candidates: List<Any>,
		usedIndices: Set<Int>
	): Any? {
		val availableCandidates = candidates.filter { trk ->
			val index = when (trk) {
				is MediaStreamAudioTrack -> trk.index
				is MediaStreamSubtitleTrack -> trk.index
				is MediaStreamVideoTrack -> trk.index
				else -> -1
			}
			index !in usedIndices
		}

		if (availableCandidates.isEmpty()) return null
		if (availableCandidates.size == 1) return availableCandidates.first()

		val formatLang = format.language?.lowercase(Locale.ROOT).orEmpty()
		val isFormatForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
			format.label?.lowercase(Locale.ROOT)?.let { it.contains("forced") || it.contains("forcé") } == true
		val isFormatDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0

		var bestCandidate: Any? = null
		var bestScore = -1000

		for (cand in availableCandidates) {
			var score = 0
			when (cand) {
				is MediaStreamSubtitleTrack -> {
					val candLang = cand.language?.lowercase(Locale.ROOT).orEmpty()
					if (cand.isForced == isFormatForced) {
						score += 100
					} else {
						score -= 50
					}
					if (formatLang.isNotEmpty() && candLang.isNotEmpty()) {
						if (normalizeLanguageCode(formatLang) == normalizeLanguageCode(candLang)) {
							score += 50
						}
					} else if (formatLang.isEmpty() && candLang.isEmpty()) {
						score += 20
					}
					if (cand.isDefault == isFormatDefault) {
						score += 20
					}
					val label = format.label
					if (!label.isNullOrBlank() && (cand.displayTitle == label || cand.title == label)) {
						score += 30
					}
				}
				is MediaStreamAudioTrack -> {
					val candLang = cand.language?.lowercase(Locale.ROOT).orEmpty()
					if (formatLang.isNotEmpty() && candLang.isNotEmpty()) {
						if (normalizeLanguageCode(formatLang) == normalizeLanguageCode(candLang)) {
							score += 50
						}
					} else if (formatLang.isEmpty() && candLang.isEmpty()) {
						score += 20
					}
					if (format.channelCount != Format.NO_VALUE && format.channelCount == cand.channels) {
						score += 30
					}
					if (cand.isDefault == isFormatDefault) {
						score += 20
					}
				}
				is MediaStreamVideoTrack -> {
					if (format.width != Format.NO_VALUE && format.width == cand.width) {
						score += 40
					}
					if (format.height != Format.NO_VALUE && format.height == cand.height) {
						score += 40
					}
				}
			}

			if (score > bestScore) {
				bestScore = score
				bestCandidate = cand
			}
		}

		return bestCandidate
	}

	override fun getTracks(trackType: Int): List<BackendTrack> {
		val trackList = mutableListOf<BackendTrack>()
		val stream = currentStream
		val currentTracks = exoPlayer.currentTracks

		val tracksForType = when (trackType) {
			C.TRACK_TYPE_AUDIO -> stream?.tracks?.filterIsInstance<MediaStreamAudioTrack>()
			C.TRACK_TYPE_TEXT -> stream?.tracks?.filterIsInstance<MediaStreamSubtitleTrack>()
			C.TRACK_TYPE_VIDEO -> stream?.tracks?.filterIsInstance<MediaStreamVideoTrack>()
			else -> null
		}

		if (tracksForType.isNullOrEmpty()) return trackList

		val prefAudio = stream?.queueEntry?.preferredAudioIndex
		val prefSub = stream?.queueEntry?.preferredSubtitleIndex
		val prefVideo = stream?.queueEntry?.preferredVideoIndex
		val isTranscoded = stream != null && stream.conversionMethod != MediaConversionMethod.None
		val activeGroups = currentTracks.groups.filter { it.type == trackType }

		for ((indexInType, mediaTrack) in tracksForType.withIndex()) {
			val index = when (mediaTrack) {
				is MediaStreamAudioTrack -> mediaTrack.index
				is MediaStreamSubtitleTrack -> mediaTrack.index
				is MediaStreamVideoTrack -> mediaTrack.index
				else -> -1
			}

			val label = when (mediaTrack) {
				is MediaStreamAudioTrack -> mediaTrack.displayTitle ?: mediaTrack.title ?: mediaTrack.language
				is MediaStreamSubtitleTrack -> mediaTrack.displayTitle ?: mediaTrack.title ?: mediaTrack.language
				is MediaStreamVideoTrack -> {
					mediaTrack.displayTitle ?: mediaTrack.title ?: buildString {
						if (mediaTrack.height > 0) {
							append("${mediaTrack.height}p")
						} else if (mediaTrack.width > 0) {
							append("${mediaTrack.width}w")
						}
						if (mediaTrack.codec.isNotEmpty()) {
							if (isNotEmpty()) append(" ")
							append(mediaTrack.codec.uppercase())
						}
						if (!mediaTrack.videoRange.isNullOrEmpty() && mediaTrack.videoRange != "SDR" && mediaTrack.videoRange != "UNKNOWN") {
							if (isNotEmpty()) append(" ")
							append(mediaTrack.videoRange)
						}
					}.ifBlank { null }
				}
				else -> null
			} ?: "Track ${indexInType + 1}"

			val language = when (mediaTrack) {
				is MediaStreamAudioTrack -> mediaTrack.language
				is MediaStreamSubtitleTrack -> mediaTrack.language
				else -> null
			}

			// Find matching ExoPlayer group info for this media track
			var matchedGroupInfo: Tracks.Group? = null
			val matchedTrackIndex = 0

			if (mediaTrack is MediaStreamSubtitleTrack && mediaTrack.isExternal) {
				matchedGroupInfo = activeGroups.firstOrNull { g ->
					g.length == 1 && g.getMediaTrackGroup().getFormat(0).id?.endsWith(":JF_EXTERNAL:$index") == true
				}
			} else if (isTranscoded) {
				val isTargetTranscodedTrack = when (trackType) {
					C.TRACK_TYPE_AUDIO -> index == (prefAudio ?: tracksForType.firstOrNull { (it as? MediaStreamAudioTrack)?.isDefault == true }?.let { (it as MediaStreamAudioTrack).index } ?: index)
					C.TRACK_TYPE_TEXT -> index == prefSub
					C.TRACK_TYPE_VIDEO -> index == (prefVideo ?: tracksForType.firstOrNull { (it as? MediaStreamVideoTrack)?.index == 0 }?.let { (it as MediaStreamVideoTrack).index } ?: index)
					else -> false
				}
				if (isTargetTranscodedTrack) {
					matchedGroupInfo = activeGroups.firstOrNull()
				}
			} else {
				matchedGroupInfo = activeGroups.firstOrNull { g ->
					val groupId = g.mediaTrackGroup.id
					groupId.endsWith(":$index") || groupId == index.toString()
				} ?: activeGroups.getOrNull(indexInType)
			}

			val group = matchedGroupInfo?.mediaTrackGroup
			val isTrackSelected = when {
				trackType == C.TRACK_TYPE_AUDIO && prefAudio != null -> index == prefAudio
				trackType == C.TRACK_TYPE_TEXT && prefSub == -1 -> false
				trackType == C.TRACK_TYPE_TEXT && prefSub != null -> index == prefSub
				trackType == C.TRACK_TYPE_VIDEO && prefVideo != null -> index == prefVideo
				matchedGroupInfo != null -> matchedGroupInfo.isTrackSelected(matchedTrackIndex)
				mediaTrack is MediaStreamAudioTrack -> mediaTrack.isDefault && trackList.none { it.isSelected }
				mediaTrack is MediaStreamSubtitleTrack -> mediaTrack.isDefault && trackList.none { it.isSelected }
				mediaTrack is MediaStreamVideoTrack -> index == 0 && trackList.none { it.isSelected }
				else -> false
			}

			trackList.add(
				BackendTrack(
					id = group?.id ?: "JF_STREAM:$index",
					type = trackType,
					label = label,
					language = language,
					isSelected = isTrackSelected,
					group = group,
					trackIndex = matchedTrackIndex,
					mediaStreamIndex = index,
				)
			)
		}

		return trackList
	}

	private fun applyPreferredTrackOverrides() {
		val queueEntry = currentStream?.queueEntry ?: return
		val prefSub = queueEntry.preferredSubtitleIndex
		val prefAudio = queueEntry.preferredAudioIndex

		val builder = exoPlayer.trackSelectionParameters.buildUpon()
		var changed = false

		if (prefSub == -1) {
			builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
			changed = true
		} else if (prefSub != null && prefSub >= 0) {
			val allTextTracks = getTracks(C.TRACK_TYPE_TEXT)
			val targetTrack = allTextTracks.firstOrNull { it.mediaStreamIndex == prefSub }
			val group = targetTrack?.group as? TrackGroup
			if (group != null) {
				builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
				builder.addOverride(TrackSelectionOverride(group, targetTrack.trackIndex))
				builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
				changed = true
			}
		}

		if (prefAudio != null && prefAudio >= 0) {
			val allAudioTracks = getTracks(C.TRACK_TYPE_AUDIO)
			val targetTrack = allAudioTracks.firstOrNull { it.mediaStreamIndex == prefAudio }
			val group = targetTrack?.group as? TrackGroup
			if (group != null) {
				builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
				builder.addOverride(TrackSelectionOverride(group, targetTrack.trackIndex))
				builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
				changed = true
			}
		}

		val prefVideo = currentStream?.queueEntry?.preferredVideoIndex
		if (prefVideo != null && prefVideo >= 0) {
			val allVideoTracks = getTracks(C.TRACK_TYPE_VIDEO)
			val targetTrack = allVideoTracks.firstOrNull { it.mediaStreamIndex == prefVideo }
			val group = targetTrack?.group as? TrackGroup
			if (group != null) {
				builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
				builder.addOverride(TrackSelectionOverride(group, targetTrack.trackIndex))
				builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
				changed = true
			}
		}

		if (changed) {
			val newParams = builder.build()
			if (exoPlayer.trackSelectionParameters != newParams) {
				exoPlayer.trackSelectionParameters = newParams
			}
		}
	}

	override fun selectTrack(trackType: Int, track: BackendTrack?) {
		val queueEntry = currentStream?.queueEntry
		if (queueEntry != null) {
			when (trackType) {
				C.TRACK_TYPE_TEXT -> queueEntry.preferredSubtitleIndex = track?.mediaStreamIndex ?: -1
				C.TRACK_TYPE_AUDIO -> if (track != null) queueEntry.preferredAudioIndex = track.mediaStreamIndex
				C.TRACK_TYPE_VIDEO -> if (track != null) queueEntry.preferredVideoIndex = track.mediaStreamIndex
			}
		}

		val currentParams = exoPlayer.trackSelectionParameters
		val builder = currentParams.buildUpon()
		if (track == null) {
			builder.clearOverridesOfType(trackType)
			builder.setTrackTypeDisabled(trackType, true)
		} else {
			builder.setTrackTypeDisabled(trackType, false)
			var group = track.group as? TrackGroup
			if (group == null && trackType == C.TRACK_TYPE_TEXT && track.mediaStreamIndex != -1) {
				val targetIndex = track.mediaStreamIndex
				val matchedGroupInfo = exoPlayer.currentTracks.groups.firstOrNull { g ->
					g.type == C.TRACK_TYPE_TEXT && g.length == 1 && g.getTrackFormat(0).id?.endsWith(":JF_EXTERNAL:$targetIndex") == true
				} ?: exoPlayer.currentTracks.groups.firstOrNull { g -> g.type == C.TRACK_TYPE_TEXT }
				group = matchedGroupInfo?.mediaTrackGroup
			}
			if (group != null) {
				builder.clearOverridesOfType(trackType)
				builder.addOverride(TrackSelectionOverride(group, track.trackIndex.coerceAtLeast(0)))
			}
		}
		val newParams = builder.build()
		if (currentParams != newParams) {
			exoPlayer.trackSelectionParameters = newParams
		}
		listener?.onTracksChanged()
	}
}

@OptIn(UnstableApi::class)
private class DelegatingSubtitleParserFactory(
	private val assSubtitleParserFactory: SubtitleParser.Factory,
	private val defaultSubtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
	private fun isAssFormat(format: Format): Boolean {
		val mime = format.sampleMimeType?.lowercase(Locale.ROOT) ?: ""
		val codecs = format.codecs?.lowercase(Locale.ROOT) ?: ""
		return mime == "text/x-ssa" || mime == "text/x-ass" || mime.contains("ass") || mime.contains("ssa") ||
			codecs.contains("ass") || codecs.contains("ssa")
	}

	override fun supportsFormat(format: Format): Boolean {
		return if (isAssFormat(format)) {
			assSubtitleParserFactory.supportsFormat(format)
		} else {
			defaultSubtitleParserFactory.supportsFormat(format)
		}
	}

	override fun getCueReplacementBehavior(format: Format): Int {
		return if (isAssFormat(format)) {
			assSubtitleParserFactory.getCueReplacementBehavior(format)
		} else {
			defaultSubtitleParserFactory.getCueReplacementBehavior(format)
		}
	}

	override fun create(format: Format): SubtitleParser {
		return if (isAssFormat(format)) {
			assSubtitleParserFactory.create(format)
		} else {
			defaultSubtitleParserFactory.create(format)
		}
	}
}

