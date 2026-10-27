package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.androidtv.util.sdk.isRealAvailableMedia
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class HomeFragmentBrowseRowDefRow(
	private val browseRowDef: BrowseRowDef
) : HomeFragmentRow, KoinComponent {
	private val userPreferences by inject<UserPreferences>()

	suspend fun prepareRowState(
		context: Context,
		api: ApiClient,
	): HomeRowState? {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		val items: List<Any> = runCatching {
			when (browseRowDef.queryType) {
				QueryType.Resume -> {
					val response = api.itemsApi.getResumeItems(browseRowDef.resumeQuery!!).content
					response.items.filter { it.isRealAvailableMedia() }.map { item ->
						BaseItemDtoBaseRowItem(item, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight)
					}
				}
				QueryType.NextUp -> {
					val response = api.tvShowsApi.getNextUp(browseRowDef.nextUpQuery!!).content
					response.items.filter { it.isRealAvailableMedia() }.map { item ->
						BaseItemDtoBaseRowItem(item, preferParentThumb, browseRowDef.isStaticHeight)
					}
				}
				QueryType.LatestItems -> {
					val response = api.userLibraryApi.getLatestMedia(browseRowDef.latestItemsQuery!!).content
					response.filter { it.isRealAvailableMedia() }.map { item ->
						BaseItemDtoBaseRowItem(
							item = item,
							preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled],
							staticHeight = browseRowDef.isStaticHeight,
							selectAction = BaseRowItemSelectAction.ShowDetails,
							preferSeriesPoster = true,
						)
					}
				}
				QueryType.LiveTvRecording -> {
					val response = api.liveTvApi.getRecordings(browseRowDef.recordingQuery!!).content
					response.items.filter { it.isRealAvailableMedia() }.map { item ->
						BaseItemDtoBaseRowItem(item, false, false)
					}
				}
				QueryType.LiveTvProgram -> {
					val response = api.liveTvApi.getRecommendedPrograms(browseRowDef.programQuery!!).content
					response.items.map { item ->
						BaseItemDtoBaseRowItem(item, false, false)
					}
				}
				else -> {
					val query = browseRowDef.query
					if (query != null) {
						val response = api.itemsApi.getItems(query).content
						response.items.filter { it.isRealAvailableMedia() }.map { item ->
							BaseItemDtoBaseRowItem(item, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight)
						}
					} else emptyList()
				}
			}
		}.getOrElse { emptyList() }

		if (items.isEmpty()) return null

		return HomeRowState(
			id = "row_${browseRowDef.headerText}_${browseRowDef.queryType}",
			title = browseRowDef.headerText,
			items = items
		)
	}

	suspend fun prepareRowState(
		context: Context,
		cardPresenter: Any?,
		api: ApiClient,
	): HomeRowState? = prepareRowState(context, api)
}
