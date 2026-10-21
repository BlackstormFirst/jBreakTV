package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.ui.presentation.CardPresenter

interface HomeFragmentRow {
	fun addRowStates(context: Context, cardPresenter: CardPresenter, onRowStateAdded: (HomeRowState) -> Unit, onRowStateRemoved: (HomeRowState) -> Unit) {
		// Default empty implementation
	}
}
