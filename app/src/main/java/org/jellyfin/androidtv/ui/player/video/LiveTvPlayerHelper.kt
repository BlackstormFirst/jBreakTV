package org.jellyfin.androidtv.ui.player.video

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

object LiveTvPlayerHelper {

	fun isLiveTv(item: BaseItemDto?): Boolean {
		if (item == null) return false
		return item.type == BaseItemKind.TV_CHANNEL ||
			item.type == BaseItemKind.PROGRAM ||
			item.type == BaseItemKind.LIVE_TV_CHANNEL
	}

	suspend fun fetchChannelWithCurrentProgram(api: ApiClient, channelId: UUID): BaseItemDto? {
		return runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getChannel(channelId).content
			}
		}.getOrElse { error ->
			Timber.e(error, "Failed to fetch channel $channelId via liveTvApi")
			runCatching {
				withContext(Dispatchers.IO) {
					api.userLibraryApi.getItem(channelId).content
				}
			}.getOrNull()
		}
	}

	suspend fun switchChannel(
		api: ApiClient,
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
		targetChannelId: UUID,
	): Boolean {
		val channel = fetchChannelWithCurrentProgram(api, targetChannelId) ?: return false

		TvManager.setLastLiveTvChannel(targetChannelId)

		val items = listOf(channel)
		videoQueueManager.clearVideoQueue()
		videoQueueManager.setCurrentVideoQueue(items)
		videoQueueManager.setCurrentMediaPosition(0)

		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, items, false)
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, 0)
		return true
	}

	suspend fun switchNextChannel(
		api: ApiClient,
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
		currentChannelId: UUID?,
	): Boolean {
		val allChannels = TvManager.getAllChannels()
		if (allChannels.isEmpty() || currentChannelId == null) return false

		val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
		val nextIndex = if (currentIndex >= 0 && currentIndex < allChannels.size - 1) {
			currentIndex + 1
		} else {
			0
		}
		val nextChannelId = allChannels[nextIndex].id
		return switchChannel(api, playbackManager, videoQueueManager, nextChannelId)
	}

	suspend fun switchPreviousChannel(
		api: ApiClient,
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
		currentChannelId: UUID?,
	): Boolean {
		val allChannels = TvManager.getAllChannels()
		if (allChannels.isEmpty() || currentChannelId == null) return false

		val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
		val prevIndex = if (currentIndex > 0) {
			currentIndex - 1
		} else {
			allChannels.size - 1
		}
		val prevChannelId = allChannels[prevIndex].id
		return switchChannel(api, playbackManager, videoQueueManager, prevChannelId)
	}

	suspend fun switchPrevTunedChannel(
		api: ApiClient,
		playbackManager: PlaybackManager,
		videoQueueManager: VideoQueueManager,
	): Boolean {
		val prevChannelId = TvManager.getPrevLiveTvChannel() ?: return false
		return switchChannel(api, playbackManager, videoQueueManager, prevChannelId)
	}
}
