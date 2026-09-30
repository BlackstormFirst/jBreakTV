package org.jellyfin.androidtv.ui.playback

import android.content.Context
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Utility class to launch the playback UI for an item.
 */
class PlaybackLauncher(
	private val mediaManager: MediaManager,
	private val videoQueueManager: VideoQueueManager,
	private val navigationRepository: NavigationRepository,
	private val userPreferences: UserPreferences,
) {
	private val BaseItemDto.supportsExternalPlayer
		get() = when (type) {
			BaseItemKind.MOVIE,
			BaseItemKind.EPISODE,
			BaseItemKind.VIDEO,
			BaseItemKind.SERIES,
			BaseItemKind.SEASON,
			BaseItemKind.RECORDING,
			BaseItemKind.TV_CHANNEL,
			BaseItemKind.PROGRAM,
				-> true

			else -> false
		}

	@JvmOverloads
	fun launch(
		context: Context,
		items: List<BaseItemDto>,
		position: Int? = null,
		replace: Boolean = false,
		itemsPosition: Int = 0,
		shuffle: Boolean = false,
	) {
		val isAudio = items.any { it.mediaType == MediaType.AUDIO }

		if (isAudio) {
			mediaManager.playNow(context, items, itemsPosition, shuffle)
			navigationRepository.navigate(Destinations.nowPlaying)
		} else {
			val items = if (shuffle) items.shuffled() else items

			val effectiveItemsPosition = if (itemsPosition == 0 && items.size > 1 && position != null && position > 0) {
				val posTicks = position.toLong() * 10000L
				val matchedIndex = items.indexOfFirst {
					val itemTicks = it.userData?.playbackPositionTicks ?: 0L
					itemTicks > 0L && abs(itemTicks - posTicks) < 600000000L
				}
				if (matchedIndex > 0) matchedIndex else 0
			} else {
				itemsPosition
			}

			videoQueueManager.clearVideoQueue()
			videoQueueManager.setCurrentVideoQueue(items.toList())
			videoQueueManager.setCurrentMediaPosition(effectiveItemsPosition)

			if (items.isEmpty()) return

			if (userPreferences[UserPreferences.useExternalPlayer] && items.all { it.supportsExternalPlayer }) {
				context.startActivity(ActivityDestinations.externalPlayer(context, position?.milliseconds ?: Duration.ZERO))
			} else if (userPreferences[UserPreferences.playbackRewriteVideoEnabled]) {
				val destination = Destinations.videoPlayerNew(position)
				navigationRepository.navigate(destination, replace)
			} else {
				val destination = Destinations.videoPlayer(position)
				navigationRepository.navigate(destination, replace)
			}
		}
	}
}
