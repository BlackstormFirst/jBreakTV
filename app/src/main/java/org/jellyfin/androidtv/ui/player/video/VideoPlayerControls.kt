package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuCheckboxItem
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayVisibilityState
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.util.apiclient.chapterImages
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.ui.playback.PlaybackIndexManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.BackendTrack
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamService
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.Queue
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.playback.core.queue.preferredAudioIndex
import org.jellyfin.playback.core.queue.preferredSubtitleIndex
import org.jellyfin.playback.core.queue.preferredVideoIndex
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.compose.koinInject
import timber.log.Timber
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun VideoPlayerControls(
	playbackManager: PlaybackManager = koinInject(),
	visibilityState: PlayerOverlayVisibilityState = rememberPlayerOverlayVisibility(),
	onPlaybackInfoClick: () -> Unit = {},
	onToggleGuide: (() -> Unit)? = null,
	zoomMode: ZoomMode = ZoomMode.FIT,
	onZoomSelect: (ZoomMode) -> Unit = {},
	onScrubbingProgressChange: ((Duration?) -> Unit)? = null,
) {
	val api = koinInject<ApiClient>()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val entry by rememberQueueEntry(playbackManager)
	val baseItem = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value ?: entry?.baseItem
	val isLiveTv = LiveTvPlayerHelper.isLiveTv(baseItem)
	val currentChannelId = baseItem?.id

	val playState by playbackManager.state.playState.collectAsState()
	var scrubbingProgress by remember { mutableStateOf<Duration?>(null) }

	val playPauseFocusRequester = remember { FocusRequester() }

	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val entries by playbackManager.queue.entries.collectAsState()
	val hasPrevious = entryIndex > 0 && entries.size > 1

	val estimatedSize = remember(entries, entryIndex) { playbackManager.queue.estimatedSize }
	val hasNext = remember(entryIndex, estimatedSize, entries) {
		entryIndex in 0 until (maxOf(estimatedSize, entries.size) - 1)
	}

	var prevHasPrevious by remember { mutableStateOf(hasPrevious) }
	var prevHasNext by remember { mutableStateOf(hasNext) }

	val coroutineScope = rememberCoroutineScope()

	LaunchedEffect(hasPrevious) {
		if (prevHasPrevious && !hasPrevious) {
			delay(50.milliseconds)
			runCatching { playPauseFocusRequester.requestFocus() }
		}
		prevHasPrevious = hasPrevious
	}

	LaunchedEffect(hasNext) {
		if (prevHasNext && !hasNext) {
			delay(50.milliseconds)
			runCatching { playPauseFocusRequester.requestFocus() }
		}
		prevHasNext = hasNext
	}

	Column(
		verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier
				.focusRestorer()
				.focusGroup()
		) {
			PlayPauseButton(playbackManager, playState, playPauseFocusRequester)
			if (isLiveTv) {
				IconButton(
					onClick = {
						coroutineScope.launch {
							LiveTvPlayerHelper.switchPreviousChannel(api, playbackManager, videoQueueManager, currentChannelId)
						}
					}
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_previous),
						contentDescription = stringResource(R.string.lbl_prev_item),
					)
				}
			} else {
				PreviousEntryButton(playbackManager)
			}
			RewindButton(playbackManager)
			FastForwardButton(playbackManager)
			if (isLiveTv) {
				IconButton(
					onClick = {
						coroutineScope.launch {
							LiveTvPlayerHelper.switchNextChannel(api, playbackManager, videoQueueManager, currentChannelId)
						}
					}
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_next),
						contentDescription = stringResource(R.string.lbl_next_item),
					)
				}
			} else {
				NextEntryButton(playbackManager)
				ChapterButton(playbackManager, visibilityState)
			}

			if (isLiveTv && onToggleGuide != null) {
				IconButton(
					onClick = { onToggleGuide.invoke() }
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_tv_play),
						contentDescription = stringResource(R.string.lbl_live_tv_guide),
					)
				}
			}

			Spacer(Modifier.weight(1f))

			SubtitleButton(playbackManager, visibilityState)
			AudioButton(playbackManager, visibilityState)
			VideoButton(playbackManager, visibilityState)
			if (!isLiveTv) {
				PlaybackSpeedButton(playbackManager, visibilityState)
			}
			MaxBitrateButton(playbackManager, visibilityState)
			ZoomButton(zoomMode, onZoomSelect, visibilityState)

			MoreOptionsButton(visibilityState) {
				PlaybackInfoButton(onClick = onPlaybackInfoClick)
			}
		}

		PlayerSeekbar(
			playbackManager = playbackManager,
			onScrubbingProgressChange = { progress ->
				scrubbingProgress = progress
				if (progress != null) visibilityState.pin() else visibilityState.unpin()
				onScrubbingProgressChange?.invoke(progress)
			},
			modifier = Modifier
				.fillMaxWidth()
				.height(4.dp)
		)

		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier
				.focusRestorer()
				.focusGroup()
		) {
			Spacer(Modifier.weight(1f))
			PositionText(playbackManager, scrubbingProgress)
		}
	}
}

