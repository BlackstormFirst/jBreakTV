package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime

data class LiveTvGuideState(
    val isLoading: Boolean = false,
    val guideStart: LocalDateTime = LocalDateTime.now(),
    val guideEnd: LocalDateTime = LocalDateTime.now().plusHours(9),
    val guideHours: Int = 9,
    val allChannels: List<BaseItemDto> = emptyList(),
    val displayedChannels: List<BaseItemDto> = emptyList(),
    val programsByChannel: Map<String, List<BaseItemDto>> = emptyMap(),
    val selectedProgram: BaseItemDto? = null,
    val hasActiveFilters: Boolean = false,
    val startIndex: Int = 0,
    val endIndex: Int = 0,
    val forceFocusChannelId: String? = null
)
