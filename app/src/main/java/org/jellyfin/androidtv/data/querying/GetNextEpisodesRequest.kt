package org.jellyfin.androidtv.data.querying

import java.util.UUID

data class GetNextEpisodesRequest(
	val seriesId: UUID,
	val seasonId: UUID,
	val startItemId: UUID,
)
