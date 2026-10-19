package org.jellyfin.androidtv.ui.itemdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.koin.android.ext.android.inject
import java.util.UUID

class MusicFavoritesListFragment : Fragment() {
	private val mediaManager by inject<MediaManager>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private val api by inject<ApiClient>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val parentIdString = arguments?.getString("ParentId")
		val parentId = Utils.uuidOrNull(parentIdString)

		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				JellyfinTheme {
					MusicFavoritesFragmentContent(parentId = parentId)
				}
			}
		}
	}

	@Composable
	private fun MusicFavoritesFragmentContent(parentId: UUID?) {
		var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
		var currentlyPlayingItemId by remember { mutableStateOf<UUID?>(null) }
		val hasAudioQueue = remember { mediaManager.hasAudioQueueItems() }

		val fakeHeaderItem = remember {
			BaseItemDto(
				id = parentId ?: UUID.randomUUID(),
				name = getString(R.string.lbl_favorites),
				overview = getString(R.string.desc_automatic_fav_songs),
				isFolder = true,
				type = BaseItemKind.MUSIC_ALBUM
			)
		}

		LaunchedEffect(parentId) {
			try {
				val loadedItems = withContext(Dispatchers.IO) {
					api.itemsApi.getItems(
						parentId = parentId,
						includeItemTypes = setOf(BaseItemKind.AUDIO),
						recursive = true,
						isMissing = false,
						isUnaired = false,
						excludeLocationTypes = setOf(LocationType.VIRTUAL),
						filters = setOf(ItemFilter.IS_FAVORITE_OR_LIKES),
						sortBy = setOf(ItemSortBy.RANDOM),
						limit = 100,
						fields = ItemRepository.itemFields,
					).content.items
				}
				items = loadedItems
			} catch (t: Throwable) {
				timber.log.Timber.e(t, "Failed to load music favorites")
			}
		}

		DisposableEffect(Unit) {
			val listener = object : AudioEventListener {
				override fun onPlaybackStateChange(newState: PlaybackController.PlaybackState, currentItem: BaseItemDto?) {
					if (newState == PlaybackController.PlaybackState.PLAYING && currentItem != null) {
						currentlyPlayingItemId = currentItem.id
					} else {
						currentlyPlayingItemId = null
					}
				}
			}
			mediaManager.addAudioEventListener(listener)
			if (mediaManager.isPlayingAudio) {
				currentlyPlayingItemId = mediaManager.currentAudioItem?.id
			}
			onDispose {
				mediaManager.removeAudioEventListener(listener)
			}
		}

		ItemListScreen(
			baseItem = fakeHeaderItem,
			items = items,
			currentlyPlayingItemId = currentlyPlayingItemId,
			hasAudioQueue = hasAudioQueue,
			onPlayAllClick = {
				if (items.isNotEmpty()) {
					playItems(items, 0, false)
				}
			},
			onShuffleAllClick = {
				if (items.isNotEmpty()) {
					playItems(items, 0, true)
				}
			},
			onAddToQueueClick = {
				if (items.isNotEmpty()) {
					mediaManager.addToAudioQueue(items)
				}
			},
			onInstantMixClick = {},
			onFavoriteClick = {},
			onOpenArtistClick = null,
			onItemClick = { _, index ->
				playItems(items, index, false)
			}
		)
	}

	private fun playItems(items: List<BaseItemDto>, index: Int, shuffle: Boolean) {
		playbackLauncher.launch(requireContext(), items, 0, false, index, shuffle)
	}
}
