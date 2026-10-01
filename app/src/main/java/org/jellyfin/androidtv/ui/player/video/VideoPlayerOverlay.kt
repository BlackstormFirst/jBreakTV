package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.mediasegment.mediaSegments
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
	zoomMode: ZoomMode = ZoomMode.FIT,
	onZoomSelect: (ZoomMode) -> Unit = {},
) {
	val visibilityState = rememberPlayerOverlayVisibility()
	var showPlaybackInfo by remember { mutableStateOf(false) }
	var scrubbingProgress by remember { mutableStateOf<Duration?>(null) }

	val rootFocusRequester = remember { FocusRequester() }
	val skipFocusRequester = remember { FocusRequester() }

	LaunchedEffect(visibilityState.visible) {
		if (!visibilityState.visible) {
			scrubbingProgress = null
		}
	}

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	val currentPositionMs = positionInfo.active.inWholeMilliseconds

	val mediaSegmentRepository = koinInject<MediaSegmentRepository>()
	val activeSegment = remember(entry, currentPositionMs, mediaSegmentRepository) {
		val autoHideMs = MediaSegmentRepository.AskToSkipAutoHideDuration.inWholeMilliseconds
		val segments = entry?.mediaSegments.orEmpty()
		segments.firstOrNull { segment ->
			val startMs = segment.startTicks / 10000
			val endMs = segment.endTicks / 10000
			val hideMs = minOf(endMs, startMs + autoHideMs)
			currentPositionMs in startMs until hideMs &&
				mediaSegmentRepository.getMediaSegmentAction(segment) == MediaSegmentAction.ASK_TO_SKIP
		}
	}

	LaunchedEffect(activeSegment, visibilityState.visible) {
		if (!visibilityState.visible) {
			delay(50.milliseconds)
			if (activeSegment != null) {
				runCatching { skipFocusRequester.requestFocus() }
			} else {
				runCatching { rootFocusRequester.requestFocus() }
			}
		}
	}

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			rootFocusRequester = rootFocusRequester,
			isSkipButtonPresent = activeSegment != null,
			onFocusSkipButton = { runCatching { skipFocusRequester.requestFocus() } },
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
						playbackManager = playbackManager,
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					visibilityState = visibilityState,
					onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
					zoomMode = zoomMode,
					onZoomSelect = onZoomSelect,
					onScrubbingProgressChange = { scrubbingProgress = it },
				)
			},
			onTogglePlayPause = {
				val isPlaying = playbackManager.state.playState.value == PlayState.PLAYING
				if (isPlaying) playbackManager.state.pause() else playbackManager.state.unpause()
			},
			onRewind = {
				playbackManager.state.rewind()
			},
			onFastForward = {
				playbackManager.state.fastForward()
			},
		)

		val activeScrubbing = scrubbingProgress
		if (activeScrubbing != null) {
			TrickplayFilmstripBar(
				entry = entry,
				scrubbingProgress = activeScrubbing,
				modifier = Modifier
					.fillMaxWidth()
					.align(Alignment.BottomCenter)
					.padding(bottom = 120.dp)
			)
		}

		if (activeSegment != null) {
			val coroutineScope = rememberCoroutineScope()

			val onSkip = {
				coroutineScope.launch {
					val targetMs = (activeSegment.endTicks / 10000) + 200
					playbackManager.state.seek(targetMs.milliseconds)
				}
			}

			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(bottom = 120.dp, end = 48.dp)
					.clip(RoundedCornerShape(6.dp))
					.background(colorResource(R.color.popup_menu_background).copy(alpha = 0.85f))
					.clickable { onSkip() }
					.onKeyEvent { keyEvent ->
						if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

						when (keyEvent.key) {
							Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
								onSkip()
								true
							}
							Key.DirectionLeft, Key.MediaRewind -> {
								runCatching { rootFocusRequester.requestFocus() }
								true
							}
							Key.DirectionRight, Key.MediaFastForward -> {
								true
							}
							Key.DirectionUp, Key.DirectionDown -> {
								visibilityState.show()
								true
							}
							Key.Back -> {
								if (visibilityState.visible) {
									visibilityState.hide()
								} else {
									runCatching { rootFocusRequester.requestFocus() }
								}
								true
							}
							else -> false
						}
					}
					.focusRequester(skipFocusRequester)
					.focusable()
					.padding(horizontal = 14.dp, vertical = 10.dp)
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
					contentDescription = null,
				)

				Text(
					text = stringResource(R.string.segment_action_skip),
					color = Color.White,
					fontSize = 18.sp,
				)
			}
		}

		// Playback info overlay - positioned below header area, always visible when enabled
		if (showPlaybackInfo) {
			PlaybackInfoOverlay(
				playbackManager = playbackManager,
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(start = 48.dp, top = 100.dp)
			)
		}

		MediaToasts(mediaToastRegistry)
	}
}

