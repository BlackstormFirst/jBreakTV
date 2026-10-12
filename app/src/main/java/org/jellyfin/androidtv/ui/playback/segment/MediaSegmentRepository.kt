package org.jellyfin.androidtv.ui.playback.segment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.sdk.duration
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.util.isLocalPath
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.pluginsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

interface MediaSegmentRepository {
	companion object {
		/**
		 * All media segments currently supported by the app. The order of these is used for the preferences UI.
		 */
		val SupportedTypes = listOf(
			MediaSegmentType.INTRO,
			MediaSegmentType.OUTRO,
			MediaSegmentType.PREVIEW,
			MediaSegmentType.RECAP,
			MediaSegmentType.COMMERCIAL,
		)

		/**
		 * The minimum duration for a media segment to allow the [MediaSegmentAction.SKIP] action.
		 */
		val SkipMinDuration = 1.seconds

		/**
		 * The minimum duration for a media segment to allow the [MediaSegmentAction.ASK_TO_SKIP] action.
		 */
		val AskToSkipMinDuration = 3.seconds

		/**
		 * The duration to wait before automatically hiding the "ask to skip" UI.
		 */
		val AskToSkipAutoHideDuration = 8.seconds
	}

	fun getDefaultSegmentTypeAction(type: MediaSegmentType): MediaSegmentAction
	fun setDefaultSegmentTypeAction(type: MediaSegmentType, action: MediaSegmentAction)

	suspend fun getSegmentsForItem(item: BaseItemDto): List<MediaSegmentDto>
	fun getMediaSegmentAction(segment: MediaSegmentDto): MediaSegmentAction
}

fun Map<MediaSegmentType, MediaSegmentAction>.toMediaSegmentActionsString() =
	map { "${it.key.serialName}=${it.value.name}" }
		.joinToString(",")

class MediaSegmentRepositoryImpl(
	private val userPreferences: UserPreferences,
	private val api: ApiClient,
	private val context: Context,
) : MediaSegmentRepository {
	private val mediaTypeActions = mutableMapOf<MediaSegmentType, MediaSegmentAction>()

	init {
		restoreMediaTypeActions()
	}

	private fun restoreMediaTypeActions() {
		val restoredMediaTypeActions = userPreferences[UserPreferences.mediaSegmentActions]
			.split(",")
			.mapNotNull {
				runCatching {
					val (type, action) = it.split('=', limit = 2)
					MediaSegmentType.fromName(type) to MediaSegmentAction.valueOf(action)
				}.getOrNull()
			}

		mediaTypeActions.clear()
		mediaTypeActions.putAll(restoredMediaTypeActions)
	}

	private fun saveMediaTypeActions() {
		userPreferences[UserPreferences.mediaSegmentActions] = mediaTypeActions.toMediaSegmentActionsString()
	}

	override fun getDefaultSegmentTypeAction(type: MediaSegmentType): MediaSegmentAction {
		// Always return no action for unsupported types
		if (!MediaSegmentRepository.SupportedTypes.contains(type)) return MediaSegmentAction.NOTHING

		return mediaTypeActions.getOrDefault(type, MediaSegmentAction.NOTHING)
	}

	override fun setDefaultSegmentTypeAction(type: MediaSegmentType, action: MediaSegmentAction) {
		// Don't allow modifying actions for unsupported types
		if (!MediaSegmentRepository.SupportedTypes.contains(type)) return

		mediaTypeActions[type] = action
		saveMediaTypeActions()
	}

	override fun getMediaSegmentAction(segment: MediaSegmentDto): MediaSegmentAction {
		val action = getDefaultSegmentTypeAction(segment.type)
		// Skip the skip action if timespan is too short
		if (action == MediaSegmentAction.SKIP && segment.duration < MediaSegmentRepository.SkipMinDuration) return MediaSegmentAction.NOTHING
		// Skip the ask to skip action if timespan is too short
		if (action == MediaSegmentAction.ASK_TO_SKIP && segment.duration < MediaSegmentRepository.AskToSkipMinDuration) return MediaSegmentAction.NOTHING
		return action
	}

	override suspend fun getSegmentsForItem(item: BaseItemDto): List<MediaSegmentDto> {
		val itemPath = item.path
		var effectiveItem = item

		// For local media files: do NOT fetch remote segments directly; create segments locally from chapters
		// using keywords retrieved from the server.
		if (isLocalPath(itemPath) && itemPath != null) {
			if (effectiveItem.chapters.isNullOrEmpty()) {
				val file = File(itemPath)
				if (file.exists() && file.canRead()) {
					val inspected = runCatching {
						withContext(Dispatchers.IO) {
							LocalVideoManager.inspectAndBuildBaseItemDto(context, file)
						}
					}.getOrNull()
					if (inspected != null) {
						effectiveItem = inspected
					}
				}
			}

			val chapters = effectiveItem.chapters.orEmpty()
			if (chapters.isEmpty()) {
				return emptyList()
			}

			return withContext(Dispatchers.IO) {
				val serverKeywords = fetchServerSegmentKeywords(api)
				buildSegmentsFromChapters(effectiveItem, chapters, serverKeywords)
			}
		}

		// For remote media files: try fetching server segments via API first
		val remoteSegments = runCatching {
			withContext(Dispatchers.IO) {
				api.mediaSegmentsApi.getItemSegments(
					itemId = item.id,
					includeSegmentTypes = MediaSegmentRepository.SupportedTypes,
				).content.items
			}
		}.getOrNull().orEmpty()

		if (remoteSegments.isNotEmpty()) {
			return remoteSegments
		}

		val chapters = effectiveItem.chapters.orEmpty()
		if (chapters.isEmpty()) {
			return emptyList()
		}

		return withContext(Dispatchers.IO) {
			val serverKeywords = fetchServerSegmentKeywords(api)
			buildSegmentsFromChapters(effectiveItem, chapters, serverKeywords)
		}
	}
}

