package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.querying.GetSeriesTimersRequest
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.android.ext.android.inject

class BrowseViewFragment : EnhancedBrowseFragment() {
	private var isLiveTvLibrary = false
	private val userPreferences by inject<UserPreferences>()

	override fun setupQueries(rowLoader: RowLoader) {
		val folder = mFolder ?: return
		val type = folder.collectionType ?: CollectionType.UNKNOWN

		when (type) {
			CollectionType.MOVIES -> {
				itemType = BaseItemKind.MOVIE

				// Resume
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_continue_watching),
						BrowsingUtils.createResumeItemsRequest(folder.id, BaseItemKind.MOVIE),
						0,
						arrayOf(ChangeTriggerType.MoviePlayback)
					)
				)

				// Latest
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_latest),
						BrowsingUtils.createLatestMediaRequest(folder.id),
						arrayOf(ChangeTriggerType.MoviePlayback, ChangeTriggerType.LibraryUpdated)
					)
				)

				// Favorites
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_favorites),
						BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.MOVIE),
						60,
						arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate)
					)
				)

				// Collections
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_collections),
						BrowsingUtils.createCollectionsRequest(folder.id),
						60,
						arrayOf(ChangeTriggerType.LibraryUpdated)
					)
				)

				rowLoader.loadRows(mRows)
			}

			CollectionType.TVSHOWS -> {
				itemType = BaseItemKind.SERIES

				// Resume
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_continue_watching),
						BrowsingUtils.createResumeItemsRequest(folder.id, BaseItemKind.EPISODE),
						0,
						arrayOf(ChangeTriggerType.TvPlayback)
					)
				)

				// Next up
				val getNextUpRequest = BrowsingUtils.createGetNextUpRequest(folder.id, userPreferences)
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_next_up),
						getNextUpRequest,
						arrayOf(ChangeTriggerType.TvPlayback)
					)
				)

				// Latest content added
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_latest),
						BrowsingUtils.createLatestMediaRequest(folder.id, BaseItemKind.EPISODE, true),
						arrayOf(ChangeTriggerType.LibraryUpdated)
					)
				)

				// Favorites
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_favorites),
						BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.SERIES),
						60,
						arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate)
					)
				)

				rowLoader.loadRows(mRows)
			}

			CollectionType.MUSIC -> {
				itemType = BaseItemKind.MUSIC_ALBUM

				// Latest
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_latest),
						BrowsingUtils.createLatestMediaRequest(folder.id, BaseItemKind.AUDIO, true),
						arrayOf(ChangeTriggerType.LibraryUpdated)
					)
				)

				// Last Played
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_last_played),
						BrowsingUtils.createLastPlayedRequest(folder.id),
						0,
						false,
						true,
						arrayOf(ChangeTriggerType.MusicPlayback, ChangeTriggerType.LibraryUpdated)
					)
				)

				// Favorites
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_favorites),
						BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.MUSIC_ALBUM),
						60,
						false,
						true,
						arrayOf(ChangeTriggerType.LibraryUpdated, ChangeTriggerType.FavoriteUpdate)
					)
				)

				// AudioPlaylists
				mRows.add(
					BrowseRowDef(
						getString(R.string.lbl_playlists),
						BrowsingUtils.createPlaylistsRequest(),
						60,
						false,
						true,
						arrayOf(ChangeTriggerType.LibraryUpdated),
						QueryType.AudioPlaylists
					)
				)

				rowLoader.loadRows(mRows)
			}

			CollectionType.LIVETV -> {
				isLiveTvLibrary = true
				showViews = true

				// On now
				mRows.add(BrowseRowDef(getString(R.string.lbl_on_now), BrowsingUtils.createLiveTVOnNowRequest()))

				// Upcoming
				mRows.add(BrowseRowDef(getString(R.string.lbl_coming_up), BrowsingUtils.createLiveTVUpcomingRequest()))

				// Fav Channels
				mRows.add(BrowseRowDef(getString(R.string.lbl_favorite_channels), BrowsingUtils.createLiveTVChannelsRequest(true)))

				// Other Channels
				mRows.add(BrowseRowDef(getString(R.string.lbl_other_channels), BrowsingUtils.createLiveTVChannelsRequest(false)))

				rowLoader.loadRows(mRows)
			}

			else -> {
				val isRecordingsView = arguments?.getBoolean(Extras.IsLiveTvSeriesRecordings, false) ?: false
				if (isRecordingsView) {
					mRows.add(BrowseRowDef(getString(R.string.lbl_series_recordings), GetSeriesTimersRequest))
					rowLoader.loadRows(mRows)
				}
			}
		}
	}
}