@Composable
private fun TrickplayFilmstripBar(
	entry: QueueEntry?,
	scrubbingProgress: Duration,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	val item = entry?.baseItem ?: return
	if (LocalVideoManager.isLocalItem(item)) return

	val trickPlayResolutions = item.mediaSources?.firstOrNull()?.id?.let { item.trickplay?.get(it) }
		?: item.trickplay?.get(item.id.toString())
		?: item.trickplay?.values?.firstOrNull()
		?: return

	val trickPlayInfo = trickPlayResolutions.values.firstOrNull() ?: return

	val mediaSourceId = item.mediaSources?.firstOrNull()?.id?.toUUIDOrNull()
		?: item.trickplay?.keys?.firstOrNull()?.toUUIDOrNull()
		?: item.id

	val currentTimeMs = scrubbingProgress.inWholeMilliseconds
	val centerTileIndex = currentTimeMs.floorDiv(trickPlayInfo.interval).toInt()

	val context = LocalContext.current

	Box(
		modifier = modifier
			.fillMaxWidth()
			.background(Color.Black.copy(alpha = 0.65f))
			.padding(vertical = 6.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
			verticalAlignment = Alignment.CenterVertically,
		) {
			for (offset in -5..5) {
				val isCenter = (offset == 0)
				val tileWidth = if (isCenter) 160.dp else 106.dp
				val tileHeight = if (isCenter) 90.dp else 60.dp

				val tileIndex = centerTileIndex + offset
				val tileTimeMs = tileIndex * trickPlayInfo.interval

				if (tileTimeMs < 0 || (item.runTimeTicks != null && tileTimeMs * 10000L > item.runTimeTicks!!)) {
					Box(
						modifier = Modifier
							.size(width = tileWidth, height = tileHeight)
							.background(Color.Black.copy(alpha = 0.4f))
					)
				} else {
					val tileSize = trickPlayInfo.tileWidth * trickPlayInfo.tileHeight
					val tileOffset = tileIndex % tileSize
					val sheetIndex = tileIndex / tileSize

					val tileOffsetX = (tileOffset % trickPlayInfo.tileWidth) * trickPlayInfo.width
					val tileOffsetY = (tileOffset / trickPlayInfo.tileWidth) * trickPlayInfo.height

					val url = remember(item.id, mediaSourceId, sheetIndex, trickPlayInfo.width) {
						api.trickplayApi.getTrickplayTileImageUrl(
							itemId = item.id,
							width = trickPlayInfo.width,
							index = sheetIndex,
							mediaSourceId = mediaSourceId,
						)
					}

					val imageRequest = remember(url, tileOffsetX, tileOffsetY, trickPlayInfo.width, trickPlayInfo.height) {
						ImageRequest.Builder(context)
							.data(url)
							.size(Size.ORIGINAL)
							.maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
							.httpHeaders(NetworkHeaders.Builder().apply {
								set(
									key = "Authorization",
									value = AuthorizationHeaderBuilder.buildHeader(
										api.clientInfo.name,
										api.clientInfo.version,
										api.deviceInfo.id,
										api.deviceInfo.name,
										api.accessToken
									)
								)
							}.build())
							.transformations(SubsetTransformation(tileOffsetX, tileOffsetY, trickPlayInfo.width, trickPlayInfo.height))
							.build()
					}

					Box(
						modifier = Modifier
							.size(width = tileWidth, height = tileHeight)
							.clip(RoundedCornerShape(4.dp))
							.background(Color.Black)
							.border(
								width = if (isCenter) 2.dp else 1.dp,
								color = if (isCenter) Color.Black else Color.Black.copy(alpha = 0.5f),
								shape = RoundedCornerShape(4.dp)
							)
					) {
						Image(
							painter = rememberAsyncImagePainter(imageRequest),
							contentDescription = null,
							modifier = Modifier.fillMaxSize()
						)
					}
				}
			}
		}

	}
}
