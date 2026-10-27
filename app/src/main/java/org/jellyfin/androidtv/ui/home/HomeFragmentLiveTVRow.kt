package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.util.Utils

class HomeFragmentLiveTVRow(
	private val context: Context,
	private val userRepository: UserRepository,
) : HomeFragmentRow {

	override fun addRowStates(
		context: Context,
		onRowStateAdded: (HomeRowState) -> Unit,
		onRowStateRemoved: (HomeRowState) -> Unit
	) {
		val title = context.getString(R.string.pref_live_tv_cat)
		val items = mutableListOf<Any>()

		// Live TV Guide button
		items.add(GridButton(LiveTvOption.LIVE_TV_GUIDE_OPTION_ID, context.getString(R.string.lbl_live_tv_guide)))
		// Live TV Recordings button
		items.add(GridButton(LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID, context.getString(R.string.lbl_recorded_tv)))
		if (Utils.canManageRecordings(userRepository.currentUser.value)) {
			// Recording Schedule button
			items.add(GridButton(LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID, context.getString(R.string.lbl_schedule)))
			// Recording Series button
			items.add(GridButton(LiveTvOption.LIVE_TV_SERIES_OPTION_ID, context.getString(R.string.lbl_series)))
		}

		onRowStateAdded(
			HomeRowState(
				id = "live_tv_row",
				title = title,
				items = items
			)
		)
	}
}
