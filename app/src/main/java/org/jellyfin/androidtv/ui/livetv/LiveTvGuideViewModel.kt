package org.jellyfin.androidtv.ui.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

class LiveTvGuideViewModel(
    private val apiClient: ApiClient,
    private val liveTvPreferences: LiveTvPreferences,
    private val systemPreferences: SystemPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(LiveTvGuideState())
    val state: StateFlow<LiveTvGuideState> = _state.asStateFlow()

    private val pageSize = 75

    fun load() {
        val guideStart = LocalDateTime.now()
        val guideHours = if (hasActiveFilters()) 4 else 9
        val guideEnd = guideStart.plusHours(guideHours.toLong())

        _state.update {
            it.copy(
                isLoading = true,
                guideStart = guideStart,
                guideEnd = guideEnd,
                guideHours = guideHours,
                hasActiveFilters = hasActiveFilters()
            )
        }

        viewModelScope.launch {
            val channels = fetchChannels()
            if (channels.isEmpty()) {
                _state.update { it.copy(isLoading = false, allChannels = emptyList(), displayedChannels = emptyList()) }
                return@launch
            }

            // Determine where to start based on the last live TV channel
            val lastChannelIdStr = systemPreferences.get(SystemPreferences.liveTvLastChannel)
            val lastChannelId = lastChannelIdStr.toUUIDOrNull()

            var startNdx = 0
            if (lastChannelId != null) {
                val index = channels.indexOfFirst { it.id == lastChannelId }
                if (index >= pageSize) {
                    startNdx = index - (pageSize / 2)
                }
            }

            _state.update {
                it.copy(
                    allChannels = channels,
                    forceFocusChannelId = lastChannelId?.toString()
                )
            }

            displayChannels(startNdx, pageSize)
        }
    }

    private suspend fun fetchChannels(): List<BaseItemDto> {
        val sortDatePlayed = liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName
        return runCatching {
            withContext(Dispatchers.IO) {
                apiClient.liveTvApi.getLiveTvChannels(
                    addCurrentProgram = true,
                    enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
                    sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
                    sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
                ).content.items.toList()
            }
        }.getOrDefault(emptyList())
    }

    fun displayChannels(start: Int, max: Int) {
        val channels = _state.value.allChannels
        if (channels.isEmpty()) return

        var end = start + max
        if (end > channels.size) {
            end = channels.size
        }

        val filtersActive = _state.value.hasActiveFilters
        val startNdx = if (filtersActive) 0 else start
        val endNdx = if (filtersActive) channels.size - 1 else end - 1

        val currentStart = _state.value.guideStart
        val currentEnd = _state.value.guideEnd

        _state.update {
            it.copy(
                isLoading = true,
                startIndex = startNdx,
                endIndex = endNdx,
                displayedChannels = channels.subList(startNdx, endNdx + 1)
            )
        }

        val channelIds = channels.subList(startNdx, endNdx + 1).mapNotNull { it.id }.toTypedArray()

        viewModelScope.launch {
            val programs = fetchPrograms(channelIds, currentStart, currentEnd)
            val programsDict = buildProgramsDict(programs, currentStart)
            _state.update {
                it.copy(
                    isLoading = false,
                    programsByChannel = programsDict
                )
            }
        }
    }

    private suspend fun fetchPrograms(channelIds: Array<UUID>, startTime: LocalDateTime, endTime: LocalDateTime): List<BaseItemDto> {
        val startTimeRounded = startTime.withMinute(if (startTime.minute >= 30) 30 else 0).withSecond(0).withNano(0)
        val endTimeRounded = endTime.minusSeconds(1)

        return runCatching {
            withContext(Dispatchers.IO) {
                apiClient.liveTvApi.getLiveTvPrograms(
                    channelIds = channelIds.toList(),
                    enableImages = false,
                    sortBy = setOf(ItemSortBy.START_DATE),
                    maxStartDate = endTimeRounded,
                    minEndDate = startTimeRounded,
                ).content.items.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun buildProgramsDict(programs: List<BaseItemDto>, startTime: LocalDateTime): Map<String, List<BaseItemDto>> {
        val dict = mutableMapOf<String, MutableList<BaseItemDto>>()
        val hasFilters = hasActiveFilters()
        for (program in programs) {
            val channelId = program.channelId?.toString() ?: continue
            val endDate = program.endDate ?: continue
            if (endDate.isAfter(startTime)) {
                if (!hasFilters || passesFilter(program)) {
                    dict.getOrPut(channelId) { mutableListOf() }.add(program)
                }
            }
        }
        return dict
    }

    private fun passesFilter(program: BaseItemDto): Boolean {
        val prefs = systemPreferences
        val movies = prefs.get(SystemPreferences.liveTvGuideFilterMovies)
        val news = prefs.get(SystemPreferences.liveTvGuideFilterNews)
        val series = prefs.get(SystemPreferences.liveTvGuideFilterSeries)
        val kids = prefs.get(SystemPreferences.liveTvGuideFilterKids)
        val sports = prefs.get(SystemPreferences.liveTvGuideFilterSports)
        val premiere = prefs.get(SystemPreferences.liveTvGuideFilterPremiere)

        if (movies && program.isMovie == true) return !premiere || program.isPremiere == true
        if (news && program.isNews == true) return !premiere || program.isPremiere == true || program.isLive == true || program.isRepeat != true
        if (series && program.isSeries == true) return !premiere || program.isPremiere == true
        if (kids && program.isKids == true) return !premiere || program.isPremiere == true
        if (sports && program.isSports == true) return !premiere || program.isPremiere == true || program.isLive == true

        return premiere && program.isPremiere == true
    }

    fun pageGuideTo(startTime: LocalDateTime) {
        var start = startTime
        if (start.isBefore(LocalDateTime.now())) {
            start = LocalDateTime.now()
        }

        val guideHours = _state.value.guideHours
        val end = start.plusHours(guideHours.toLong())

        _state.update {
            it.copy(
                guideStart = start,
                guideEnd = end,
                isLoading = true
            )
        }

        // Re-fetch programs for currently displayed channels
        displayChannels(_state.value.startIndex, pageSize)
    }

    fun setSelectedProgram(program: BaseItemDto) {
        _state.update { it.copy(selectedProgram = program) }
    }

    private fun hasActiveFilters(): Boolean {
        val prefs = systemPreferences
        return prefs.get(SystemPreferences.liveTvGuideFilterMovies) ||
               prefs.get(SystemPreferences.liveTvGuideFilterNews) ||
               prefs.get(SystemPreferences.liveTvGuideFilterSeries) ||
               prefs.get(SystemPreferences.liveTvGuideFilterKids) ||
               prefs.get(SystemPreferences.liveTvGuideFilterSports) ||
               prefs.get(SystemPreferences.liveTvGuideFilterPremiere)
    }
}
