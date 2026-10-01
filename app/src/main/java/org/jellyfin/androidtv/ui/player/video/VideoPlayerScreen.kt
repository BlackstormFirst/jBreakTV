package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import java.io.File

private const val DefaultVideoAspectRatio = 16f / 9f

@Composable
fun VideoPlayerScreen() {
	val playbackManager = koinInject<PlaybackManager>()
	val context = LocalContext.current

	val entry by rememberQueueEntry(playbackManager)
	val baseItem = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	LaunchedEffect(entry, baseItem?.id) {
		val currentItem = baseItem
		if (currentItem != null && LocalVideoManager.isLocalItem(currentItem) && (currentItem.runTimeTicks == null || currentItem.mediaStreams.isNullOrEmpty())) {
			val path = currentItem.path
			if (!path.isNullOrEmpty()) {
				val file = File(path)
				if (file.exists()) {
					val enrichedItem = withContext(Dispatchers.IO) {
						LocalVideoManager.inspectAndBuildBaseItemDto(context, file)
					}
					entry?.baseItem = enrichedItem
				}
			}
		}
	}

	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playing by remember {
		playbackManager.state.playState.map { it == PlayState.PLAYING }
	}.collectAsState(false)
	ScreensaverLock(
		enabled = playing,
	)

	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { !it.isNaN() && it > 0f } ?: DefaultVideoAspectRatio

	var zoomMode by remember { mutableStateOf(ZoomMode.FIT) }


	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)

	Box(
		modifier = Modifier
			.background(Color.Black)
			.fillMaxSize()
	) {
		val surfaceModifier = when (zoomMode) {
			ZoomMode.FIT -> Modifier
				.aspectRatio(aspectRatio, videoSize.height < videoSize.width)
				.fillMaxSize()
				.align(Alignment.Center)
			ZoomMode.AUTO_CROP -> {
				val screenRatio = 16f / 9f
				val cropScale = when {
					aspectRatio > screenRatio -> aspectRatio / screenRatio
					aspectRatio < screenRatio -> screenRatio / aspectRatio
					else -> 1f
				}
				Modifier
					.graphicsLayer(scaleX = cropScale, scaleY = cropScale)
					.fillMaxSize()
					.align(Alignment.Center)
			}
			ZoomMode.STRETCH -> Modifier
				.fillMaxSize()
				.align(Alignment.Center)
		}

		PlayerSurface(
			playbackManager = playbackManager,
			modifier = surfaceModifier,
		)

		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = surfaceModifier,
		)

		VideoPlayerOverlay(
			playbackManager = playbackManager,
			mediaToastRegistry = mediaToastRegistry,
			zoomMode = zoomMode,
			onZoomSelect = { zoomMode = it },
		)
	}
}



