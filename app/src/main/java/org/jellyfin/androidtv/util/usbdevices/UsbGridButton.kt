package org.jellyfin.androidtv.util.usbdevices

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton

const val USB_GRID_BUTTON_ID = 888123

class UsbGridButton : GridButton(
    USB_GRID_BUTTON_ID,
    "Périphériques Locaux ou USB",
    R.drawable.ic_folder
)
