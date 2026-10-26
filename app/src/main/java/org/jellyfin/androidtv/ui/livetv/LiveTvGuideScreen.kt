package org.jellyfin.androidtv.ui.livetv

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Composable
fun LiveTvGuideScreen(
    viewModel: LiveTvGuideViewModel,
    getImageUrl: (BaseItemDto) -> String?,
    onProgramSelected: (BaseItemDto) -> Unit,
    onProgramClicked: (BaseItemDto) -> Unit,
    onOptionsClicked: () -> Unit,
    onFiltersClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val context = LocalContext.current
    var selectedDetailProgram by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<BaseItemDto?>(null) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(colorResource(R.color.program_scroller_bg))) {
            // Timeline Header
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                // Placeholder above Channel list with Options/Filters buttons
                Row(
                    modifier = Modifier
                        .width(160.dp)
                        .height(30.dp)
                        .background(colorResource(R.color.timeline_bg)),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.tv.material3.Button(onClick = onFiltersClicked) {
                        androidx.tv.material3.Text(text = "Filters", fontSize = 10.sp)
                    }
                    androidx.tv.material3.Button(onClick = onOptionsClicked) {
                        androidx.tv.material3.Text(text = "Options", fontSize = 10.sp)
                    }
                }

                // Timeline
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState, enabled = false)
                ) {
                    TimelineRow(
                        startTime = state.guideStart,
                        hours = state.guideHours
                    )
                }
            }

            // Main Grid (Channels and Programs)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(state.displayedChannels, key = { _, channel -> channel.id.toString() }) { index, channel ->
                    Row {
                        val isFavorite = channel.userData?.isFavorite == true
                        ChannelHeader(
                            channel = channel,
                            isFavorite = isFavorite,
                            imageUrl = getImageUrl(channel)
                        )

                        // Programs for this channel
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScrollState, enabled = false)
                        ) {
                            val programs = state.programsByChannel[channel.id?.toString()] ?: emptyList()
                            ProgramRow(
                                programs = programs,
                                channelId = channel.id,
                                guideStart = state.guideStart,
                                guideEnd = state.guideEnd,
                                onProgramSelected = {
                                    viewModel.setSelectedProgram(it)
                                    onProgramSelected(it)
                                },
                                onProgramClicked = { program ->
                                    if (program.id != null) {
                                        selectedDetailProgram = program
                                    } else {
                                        onProgramClicked(program)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        ProgramDetailDialog(
            program = selectedDetailProgram,
            visible = selectedDetailProgram != null,
            onDismissRequest = { selectedDetailProgram = null },
            onPlayClicked = { program ->
                onProgramClicked(program)
            },
            onRecordClicked = { program ->
                // Record
            },
            onRecordSeriesClicked = { program ->
                // Record Series
            },
            onCancelRecordClicked = { program ->
                // Cancel
            }
        )
    }
}

@Composable
private fun ProgramRow(
    programs: List<BaseItemDto>,
    channelId: UUID?,
    guideStart: LocalDateTime,
    guideEnd: LocalDateTime,
    onProgramSelected: (BaseItemDto) -> Unit,
    onProgramClicked: (BaseItemDto) -> Unit
) {
    Row {
        var currentCursor = guideStart
        val context = LocalContext.current

        for (program in programs) {
            val start = program.startDate ?: currentCursor
            var effectiveStart = start
            if (effectiveStart.isBefore(guideStart)) {
                effectiveStart = guideStart
            }

            // Fill empty gap before this program
            if (effectiveStart.isAfter(currentCursor)) {
                val gapMinutes = ChronoUnit.MINUTES.between(currentCursor, effectiveStart).toInt()
                if (gapMinutes > 0) {
                    val emptyProgram = createNoProgramDataBaseItem(context, channelId, currentCursor, effectiveStart)
                    ProgramCell(
                        program = emptyProgram,
                        durationMinutes = gapMinutes,
                        isStartCut = false,
                        onClick = { },
                        onFocus = { }
                    )
                }
            }

            val end = program.endDate ?: guideEnd
            var effectiveEnd = end
            if (effectiveEnd.isAfter(guideEnd)) {
                effectiveEnd = guideEnd
            }

            val durationMinutes = ChronoUnit.MINUTES.between(effectiveStart, effectiveEnd).toInt()
            if (durationMinutes > 0) {
                val isStartCut = start.isBefore(guideStart)
                ProgramCell(
                    program = program,
                    durationMinutes = durationMinutes,
                    isStartCut = isStartCut,
                    onClick = { onProgramClicked(program) },
                    onFocus = { onProgramSelected(program) }
                )
            }

            currentCursor = effectiveEnd
        }

        // Fill empty gap at the end
        if (currentCursor.isBefore(guideEnd)) {
            val gapMinutes = ChronoUnit.MINUTES.between(currentCursor, guideEnd).toInt()
            if (gapMinutes > 0) {
                val emptyProgram = createNoProgramDataBaseItem(context, channelId, currentCursor, guideEnd)
                ProgramCell(
                    program = emptyProgram,
                    durationMinutes = gapMinutes,
                    isStartCut = false,
                    onClick = { },
                    onFocus = { }
                )
            }
        }
    }
}
