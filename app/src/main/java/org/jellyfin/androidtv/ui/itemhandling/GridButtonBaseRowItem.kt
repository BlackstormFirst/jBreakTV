package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.GridButton

class GridButtonBaseRowItem(
	val gridButton: GridButton,
) : BaseRowItem(
	baseRowType = BaseRowType.GridButton,
	staticHeight = true,
) {
	override fun getImage(imageType: ImageType) = null
	override fun getFullName(context: Context) = gridButton.text
	override fun getName(context: Context) = gridButton.text

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is GridButtonBaseRowItem) return false
		return gridButton == other.gridButton
	}

	override fun hashCode(): Int {
		return gridButton.hashCode()
	}
}
