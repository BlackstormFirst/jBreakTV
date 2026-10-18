package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem

data class HomeRowState(
    val id: String,
    val title: String? = null,
    val items: List<Any> = emptyList(),
    val sectionIndex: Int = Int.MAX_VALUE,
    val sectionType: HomeSectionType? = null
)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Success(val rows: List<HomeRowState>) : HomeUiState
    data class Error(val message: String? = null) : HomeUiState
}

fun getItemKey(item: Any): String = when (item) {
    is BaseRowItem -> item.itemId?.toString() ?: item.hashCode().toString()
    is GridButtonBaseRowItem -> "grid_${item.gridButton.id}"
    is GridButton -> "grid_${item.id}"
    is UsbBaseRowItem -> "usb_tile"
    is AppNotification -> "notification_${item.message}"
    else -> item.hashCode().toString()
}
