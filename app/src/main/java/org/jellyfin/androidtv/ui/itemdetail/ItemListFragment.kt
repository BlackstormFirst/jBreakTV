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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.sdk.canPlay
import org.jellyfin.androidtv.util.sdk.isNonexistentOrVirtualMedia
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.android.ext.android.inject
import java.util.UUID

class ItemListFragment : Fragment() {
	private val backgroundService by inject<BackgroundService>()
	private val mediaManager by inject<MediaManager>()
	private val navigationRepository by inject<NavigationRepository>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private val playbackHelper by inject<PlaybackHelper>()
	private val itemMutationRepository by inject<ItemMutationRepository>()
	private val api by inject<ApiClient>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val itemIdString = arguments?.getString("ItemId")
		val itemId = Utils.uuidOrNull(itemIdString)

		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				JellyfinTheme {
					ItemListFragmentContent(itemId = itemId)
				}
			}
		}
	}

	@Composable
	private fun ItemListFragmentContent(itemId: UUID?) {
		val coroutineScope = rememberCoroutineScope()
		var baseItem by remember { mutableStateOf<BaseItemDto?>(null) }
		var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
		var currentlyPlayingItemId by remember { mutableStateOf<UUID?>(null) }
		val hasAudioQueue = remember { mediaManager.hasAudioQueueItems() }

		// Load item & playlist items
		LaunchedEffect(itemId) {
			if (itemId != null) {
				try {
					val loadedItem = withContext(Dispatchers.IO) {
						api.userLibraryApi.getItem(itemId).content
					}
					baseItem = loadedItem
					backgroundService.setBackground(loadedItem, fallbackToSplashscreen = true)

					val loadedItems = withContext(Dispatchers.IO) {
						when {
							loadedItem.type == BaseItemKind.PLAYLIST -> api.playlistsApi.getPlaylistItems(
								playlistId = loadedItem.id,
								limit = 150,
								fields = ItemRepository.itemFields,
							).content.items

							else -> api.itemsApi.getItems(
								parentId = loadedItem.id,
								includeItemTypes = setOf(BaseItemKind.AUDIO),
								recursive = true,
								sortBy = setOf(
									ItemSortBy.PARENT_INDEX_NUMBER,
									ItemSortBy.INDEX_NUMBER,
									ItemSortBy.SORT_NAME
								),
								limit = 200,
								fields = ItemRepository.itemFields,
							).content.items
						}
					}
					items = if (loadedItems.all { it.indexNumber == null }) {
						loadedItems
					} else {
						loadedItems.sortedWith(
							compareBy<BaseItemDto> { it.parentIndexNumber ?: 1 }
								.thenBy { it.indexNumber ?: Int.MAX_VALUE }
						)
					}
				} catch (t: Throwable) {
					timber.log.Timber.e(t, "Failed to load item list")
				}
			}
		}

		// Observe audio playback state
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
			baseItem = baseItem,
			items = items,
			currentlyPlayingItemId = currentlyPlayingItemId,
			hasAudioQueue = hasAudioQueue,
			onPlayAllClick = {
				if (items.isNotEmpty()) {
					playItems(items, 0, false)
				}
			},
			onShuffleAllClick = {
				if (baseItem != null) {
					playbackHelper.retrieveAndPlay(baseItem!!.id, true, requireContext())
				}
			},
			onAddToQueueClick = {
				if (items.isNotEmpty()) {
					mediaManager.addToAudioQueue(items)
				}
			},
			onInstantMixClick = {
				if (baseItem != null) {
					playbackHelper.playInstantMix(requireContext(), baseItem!!)
				}
			},
			onFavoriteClick = {
				baseItem?.let { current ->
					val isFav = current.userData?.isFavorite == true
					coroutineScope.launch(Dispatchers.IO) {
						val updatedUserData = itemMutationRepository.setFavorite(current.id, !isFav)
						baseItem = current.copy(userData = updatedUserData)
					}
				}
			},
			onOpenArtistClick = baseItem?.albumArtists?.firstOrNull()?.id?.let { artistId ->
				{ navigationRepository.navigate(Destinations.itemDetails(artistId)) }
			},
			onItemClick = { _, index ->
				playItems(items, index, false)
			},
			onHomeClick = {
				navigationRepository.reset(Destinations.home, clearHistory = true)
			},
			onItemUpdated = { index, updatedItem ->
				val currentList = items.toMutableList()
				if (index in currentList.indices) {
					currentList[index] = updatedItem
					items = currentList
				}
			}
		)
	}

	private fun playItems(items: List<BaseItemDto>, index: Int, shuffle: Boolean) {
		val playableItems = items.filter { it.canPlay() && !it.isNonexistentOrVirtualMedia() }
		if (playableItems.isEmpty()) return
		val targetItem = if (index in items.indices) items[index] else null
		val newIndex = if (targetItem != null) playableItems.indexOfFirst { it.id == targetItem.id }.coerceAtLeast(0) else 0
		val item = playableItems.getOrNull(newIndex)
		var pos = 0
		if (item?.userData != null) {
			pos = Math.toIntExact((item.userData?.playbackPositionTicks ?: 0L) / 10000L)
		}
		playbackLauncher.launch(requireContext(), playableItems, pos, false, newIndex, shuffle)
	}
}
