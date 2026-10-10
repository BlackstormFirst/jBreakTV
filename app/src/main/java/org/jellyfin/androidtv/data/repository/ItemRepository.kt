package org.jellyfin.androidtv.data.repository

import org.jellyfin.sdk.model.api.ItemFields

object ItemRepository {
	val itemFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHANNEL_INFO,
		ItemFields.CHAPTERS,
		ItemFields.CHILD_COUNT,
		ItemFields.CUMULATIVE_RUN_TIME_TICKS,
		ItemFields.DATE_CREATED,
		ItemFields.DISPLAY_PREFERENCES_ID,
		ItemFields.GENRES,
		ItemFields.ITEM_COUNTS,
		ItemFields.MEDIA_SOURCE_COUNT,
		ItemFields.MEDIA_SOURCES,
		ItemFields.MEDIA_STREAMS,
		ItemFields.OVERVIEW,
		ItemFields.PATH,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.TAGLINES,
		ItemFields.TRICKPLAY,
	)

	// Lighter field set for browsing rows & grids - excludes heavy fields like
	// MediaSources, MediaStreams, Chapters, Trickplay that aren't needed for display.
	// Includes Overview for description text in Leanback headers/summaries.
	// Full item data is fetched when user selects an item.
	val browseFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHILD_COUNT,
		ItemFields.DATE_CREATED,
		ItemFields.OVERVIEW,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
	)

	// Light field set for Android TV background channel worker (LeanbackChannelWorker).
	// Includes Overview for preview program descriptions, while excluding heavy fields like
	// MediaSources, MediaStreams, Chapters, Trickplay that aren't needed for channel cards.
	val channelWorkerFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHILD_COUNT,
		ItemFields.DATE_CREATED,
		ItemFields.OVERVIEW,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
	)

	// Field set for playback queues and playlists retrieval.
	// Includes media sources, streams, chapters, trickplay, overview, and path required for
	// OSD interface displays, chapter selection, trickplay thumbnails, and playback track selection.
	val playbackFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHANNEL_INFO,
		ItemFields.CHAPTERS,
		ItemFields.MEDIA_SOURCES,
		ItemFields.MEDIA_STREAMS,
		ItemFields.OVERVIEW,
		ItemFields.PATH,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.TRICKPLAY,
	)

	// Field set for Next Up - includes Overview for episode description in headers.
	val nextUpFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.DATE_CREATED,
		ItemFields.OVERVIEW,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
	)
}
