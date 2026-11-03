package org.jellyfin.androidtv.ui.playback

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class PlaybackIndexManager : KoinComponent {
	private val userPreferences by inject<UserPreferences>()
	private val videoQueueManager by inject<VideoQueueManager>()

	private fun areLanguagesEqual(lang1: String?, lang2: String?): Boolean {
		if (lang1 == null || lang2 == null) return false
		if (lang1.equals(lang2, ignoreCase = true)) return true
		if (lang1.length >= 2 && lang2.length >= 2) {
			if (lang1.substring(0, 2).equals(lang2.substring(0, 2), ignoreCase = true)) {
				return true
			}
		}
		val l1 = lang1.lowercase()
		val l2 = lang2.lowercase()
		return (l1 == "fre" && l2 == "fra") || (l1 == "fra" && l2 == "fre") ||
			(l1 == "ger" && l2 == "deu") || (l1 == "deu" && l2 == "ger") ||
			(l1 == "chi" && l2 == "zho") || (l1 == "zho" && l2 == "chi")
	}

	private fun matchAudioStream(
		streams: List<MediaStream>?,
		targetLang: String?,
		targetTitle: String?,
		targetCodec: String?,
		targetDefault: Boolean?,
		targetHI: Boolean?,
		targetIndexInType: Int?,
	): Int? {
		if (streams.isNullOrEmpty() || targetLang.isNullOrEmpty()) return null

		val matchingLangStreams = streams.filter { s ->
			s.type == MediaStreamType.AUDIO && areLanguagesEqual(targetLang, s.language)
		}

		if (matchingLangStreams.isNotEmpty()) {
			// 1. Title + Codec match
			if (!targetTitle.isNullOrEmpty() && !targetCodec.isNullOrEmpty()) {
				for (s in matchingLangStreams) {
					if (s.title != null && targetTitle.equals(s.title, ignoreCase = true) &&
						targetCodec.equals(s.codec, ignoreCase = true)
					) {
						Timber.d("Best audio found (title+lang+codec match)")
						return s.index
					}
				}
			}

			// 2. Title match (regardless of codec)
			if (!targetTitle.isNullOrEmpty()) {
				for (s in matchingLangStreams) {
					if (s.title != null && targetTitle.equals(s.title, ignoreCase = true)) {
						Timber.d("Best audio found (title+lang match)")
						return s.index
					}
				}
			}

			// 3. Codec match
			if (!targetCodec.isNullOrEmpty()) {
				for (s in matchingLangStreams) {
					if (targetCodec.equals(s.codec, ignoreCase = true)) {
						Timber.d("Best audio found (lang+codec match)")
						return s.index
					}
				}
			}

			// 4. Direct match by index in audio streams for that language
			if (targetIndexInType != null && targetIndexInType >= 0 && targetIndexInType < matchingLangStreams.size) {
				Timber.d("Best audio found (lang+indexInType match)")
				return matchingLangStreams[targetIndexInType].index
			}

			// 5. Match HI or default
			val preferHI = targetHI == true
			if (preferHI) {
				for (s in matchingLangStreams) {
					if (s.isHearingImpaired) {
						return s.index
					}
				}
			} else {
				for (s in matchingLangStreams) {
					if (s.isDefault && !s.isHearingImpaired) {
						return s.index
					}
				}
				for (s in matchingLangStreams) {
					if (!s.isHearingImpaired) {
						return s.index
					}
				}
			}

			// Fallback: First audio stream in target language
			return matchingLangStreams[0].index
		}

		// --- PHASE 2 : Codec priority over another language (if target language was not found) ---
		if (!targetCodec.isNullOrEmpty()) {
			for (s in streams) {
				if (targetCodec.equals(s.codec, ignoreCase = true)) {
					return s.index
				}
			}
		}

		return null
	}

	fun getBestAudioIndex(info: MediaSourceInfo?): Int? {
		if (info == null || info.mediaStreams.isNullOrEmpty()) return null

		val allAudioStreams = info.mediaStreams?.filter { stream -> stream.type == MediaStreamType.AUDIO }.orEmpty()
		if (allAudioStreams.isEmpty()) return null

		val isLocal = LocalVideoManager.isLocalSource(info)
		val lastAudioLanguage = videoQueueManager.getLastPlayedAudioLanguageIsoCode()

		if (!isLocal && lastAudioLanguage.isNullOrEmpty()) {
			val serverDefault = info.defaultAudioStreamIndex
			if (serverDefault != null) {
				return serverDefault
			}
		}

		val userAudioLangRemoteSetting = userPreferences[UserSettingPreferences.audioLangRemoteSetting]
		val userAudioAlwaysDefaultRemoteSetting = userPreferences[UserSettingPreferences.userAlwaysUseAudioDefault]
		val userSubMode = userPreferences[UserSettingPreferences.subMode]

		Timber.i("Remote user audio lang settings: %s", userAudioLangRemoteSetting)
		Timber.i("Remote user audio always default settings: %b", userAudioAlwaysDefaultRemoteSetting)

		val lastAudioTitle = videoQueueManager.getLastPlayedAudioTitle()
		val lastAudioCodec = videoQueueManager.getLastPlayedAudioCodec()
		val lastAudioDefaultState = videoQueueManager.getLastPlayedAudioDefaultState()
		val lastAudioHearingImpairedState = videoQueueManager.getLastPlayedAudioHearingImpairedState()
		val lastAudioIndexInType = videoQueueManager.getLastPlayedAudioIndexInType()

		var matchingIndex: Int? = null

		// Mode SMART or User prefers default audio track
		if (userSubMode == R.string.subtitle_mode_smart) {
			if (userAudioAlwaysDefaultRemoteSetting) {
				for (stream in allAudioStreams) {
					if (stream.isDefault) {
						Timber.d("Best smart audio found (Always Use Default)!")
						matchingIndex = stream.index
						break
					}
				}
			}

			// Priority 1 in SMART mode: Preferred native audio language if available on new item
			if (matchingIndex == null && userAudioLangRemoteSetting.isNotEmpty()) {
				matchingIndex = matchAudioStream(
					allAudioStreams,
					userAudioLangRemoteSetting,
					lastAudioTitle,
					lastAudioCodec,
					true,
					false,
					lastAudioIndexInType
				)
			}

			// Priority 2: Last played audio language (queue persistence)
			if (matchingIndex == null && !lastAudioLanguage.isNullOrEmpty()) {
				matchingIndex = matchAudioStream(
					allAudioStreams,
					lastAudioLanguage,
					lastAudioTitle,
					lastAudioCodec,
					lastAudioDefaultState,
					lastAudioHearingImpairedState,
					lastAudioIndexInType
				)
			}
		}

		// Mode DEFAULT / Queue persistence (or fallback if SMART didn't find matching language)
		if (matchingIndex == null && !lastAudioLanguage.isNullOrEmpty()) {
			matchingIndex = matchAudioStream(
				allAudioStreams,
				lastAudioLanguage,
				lastAudioTitle,
				lastAudioCodec,
				lastAudioDefaultState,
				lastAudioHearingImpairedState,
				lastAudioIndexInType
			)
		}

		// General Fallbacks
		if (matchingIndex == null) {
			for (stream in allAudioStreams) {
				if (stream.isDefault && !stream.isHearingImpaired) {
					matchingIndex = stream.index
					break
				}
			}
		}

		if (matchingIndex == null) {
			// Fallback 1: Stream marked default
			for (stream in allAudioStreams) {
				if (stream.isDefault) {
					matchingIndex = stream.index
					Timber.d("Best audio found on fallback (stream default)")
					break
				}
			}
		}

		if (matchingIndex == null) {
			// Fallback 2: Server default audio index
			matchingIndex = info.defaultAudioStreamIndex
			if (matchingIndex != null) {
				Timber.d("Best audio found on fallback (server default index: %d)", matchingIndex)
			}
		}

		if (matchingIndex == null && allAudioStreams.isNotEmpty()) {
			// Fallback 3: First audio stream
			matchingIndex = allAudioStreams[0].index
			Timber.d("Best audio found on fallback (first audio stream)")
		}

		Timber.i("Best audio found on index: %s for media: '%s'", matchingIndex, info.name)
		return matchingIndex
	}

	private fun matchSubtitleStream(
		streams: List<MediaStream>?,
		targetLang: String?,
		targetTitle: String?,
		targetCodec: String?,
		targetDefault: Boolean?,
		targetForced: Boolean?,
		targetHI: Boolean?,
	): Int? {
		if (streams.isNullOrEmpty() || targetLang.isNullOrEmpty()) return null

		// 1. targetLang + title + codec + forced + HI
		if (!targetTitle.isNullOrEmpty()) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					s.title != null &&
					targetTitle.equals(s.title, ignoreCase = true) &&
					(targetCodec == null || targetCodec.equals(s.codec, ignoreCase = true)) &&
					(targetForced == null || targetForced == s.isForced) &&
					(targetHI == null || targetHI == s.isHearingImpaired)
				) {
					Timber.d("Best sub found ! (lang+title+codec+forced+HI)")
					return s.index
				}
			}

			// 2. targetLang + title + forced (title match regardless of codec/HI)
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					s.title != null &&
					targetTitle.equals(s.title, ignoreCase = true) &&
					(targetForced == null || targetForced == s.isForced)
				) {
					Timber.d("Best sub found ! (lang+title+forced)")
					return s.index
				}
			}
		}

		// 4. targetLang + codec + default + forced + HI
		if (targetCodec != null && targetDefault != null && targetForced != null && targetHI != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetCodec.equals(s.codec, ignoreCase = true) &&
					targetDefault == s.isDefault &&
					targetForced == s.isForced &&
					targetHI == s.isHearingImpaired
				) {
					Timber.d("Best sub found ! (lang+codec+default+forced+HI)")
					return s.index
				}
			}
		}

		// 5. targetLang + codec + forced + HI
		if (targetCodec != null && targetForced != null && targetHI != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetCodec.equals(s.codec, ignoreCase = true) &&
					targetForced == s.isForced &&
					targetHI == s.isHearingImpaired
				) {
					Timber.d("Best sub found ! (lang+codec+forced+HI)")
					return s.index
				}
			}
		}

		// 6. targetLang + codec + forced
		if (targetCodec != null && targetForced != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetCodec.equals(s.codec, ignoreCase = true) &&
					targetForced == s.isForced
				) {
					Timber.d("Best sub found ! (lang+codec+forced)")
					return s.index
				}
			}
		}

		// 7. targetLang + forced + HI
		if (targetForced != null && targetHI != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetForced == s.isForced &&
					targetHI == s.isHearingImpaired
				) {
					Timber.d("Best sub found ! (lang+forced+HI)")
					return s.index
				}
			}
		}

		// 8. targetLang + forced
		if (targetForced != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetForced == s.isForced
				) {
					Timber.d("Best sub found ! (lang+forced)")
					return s.index
				}
			}
		}

		// 9. targetLang + default + forced
		if (targetDefault != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetDefault == s.isDefault &&
					(targetForced == null || targetForced == s.isForced)
				) {
					Timber.d("Best sub found ! (lang+default+forced)")
					return s.index
				}
			}
		}

		// 10. targetLang + HI + forced
		if (targetHI != null) {
			for (s in streams) {
				if (areLanguagesEqual(targetLang, s.language) &&
					targetHI == s.isHearingImpaired &&
					(targetForced == null || targetForced == s.isForced)
				) {
					Timber.d("Best sub found ! (lang+HI+forced)")
					return s.index
				}
			}
		}

		// 11. targetLang + forced
		for (s in streams) {
			if (areLanguagesEqual(targetLang, s.language) &&
				(targetForced == null || targetForced == s.isForced)
			) {
				Timber.d("Best sub found ! (lang+forced)")
				return s.index
			}
		}

		return null
	}

	fun getBestSubtitleIndex(info: MediaSourceInfo?, context: Context?): Int? {
		return getBestSubtitleIndex(info, context, null)
	}

	fun getBestSubtitleIndex(info: MediaSourceInfo?, context: Context?, currentAudioLanguage: String?): Int? {
		if (info == null || info.mediaStreams.isNullOrEmpty()) {
			return -1
		}

		val isLocal = LocalVideoManager.isLocalSource(info)
		val lastSubtitleLanguage = videoQueueManager.getLastPlayedSubtitleLanguageIsoCode()

		if (!isLocal && lastSubtitleLanguage == null) {
			val serverDefaultSub = info.defaultSubtitleStreamIndex
			if (serverDefaultSub != null) {
				return serverDefaultSub
			}
		}

		val allSubtitleStreams = info.mediaStreams?.filter { s -> s.type == MediaStreamType.SUBTITLE }.orEmpty()
		if (allSubtitleStreams.isEmpty()) {
			return -1
		}

		var audioLang = currentAudioLanguage

		if (audioLang == null) {
			val currentAudioIndex = getBestAudioIndex(info)
			if (currentAudioIndex != null) {
				for (stream in info.mediaStreams.orEmpty()) {
					if (stream.type == MediaStreamType.AUDIO && stream.index == currentAudioIndex) {
						audioLang = stream.language
						break
					}
				}
			}
		}
		if (audioLang == null) {
			audioLang = videoQueueManager.getLastPlayedAudioLanguageIsoCode()
		}

		val userAudioLangRemoteSetting = userPreferences[UserSettingPreferences.audioLangRemoteSetting]
		val userSubLangRemoteSetting = userPreferences[UserSettingPreferences.subLangRemoteSetting]
		val userSubMode = userPreferences[UserSettingPreferences.subMode]
		val userSubModeTitle = if (context != null) context.getString(userSubMode) else "Unknown"

		Timber.i("Remote user sub lang settings: %s", userSubLangRemoteSetting)
		Timber.i("Remote user sub mode settings: %s", userSubModeTitle)
		Timber.i("Current audio language: %s", audioLang)

		val lastSubtitleCodec = videoQueueManager.getLastPlayedSubtitleCodec()
		val lastSubtitleDefaultState = videoQueueManager.getLastPlayedSubtitleDefaultState()
		val lastSubtitleForcedState = videoQueueManager.getLastPlayedSubtitleForcedState()
		val lastSubtitleHearingImpairedState = videoQueueManager.getLastPlayedSubtitleHearingImpairedState()
		val lastSubtitleTitle = videoQueueManager.getLastPlayedSubtitleTitle()

		var matchingIndex: Int? = null

		// MODE NONE
		if (userSubMode == R.string.subtitle_mode_none) {
			Timber.i("Subtitle mode is NONE -> disabling subtitles")
			return -1
		}

		// MODE ONLY_FORCED
		if (userSubMode == R.string.subtitle_mode_only_forced) {
			if (!userSubLangRemoteSetting.isNullOrEmpty()) {
				matchingIndex = matchSubtitleStream(
					allSubtitleStreams,
					userSubLangRemoteSetting,
					lastSubtitleTitle,
					lastSubtitleCodec,
					lastSubtitleDefaultState,
					true,
					lastSubtitleHearingImpairedState
				)
			}
			if (matchingIndex == null && !audioLang.isNullOrEmpty()) {
				matchingIndex = matchSubtitleStream(
					allSubtitleStreams,
					audioLang,
					lastSubtitleTitle,
					lastSubtitleCodec,
					lastSubtitleDefaultState,
					true,
					lastSubtitleHearingImpairedState
				)
			}
			if (matchingIndex == null && !lastSubtitleLanguage.isNullOrEmpty()) {
				matchingIndex = matchSubtitleStream(
					allSubtitleStreams,
					lastSubtitleLanguage,
					lastSubtitleTitle,
					lastSubtitleCodec,
					lastSubtitleDefaultState,
					true,
					lastSubtitleHearingImpairedState
				)
			}
			if (matchingIndex == null) {
				for (stream in allSubtitleStreams) {
					if (stream.isForced && (areLanguagesEqual(userSubLangRemoteSetting, stream.language) || areLanguagesEqual(audioLang, stream.language))) {
						Timber.d("Best sub found (ONLY_FORCED fallback in preferred language)")
						matchingIndex = stream.index
						break
					}
				}
			}
			return matchingIndex ?: -1
		}

		// MODE ALWAYS
		if (userSubMode == R.string.subtitle_mode_always) {
			matchingIndex = matchSubtitleStream(
				allSubtitleStreams,
				userSubLangRemoteSetting,
				lastSubtitleTitle,
				lastSubtitleCodec,
				lastSubtitleDefaultState,
				lastSubtitleForcedState,
				lastSubtitleHearingImpairedState
			)
			if (matchingIndex == null) {
				matchingIndex = matchSubtitleStream(
					allSubtitleStreams,
					lastSubtitleLanguage,
					lastSubtitleTitle,
					lastSubtitleCodec,
					lastSubtitleDefaultState,
					lastSubtitleForcedState,
					lastSubtitleHearingImpairedState
				)
			}
			if (matchingIndex == null) {
				for (s in allSubtitleStreams) {
					if (s.isDefault) {
						matchingIndex = s.index
						break
					}
				}
			}
			if (matchingIndex == null && allSubtitleStreams.isNotEmpty()) {
				matchingIndex = allSubtitleStreams[0].index
			}
			return matchingIndex ?: -1
		}

		// MODE SMART
		if (userSubMode == R.string.subtitle_mode_smart) {
			val audioMatchesUserPreference = userAudioLangRemoteSetting.isEmpty() ||
				audioLang == null ||
				"und".equals(audioLang, ignoreCase = true) ||
				"null".equals(audioLang, ignoreCase = true) ||
				areLanguagesEqual(audioLang, userAudioLangRemoteSetting)

			if (audioMatchesUserPreference) {
				Timber.d("SMART mode: Audio matches user preference (%s) -> Checking for forced subtitles only", audioLang)

				if (!userSubLangRemoteSetting.isNullOrEmpty()) {
					matchingIndex = matchSubtitleStream(
						allSubtitleStreams,
						userSubLangRemoteSetting,
						lastSubtitleTitle,
						lastSubtitleCodec,
						lastSubtitleDefaultState,
						true,
						lastSubtitleHearingImpairedState
					)
				}

				if (matchingIndex == null && !audioLang.isNullOrEmpty()) {
					matchingIndex = matchSubtitleStream(
						allSubtitleStreams,
						audioLang,
						lastSubtitleTitle,
						lastSubtitleCodec,
						lastSubtitleDefaultState,
						true,
						lastSubtitleHearingImpairedState
					)
				}

				if (matchingIndex == null && !lastSubtitleLanguage.isNullOrEmpty()) {
					matchingIndex = matchSubtitleStream(
						allSubtitleStreams,
						lastSubtitleLanguage,
						lastSubtitleTitle,
						lastSubtitleCodec,
						lastSubtitleDefaultState,
						true,
						lastSubtitleHearingImpairedState
					)
				}

				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (s.isForced && areLanguagesEqual(userSubLangRemoteSetting, s.language)) {
							matchingIndex = s.index
							Timber.d("SMART mode: Found forced subtitle in user sub lang (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (s.isForced && areLanguagesEqual(audioLang, s.language)) {
							matchingIndex = s.index
							Timber.d("SMART mode: Found forced subtitle in audio lang (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (s.isForced) {
							matchingIndex = s.index
							Timber.d("SMART mode: Found generic forced subtitle (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null) {
					Timber.d("SMART mode: No forced subtitle found -> disabling subtitles (-1)")
					return -1
				}
			} else {
				Timber.d("SMART mode: Audio (%s) differs from user preference (%s) -> Full subtitles required", audioLang, userAudioLangRemoteSetting)
				matchingIndex = matchSubtitleStream(
					allSubtitleStreams,
					userSubLangRemoteSetting,
					lastSubtitleTitle,
					lastSubtitleCodec,
					lastSubtitleDefaultState,
					false,
					lastSubtitleHearingImpairedState
				)

				if (matchingIndex == null && !lastSubtitleLanguage.isNullOrEmpty()) {
					matchingIndex = matchSubtitleStream(
						allSubtitleStreams,
						lastSubtitleLanguage,
						lastSubtitleTitle,
						lastSubtitleCodec,
						lastSubtitleDefaultState,
						false,
						lastSubtitleHearingImpairedState
					)
				}

				if (matchingIndex == null && !userSubLangRemoteSetting.isNullOrEmpty()) {
					for (s in allSubtitleStreams) {
						if (areLanguagesEqual(userSubLangRemoteSetting, s.language) && !s.isForced) {
							matchingIndex = s.index
							Timber.d("SMART mode fallback: Found full subtitle in user sub lang (%d)", matchingIndex)
							break
						}
					}
				}

				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (s.isDefault && !s.isForced) {
							matchingIndex = s.index
							Timber.d("SMART mode fallback: Default full subtitle found (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (s.isDefault) {
							matchingIndex = s.index
							Timber.d("SMART mode fallback: Default subtitle found (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null) {
					for (s in allSubtitleStreams) {
						if (!s.isForced) {
							matchingIndex = s.index
							Timber.d("SMART mode fallback: First full subtitle found (%d)", matchingIndex)
							break
						}
					}
				}
				if (matchingIndex == null && allSubtitleStreams.isNotEmpty()) {
					matchingIndex = allSubtitleStreams[0].index
					Timber.d("SMART mode fallback: First available subtitle found (%d)", matchingIndex)
				}
			}
			return matchingIndex ?: -1
		}

		// MODE DEFAULT / Queue persistence
		if ("" == lastSubtitleLanguage) {
			Timber.i("User explicitly disabled subtitles on previous video -> keeping disabled (-1)")
			return -1
		}

		if (!lastSubtitleLanguage.isNullOrEmpty()) {
			matchingIndex = matchSubtitleStream(
				allSubtitleStreams,
				lastSubtitleLanguage,
				lastSubtitleTitle,
				lastSubtitleCodec,
				lastSubtitleDefaultState,
				lastSubtitleForcedState,
				lastSubtitleHearingImpairedState
			)
		}

		if (matchingIndex == null) {
			matchingIndex = info.defaultSubtitleStreamIndex
			if (matchingIndex != null && matchingIndex != -1) {
				Timber.d("Best subtitle found on server default index: %d", matchingIndex)
			} else {
				matchingIndex = null
			}
		}

		if (matchingIndex == null) {
			for (s in allSubtitleStreams) {
				if (s.isDefault && !s.isForced) {
					matchingIndex = s.index
					Timber.d("Best subtitle found on default full stream: %d", matchingIndex)
					break
				}
			}
		}

		if (matchingIndex == null) {
			for (s in allSubtitleStreams) {
				if (s.isDefault) {
					matchingIndex = s.index
					Timber.d("Best subtitle found on default stream: %d", matchingIndex)
					break
				}
			}
		}

		if (matchingIndex == null) {
			for (s in allSubtitleStreams) {
				if (!s.isForced) {
					matchingIndex = s.index
					Timber.d("Best subtitle found on first full stream: %d", matchingIndex)
					break
				}
			}
		}

		if (matchingIndex == null) {
			matchingIndex = -1
		}

		Timber.i("Best subtitle found on index: %d for media: '%s'", matchingIndex, info.name)
		return matchingIndex
	}

	fun getBestVideoIndex(info: MediaSourceInfo?): Int? {
		if (info == null || info.mediaStreams.isNullOrEmpty()) return null

		val allVideoStreams = info.mediaStreams?.filter { stream -> stream.type == MediaStreamType.VIDEO }.orEmpty()
		if (allVideoStreams.isEmpty()) return null

		val lastVideoTitle = videoQueueManager.getLastPlayedVideoTitle()
		val lastVideoCodec = videoQueueManager.getLastPlayedVideoCodec()
		val lastVideoDefaultState = videoQueueManager.getLastPlayedVideoDefaultState()
		val lastVideoIndexInType = videoQueueManager.getLastPlayedVideoIndexInType()

		// 1. Title + Codec match
		if (lastVideoTitle != null && lastVideoCodec != null) {
			for (s in allVideoStreams) {
				if (lastVideoTitle.equals(s.title, ignoreCase = true) && lastVideoCodec.equals(s.codec, ignoreCase = true)) {
					Timber.d("Best video found (title+codec match): %d", s.index)
					return s.index
				}
			}
		}

		// 2. Index in video type match
		if (lastVideoIndexInType != null && lastVideoIndexInType >= 0 && lastVideoIndexInType < allVideoStreams.size) {
			val candidate = allVideoStreams[lastVideoIndexInType]
			Timber.d("Best video found (indexInType match): %d", candidate.index)
			return candidate.index
		}

		// 3. Match by Default State
		if (lastVideoDefaultState != null) {
			for (stream in allVideoStreams) {
				if (lastVideoDefaultState == stream.isDefault) {
					Timber.d("Best video found (defaultState match): %d", stream.index)
					return stream.index
				}
			}
		}

		// Fallback 1: Stream marked default
		for (stream in allVideoStreams) {
			if (stream.isDefault) {
				Timber.d("Best video found (fallback stream default): %d", stream.index)
				return stream.index
			}
		}

		// Fallback 2: First video stream
		val fallbackIndex = allVideoStreams[0].index
		Timber.d("Best video found (fallback first stream): %d", fallbackIndex)
		return if (fallbackIndex >= 0) fallbackIndex else null
	}
}
