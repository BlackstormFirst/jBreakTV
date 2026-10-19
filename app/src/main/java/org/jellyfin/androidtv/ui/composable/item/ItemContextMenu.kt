package org.jellyfin.androidtv.ui.composable.item

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.androidtv.util.playFirstUnwatchedItem
import org.jellyfin.androidtv.util.sdk.canPlay
import org.jellyfin.androidtv.util.toggleFavorite
import org.jellyfin.androidtv.util.togglePlayed
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.compose.koinInject

@Composable
fun ItemContextMenu(
	item: BaseRowItem?,
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!expanded || item == null) return

	val context = LocalContext.current
	val activity = context.getActivity() as? FragmentActivity
	val lifecycleOwner = LocalLifecycleOwner.current

	val itemMutationRepository = koinInject<ItemMutationRepository>()
	val customMessageRepository = koinInject<CustomMessageRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val mediaManager = koinInject<MediaManager>()

	val baseItem = item.baseItem ?: return
	val userData = baseItem.userData

	val itemCanPlay = baseItem.canPlay()
	val isFolder = baseItem.isFolder == true
	val isMusic = baseItem.type == BaseItemKind.MUSIC_ALBUM ||
		baseItem.type == BaseItemKind.MUSIC_ARTIST ||
		baseItem.type == BaseItemKind.AUDIO ||
		(baseItem.type == BaseItemKind.PLAYLIST && MediaType.AUDIO == baseItem.mediaType)

	val isPlayed = userData?.played == true
	val isFavorite = userData?.isFavorite == true

	var hasReleasedInitialKey by remember(item) { mutableStateOf(false) }

	val keyModifier = Modifier.onPreviewKeyEvent { keyEvent ->
		val nativeEvent = keyEvent.nativeKeyEvent
		val keyCode = nativeEvent.keyCode
		val isEnterKey = keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
			keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
			keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER

		if (!hasReleasedInitialKey && isEnterKey) {
			if (nativeEvent.action == android.view.KeyEvent.ACTION_UP) {
				hasReleasedInitialKey = true
			}
			true
		} else {
			false
		}
	}

	fun handleMenuAction(action: () -> Unit) {
		if (hasReleasedInitialKey) {
			action()
		}
	}

	Popover(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		alignment = Alignment.Center,
		modifier = keyModifier.then(modifier),
	) {
		PopoverMenu {
			// 1. Play / Play All / Play First Unwatched
			if (itemCanPlay) {
				if (isFolder &&
					baseItem.type != BaseItemKind.MUSIC_ALBUM &&
					baseItem.type != BaseItemKind.PLAYLIST &&
					baseItem.type != BaseItemKind.MUSIC_ARTIST &&
					userData?.unplayedItemCount != null &&
					userData.unplayedItemCount!! > 0
				) {
					PopoverMenuItem(onClick = {
						handleMenuAction {
							activity?.playFirstUnwatchedItem(baseItem.id)
						}
					}) {
						Text(stringResource(R.string.lbl_play_first_unwatched))
					}
				}

				PopoverMenuItem(onClick = {
					handleMenuAction {
						if (activity != null) {
							playbackHelper.retrieveAndPlay(baseItem.id, false, activity)
						}
					}
				}) {
					Text(stringResource(if (isFolder) R.string.lbl_play_all else R.string.lbl_play))
				}

				if (isFolder) {
					PopoverMenuItem(onClick = {
						handleMenuAction {
							if (activity != null) {
								playbackHelper.retrieveAndPlay(baseItem.id, true, activity)
							}
						}
					}) {
						Text(stringResource(R.string.lbl_shuffle_all))
					}
				}
			}

			// 2. Music Actions
			if (isMusic) {
				PopoverMenuItem(onClick = {
					handleMenuAction {
						if (activity != null) {
							playbackHelper.getItemsToPlay(activity, baseItem, false, false, object : Response<List<BaseItemDto>>(activity.lifecycle) {
								override fun onResponse(response: List<BaseItemDto>) {
									mediaManager.addToAudioQueue(response)
								}

								override fun onError(exception: Exception) {
									Toast.makeText(activity, R.string.msg_cannot_play_time, Toast.LENGTH_SHORT).show()
								}
							})
						}
					}
				}) {
					Text(stringResource(R.string.lbl_add_to_queue))
				}

				if (baseItem.type != BaseItemKind.PLAYLIST) {
					PopoverMenuItem(onClick = {
						handleMenuAction {
							if (activity != null) {
								playbackHelper.playInstantMix(activity, baseItem)
							}
						}
					}) {
						Text(stringResource(R.string.lbl_instant_mix))
					}
				}
			} else {
				// 3. Mark as Played / Unplayed
				PopoverMenuItem(onClick = {
					handleMenuAction {
						togglePlayed(lifecycleOwner, itemMutationRepository, customMessageRepository, baseItem.id, !isPlayed)
					}
				}) {
					Text(stringResource(if (isPlayed) R.string.lbl_mark_unplayed else R.string.lbl_mark_played))
				}
			}

			// 4. Toggle Favorite
			PopoverMenuItem(onClick = {
				handleMenuAction {
					toggleFavorite(lifecycleOwner, itemMutationRepository, customMessageRepository, baseItem.id, !isFavorite)
				}
			}) {
				Text(stringResource(if (isFavorite) R.string.lbl_remove_favorite else R.string.lbl_add_favorite))
			}
		}
	}
}