@Composable
private fun PlayPauseButton(
	playbackManager: PlaybackManager,
	playState: PlayState,
	focusRequester: FocusRequester = remember { FocusRequester() },
) {
	IconButton(
		onClick = {
			when (playState) {
				PlayState.STOPPED,
				PlayState.ERROR -> playbackManager.state.play()

				PlayState.PLAYING -> playbackManager.state.pause()
				PlayState.PAUSED -> playbackManager.state.unpause()
			}
		},
		modifier = Modifier.focusRequester(focusRequester),
	) {
		AnimatedContent(playState) { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
						contentDescription = stringResource(R.string.lbl_pause),
					)
				}

				PlayState.STOPPED,
				PlayState.PAUSED,
				PlayState.ERROR -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_play),
						contentDescription = stringResource(R.string.lbl_play),
					)
				}
			}
		}
	}
}

@Composable
private fun RewindButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.rewind() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_rewind),
		contentDescription = stringResource(R.string.rewind),
	)
}

@Composable
private fun FastForwardButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.fastForward() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_fast_forward),
		contentDescription = stringResource(R.string.fast_forward),
	)
}

@Composable
private fun PreviousEntryButton(
	playbackManager: PlaybackManager,
) {
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val entries by playbackManager.queue.entries.collectAsState()
	if (entryIndex <= 0 || entries.size <= 1) return

	val coroutineScope = rememberCoroutineScope()

	IconButton(
		onClick = {
			coroutineScope.launch {
				playbackManager.queue.previous()
			}
		},
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_previous),
			contentDescription = stringResource(R.string.lbl_prev_item),
		)
	}
}

@Composable
private fun NextEntryButton(
	playbackManager: PlaybackManager,
) {
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val entries by playbackManager.queue.entries.collectAsState()
	val estimatedSize = remember(entries, entryIndex) { playbackManager.queue.estimatedSize }
	val hasNext = remember(entryIndex, estimatedSize, entries) {
		entryIndex in 0 until (maxOf(estimatedSize, entries.size) - 1)
	}
	if (!hasNext) return

	val coroutineScope = rememberCoroutineScope()

	IconButton(
		onClick = {
			coroutineScope.launch {
				playbackManager.queue.next()
			}
		},
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next),
			contentDescription = stringResource(R.string.lbl_next_item),
		)
	}
}

private fun Duration.formatted(includeHours: Boolean): String {
	val totalSeconds = toInt(DurationUnit.SECONDS)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60

	return if (includeHours) "%02d:%02d:%02d".format(hours, minutes, seconds)
	else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PositionText(
	playbackManager: PlaybackManager,
	scrubbingProgress: Duration? = null,
) {
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	if (positionInfo.duration == Duration.ZERO) return

	val activePosition = scrubbingProgress ?: positionInfo.active

	val text = remember(activePosition, positionInfo.duration) {
		val includeHours = positionInfo.duration.inWholeMinutes >= 60
		val activeFormatted = activePosition.formatted(includeHours)
		val durationFormatted = positionInfo.duration.formatted(includeHours)

		"$activeFormatted / $durationFormatted"
	}

	Text(
		text = text,
		style = LocalTextStyle.current.copy(color = Color.White)
	)
}

@Composable
private fun MoreOptionsButton(
	visibilityState: PlayerOverlayVisibilityState,
	content: @Composable () -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	IconButton(
		onClick = { expanded = true },
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_more),
			contentDescription = stringResource(R.string.lbl_other_options),
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp)
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier
				.padding(4.dp)
		) {
			content()
		}
	}
}

