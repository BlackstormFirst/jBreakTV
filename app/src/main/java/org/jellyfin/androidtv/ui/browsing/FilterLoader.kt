package org.jellyfin.androidtv.ui.browsing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemSortBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.VideoType;
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object FilterLoader {
	private val countSemaphore = Semaphore(3)
	private var genresJob: Job? = null
	private var audioJob: Job? = null
	private var resolutionJob: Job? = null

	@JvmStatic
	fun loadGenres(
		apiClient: ApiClient,
		parentId: UUID?,
		userId: UUID? = null,
		includeItemTypes: List<BaseItemKind>? = null,
		audioLanguages: List<String>? = null,
		is4k: Boolean? = null,
		isHd: Boolean? = null,
		is3d: Boolean? = null,
		videoTypes: List<VideoType>? = null,
		itemFilters: List<ItemFilter>? = null,
		callback: GenreCallback
	) {
		genresJob?.cancel()
		genresJob = CoroutineScope(Dispatchers.IO).launch {
			try {
				val genresResponse = apiClient.genresApi.getGenres(
					parentId = parentId,
					userId = userId,
					includeItemTypes = includeItemTypes,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					enableImages = false
				)

				val genreNames = genresResponse.content.items?.mapNotNull { it.name } ?: emptyList()

				val genresWithCounts = genreNames.map { name ->
					async {
						val count = getCount(
							apiClient, parentId, userId, includeItemTypes,
							genres = listOf(name),
							audioLanguages = audioLanguages,
							is4k = is4k, isHd = isHd, is3d = is3d,
							videoTypes = videoTypes,
							itemFilters = itemFilters
						)
						name to count
					}
				}.awaitAll().toMap()

				withContext(Dispatchers.Main) {
					callback.onGenresLoaded(genresWithCounts)
				}
			} catch (e: Exception) {
				Timber.e(e, "Erreur de chargement des genres")
				withContext(Dispatchers.Main) {
					callback.onGenresLoaded(emptyMap())
				}
			}
		}
	}

	fun interface GenreCallback {
		fun onGenresLoaded(genres: Map<String, Int>)
	}

	@JvmStatic
	fun loadAudioLanguages(
		apiClient: ApiClient,
		parentId: UUID?,
		userId: UUID? = null,
		includeItemTypes: List<BaseItemKind>? = null,
		genres: List<String>? = null,
		is4k: Boolean? = null,
		isHd: Boolean? = null,
		is3d: Boolean? = null,
		videoTypes: List<VideoType>? = null,
		itemFilters: List<ItemFilter>? = null,
		callback: AudioCallback
	) {
		audioJob?.cancel()
		audioJob = CoroutineScope(Dispatchers.IO).launch {
			try {
				val response = apiClient.filterApi.getQueryFiltersLegacy(
					parentId = parentId,
					userId = userId,
					mediaTypes = setOf(org.jellyfin.sdk.model.api.MediaType.VIDEO)
				)

				val tags = response.content.tags
				var languages = tags?.filter { it.startsWith("#language_", ignoreCase = true) }
					?.map { it.substringAfter("#language_") }
					?.distinct()?.sorted() ?: emptyList<String>()

				if (languages.isEmpty()) {
					val response2 = apiClient.filterApi.getQueryFilters(
						parentId = parentId,
						userId = userId,
						recursive = true
					)
					languages = response2.content.tags?.filter { it.startsWith("#language_", ignoreCase = true) }
						?.map { it.substringAfter("#language_") }
						?.distinct()?.sorted() ?: emptyList<String>()
				}

				val languagesWithCounts = languages.map { lang ->
					async {
						val count = getCount(
							apiClient, parentId, userId, includeItemTypes,
							genres = genres,
							audioLanguages = listOf(lang),
							is4k = is4k, isHd = isHd, is3d = is3d,
							videoTypes = videoTypes,
							itemFilters = itemFilters
						)
						lang to count
					}
				}.awaitAll().toMap()

				withContext(Dispatchers.Main) {
					callback.onAudioLoaded(languagesWithCounts)
				}
			} catch (e: Exception) {
				Timber.e(e, "Erreur de chargement des langues audio")
				withContext(Dispatchers.Main) {
					callback.onAudioLoaded(emptyMap())
				}
			}
		}
	}

	fun interface AudioCallback {
		fun onAudioLoaded(languages: Map<String, Int>)
	}

	@JvmStatic
	fun loadResolutionCounts(
		apiClient: ApiClient,
		parentId: UUID?,
		userId: UUID? = null,
		includeItemTypes: List<BaseItemKind>? = null,
		genres: List<String>? = null,
		audioLanguages: List<String>? = null,
		itemFilters: List<ItemFilter>? = null,
		callback: ResolutionCallback
	) {
		resolutionJob?.cancel()
		resolutionJob = CoroutineScope(Dispatchers.IO).launch {
			val counts = mutableMapOf<String, Int>()
			try {
				val tasks = mutableMapOf<String, Deferred<Int>>()

				tasks["4K"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, is4k = true, itemFilters = itemFilters) }
				tasks["HD"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, isHd = true, itemFilters = itemFilters) }
				tasks["SD"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, is4k = false, isHd = false, itemFilters = itemFilters) }
				tasks["3D"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, is3d = true, itemFilters = itemFilters) }

				tasks["DVD"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, videoTypes = listOf(VideoType.DVD), itemFilters = itemFilters) }
				tasks["ISO"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, videoTypes = listOf(VideoType.ISO), itemFilters = itemFilters) }
				tasks["Blu-ray"] = async { getCount(apiClient, parentId, userId, includeItemTypes, genres = genres, audioLanguages = audioLanguages, videoTypes = listOf(VideoType.BLU_RAY), itemFilters = itemFilters) }

				tasks.forEach { (key, task) ->
					counts[key] = task.await()
				}

				withContext(Dispatchers.Main) {
					callback.onResolutionsLoaded(counts)
				}
			} catch (e: Exception) {
				Timber.e(e, "Erreur de chargement des comptes de résolution")
				withContext(Dispatchers.Main) {
					callback.onResolutionsLoaded(emptyMap())
				}
			}
		}
	}

	private suspend fun getCount(
		apiClient: ApiClient,
		parentId: UUID?,
		userId: UUID?,
		includeItemTypes: List<BaseItemKind>?,
		genres: List<String>? = null,
		audioLanguages: List<String>? = null,
		is4k: Boolean? = null,
		isHd: Boolean? = null,
		is3d: Boolean? = null,
		videoTypes: List<VideoType>? = null,
		itemFilters: List<ItemFilter>? = null
	): Int = countSemaphore.withPermit {
		val activeTags = mutableListOf<String>()
		audioLanguages?.forEach { activeTags.add("#language_$it") }

		return try {
			apiClient.itemsApi.getItems(
				parentId = parentId,
				userId = userId,
				includeItemTypes = includeItemTypes,
				genres = genres?.toSet(),
				tags = if (activeTags.isEmpty()) null else activeTags,
				is4k = is4k,
				isHd = isHd,
				is3d = is3d,
				videoTypes = videoTypes,
				filters = itemFilters,
				limit = 0,
				enableTotalRecordCount = true,
				recursive = true,
				enableImages = false,
				enableUserData = false,
				fields = emptySet()
			).content.totalRecordCount
		} catch (e: Exception) {
			0
		}
	}

	fun interface ResolutionCallback {
		fun onResolutionsLoaded(counts: Map<String, Int>)
	}

	@JvmStatic
	@JvmOverloads
	fun createFilteredGridRequest(
		folder: BaseItemDto,
		genres: List<String>,
		audioLanguages: List<String>? = null,
		is4k: Boolean? = null,
		isHd: Boolean? = null,
		is3d: Boolean? = null,
		videoTypes: List<VideoType>? = null,
		tags: List<String>? = null,
		itemFilters: List<ItemFilter>? = null
	): GetItemsRequest {
		val baseRequest = BrowsingUtils.createBrowseGridItemsRequest(folder)
		val activeGenres = if (genres.isEmpty()) null else genres
		val activeTags = mutableListOf<String>()

		audioLanguages?.forEach { lang ->
			activeTags.add("#language_$lang")
		}

		tags?.let { activeTags.addAll(it) }

		return baseRequest.copy(
			genres = activeGenres,
			tags = if (activeTags.isEmpty()) null else activeTags,
			is4k = is4k,
			isHd = isHd,
			is3d = is3d,
			videoTypes = videoTypes,
			filters = itemFilters
		)
	}
}