private suspend fun fetchServerSegmentKeywords(api: ApiClient): Map<MediaSegmentType, Set<String>> {
	val keywordsMap = mutableMapOf<MediaSegmentType, MutableSet<String>>()
	runCatching {
		val plugins = api.pluginsApi.getPlugins().content
		val skipperPlugins = plugins.filter {
			val n = it.name.lowercase(Locale.ROOT)
			n.contains("intro") || n.contains("segment") || n.contains("skipper")
		}
		for (plugin in skipperPlugins) {
			runCatching {
				val rawResponse = api.request(
					pathTemplate = "/Plugins/{pluginId}/Configuration",
					pathParameters = mapOf("pluginId" to plugin.id),
				)
				val jsonString = rawResponse.body.decodeToString()
				parsePluginConfigurationJson(jsonString, keywordsMap)
			}
		}
	}
	return keywordsMap
}

private fun parsePluginConfigurationJson(
	jsonString: String,
	keywordsMap: MutableMap<MediaSegmentType, MutableSet<String>>,
) {
	runCatching {
		val jsonObject = JSONObject(jsonString)
		val keys = jsonObject.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val keyLower = key.lowercase(Locale.ROOT)
			val targetType = when {
				keyLower.contains("intro") -> MediaSegmentType.INTRO
				keyLower.contains("outro") || keyLower.contains("ending") -> MediaSegmentType.OUTRO
				keyLower.contains("recap") -> MediaSegmentType.RECAP
				keyLower.contains("preview") -> MediaSegmentType.PREVIEW
				keyLower.contains("commercial") || keyLower.contains("pub") || keyLower.contains("sponsor") -> MediaSegmentType.COMMERCIAL
				else -> null
			} ?: continue

			val keywordsSet = keywordsMap.getOrPut(targetType) { mutableSetOf() }

			when {
				jsonObject.isNull(key) -> continue

				jsonObject.optJSONArray(key) != null -> {
					val array = jsonObject.getJSONArray(key)
					for (i in 0 until array.length()) {
						val item = array.optString(i, "").lowercase(Locale.ROOT).trim()
						if (item.isNotBlank()) {
							keywordsSet.add(item)
						}
					}
				}

				jsonObject.optJSONObject(key) != null -> {
					val nested = jsonObject.getJSONObject(key)
					val nestedKeys = nested.keys()
					while (nestedKeys.hasNext()) {
						val nk = nestedKeys.next()
						val item = nested.optString(nk, "").lowercase(Locale.ROOT).trim()
						if (item.isNotBlank()) {
							keywordsSet.add(item)
						}
					}
				}

				else -> {
					val strValue = jsonObject.optString(key, "")
					strValue.split(',', ';', '\n').forEach { raw ->
						val item = raw.lowercase(Locale.ROOT).trim()
						if (item.isNotBlank()) {
							keywordsSet.add(item)
						}
					}
				}
			}
		}
	}
}

