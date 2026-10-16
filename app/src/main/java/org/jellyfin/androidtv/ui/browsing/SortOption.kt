package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

data class SortOption(
	@JvmField val name: String,
	@JvmField val value: ItemSortBy,
	@JvmField val order: SortOrder
)
