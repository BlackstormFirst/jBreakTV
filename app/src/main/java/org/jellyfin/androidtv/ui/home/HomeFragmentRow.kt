package org.jellyfin.androidtv.ui.home

import android.content.Context


interface HomeFragmentRow {
	fun addRowStates(context: Context, onRowStateAdded: (HomeRowState) -> Unit, onRowStateRemoved: (HomeRowState) -> Unit) {
		// Default empty implementation
	}
}
