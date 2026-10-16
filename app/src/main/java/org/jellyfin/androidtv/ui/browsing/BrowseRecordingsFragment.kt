package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R

class BrowseRecordingsFragment : EnhancedBrowseFragment() {
	override fun setupQueries(rowLoader: RowLoader) {
		showViews = true

		// Latest Recordings
		mRows.add(BrowseRowDef(getString(R.string.lbl_recent_recordings), BrowsingUtils.createLiveTVRecordingsRequest(40), 40))

		// Movies
		val moviesDef = BrowseRowDef(getString(R.string.lbl_movies), BrowsingUtils.createLiveTVMovieRecordingsRequest(), 60)

		// Shows
		val showsDef = BrowseRowDef(getString(R.string.lbl_tv_series), BrowsingUtils.createLiveTVSeriesRecordingsRequest(), 60)

		mRows.add(showsDef)
		mRows.add(moviesDef)

		// Sports
		mRows.add(BrowseRowDef(getString(R.string.lbl_sports), BrowsingUtils.createLiveTVSportsRecordingsRequest(), 60))

		// Kids
		mRows.add(BrowseRowDef(getString(R.string.lbl_kids), BrowsingUtils.createLiveTVKidsRecordingsRequest(), 60))

		rowLoader.loadRows(mRows)
	}
}