@Composable
fun PlaybackInfoButton(
	onClick: () -> Unit,
) = IconButton(
	onClick = onClick,
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_info),
		contentDescription = stringResource(R.string.playback_info),
	)
}

@Composable
private fun SubtitleButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val coroutineScope = rememberCoroutineScope()
	val context = LocalContext.current
	val videoQueueManager = koinInject<VideoQueueManager>()
	val playbackIndexManager = koinInject<PlaybackIndexManager>()
	val entry by rememberQueueEntry(playbackManager)
	val baseItem = entry?.baseItem
	val backend = playbackManager.backend
	val subStreams = baseItem?.mediaStreams?.filter { it.type == MediaStreamType.SUBTITLE }.orEmpty()

	val isLocal = baseItem?.let { LocalVideoManager.isLocalItem(it) } == true
	val mediaSource = baseItem?.mediaSources?.firstOrNull() ?: baseItem?.let { item ->
		MediaSourceInfo(
			protocol = if (isLocal) MediaProtocol.FILE else MediaProtocol.HTTP,
			id = item.id.toString(),
			path = item.path,
			type = MediaSourceType.DEFAULT,
			isRemote = !isLocal,
			mediaStreams = item.mediaStreams,
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
	}

	val currentPlayableStream = entry?.mediaStream
	val isTranscodingOrRemux = currentPlayableStream?.conversionMethod != null && currentPlayableStream.conversionMethod != MediaConversionMethod.None

	var cachedSubTracks by remember { mutableStateOf<List<BackendTrack>>(emptyList()) }
	val rawSubTracks = if (expanded) backend.getTracks(3).also { cachedSubTracks = it } else cachedSubTracks

	val activeAudioIndex = entry?.preferredAudioIndex
		?: videoQueueManager.getSelectedAudioStreamIndex()
		?: mediaSource?.let { playbackIndexManager.getBestAudioIndex(it) }

	val activeSubIndex = entry?.preferredSubtitleIndex
		?: videoQueueManager.getSelectedSubtitleStreamIndex()
		?: mediaSource?.let {
			val activeAudioLang = mediaSource.mediaStreams?.firstOrNull { s -> s.index == activeAudioIndex }?.language
			playbackIndexManager.getBestSubtitleIndex(it, context, activeAudioLang)
		}

	val subTracks = if (rawSubTracks.isNotEmpty() && !isTranscodingOrRemux) {
		rawSubTracks
	} else {
		subStreams.mapIndexed { idx, stream ->
			val isSelected = activeSubIndex != null && activeSubIndex >= 0 && stream.index == activeSubIndex
			BackendTrack(
				id = "JF_STREAM:${stream.index}",
				type = 3,
				label = stream.displayTitle ?: stream.title ?: stream.language ?: "Track ${idx + 1}",
				language = stream.language,
				isSelected = isSelected,
				mediaStreamIndex = stream.index,
			)
		}
	}
	val isNoneSelected = activeSubIndex == null || activeSubIndex < 0 || subTracks.none { it.isSelected }
	val selectedFocusRequester = remember { FocusRequester() }

	IconButton(
		onClick = { expanded = true },
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_select_subtitle),
			contentDescription = stringResource(R.string.lbl_subtitle_track),
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
		initialFocusRequester = selectedFocusRequester,
	) {
		PopoverMenu {
			PopoverMenuCheckboxItem(
				selected = isNoneSelected,
				focusRequester = if (isNoneSelected) selectedFocusRequester else null,
				onClick = {
					if (isNoneSelected) {
						Timber.d("VideoPlayerControls: skipping subtitle switch, already set to -1")
						expanded = false
						return@PopoverMenuCheckboxItem
					}
					videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
					videoQueueManager.setLastPlayedSubtitleTitle(null)
					videoQueueManager.setLastPlayedSubtitleCodec(null)
					videoQueueManager.setSelectedSubtitleStreamIndex(-1)
					entry?.preferredSubtitleIndex = -1

					if (!isTranscodingOrRemux) {
						backend.selectTrack(3, null)
					} else {
						val currentPos = backend.getPositionInfo().active
						entry?.let { currentEntry ->
							val baseItemObj = currentEntry.baseItem
							if (baseItemObj != null) {
								val newTicks = currentPos.inWholeMilliseconds * 10000L
								val newUserData = baseItemObj.userData?.copy(playbackPositionTicks = newTicks)
								currentEntry.baseItem = baseItemObj.copy(userData = newUserData)
							}
							coroutineScope.launch {
								playbackManager.getService<MediaStreamService>()?.reloadStream(currentEntry)
							}
						}
					}
					expanded = false
				}
			) {
				Text(stringResource(R.string.lbl_none))
			}
			subTracks.forEachIndexed { trackIndexInList, track ->
				PopoverMenuCheckboxItem(
					selected = track.isSelected,
					focusRequester = if (track.isSelected) selectedFocusRequester else null,
					onClick = {
						if (track.isSelected || (activeSubIndex != null && activeSubIndex >= 0 && track.mediaStreamIndex == activeSubIndex)) {
							Timber.d("VideoPlayerControls: skipping subtitle switch, already set to ${track.mediaStreamIndex}")
							expanded = false
							return@PopoverMenuCheckboxItem
						}
						val mediaStream = subStreams.firstOrNull { it.index == track.mediaStreamIndex }
							?: subStreams.firstOrNull { (it.language != null && it.language.equals(track.language, ignoreCase = true)) || it.displayTitle == track.label || it.title == track.label }
							?: subStreams.getOrNull(trackIndexInList)
						val lang = track.language ?: mediaStream?.language
						videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(lang)
						videoQueueManager.setLastPlayedSubtitleTitle(mediaStream?.title ?: track.label)
						val codec = mediaStream?.codec
						if (codec != null) videoQueueManager.setLastPlayedSubtitleCodec(codec)
						videoQueueManager.setLastPlayedSubtitleDefaultState(mediaStream?.isDefault ?: false)
						videoQueueManager.setLastPlayedSubtitleForcedState(mediaStream?.isForced ?: false)
						videoQueueManager.setLastPlayedSubtitleHearingImpairedState(mediaStream?.isHearingImpaired ?: false)
						videoQueueManager.setSelectedSubtitleStreamIndex(track.mediaStreamIndex)
						entry?.preferredSubtitleIndex = track.mediaStreamIndex

						val isExternalSubtitle = mediaStream?.isExternal == true || track.id.startsWith("JF_EXTERNAL:")
						val isTrackInExoPlayer = track.group != null

						if (!isTranscodingOrRemux || isExternalSubtitle || isTrackInExoPlayer) {
							backend.selectTrack(3, track)
						} else {
							val currentPos = backend.getPositionInfo().active
							entry?.let { currentEntry ->
								val baseItemObj = currentEntry.baseItem
								if (baseItemObj != null) {
									val newTicks = currentPos.inWholeMilliseconds * 10000L
									val newUserData = baseItemObj.userData?.copy(playbackPositionTicks = newTicks)
									currentEntry.baseItem = baseItemObj.copy(userData = newUserData)
								}
								coroutineScope.launch {
									playbackManager.getService<MediaStreamService>()?.reloadStream(currentEntry)
								}
							}
						}
						expanded = false
					}
				) {
					Text(track.label)
				}
			}
		}
	}
}

