package org.jellyfin.androidtv.util

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

fun FragmentActivity.playFirstUnwatchedItem(parentId: UUID) {
	val api by inject<ApiClient>()
	val playbackHelper by inject<PlaybackHelper>()

	lifecycleScope.launch(Dispatchers.IO) {
		runCatching {
			api.itemsApi.getItems(
				parentId = parentId,
				recursive = true,
				isMissing = false,
				sortBy = setOf(ItemSortBy.SORT_NAME),
				sortOrder = setOf(SortOrder.ASCENDING),
				limit = 1,
				excludeItemTypes = setOf(),
				filters = setOf(ItemFilter.IS_UNPLAYED),
			)
		}.fold(
			onSuccess = { response ->
				val item = response.content.items.firstOrNull()
				withContext(Dispatchers.Main) {
					if (item == null) {
						Toast.makeText(
							this@playFirstUnwatchedItem,
							R.string.msg_no_items,
							Toast.LENGTH_LONG
						).show()
					} else {
						playbackHelper.retrieveAndPlay(item.id, false, this@playFirstUnwatchedItem)
					}
				}
			},
			onFailure = { error ->
				Timber.e(error, "Error trying to play first unwatched")
				Toast.makeText(
					this@playFirstUnwatchedItem,
					R.string.msg_video_playback_error,
					Toast.LENGTH_LONG
				).show()
			},
		)
	}
}

fun togglePlayed(
	lifecycleOwner: LifecycleOwner,
	itemMutationRepository: ItemMutationRepository,
	customMessageRepository: CustomMessageRepository,
	itemId: UUID,
	played: Boolean,
) {
	lifecycleOwner.lifecycleScope.launch {
		runCatching {
			itemMutationRepository.setPlayed(itemId, played)
		}.onSuccess {
			customMessageRepository.pushMessage(CustomMessage.RefreshCurrentItem)
		}.onFailure { e ->
			Timber.e(e, "Failed to toggle played state for item $itemId")
		}
	}
}

fun toggleFavorite(
	lifecycleOwner: LifecycleOwner,
	itemMutationRepository: ItemMutationRepository,
	customMessageRepository: CustomMessageRepository,
	itemId: UUID,
	favorite: Boolean,
) {
	lifecycleOwner.lifecycleScope.launch {
		runCatching {
			itemMutationRepository.setFavorite(itemId, favorite)
		}.onSuccess {
			customMessageRepository.pushMessage(CustomMessage.RefreshCurrentItem)
		}.onFailure { e ->
			Timber.e(e, "Failed to toggle favorite state for item $itemId")
		}
	}
}

