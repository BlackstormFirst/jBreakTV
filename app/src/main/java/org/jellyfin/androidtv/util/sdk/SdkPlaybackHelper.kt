@file:Suppress("DEPRECATION")

package org.jellyfin.androidtv.util.sdk

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SdkPlaybackHelper(
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val playbackLauncher: PlaybackLauncher,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val videoQueueManager: VideoQueueManager,
) : PlaybackHelper {
	companion object {
		const val ITEM_QUERY_LIMIT = 150
	}

	override fun getItemsToPlay(
		context: Context,
		mainItem: BaseItemDto,
		allowIntros: Boolean,
		shuffle: Boolean,
		outerResponse: Response<List<BaseItemDto>>
	) {
		val loadingDialog = showLoadingDialog(context)
		getScope(context).launch {
			runCatching {
				val items = getItems(mainItem, allowIntros, shuffle)
				if (items.isEmpty() && !mainItem.mediaSources.isNullOrEmpty()) listOf(mainItem)
				else items
			}.fold(
				onSuccess = { items ->
					dismissLoadingDialog(loadingDialog)
					outerResponse.onResponse(items)
				},
				onFailure = { exception ->
					dismissLoadingDialog(loadingDialog)
					when (exception) {
						is Exception -> outerResponse.onError(exception)
						else -> outerResponse.onError(Exception(exception))
					}
				}
			)
		}
	}

	private suspend fun getItems(
		mainItem: BaseItemDto,
		allowIntros: Boolean,
		shuffle: Boolean,
	): List<BaseItemDto> = withContext(Dispatchers.IO) {
		when (mainItem.type) {
			BaseItemKind.EPISODE -> {
				val seriesId = mainItem.seriesId
				if (userPreferences[UserPreferences.mediaQueuingEnabled] && seriesId != null) {
					val cachedSeriesId = videoQueueManager.getFullSeriesId()
					val cachedQueue = videoQueueManager.getFullSeriesQueue()

					if (!shuffle && cachedSeriesId == seriesId && !cachedQueue.isNullOrEmpty() && cachedQueue.any { it.id == mainItem.id }) {
						val updatedQueue = cachedQueue.map { item ->
							if (item.id == mainItem.id) {
								item.copy(
									userData = mainItem.userData ?: item.userData,
									mediaSources = mainItem.mediaSources ?: item.mediaSources,
								)
							} else {
								item
							}
						}
						videoQueueManager.setFullSeriesQueue(seriesId, updatedQueue)
						updatedQueue
					} else {
						val response by api.tvShowsApi.getEpisodes(
							seriesId = seriesId,
							isMissing = false,
							sortBy = if (shuffle) ItemSortBy.RANDOM else null,
							fields = ItemRepository.playbackFields
						)

						val allEpisodes = response.items.filter { it.canPlay() }
						val currentIndex = allEpisodes.indexOfFirst { it.id == mainItem.id }

						if (currentIndex != -1) {
							videoQueueManager.setFullSeriesQueue(seriesId, allEpisodes)
							allEpisodes
						} else {
							videoQueueManager.setFullSeriesQueue(null, null)
							if (mainItem.canPlay()) listOf(mainItem) else emptyList()
						}
					}
				} else {
					videoQueueManager.setFullSeriesQueue(null, null)
					if (mainItem.canPlay()) listOf(mainItem) else emptyList()
				}
			}

			BaseItemKind.SERIES -> {
				val response by api.tvShowsApi.getEpisodes(
					seriesId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) ItemSortBy.RANDOM else null,
					fields = ItemRepository.playbackFields,
				)
				val episodes = response.items.filter { it.canPlay() }
				videoQueueManager.setFullSeriesQueue(mainItem.id, episodes)
				episodes
			}

			BaseItemKind.SEASON -> {
				val seriesId = requireNotNull(mainItem.seriesId)
				val response by api.tvShowsApi.getEpisodes(
					seriesId = seriesId,
					seasonId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) ItemSortBy.RANDOM else null,
					fields = ItemRepository.playbackFields,
				)
				val episodes = response.items.filter { it.canPlay() }
				videoQueueManager.setFullSeriesQueue(seriesId, episodes)
				episodes
			}

			BaseItemKind.FOLDER -> {
				videoQueueManager.setFullSeriesQueue(null)
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					includeItemTypes = listOf(
						BaseItemKind.EPISODE,
						BaseItemKind.MOVIE,
						BaseItemKind.VIDEO
					),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(
						ItemSortBy.PARENT_INDEX_NUMBER,
						ItemSortBy.INDEX_NUMBER,
						ItemSortBy.SORT_NAME
					),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items.filter { it.canPlay() }
			}

			BaseItemKind.BOX_SET -> {
				videoQueueManager.setFullSeriesQueue(null)
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					includeItemTypes = listOf(
						BaseItemKind.EPISODE,
						BaseItemKind.MOVIE,
						BaseItemKind.VIDEO
					),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else null,
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items.filter { it.canPlay() }
			}

			BaseItemKind.MUSIC_ALBUM -> {
				videoQueueManager.setFullSeriesQueue(null)
				val response by api.itemsApi.getItems(
					isMissing = false,
					mediaTypes = listOf(MediaType.AUDIO),
					filters = listOf(ItemFilter.IS_NOT_FOLDER),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(
						ItemSortBy.ALBUM,
						ItemSortBy.PARENT_INDEX_NUMBER,
						ItemSortBy.INDEX_NUMBER,
						ItemSortBy.SORT_NAME
					),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
					albumIds = listOf(mainItem.id)
				)

				response.items
			}

			BaseItemKind.MUSIC_ARTIST -> {
				videoQueueManager.setFullSeriesQueue(null)
				val response by api.itemsApi.getItems(
					isMissing = false,
					mediaTypes = listOf(MediaType.AUDIO),
					filters = listOf(ItemFilter.IS_NOT_FOLDER),
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else listOf(
						ItemSortBy.ALBUM,
						ItemSortBy.PARENT_INDEX_NUMBER,
						ItemSortBy.INDEX_NUMBER,
						ItemSortBy.SORT_NAME
					),
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields,
					artistIds = listOf(mainItem.id)
				)

				response.items
			}

			BaseItemKind.PLAYLIST -> {
				videoQueueManager.setFullSeriesQueue(null)
				val response by api.itemsApi.getItems(
					parentId = mainItem.id,
					isMissing = false,
					sortBy = if (shuffle) listOf(ItemSortBy.RANDOM) else null,
					recursive = true,
					limit = ITEM_QUERY_LIMIT,
					fields = ItemRepository.itemFields
				)

				response.items.filter { it.canPlay() }
			}

			BaseItemKind.PROGRAM -> {
				val parentId = requireNotNull(mainItem.parentId)
				val channel by api.userLibraryApi.getItem(parentId)
				val channelWithProgramMetadata = channel.copy(
					premiereDate = mainItem.premiereDate,
					endDate = mainItem.endDate,
					officialRating = mainItem.officialRating,
					runTimeTicks = mainItem.runTimeTicks,
				)

				listOf(channelWithProgramMetadata)
			}

			BaseItemKind.TV_CHANNEL -> {
				val channel by api.liveTvApi.getChannel(mainItem.id)
				val currentProgram = channel.currentProgram
				if (currentProgram != null) {
					val channelWithCurrentProgramMetadata = channel.copy(
						premiereDate = currentProgram.premiereDate,
						endDate = currentProgram.endDate,
						officialRating = currentProgram.officialRating,
						runTimeTicks = currentProgram.runTimeTicks,
					)
					listOf(channelWithCurrentProgramMetadata)
				} else {
					listOf(channel)
				}
			}

			else -> {
				val parts = getParts(mainItem)
				val addIntros = allowIntros && userPreferences[UserPreferences.cinemaModeEnabled]

				if (addIntros) {
					val intros = runCatching { api.userLibraryApi.getIntros(mainItem.id).content.items }.getOrNull()
						.orEmpty()
						// Force the type to be trailer as the legacy playback UI uses it to determine if it should show the next up screen
						.map { it.copy(type = BaseItemKind.TRAILER) }

					intros + parts
				} else {
					parts
				}
			}
		}
	}

	private suspend fun getParts(item: BaseItemDto): List<BaseItemDto> = buildList {
		add(item)

		val partCount = item.partCount
		if (partCount != null && partCount > 1) {
			val response by api.videosApi.getAdditionalPart(item.id)
			addAll(response.items)
		}
	}

	override fun retrieveAndPlay(itemId: UUID, shuffle: Boolean, context: Context) {
		val loadingDialog = showLoadingDialog(context)
		getScope(context).launch {
			runCatching {
				val resumeSubtractDuration =
					userPreferences[UserPreferences.resumeSubtractDuration].toIntOrNull()?.seconds
						?: Duration.ZERO

				val item = withContext(Dispatchers.IO) {
					val response by api.userLibraryApi.getItem(itemId)
					response
				}

				val pos = item.userData?.playbackPositionTicks?.ticks?.minus(resumeSubtractDuration) ?: Duration.ZERO

				val allowIntros = pos == Duration.ZERO && item.type == BaseItemKind.MOVIE
				var items = getItems(item, allowIntros, shuffle)
				var targetIndex = items.indexOfFirst { it.id == item.id }
				if (targetIndex == -1) {
					items = if (item.canPlay()) listOf(item) + items else items
					targetIndex = items.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
				}

				Triple(items, pos, targetIndex)
			}.fold(
				onSuccess = { (items, pos, targetIndex) ->
					dismissLoadingDialog(loadingDialog)
					playbackLauncher.launch(
						context,
						items,
						pos.inWholeMilliseconds.toInt(),
						playbackControllerContainer.playbackController?.hasFragment() == true,
						targetIndex,
						shuffle,
					)
				},
				onFailure = { exception ->
					dismissLoadingDialog(loadingDialog)
					Toast.makeText(context, exception.localizedMessage ?: "Error playing item", Toast.LENGTH_LONG).show()
				}
			)
		}
	}

	override fun retrieveAndPlay(itemIds: List<UUID>, shuffle: Boolean, position: Long?, index: Int?, context: Context) {
		val loadingDialog = showLoadingDialog(context)
		getScope(context).launch {
			runCatching {
				val resumeSubtractDuration =
					userPreferences[UserPreferences.resumeSubtractDuration].toIntOrNull()?.seconds
						?: Duration.ZERO

				val items = withContext(Dispatchers.IO) {
					val response by api.itemsApi.getItems(
						ids = itemIds,
						fields = ItemRepository.itemFields,
					)
					response.items
				}

				val pos = position?.ticks ?: items[0].userData?.playbackPositionTicks?.ticks?.minus(
					resumeSubtractDuration
				) ?: Duration.ZERO

				Pair(items, pos)
			}.fold(
				onSuccess = { (items, pos) ->
					dismissLoadingDialog(loadingDialog)
					playbackLauncher.launch(
						context,
						items,
						pos.inWholeMilliseconds.toInt(),
						playbackControllerContainer.playbackController?.hasFragment() == true,
						index ?: 0,
						shuffle,
					)
				},
				onFailure = { exception ->
					dismissLoadingDialog(loadingDialog)
					Toast.makeText(context, exception.localizedMessage ?: "Error playing items", Toast.LENGTH_LONG).show()
				}
			)
		}
	}

	override fun playInstantMix(context: Context, item: BaseItemDto) {
		val loadingDialog = showLoadingDialog(context)
		getScope(context).launch {
			runCatching {
				withContext(Dispatchers.IO) {
					api.instantMixApi.getInstantMixFromItem(
						itemId = item.id,
						fields = ItemRepository.itemFields
					).content
				}
			}.fold(
				onSuccess = { response ->
					dismissLoadingDialog(loadingDialog)
					val items = response.items
					if (items.isNotEmpty()) {
						playbackLauncher.launch(context, items)
					} else {
						Toast.makeText(context, R.string.msg_no_playable_items, Toast.LENGTH_LONG).show()
					}
				},
				onFailure = { exception ->
					dismissLoadingDialog(loadingDialog)
					Toast.makeText(context, exception.localizedMessage ?: "Error starting instant mix", Toast.LENGTH_LONG).show()
				}
			)
		}
	}

	private fun showLoadingDialog(context: Context): android.app.AlertDialog? {
		val activity = findActivity(context) ?: return null
		if (activity.isFinishing || activity.isDestroyed) return null

		var dialog: android.app.AlertDialog? = null
		try {
			val progressBar = android.widget.ProgressBar(activity).apply {
				isIndeterminate = true
				val padding = 32
				setPadding(padding, padding, padding, padding)
			}
			val layout = android.widget.LinearLayout(activity).apply {
				orientation = android.widget.LinearLayout.HORIZONTAL
				gravity = android.view.Gravity.CENTER
				addView(progressBar)
				val textView = android.widget.TextView(activity).apply {
					text = activity.getString(R.string.loading)
					setTextColor(android.graphics.Color.WHITE)
					setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
					setPadding(16, 0, 16, 0)
				}
				addView(textView)
			}

			dialog = android.app.AlertDialog.Builder(activity)
				.setView(layout)
				.setCancelable(false)
				.create()

			dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
			dialog.show()
		} catch (e: Exception) {
			Timber.e(e, "Failed to show loading dialog")
		}
		return dialog
	}

	private fun dismissLoadingDialog(dialog: android.app.AlertDialog?) {
		if (dialog == null) return
		try {
			if (dialog.isShowing) {
				dialog.dismiss()
			}
		} catch (e: Exception) {
			Timber.e(e, "Failed to dismiss loading dialog")
		}
	}

	private fun findActivity(context: Context): android.app.Activity? {
		var ctx = context
		while (ctx is android.content.ContextWrapper) {
			if (ctx is android.app.Activity) return ctx
			ctx = ctx.baseContext
		}
		return null
	}

	private fun getScope(context: Context? = null) = when (context) {
		is LifecycleOwner -> context.lifecycleScope
		else -> ProcessLifecycleOwner.get().lifecycleScope
	}
}