@Composable
private fun AudioButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val coroutineScope = rememberCoroutineScope()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val playbackIndexManager = koinInject<PlaybackIndexManager>()
	val entry by rememberQueueEntry(playbackManager)
	val baseItem = entry?.baseItem
	val backend = playbackManager.backend
	val audioStreams = baseItem?.mediaStreams?.filter { it.type == MediaStreamType.AUDIO }.orEmpty()

	val isLocal = baseItem?.let { LocalVideoManager.isLocalItem(it) } == true
	val mediaSource = baseItem?.mediaSources?.firstOrNull() ?: baseItem?.let { item ->
		MediaSourceInfo(
			protocol = if (isLocal) MediaProtocol.FILE else MediaProtocol.HTTP,
			id = item.id.toString(),
			path = item.path,
			type = MediaSourceType.DEFAULT,
			isRemote = !isLocal,
			mediaStreams = item.mediaStreams,
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
	}

	val currentPlayableStream = entry?.mediaStream
	val isTranscodingOrRemux = currentPlayableStream?.conversionMethod != null && currentPlayableStream.conversionMethod != MediaConversionMethod.None

	var cachedAudioTracks by remember { mutableStateOf<List<BackendTrack>>(emptyList()) }
	val rawAudioTracks = if (expanded) backend.getTracks(1).also { cachedAudioTracks = it } else cachedAudioTracks

	val activeAudioIndex = entry?.preferredAudioIndex
		?: videoQueueManager.getSelectedAudioStreamIndex()
		?: mediaSource?.let { playbackIndexManager.getBestAudioIndex(it) }

	val audioTracks = if (rawAudioTracks.isNotEmpty() && !isTranscodingOrRemux) {
		rawAudioTracks
	} else {
		audioStreams.mapIndexed { idx, stream ->
			val isSelected = activeAudioIndex != null && stream.index == activeAudioIndex
			BackendTrack(
				id = "JF_STREAM:${stream.index}",
				type = 1,
				label = stream.displayTitle ?: stream.title ?: stream.language ?: "Track ${idx + 1}",
				language = stream.language,
				isSelected = isSelected,
				mediaStreamIndex = stream.index,
			)
		}
	}
	val selectedFocusRequester = remember { FocusRequester() }

	IconButton(
		onClick = { expanded = true },
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_select_audio),
			contentDescription = stringResource(R.string.lbl_audio_track),
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
		initialFocusRequester = selectedFocusRequester,
	) {
		PopoverMenu {
			audioTracks.forEachIndexed { trackIndexInList, track ->
				PopoverMenuCheckboxItem(
					selected = track.isSelected,
					focusRequester = if (track.isSelected) selectedFocusRequester else null,
					onClick = {
						if (track.isSelected || (activeAudioIndex != null && activeAudioIndex >= 0 && track.mediaStreamIndex == activeAudioIndex)) {
							Timber.d("VideoPlayerControls: skipping audio switch, already set to ${track.mediaStreamIndex}")
							expanded = false
							return@PopoverMenuCheckboxItem
						}
						val mediaStream = audioStreams.firstOrNull { it.index == track.mediaStreamIndex }
							?: audioStreams.firstOrNull { (it.language != null && it.language.equals(track.language, ignoreCase = true)) || it.displayTitle == track.label || it.title == track.label }
							?: audioStreams.getOrNull(trackIndexInList)
						val lang = track.language ?: mediaStream?.language
						if (lang != null) videoQueueManager.setLastPlayedAudioLanguageIsoCode(lang)
						val codec = mediaStream?.codec
						if (codec != null) videoQueueManager.setLastPlayedAudioCodec(codec)
						videoQueueManager.setLastPlayedAudioTitle(mediaStream?.title ?: track.label)
						videoQueueManager.setLastPlayedAudioDefaultState(mediaStream?.isDefault ?: false)
						videoQueueManager.setLastPlayedAudioHearingImpairedState(mediaStream?.isHearingImpaired ?: false)
						val audioTypeIndex = if (mediaStream != null) audioStreams.indexOf(mediaStream) else trackIndexInList
						if (audioTypeIndex >= 0) videoQueueManager.setLastPlayedAudioIndexInType(audioTypeIndex)
						videoQueueManager.setSelectedAudioStreamIndex(track.mediaStreamIndex)

						val currentPlayableStream = entry?.mediaStream
						val isTranscodingOrRemux = currentPlayableStream?.conversionMethod != null && currentPlayableStream.conversionMethod != MediaConversionMethod.None
						val isTrackInExoPlayer = track.group != null

						if (!isTranscodingOrRemux && isTrackInExoPlayer) {
							backend.selectTrack(1, track)
						} else {
							val currentPos = backend.getPositionInfo().active
							entry?.let { currentEntry ->
								currentEntry.preferredAudioIndex = track.mediaStreamIndex
								val baseItemObj = currentEntry.baseItem
								if (baseItemObj != null) {
									val newTicks = currentPos.inWholeMilliseconds * 10000L
									val newUserData = baseItemObj.userData?.copy(playbackPositionTicks = newTicks)
									currentEntry.baseItem = baseItemObj.copy(userData = newUserData)
								}
								coroutineScope.launch {
									playbackManager.getService<MediaStreamService>()?.reloadStream(currentEntry)
								}
							}
						}
						expanded = false
					}
				) {
					Text(track.label)
				}
			}
		}
	}
}

