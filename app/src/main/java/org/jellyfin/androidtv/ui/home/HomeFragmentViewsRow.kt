package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.setInitialItems
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbStorageManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

class HomeFragmentViewsRow(
	val small: Boolean,
) : HomeFragmentRow, KoinComponent {
	private companion object {
		val smallCardPresenter = CardPresenter(true, 75)
		val largeCardPresenter = CardPresenter(true, 126)
	}

	suspend fun createListRow(
		context: Context,
		userViewsRepository: UserViewsRepository,
		rowsAdapter: MutableObjectAdapter<Row>? = null,
	): ListRow? {
		val presenter = if (small) smallCardPresenter else largeCardPresenter
		val rowAdapter = ItemRowAdapter(context, GetUserViewsRequest, presenter, rowsAdapter)

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

		rowAdapter.setInitialItems(finalServerAndUsbItems) { item, _ ->
			when (item) {
				is UsbBaseRowItem -> item
				is BaseItemDto -> BaseItemDtoBaseRowItem(item, staticHeight = true)
				else -> null
			}
		}

		val header = HeaderItem(context.getString(R.string.lbl_my_media))
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		return row
	}

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		val presenter = if (small) smallCardPresenter else largeCardPresenter
		val rowAdapter = ItemRowAdapter(context, GetUserViewsRequest, presenter, rowsAdapter)

		val header = HeaderItem(context.getString(R.string.lbl_my_media))
		val row = ListRow(header, rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}
}
