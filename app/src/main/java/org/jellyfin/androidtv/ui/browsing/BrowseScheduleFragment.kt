package org.jellyfin.androidtv.ui.browsing

class BrowseScheduleFragment : EnhancedBrowseFragment() {
	override fun setupQueries(rowLoader: RowLoader) {
		rowLoader.loadRows(mRows)
	}
}
