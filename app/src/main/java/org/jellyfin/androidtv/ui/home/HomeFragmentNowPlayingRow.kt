package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import kotlinx.coroutines.CoroutineScope
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.AudioQueueBaseRowAdapter
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.playback.core.PlaybackManager

class HomeFragmentNowPlayingRow(
	private val coroutineScope: CoroutineScope,
	private val playbackManager: PlaybackManager,
	private val mediaManager: MediaManager,
) : HomeFragmentRow {
	var row: ListRow? = null
	private var onRowAdded: ((ListRow) -> Unit)? = null
	private var onRowRemoved: ((ListRow) -> Unit)? = null

	override fun addRows(
		context: Context,
		cardPresenter: CardPresenter,
		onRowAdded: (ListRow) -> Unit,
		onRowRemoved: (ListRow) -> Unit
	) {
		this.onRowAdded = onRowAdded
		this.onRowRemoved = onRowRemoved
		update(context)
	}

	fun update(context: Context) {
		if (mediaManager.hasAudioQueueItems()) {
			// Ensure row exists
			if (row == null) {
				row = ListRow(
					HeaderItem(context.getString(R.string.lbl_now_playing)),
					AudioQueueBaseRowAdapter(playbackManager, coroutineScope)
				)
				onRowAdded?.invoke(row!!)
			}
		} else if (row != null) {
			onRowRemoved?.invoke(row!!)
			row = null
		}
	}
}
