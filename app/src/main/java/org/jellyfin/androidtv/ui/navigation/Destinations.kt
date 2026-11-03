package org.jellyfin.androidtv.ui.navigation

import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.ui.browsing.BrowseGridFragment
import org.jellyfin.androidtv.ui.browsing.BrowseRecordingsFragment
import org.jellyfin.androidtv.ui.browsing.BrowseScheduleFragment
import org.jellyfin.androidtv.ui.browsing.BrowseViewFragment
import org.jellyfin.androidtv.ui.browsing.ByGenreFragment
import org.jellyfin.androidtv.ui.browsing.ByLetterFragment
import org.jellyfin.androidtv.ui.browsing.CollectionFragment
import org.jellyfin.androidtv.ui.browsing.GenericFolderFragment
import org.jellyfin.androidtv.ui.browsing.SuggestedMoviesFragment
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment
import org.jellyfin.androidtv.ui.itemdetail.ItemListFragment
import org.jellyfin.androidtv.ui.itemdetail.MusicFavoritesListFragment
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment
import org.jellyfin.androidtv.ui.playback.AudioNowPlayingFragment
import org.jellyfin.androidtv.ui.playback.nextup.NextUpFragment
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingFragment
import org.jellyfin.androidtv.ui.player.photo.PhotoPlayerFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment
import org.jellyfin.androidtv.ui.search.SearchFragment
import org.jellyfin.androidtv.util.usbdevices.ARG_CURRENT_DIR
import org.jellyfin.androidtv.util.usbdevices.ARG_VOLUME_NAME
import org.jellyfin.androidtv.util.usbdevices.UsbFileExplorerFragment
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

@Suppress("TooManyFunctions")
object Destinations {
	// General
	val home = fragmentDestination<HomeFragment>()
	fun search(query: String? = null) = fragmentDestination<SearchFragment> {
		putString(SearchFragment.EXTRA_QUERY, query)
	}
	val usbVolumeSelector = fragmentDestination<org.jellyfin.androidtv.util.usbdevices.UsbVolumeSelectorFragment>()
	fun usbExplorer(currentDir: String, rootPath: String, volumeName: String) = fragmentDestination<UsbFileExplorerFragment> {
		putString(ARG_CURRENT_DIR, currentDir)
		putString(org.jellyfin.androidtv.util.usbdevices.ARG_ROOT_PATH, rootPath)
		putString(ARG_VOLUME_NAME, volumeName)
	}

	// Browsing
	fun libraryBrowser(item: UUID, includeType: String? = null) =
		fragmentDestination<BrowseGridFragment> {
			putString(Extras.Folder, item.toString())
			putString(Extras.IncludeType, includeType)
		}

	fun librarySmartScreen(item: UUID) = fragmentDestination<BrowseViewFragment> {
		putString(Extras.Folder, item.toString())
	}

	fun collectionBrowser(item: UUID) = fragmentDestination<CollectionFragment> {
		putString(Extras.Folder, item.toString())
	}

	fun folderBrowser(item: UUID) = fragmentDestination<GenericFolderFragment> {
		putString(Extras.Folder, item.toString())
	}

	fun libraryByGenres(item: UUID, includeType: String) =
		fragmentDestination<ByGenreFragment> {
			putString(Extras.Folder, item.toString())
			putString(Extras.IncludeType, includeType)
		}

	fun libraryByLetter(item: UUID, includeType: String) =
		fragmentDestination<ByLetterFragment> {
			putString(Extras.Folder, item.toString())
			putString(Extras.IncludeType, includeType)
		}

	fun librarySuggestions(item: UUID) =
		fragmentDestination<SuggestedMoviesFragment> {
			putString(Extras.Folder, item.toString())
		}

	// Item details
	fun itemDetails(item: UUID) = fragmentDestination<FullDetailsFragment> {
		putString("ItemId", item.toString())
	}

	fun channelDetails(item: UUID) = fragmentDestination<FullDetailsFragment> {
		putString("ItemId", item.toString())
	}

	fun seriesTimerDetails(item: UUID) = fragmentDestination<FullDetailsFragment> {
		putString("ItemId", item.toString())
	}

	fun itemList(item: UUID) = fragmentDestination<ItemListFragment> {
		putString("ItemId", item.toString())
	}

	fun musicFavorites(parent: UUID) = fragmentDestination<MusicFavoritesListFragment> {
		putString("ParentId", parent.toString())
	}

	// Live TV
	val liveTvGuide = fragmentDestination<LiveTvGuideFragment>()
	val liveTvSchedule = fragmentDestination<BrowseScheduleFragment>()
	val liveTvRecordings = fragmentDestination<BrowseRecordingsFragment>()
	val liveTvSeriesRecordings = fragmentDestination<BrowseViewFragment> {
		putBoolean(Extras.IsLiveTvSeriesRecordings, true)
	}

	// Playback
	val nowPlaying = fragmentDestination<AudioNowPlayingFragment>()

	fun photoPlayer(
		item: UUID,
		autoPlay: Boolean,
		albumSortBy: ItemSortBy?,
		albumSortOrder: SortOrder?,
	) = fragmentDestination<PhotoPlayerFragment> {
		putString(PhotoPlayerFragment.ARGUMENT_ITEM_ID, item.toString())
		putString(PhotoPlayerFragment.ARGUMENT_ALBUM_SORT_BY, albumSortBy?.serialName)
		putString(PhotoPlayerFragment.ARGUMENT_ALBUM_SORT_ORDER, albumSortOrder?.serialName)
		putBoolean(PhotoPlayerFragment.ARGUMENT_AUTO_PLAY, autoPlay)
	}

	fun videoPlayer(position: Int?) = fragmentDestination<VideoPlayerFragment> {
		if (position != null) putInt(VideoPlayerFragment.EXTRA_POSITION, position)
	}

	fun videoPlayerNew(position: Int?) = videoPlayer(position)

	fun nextUp(item: UUID) = fragmentDestination<NextUpFragment> {
		putString(NextUpFragment.ARGUMENT_ITEM_ID, item.toString())
	}

	fun stillWatching(item: UUID) = fragmentDestination<StillWatchingFragment> {
		putString(NextUpFragment.ARGUMENT_ITEM_ID, item.toString())
	}
}
