package org.jellyfin.androidtv.util.usbdevices

import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

val USB_TILE_UUID: UUID = UUID.nameUUIDFromBytes("jBreakTV_USB_Tile_UUID_v1".toByteArray())

class UsbBaseRowItem : BaseItemDtoBaseRowItem(
    BaseItemDto(
        id = USB_TILE_UUID,
        name = "Périphériques Locaux ou USB",
        type = BaseItemKind.COLLECTION_FOLDER
    ),
    false,
    true
)