@Composable
private fun VideoButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val coroutineScope = rememberCoroutineScope()
	val videoQueueManager = koinInject<VideoQueueManager>()
	val playbackIndexManager = koinInject<PlaybackIndexManager>()
	val entry by rememberQueueEntry(playbackManager)
	val baseItem = entry?.baseItem
	val backend = playbackManager.backend
	val videoStreams = baseItem?.mediaStreams?.filter { it.type == MediaStreamType.VIDEO }.orEmpty()

	val isLocal = baseItem?.let { LocalVideoManager.isLocalItem(it) } == true
	val mediaSource = baseItem?.mediaSources?.firstOrNull() ?: baseItem?.let { item ->
		MediaSourceInfo(
			protocol = if (isLocal) MediaProtocol.FILE else MediaProtocol.HTTP,
			id = item.id.toString(),
			path = item.path,
			type = MediaSourceType.DEFAULT,
			isRemote = !isLocal,
			mediaStreams = item.mediaStreams,
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
	}

	val currentPlayableStream = entry?.mediaStream
	val isTranscodingOrRemux = currentPlayableStream?.conversionMethod != null && currentPlayableStream.conversionMethod != MediaConversionMethod.None

	var cachedVideoTracks by remember { mutableStateOf<List<BackendTrack>>(emptyList()) }
	val rawVideoTracks = if (expanded) backend.getTracks(2).also { cachedVideoTracks = it } else cachedVideoTracks

	val activeVideoIndex = entry?.preferredVideoIndex
		?: videoQueueManager.getSelectedVideoStreamIndex()
		?: mediaSource?.let { playbackIndexManager.getBestVideoIndex(it) }

	val videoTracks = if (rawVideoTracks.isNotEmpty() && !isTranscodingOrRemux) {
		rawVideoTracks
	} else {
		videoStreams.mapIndexed { idx, stream ->
			val isSelected = activeVideoIndex != null && stream.index == activeVideoIndex
			val streamCodec = stream.codec
			val label = stream.displayTitle ?: stream.title ?: buildString {
				if ((stream.height ?: 0) > 0) append("${stream.height}p")
				else if ((stream.width ?: 0) > 0) append("${stream.width}w")
				if (!streamCodec.isNullOrEmpty()) {
					if (isNotEmpty()) append(" ")
					append(streamCodec.uppercase())
				}
			}.ifBlank { "Track ${idx + 1}" }

			BackendTrack(
				id = "JF_STREAM:${stream.index}",
				type = 2,
				label = label,
				isSelected = isSelected,
				mediaStreamIndex = stream.index,
			)
		}
	}

	val selectedFocusRequester = remember { FocusRequester() }

	if (videoStreams.size > 1 || videoTracks.size > 1) {
		IconButton(
			onClick = { expanded = true },
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_video),
				contentDescription = stringResource(R.string.lbl_video_track),
			)
		}

		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.TopCenter,
			offset = DpOffset(0.dp, (-5).dp),
			initialFocusRequester = selectedFocusRequester,
		) {
			PopoverMenu {
				videoTracks.forEachIndexed { trackIndexInList, track ->
					val isSelected = track.isSelected || (activeVideoIndex != null && activeVideoIndex >= 0 && track.mediaStreamIndex == activeVideoIndex)
					PopoverMenuCheckboxItem(
						selected = isSelected,
						focusRequester = if (isSelected) selectedFocusRequester else null,
						onClick = {
							if (isSelected) {
								Timber.d("VideoPlayerControls: skipping video switch, already set to ${track.mediaStreamIndex}")
								expanded = false
								return@PopoverMenuCheckboxItem
							}
							val mediaStream = videoStreams.firstOrNull { it.index == track.mediaStreamIndex }
								?: videoStreams.firstOrNull { it.displayTitle == track.label || it.title == track.label }
								?: videoStreams.getOrNull(trackIndexInList)
							val videoTypeIndex = if (mediaStream != null) videoStreams.indexOf(mediaStream) else trackIndexInList
							videoQueueManager.setLastPlayedVideoDefaultState(mediaStream?.isDefault ?: false)
							val codec = mediaStream?.codec
							if (codec != null) videoQueueManager.setLastPlayedVideoCodec(codec)
							videoQueueManager.setLastPlayedVideoTitle(mediaStream?.title ?: track.label)
							if (videoTypeIndex >= 0) videoQueueManager.setLastPlayedVideoIndexInType(videoTypeIndex)
							videoQueueManager.setSelectedVideoStreamIndex(track.mediaStreamIndex)
							entry?.preferredVideoIndex = track.mediaStreamIndex

							val isTrackInExoPlayer = track.group != null

							if (!isTranscodingOrRemux && isTrackInExoPlayer) {
								backend.selectTrack(2, track)
							} else {
								val currentPos = backend.getPositionInfo().active
								entry?.let { currentEntry ->
									val baseItemObj = currentEntry.baseItem
									if (baseItemObj != null) {
										val newTicks = currentPos.inWholeMilliseconds * 10000L
										val newUserData = baseItemObj.userData?.copy(playbackPositionTicks = newTicks)
										currentEntry.baseItem = baseItemObj.copy(userData = newUserData)
									}
									coroutineScope.launch {
										playbackManager.getService<MediaStreamService>()?.reloadStream(currentEntry)
									}
								}
							}
							expanded = false
						}
					) {
						Text(track.label)
					}
				}
			}
		}
	}
}

