package org.jellyfin.androidtv.ui.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.util.UUID

fun BaseItemDto.copyWithLastPlayedDate(
	lastPlayedDate: LocalDateTime,
) = copy(
	userData = userData?.copy(
		lastPlayedDate = lastPlayedDate,
	)
)

suspend fun fetchLiveTvChannels(
	api: ApiClient,
	liveTvPreferences: LiveTvPreferences,
): List<BaseItemDto>? {
	val sortDatePlayed =
		liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName

	return runCatching {
		withContext(Dispatchers.IO) {
			api.liveTvApi.getLiveTvChannels(
				addCurrentProgram = true,
				enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
				sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
				sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
			).content.items.toList()
		}
	}.getOrNull()
}

suspend fun fetchPrograms(
	api: ApiClient,
	channelIds: List<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
): List<BaseItemDto>? {
	return runCatching {
		withContext(Dispatchers.IO) {
			api.liveTvApi.getLiveTvPrograms(
				channelIds = channelIds,
				enableImages = false,
				sortBy = setOf(ItemSortBy.START_DATE),
				maxStartDate = endTime,
				minEndDate = startTime,
			).content.items.toList()
		}
	}.getOrNull()
}
