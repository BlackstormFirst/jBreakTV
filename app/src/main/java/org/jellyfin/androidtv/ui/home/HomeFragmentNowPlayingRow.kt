package org.jellyfin.androidtv.ui.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem

class HomeFragmentNowPlayingRow(
	private val coroutineScope: CoroutineScope,
	private val playbackManager: PlaybackManager,
	private val mediaManager: MediaManager,
) : HomeFragmentRow {
	private var onRowStateAdded: ((HomeRowState) -> Unit)? = null
	private var onRowStateRemoved: ((HomeRowState) -> Unit)? = null
	private var rowState: HomeRowState? = null
	private var title: String = ""

	override fun addRowStates(
		context: Context,
		cardPresenter: CardPresenter,
		onRowStateAdded: (HomeRowState) -> Unit,
		onRowStateRemoved: (HomeRowState) -> Unit
	) {
		this.title = context.getString(R.string.lbl_now_playing)
		this.onRowStateAdded = onRowStateAdded
		this.onRowStateRemoved = onRowStateRemoved

		coroutineScope.launch {
			updateState()
			playbackManager.queue.entry.onEach { updateState() }.launchIn(this)
			playbackManager.queue.entries.onEach { updateState() }.launchIn(this)
			playbackManager.state.playbackOrder.onEach { updateState() }.launchIn(this)
		}
	}

	fun update() {
		coroutineScope.launch {
			updateState()
		}
	}

	private suspend fun updateState() {
		if (mediaManager.hasAudioQueueItems()) {
			val currentItem = playbackManager.queue.entry.value?.let(::AudioQueueBaseRowItem)?.apply {
				playing = true
			}

			val upcomingItems = playbackManager.queue.peekNext(100)
				.mapIndexedNotNull { _, item -> item.takeIf { it.baseItem != null }?.let(::AudioQueueBaseRowItem) }

			val items = listOfNotNull(currentItem) + upcomingItems

			val newState = HomeRowState(
				id = "now_playing_row",
				title = title,
				items = items
			)
			rowState = newState
			onRowStateAdded?.invoke(newState)
		} else if (rowState != null) {
			onRowStateRemoved?.invoke(rowState!!)
			rowState = null
		}
	}
}
