package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.ListRow
import org.jellyfin.androidtv.ui.presentation.CardPresenter

interface HomeFragmentRow {
	fun addRows(context: Context, cardPresenter: CardPresenter, onRowAdded: (ListRow) -> Unit, onRowRemoved: (ListRow) -> Unit)
}