@Composable
private fun PlaybackSpeedButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val currentSpeed by playbackManager.state.speed.collectAsState()
	val speeds = remember { listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
	val selectedFocusRequester = remember { FocusRequester() }

	IconButton(
		onClick = { expanded = true },
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_playback_speed),
			contentDescription = stringResource(R.string.lbl_playback_speed),
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
		initialFocusRequester = selectedFocusRequester,
	) {
		PopoverMenu {
			speeds.forEach { speed ->
				val isSelected = currentSpeed == speed
				PopoverMenuCheckboxItem(
					selected = isSelected,
					focusRequester = if (isSelected) selectedFocusRequester else null,
					onClick = {
						playbackManager.state.setSpeed(speed)
						expanded = false
					}
				) {
					Text("${speed}x")
				}
			}
		}
	}
}

@Composable
private fun MaxBitrateButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) {
	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value ?: entry?.baseItem
	if (item != null && LocalVideoManager.isLocalItem(item)) return

	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	var maxBitrate by rememberPreference(userPreferences, UserPreferences.maxBitrate)
	val options = remember(context) { getQualityProfiles(context).toList() }
	val selectedFocusRequester = remember { FocusRequester() }
	val coroutineScope = rememberCoroutineScope()

	Box {
		IconButton(
			onClick = { expanded = true },
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_select_quality),
				contentDescription = stringResource(R.string.lbl_quality_profile),
			)
		}

		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.TopCenter,
			offset = DpOffset(0.dp, (-5).dp),
			initialFocusRequester = selectedFocusRequester,
		) {
			PopoverMenu {
				options.forEach { (value, label) ->
					val isSelected = maxBitrate == value
					PopoverMenuCheckboxItem(
						selected = isSelected,
						focusRequester = if (isSelected) selectedFocusRequester else null,
						onClick = {
							if (maxBitrate != value) {
								maxBitrate = value
								coroutineScope.launch {
									val currentPositionMs = playbackManager.state.positionInfo.active.inWholeMilliseconds
									val entry = playbackManager.queue.entry.value
									if (entry != null) {
										val updatedUserData = entry.baseItem?.userData?.copy(playbackPositionTicks = currentPositionMs * 10000L)
										entry.baseItem = entry.baseItem?.copy(userData = updatedUserData)
										entry.mediaStream = null
										val currentIndex = playbackManager.queue.entryIndex.value
										if (currentIndex != Queue.INDEX_NONE) {
											playbackManager.queue.setIndex(Queue.INDEX_NONE)
											playbackManager.queue.setIndex(currentIndex)
										}
									}
								}
							}
							expanded = false
						}
					) {
						Text(label)
					}
				}
			}
		}
	}
}

