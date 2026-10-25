package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

interface PlaybackHelper {
	fun getItemsToPlay(
		context: Context,
		mainItem: BaseItemDto,
		allowIntros: Boolean,
		shuffle: Boolean,
		onSuccess: (List<BaseItemDto>) -> Unit,
		onError: ((Exception) -> Unit)? = null,
	)

	fun retrieveAndPlay(itemId: UUID, shuffle: Boolean, context: Context)

	fun retrieveAndPlay(itemId: UUID, shuffle: Boolean, context: Context, startPosition: Long?)

	fun retrieveAndPlay(itemIds: List<UUID>, shuffle: Boolean, position: Long?, index: Int?, context: Context)

	fun playInstantMix(context: Context, item: BaseItemDto)
}
