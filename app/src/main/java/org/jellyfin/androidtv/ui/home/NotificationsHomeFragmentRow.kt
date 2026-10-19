package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.notification.AppNotificationPresenter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

class NotificationsHomeFragmentRow(
	coroutineScope: CoroutineScope,
	private val notificationsRepository: NotificationsRepository,
) : HomeFragmentRow, OnItemViewClickedListener {
	private val announcementAdapter by lazy { MutableObjectAdapter<AppNotification>(AppNotificationPresenter()) }
	val listRow by lazy { ListRow(null, announcementAdapter) }
	private var rowAdded = false
	private var onRowAdded: ((ListRow) -> Unit)? = null
	private var onRowRemoved: ((ListRow) -> Unit)? = null

	init {
		notificationsRepository.notifications.onEach { notifications ->
			announcementAdapter.replaceAll(notifications)
			update(notifications.isEmpty())
		}.launchIn(coroutineScope)
	}

	private fun update(empty: Boolean) {
		if (onRowAdded == null) return

		if (empty && rowAdded) {
			onRowRemoved?.invoke(listRow)
			rowAdded = false
		}

		if (!empty && !rowAdded) {
			onRowAdded?.invoke(listRow)
			rowAdded = true
		}
	}

	override fun addRows(context: Context, cardPresenter: CardPresenter, onRowAdded: (ListRow) -> Unit, onRowRemoved: (ListRow) -> Unit) {
		this.onRowAdded = onRowAdded
		this.onRowRemoved = onRowRemoved
		update(notificationsRepository.notifications.value.isEmpty())
	}

	override fun onItemClicked(itemViewHolder: Presenter.ViewHolder?, item: Any?, rowViewHolder: RowPresenter.ViewHolder?, row: Row?) {
		if (item !is AppNotification) return

		notificationsRepository.dismissNotification(item)
	}
}
