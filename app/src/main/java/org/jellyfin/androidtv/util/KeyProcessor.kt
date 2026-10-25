package org.jellyfin.androidtv.util

import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.sdk.canPlay
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.UUID

class KeyProcessor {

    private val mediaManager: MediaManager by inject(MediaManager::class.java)
    private val navigationRepository: NavigationRepository by inject(NavigationRepository::class.java)
    private val itemMutationRepository: ItemMutationRepository by inject(ItemMutationRepository::class.java)
    private val customMessageRepository: CustomMessageRepository by inject(CustomMessageRepository::class.java)
    private val playbackHelper: PlaybackHelper by inject(PlaybackHelper::class.java)

    fun handleKey(key: Int, rowItem: BaseRowItem?, activity: FragmentActivity): Boolean {
        if (rowItem == null) return false

        when (key) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (mediaManager.isPlayingAudio && (rowItem.baseRowType != BaseRowType.BaseItem || rowItem.baseItem?.type != BaseItemKind.PHOTO)) {
                    // Rewrite uses media sessions which the system automatically manipulates on key presses
                    return false
                }

                when (rowItem.baseRowType) {
                    BaseRowType.BaseItem -> {
                        val item = rowItem.baseItem ?: return false
                        if (!item.canPlay()) return false
                        when (item.type) {
                            BaseItemKind.AUDIO -> {
                                if (rowItem is AudioQueueBaseRowItem) {
                                    createItemMenu(rowItem, item.userData, activity)
                                    return true
                                }
                                // fall through...
                                playbackHelper.retrieveAndPlay(item.id, false, activity)
                                return true
                            }
                            BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL, BaseItemKind.VIDEO, BaseItemKind.PROGRAM, BaseItemKind.TRAILER -> {
                                // retrieve full item and play
                                playbackHelper.retrieveAndPlay(item.id, false, activity)
                                return true
                            }
                            BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.BOX_SET -> {
                                createPlayMenu(rowItem, false, activity)
                                return true
                            }
                            BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST -> {
                                createPlayMenu(rowItem, true, activity)
                                return true
                            }
                            BaseItemKind.PLAYLIST -> {
                                createPlayMenu(rowItem, MediaType.AUDIO == item.mediaType, activity)
                                return true
                            }
                            BaseItemKind.PHOTO -> {
                                navigationRepository.navigate(
                                    Destinations.photoPlayer(
                                        item.id,
                                        true,
                                        ItemSortBy.SORT_NAME,
                                        SortOrder.ASCENDING
                                    )
                                )
                                return true
                            }
                            else -> {}
                        }
                    }
                    BaseRowType.Person -> {}
                    BaseRowType.Chapter -> {}
                    BaseRowType.LiveTvChannel, BaseRowType.LiveTvRecording -> {
                        // retrieve full item and play
                        val itemId = rowItem.itemId
                        if (itemId != null) {
                            playbackHelper.retrieveAndPlay(itemId, false, activity)
                        }
                        return true
                    }
                    BaseRowType.LiveTvProgram -> {
                        // retrieve channel this program belongs to and play
                        val channelId = rowItem.baseItem?.channelId
                        if (channelId != null) {
                            playbackHelper.retrieveAndPlay(channelId, false, activity)
                        }
                        return true
                    }
                    BaseRowType.GridButton -> {}
                    BaseRowType.SeriesTimer -> {}
                }

                // Rewrite uses media sessions which the system automatically manipulates on key presses
                if (mediaManager.hasAudioQueueItems()) return false
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                Timber.d("Menu for: %s", rowItem.getFullName(activity))

                // Create a contextual menu based on item
                when (rowItem.baseRowType) {
                    BaseRowType.BaseItem -> {
                        val item = rowItem.baseItem ?: return false
                        when (item.type) {
                            BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL, BaseItemKind.VIDEO, BaseItemKind.PROGRAM, BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.BOX_SET, BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST, BaseItemKind.PLAYLIST, BaseItemKind.AUDIO, BaseItemKind.TRAILER -> {
                                // generate a standard item menu
                                createItemMenu(rowItem, item.userData, activity)
                            }
                            else -> {}
                        }
                    }
                    BaseRowType.Person -> {}
                    BaseRowType.Chapter -> {}
                    BaseRowType.LiveTvChannel -> {}
                    BaseRowType.LiveTvRecording -> {}
                    BaseRowType.GridButton -> {}
                    BaseRowType.LiveTvProgram -> {}
                    BaseRowType.SeriesTimer -> {}
                }
                return true
            }
        }
        return false
    }

    @JvmOverloads
    fun createItemMenu(
        rowItem: BaseRowItem?,
        userData: UserItemDataDto?,
        activity: FragmentActivity?,
        anchorView: View? = null
    ): PopupMenu? {
        if (activity == null || activity.isFinishing || activity.isDestroyed || rowItem == null) return null
        val item = rowItem.baseItem ?: return null

        val targetView = anchorView ?: (activity.currentFocus ?: activity.window.decorView)
        val menu = PopupMenu(activity, targetView, Gravity.END)
        var order = 0

        if (rowItem is AudioQueueBaseRowItem) {
            if (rowItem.baseItem != mediaManager.currentAudioItem) {
                menu.menu.add(0, MENU_ADVANCE_QUEUE, order++, R.string.lbl_play_from_here)
            }
            menu.menu.add(0, MENU_GOTO_NOW_PLAYING, order++, R.string.lbl_goto_now_playing)

            if (mediaManager.currentAudioQueueSize > 1) {
                menu.menu.add(0, MENU_TOGGLE_SHUFFLE, order++, R.string.lbl_shuffle_queue)
            }

            if (userData != null) {
                if (userData.isFavorite == true) {
                    menu.menu.add(0, MENU_UNMARK_FAVORITE, order++, activity.getString(R.string.lbl_remove_favorite))
                } else {
                    menu.menu.add(0, MENU_MARK_FAVORITE, order++, activity.getString(R.string.lbl_add_favorite))
                }
            }

            menu.menu.add(0, MENU_REMOVE_FROM_QUEUE, order++, R.string.lbl_remove_from_queue)

            if (mediaManager.hasAudioQueueItems()) {
                menu.menu.add(0, MENU_CLEAR_QUEUE, order++, R.string.lbl_clear_queue)
            }
        } else {
            val isFolder = item.isFolder == true
            if (item.canPlay()) {
                if (isFolder &&
                    item.type != BaseItemKind.MUSIC_ALBUM &&
                    item.type != BaseItemKind.PLAYLIST &&
                    item.type != BaseItemKind.MUSIC_ARTIST &&
                    userData?.unplayedItemCount != null &&
                    userData.unplayedItemCount!! > 0
                ) {
                    menu.menu.add(0, MENU_PLAY_FIRST_UNWATCHED, order++, R.string.lbl_play_first_unwatched)
                }
                menu.menu.add(0, MENU_PLAY, order++, if (isFolder) R.string.lbl_play_all else R.string.lbl_play)
                if (isFolder) {
                    menu.menu.add(0, MENU_PLAY_SHUFFLE, order++, R.string.lbl_shuffle_all)
                }
            }

            val isMusic = item.type == BaseItemKind.MUSIC_ALBUM ||
                    item.type == BaseItemKind.MUSIC_ARTIST ||
                    item.type == BaseItemKind.AUDIO ||
                    (item.type == BaseItemKind.PLAYLIST && MediaType.AUDIO == item.mediaType)

            if (isMusic) {
                menu.menu.add(0, MENU_ADD_QUEUE, order++, R.string.lbl_add_to_queue)
            }

            val isPlayed = userData?.played == true
            val isFavorite = userData?.isFavorite == true

            if (isMusic) {
                if (item.type != BaseItemKind.PLAYLIST) {
                    menu.menu.add(0, MENU_INSTANT_MIX, order++, R.string.lbl_instant_mix)
                }
            } else {
                if (isPlayed) {
                    menu.menu.add(0, MENU_UNMARK_PLAYED, order++, activity.getString(R.string.lbl_mark_unplayed))
                } else {
                    menu.menu.add(0, MENU_MARK_PLAYED, order++, activity.getString(R.string.lbl_mark_played))
                }
            }

            if (isFavorite) {
                menu.menu.add(0, MENU_UNMARK_FAVORITE, order++, activity.getString(R.string.lbl_remove_favorite))
            } else {
                menu.menu.add(0, MENU_MARK_FAVORITE, order++, activity.getString(R.string.lbl_add_favorite))
            }
        }

        if (!menu.menu.hasVisibleItems()) {
            return null
        }

        menu.setOnMenuItemClickListener(KeyProcessorItemMenuClickListener(activity, rowItem))
        menu.show()
        return menu
    }

    private fun createPlayMenu(rowItem: BaseRowItem?, isMusic: Boolean, activity: FragmentActivity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed || rowItem?.baseItem == null) return
        val anchorView = activity.currentFocus ?: activity.window.decorView
        val menu = PopupMenu(activity, anchorView, Gravity.END)
        var order = 0
        if (!isMusic && rowItem.baseItem.type != BaseItemKind.PLAYLIST) {
            menu.menu.add(0, MENU_PLAY_FIRST_UNWATCHED, order++, R.string.lbl_play_first_unwatched)
        }
        menu.menu.add(0, MENU_PLAY, order++, R.string.lbl_play_all)
        menu.menu.add(0, MENU_PLAY_SHUFFLE, order++, R.string.lbl_shuffle_all)
        if (isMusic) {
            menu.menu.add(0, MENU_ADD_QUEUE, order, R.string.lbl_add_to_queue)
        }

        if (!menu.menu.hasVisibleItems()) return

        menu.setOnMenuItemClickListener(KeyProcessorItemMenuClickListener(activity, rowItem))
        menu.show()
    }

    private inner class KeyProcessorItemMenuClickListener(
        private val activity: FragmentActivity,
        private val rowItem: BaseRowItem
    ) : PopupMenu.OnMenuItemClickListener {
        private val item: BaseItemDto = rowItem.baseItem!!

        override fun onMenuItemClick(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                MENU_PLAY -> {
                    playbackHelper.retrieveAndPlay(item.id, false, activity)
                    return true
                }
                MENU_PLAY_SHUFFLE -> {
                    playbackHelper.retrieveAndPlay(item.id, true, activity)
                    return true
                }
                MENU_ADD_QUEUE -> {
                    playbackHelper.getItemsToPlay(
                        context = activity,
                        mainItem = item,
                        allowIntros = false,
                        shuffle = false,
                        onSuccess = { response -> mediaManager.addToAudioQueue(response) },
                        onError = { Toast.makeText(activity, R.string.msg_cannot_play_time, Toast.LENGTH_LONG).show() }
                    )
                    return true
                }
                MENU_PLAY_FIRST_UNWATCHED -> {
                    activity.playFirstUnwatchedItem(item.id)
                    return true
                }
                MENU_MARK_FAVORITE -> {
                    toggleFavorite(activity, item.id, true)
                    return true
                }
                MENU_UNMARK_FAVORITE -> {
                    toggleFavorite(activity, item.id, false)
                    return true
                }
                MENU_MARK_PLAYED -> {
                    togglePlayed(activity, item.id, true)
                    return true
                }
                MENU_UNMARK_PLAYED -> {
                    togglePlayed(activity, item.id, false)
                    return true
                }
                MENU_GOTO_NOW_PLAYING -> {
                    navigationRepository.navigate(Destinations.nowPlaying)
                    return true
                }
                MENU_TOGGLE_SHUFFLE -> {
                    mediaManager.shuffleAudioQueue()
                    return true
                }
                MENU_REMOVE_FROM_QUEUE -> {
                    if (rowItem is AudioQueueBaseRowItem) {
                        mediaManager.removeFromAudioQueue(rowItem.queueEntry)
                    }
                    return true
                }
                MENU_ADVANCE_QUEUE -> {
                    if (rowItem is AudioQueueBaseRowItem) {
                        mediaManager.playFrom(rowItem.queueEntry)
                    }
                    return true
                }
                MENU_CLEAR_QUEUE -> {
                    mediaManager.clearAudioQueue()
                    return true
                }
                MENU_INSTANT_MIX -> {
                    playbackHelper.playInstantMix(activity, item)
                    return true
                }
            }

            return false
        }
    }

    private fun togglePlayed(lifecycleOwner: LifecycleOwner, itemId: UUID, played: Boolean) {
        togglePlayed(
            lifecycleOwner,
            itemMutationRepository,
            customMessageRepository,
            itemId,
            played
        )
    }

    private fun toggleFavorite(lifecycleOwner: LifecycleOwner, itemId: UUID, favorite: Boolean) {
        toggleFavorite(
            lifecycleOwner,
            itemMutationRepository,
            customMessageRepository,
            itemId,
            favorite
        )
    }

    companion object {
        const val MENU_MARK_FAVORITE = 0
        const val MENU_UNMARK_FAVORITE = 1
        const val MENU_MARK_PLAYED = 2
        const val MENU_UNMARK_PLAYED = 3
        const val MENU_PLAY = 4
        const val MENU_PLAY_SHUFFLE = 5
        const val MENU_PLAY_FIRST_UNWATCHED = 6
        const val MENU_ADD_QUEUE = 7
        const val MENU_ADVANCE_QUEUE = 8
        const val MENU_REMOVE_FROM_QUEUE = 9
        const val MENU_GOTO_NOW_PLAYING = 10
        const val MENU_INSTANT_MIX = 11
        const val MENU_CLEAR_QUEUE = 12
        const val MENU_TOGGLE_SHUFFLE = 13
    }
}
