package org.jellyfin.androidtv.ui.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter

class NotificationsHomeFragmentRow(
	coroutineScope: CoroutineScope,
	private val notificationsRepository: NotificationsRepository,
) : HomeFragmentRow {

	private var onRowStateAdded: ((HomeRowState) -> Unit)? = null
	private var onRowStateRemoved: ((HomeRowState) -> Unit)? = null
	private var rowState: HomeRowState? = null

	init {
		notificationsRepository.notifications.onEach { notifications ->
			updateState(notifications)
		}.launchIn(coroutineScope)
	}

	override fun addRowStates(
		context: Context,
		cardPresenter: CardPresenter,
		onRowStateAdded: (HomeRowState) -> Unit,
		onRowStateRemoved: (HomeRowState) -> Unit
	) {
		this.onRowStateAdded = onRowStateAdded
		this.onRowStateRemoved = onRowStateRemoved
		updateState(notificationsRepository.notifications.value)
	}

	private fun updateState(notifications: List<AppNotification>) {
		if (notifications.isEmpty()) {
			if (rowState != null) {
				onRowStateRemoved?.invoke(rowState!!)
				rowState = null
			}
		} else {
			val newState = HomeRowState(
				id = "notifications_row",
				title = null,
				items = notifications
			)
			rowState = newState
			onRowStateAdded?.invoke(newState)
		}
	}
}