@Composable
private fun ZoomButton(
	zoomMode: ZoomMode,
	onZoomSelect: (ZoomMode) -> Unit,
	visibilityState: PlayerOverlayVisibilityState,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val selectedFocusRequester = remember { FocusRequester() }

	IconButton(
		onClick = { expanded = true },
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_aspect_ratio),
			contentDescription = stringResource(R.string.lbl_zoom),
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
		initialFocusRequester = selectedFocusRequester,
	) {
		PopoverMenu {
			ZoomMode.entries.forEach { mode ->
				val isSelected = zoomMode == mode
				PopoverMenuCheckboxItem(
					selected = isSelected,
					focusRequester = if (isSelected) selectedFocusRequester else null,
					onClick = {
						onZoomSelect(mode)
						expanded = false
					}
				) {
					Text(stringResource(mode.nameRes))
				}
			}
		}
	}
}

private fun formatTicks(ticks: Long): String {
	val totalSeconds = ticks / 10_000_000L
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60
	return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
	else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun ChapterButton(
	playbackManager: PlaybackManager,
	visibilityState: PlayerOverlayVisibilityState,
) {
	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val chapters = item?.chapters.orEmpty()
	if (chapters.isEmpty()) return

	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(expanded) {
		if (expanded) visibilityState.pin() else visibilityState.unpin()
	}

	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)

	// Capture active chapter index once when opening the popover to prevent focus jumps / crashes during background playback
	val activeChapterIndex = remember(expanded) {
		if (expanded) {
			val currentTicks = positionInfo.active.inWholeMilliseconds * 10000L
			chapters.indexOfLast { currentTicks >= it.startPositionTicks }.coerceAtLeast(0)
		} else 0
	}

	val selectedFocusRequester = remember { FocusRequester() }
	val coroutineScope = rememberCoroutineScope()
	val context = LocalContext.current
	val api = koinInject<ApiClient>()

	Box {
		IconButton(
			onClick = { expanded = true },
		) {
			Icon(
				imageVector = ImageVector.vectorResource(R.drawable.ic_select_chapter),
				contentDescription = stringResource(R.string.lbl_chapters),
			)
		}

		Popover(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			alignment = Alignment.TopCenter,
			offset = DpOffset(0.dp, (-5).dp),
			initialFocusRequester = selectedFocusRequester,
		) {
			PopoverMenu {
				chapters.forEachIndexed { index, chapter ->
					val isSelected = index == activeChapterIndex
					val itemFocusRequester = if (isSelected) selectedFocusRequester else null

					val chapterImage = item?.chapterImages?.getOrNull(index)
					val serverUrl = if (!chapterImage?.tag.isNullOrBlank()) {
						runCatching { chapterImage.getUrl(api, maxWidth = 160) }.getOrNull()
					} else null

					val chapterImageRequest = remember(item?.id, index, item?.path, serverUrl) {
						val localPath = item?.path
						val localFile = if (!localPath.isNullOrEmpty()) {
							File(context.cacheDir, "chapters/${localPath.hashCode()}_chap_${index}.jpg")
						} else null

						if (localFile != null && localFile.exists()) {
							ImageRequest.Builder(context).data(localFile).build()
						} else if (!serverUrl.isNullOrEmpty()) {
							ImageRequest.Builder(context).data(serverUrl).build()
						} else null
					}

					PopoverMenuCheckboxItem(
						selected = isSelected,
						focusRequester = itemFocusRequester,
						onClick = {
							coroutineScope.launch {
								runCatching {
									playbackManager.state.seek((chapter.startPositionTicks / 10000L).milliseconds)
								}
							}
							expanded = false
						}
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(10.dp)
						) {
							if (chapterImageRequest != null) {
								Image(
									painter = rememberAsyncImagePainter(chapterImageRequest),
									contentDescription = null,
									modifier = Modifier
										.size(width = 48.dp, height = 27.dp)
										.clip(RoundedCornerShape(3.dp))
								)
							}
							Column {
								Text(
									text = chapter.name.orEmpty().ifBlank { "Chapitre ${index + 1}" },
									style = LocalTextStyle.current.copy(fontSize = 14.sp)
								)
								Text(
									text = formatTicks(chapter.startPositionTicks),
									style = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
								)
							}
						}
					}
				}
			}
		}
	}
}
