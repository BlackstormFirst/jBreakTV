package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItemSelectAction
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.setInitialItems
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
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

	suspend fun prepareRow(
		context: Context,
		cardPresenter: CardPresenter,
		api: ApiClient,
		rowsAdapter: MutableObjectAdapter<Row>? = null,
	): ListRow? {
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		val rowAdapter = when (browseRowDef.queryType) {
			QueryType.NextUp -> ItemRowAdapter(context, browseRowDef.nextUpQuery, preferParentThumb, cardPresenter, rowsAdapter)
			QueryType.LatestItems -> ItemRowAdapter(context, browseRowDef.latestItemsQuery, userPreferences[UserPreferences.seriesThumbnailsEnabled], cardPresenter, rowsAdapter)
			QueryType.Views -> ItemRowAdapter(context, GetUserViewsRequest, cardPresenter, rowsAdapter)
			QueryType.SimilarSeries -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarSeries, cardPresenter, rowsAdapter)
			QueryType.SimilarMovies -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarMovies, cardPresenter, rowsAdapter)
			QueryType.LiveTvChannel -> ItemRowAdapter(context, browseRowDef.tvChannelQuery, 40, cardPresenter, rowsAdapter)
			QueryType.LiveTvProgram -> ItemRowAdapter(context, browseRowDef.programQuery, cardPresenter, rowsAdapter)
			QueryType.LiveTvRecording -> ItemRowAdapter(context, browseRowDef.recordingQuery, browseRowDef.chunkSize, cardPresenter, rowsAdapter)
			QueryType.Resume -> ItemRowAdapter(context, browseRowDef.resumeQuery, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter)
			else -> ItemRowAdapter(context, browseRowDef.query, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter, browseRowDef.queryType)
		}

		rowAdapter.setReRetrieveTriggers(browseRowDef.changeTriggers)

		val hasData = runCatching {
			when (browseRowDef.queryType) {
				QueryType.Resume -> {
					val response = api.itemsApi.getResumeItems(browseRowDef.resumeQuery).content
					val filteredItems = response.items.filter { it.isRealAvailableMedia() }
					if (filteredItems.isEmpty()) false
					else {
						rowAdapter.setInitialItems(filteredItems) { item, _ ->
							BaseItemDtoBaseRowItem(item, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight)
						}
						true
					}
				}
				QueryType.NextUp -> {
					val response = api.tvShowsApi.getNextUp(browseRowDef.nextUpQuery).content
					val nextUpItems = response.items.filter { it.isRealAvailableMedia() }
					if (nextUpItems.isEmpty()) false
					else {
						rowAdapter.setInitialItems(nextUpItems) { item, _ ->
							BaseItemDtoBaseRowItem(item, preferParentThumb, browseRowDef.isStaticHeight)
						}
						true
					}
				}
				QueryType.LatestItems -> {
					val response = api.userLibraryApi.getLatestMedia(browseRowDef.latestItemsQuery).content
					val filteredItems = response.filter { it.isRealAvailableMedia() }
					if (filteredItems.isEmpty()) false
					else {
						rowAdapter.setInitialItems(filteredItems) { item, _ ->
							BaseItemDtoBaseRowItem(item, userPreferences[UserPreferences.seriesThumbnailsEnabled], browseRowDef.isStaticHeight, BaseRowItemSelectAction.ShowDetails, userPreferences[UserPreferences.seriesThumbnailsEnabled])
						}
						true
					}
				}
				QueryType.LiveTvRecording -> {
					val response = api.liveTvApi.getRecordings(browseRowDef.recordingQuery).content
					val filteredItems = response.items.filter { it.isRealAvailableMedia() }
					if (filteredItems.isEmpty()) false
					else {
						rowAdapter.setInitialItems(filteredItems) { item, _ ->
							BaseItemDtoBaseRowItem(item, false, false)
						}
						true
					}
				}
				QueryType.LiveTvProgram -> {
					val response = api.liveTvApi.getRecommendedPrograms(browseRowDef.programQuery).content
					if (response.items.isEmpty()) false
					else {
						rowAdapter.setInitialItems(response.items) { item, _ ->
							BaseItemDtoBaseRowItem(item, false, false)
						}
						true
					}
				}
				else -> {
					val query = browseRowDef.query
					if (query != null) {
						val response = api.itemsApi.getItems(query).content
						val filteredItems = response.items.filter { it.isRealAvailableMedia() }
						if (filteredItems.isEmpty()) false
						else {
							rowAdapter.setInitialItems(filteredItems) { item, _ ->
								BaseItemDtoBaseRowItem(item, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight)
							}
							true
						}
					} else false
				}
			}
		}.getOrElse { false }

		if (!hasData) return null

		val header = HeaderItem(browseRowDef.headerText)
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		return row
	}

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val header = HeaderItem(browseRowDef.headerText)
		val preferParentThumb = userPreferences[UserPreferences.seriesThumbnailsEnabled]

		val rowAdapter = when (browseRowDef.queryType) {
			QueryType.NextUp -> ItemRowAdapter(context, browseRowDef.nextUpQuery, preferParentThumb, cardPresenter, rowsAdapter)
			QueryType.LatestItems -> ItemRowAdapter(context, browseRowDef.latestItemsQuery, userPreferences[UserPreferences.seriesThumbnailsEnabled], cardPresenter, rowsAdapter)
			QueryType.Views -> ItemRowAdapter(context, GetUserViewsRequest, cardPresenter, rowsAdapter)
			QueryType.SimilarSeries -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarSeries, cardPresenter, rowsAdapter)
			QueryType.SimilarMovies -> ItemRowAdapter(context, browseRowDef.similarQuery, QueryType.SimilarMovies, cardPresenter, rowsAdapter)
			QueryType.LiveTvChannel -> ItemRowAdapter(context, browseRowDef.tvChannelQuery, 40, cardPresenter, rowsAdapter)
			QueryType.LiveTvProgram -> ItemRowAdapter(context, browseRowDef.programQuery, cardPresenter, rowsAdapter)
			QueryType.LiveTvRecording -> ItemRowAdapter(context, browseRowDef.recordingQuery, browseRowDef.chunkSize, cardPresenter, rowsAdapter)
			QueryType.Resume -> ItemRowAdapter(context, browseRowDef.resumeQuery, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter)
			else -> ItemRowAdapter(context, browseRowDef.query, browseRowDef.chunkSize, browseRowDef.preferParentThumb, browseRowDef.isStaticHeight, cardPresenter, rowsAdapter, browseRowDef.queryType)
		}

		rowAdapter.setReRetrieveTriggers(browseRowDef.changeTriggers)
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}
}