private fun buildSegmentsFromChapters(
	item: BaseItemDto,
	chapters: List<ChapterInfo>,
	serverKeywords: Map<MediaSegmentType, Set<String>>,
): List<MediaSegmentDto> {
	val segments = mutableListOf<MediaSegmentDto>()
	val defaultIntro = setOf("intro", "opening", "op", "générique", "générique de début", "introduction")
	val defaultOutro = setOf("outro", "ending", "ed", "credits", "crédits", "générique de fin", "générique fin")
	val defaultRecap = setOf("recap", "résumé", "previously", "précédemment")
	val defaultPreview = setOf("preview", "teaser", "prochainement", "next episode", "au prochain épisode")
	val defaultCommercial = setOf("commercial", "pub", "publicité", "sponsor")

	val introKeywords = serverKeywords[MediaSegmentType.INTRO]?.takeIf { it.isNotEmpty() } ?: defaultIntro
	val outroKeywords = serverKeywords[MediaSegmentType.OUTRO]?.takeIf { it.isNotEmpty() } ?: defaultOutro
	val recapKeywords = serverKeywords[MediaSegmentType.RECAP]?.takeIf { it.isNotEmpty() } ?: defaultRecap
	val previewKeywords = serverKeywords[MediaSegmentType.PREVIEW]?.takeIf { it.isNotEmpty() } ?: defaultPreview
	val commercialKeywords = serverKeywords[MediaSegmentType.COMMERCIAL]?.takeIf { it.isNotEmpty() } ?: defaultCommercial

	for (i in chapters.indices) {
		val chapter = chapters[i]
		val rawName = chapter.name.orEmpty().lowercase(Locale.ROOT).trim()
		if (rawName.isBlank()) continue

		val startTicks = chapter.startPositionTicks
		val endTicks = if (i < chapters.size - 1) {
			chapters[i + 1].startPositionTicks
		} else {
			item.runTimeTicks ?: (startTicks + 300_000_000L)
		}

		if (endTicks <= startTicks) continue

		val type = when {
			introKeywords.any { rawName.contains(it) } -> MediaSegmentType.INTRO
			outroKeywords.any { rawName.contains(it) } -> MediaSegmentType.OUTRO
			recapKeywords.any { rawName.contains(it) } -> MediaSegmentType.RECAP
			previewKeywords.any { rawName.contains(it) } -> MediaSegmentType.PREVIEW
			commercialKeywords.any { rawName.contains(it) } -> MediaSegmentType.COMMERCIAL
			else -> null
		}

		if (type != null) {
			val segId = UUID.nameUUIDFromBytes("local_seg_${item.id}_$i".toByteArray())
			segments.add(
				MediaSegmentDto(
					id = segId,
					itemId = item.id,
					type = type,
					startTicks = startTicks,
					endTicks = endTicks,
				)
			)
		}
	}

	return segments
}
