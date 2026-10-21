package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbStorageManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class HomeFragmentViewsRow(
	val small: Boolean,
) : HomeFragmentRow, KoinComponent {

	suspend fun createRowState(
		context: Context,
		userViewsRepository: UserViewsRepository,
	): HomeRowState? {
		val userPreferences = get<UserPreferences>()
		val isLocalPlayerUsbEnabled = userPreferences[UserPreferences.localPlayerUsbEnabled]
		val usbVolumes = UsbStorageManager.getMountedVolumes(context)
		val hasUsb = isLocalPlayerUsbEnabled && usbVolumes.isNotEmpty()

		val filteredItems = userViewsRepository.getCachedOrFetchedViews()

		if (filteredItems.isEmpty() && !hasUsb) return null

		val finalServerAndUsbItems = buildList {
			if (hasUsb) add(UsbBaseRowItem())
			addAll(filteredItems)
		}

		val rowItems = finalServerAndUsbItems.mapNotNull { item ->
			when (item) {
				is UsbBaseRowItem -> item
				is BaseItemDto -> BaseItemDtoBaseRowItem(item, staticHeight = true)
				else -> null
			}
		}

		return HomeRowState(
			id = "views_row_${if (small) "small" else "large"}",
			title = context.getString(R.string.lbl_my_media),
			items = rowItems
		)
	}
}
