package org.jellyfin.androidtv.ui.itemdetail

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jellyfin.androidtv.data.service.BackgroundService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.base.popover.PopoverMenu
import org.jellyfin.androidtv.ui.base.popover.PopoverMenuCheckboxItem
import androidx.compose.foundation.basicMarquee
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.util.sdk.getSeasonEpisodeName
import org.jellyfin.androidtv.util.sdk.isNonexistentOrVirtualMedia
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
import org.jellyfin.androidtv.ui.home.collectAsStateAny
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.presentation.composable.BaseRowItemCard
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import org.koin.compose.koinInject
import java.util.Locale
import java.util.UUID

/**
 * BringIntoViewSpec for 10-foot TV vertical LazyColumn navigation matching Leanback VerticalGridView:
 * - Aligns focused row at fixed ideal pivot (40% from bottom of screen = 60% from top of screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberTvPivotBringIntoViewSpec(): BringIntoViewSpec {
    return remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val targetTop = containerSize * 0.63f
                return offset - targetTop
            }
        }
    }
}

/**
 * BringIntoViewSpec for 10-foot TV horizontal LazyRow navigation matching Leanback HorizontalGridView:
 * - Keeps focused items within specified padding bounds during horizontal scrolling (both left-to-right and right-to-left).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberHorizontalBringIntoViewSpec(paddingPx: Float = 0f): BringIntoViewSpec {
    return remember(paddingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                val trailingEdge = offset + size
                val minVisible = paddingPx
                val maxVisible = containerSize - paddingPx

                return when {
                    offset < minVisible -> offset - minVisible
                    trailingEdge > maxVisible -> trailingEdge - maxVisible
                    else -> 0f
                }
            }
        }
    }
}

/**
 * Composable matching ExpandableTextView.kt and view_row_details.xml:
 * Fixed 120dp / 180dp height matching Leanback's layout_height="0dp" between genre and left info items.
 * Displays overview text up to maxLines (6 or 9 for Person). If ellipsized, it becomes focusable and opens a full overlay dialog on click.
 */
@Composable
fun ExpandableOverviewText(
    overviewText: String?,
    maxLines: Int = 6,
    modifier: Modifier = Modifier
) {
    var isEllipsized by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    val boxHeight = if (maxLines > 6) 180.dp else 120.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(boxHeight)
            .then(
                if (isEllipsized) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isFocused) JellyfinTheme.colorScheme.buttonFocused.copy(alpha = 0.2f) else Color.Transparent)
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) JellyfinTheme.colorScheme.buttonFocused else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 2.dp)
                        .onFocusChanged { isFocused = it.isFocused }
                        .clickable { showDialog = true }
                } else Modifier
            )
    ) {
        if (!overviewText.isNullOrBlank()) {
            Text(
                text = overviewText,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White
                ),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { textLayoutResult ->
                    if (textLayoutResult.hasVisualOverflow) {
                        isEllipsized = true
                    }
                }
            )
        }
    }

    if (showDialog && !overviewText.isNullOrBlank()) {
        val scrollState = rememberScrollState()
        val focusRequester = remember { FocusRequester() }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(600.dp)
                        .height(400.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JellyfinTheme.colorScheme.surface)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        coroutineScope.launch {
                                            val target = (scrollState.value + 120).coerceAtMost(scrollState.maxValue)
                                            scrollState.animateScrollTo(target)
                                        }
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        coroutineScope.launch {
                                            val target = (scrollState.value - 120).coerceAtLeast(0)
                                            scrollState.animateScrollTo(target)
                                        }
                                        true
                                    }
                                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                        showDialog = false
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = overviewText,
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White
                            )
                        )
                    }
                }

                // Close hint at bottom matching popup_expandable_text_view.xml
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_control_select),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.press_to_close),
                        style = TextStyle(fontSize = 14.sp, color = Color.White)
                    )
                }
            }
        }
    }
}

/**
 * TextUnderButton composable matching layout/text_under_button.xml
 */
@Composable
fun TextUnderButton(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    onFocused: () -> Unit = {},
    modifier: Modifier = Modifier,
    isActivated: Boolean = false,
    activeIconColor: Color? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val buttonBackgroundColor = if (isFocused) {
        JellyfinTheme.colorScheme.buttonFocused
    } else if (isActivated) {
        JellyfinTheme.colorScheme.buttonActive
    } else {
        colorResource(R.color.button_default_normal_background)
    }

    val effectiveActiveIconColor = activeIconColor ?: when (iconRes) {
        R.drawable.ic_check -> Color.Red
        R.drawable.ic_star -> Color(0xFFFFD700)
        else -> null
    }

    val iconColor = if (isActivated && effectiveActiveIconColor != null) {
        effectiveActiveIconColor
    } else if (isFocused) {
        JellyfinTheme.colorScheme.onButtonFocused
    } else {
        Color.White
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(85.dp)
            .height(75.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(70.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(buttonBackgroundColor)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = label,
            style = TextStyle(
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MediaInfoRowText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isMarquee: Boolean = false,
    isFocused: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.6f)
            )
        )
        Spacer(Modifier.width(4.dp))
        val marqueeModifier = if (isMarquee && isFocused) {
            Modifier.basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 800
            )
        } else {
            Modifier
        }
        Text(
            text = value,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.End
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .then(marqueeModifier)
        )
    }
}

@Composable
fun MediaStreamCard(
    stream: MediaStream,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val cardBg = if (isFocused) {
        JellyfinTheme.colorScheme.listButtonFocused
    } else {
        JellyfinTheme.colorScheme.surface
    }

    val borderColor = if (isFocused) {
        JellyfinTheme.colorScheme.buttonFocused
    } else {
        Color.Transparent
    }

    val streamTypeTitle = when (stream.type) {
        MediaStreamType.VIDEO -> stringResource(R.string.playback_info_video_title)
        MediaStreamType.AUDIO -> stringResource(R.string.playback_info_audio_title)
        MediaStreamType.SUBTITLE -> stringResource(R.string.playback_info_subtitle_title)
        else -> stream.type.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    val streamTitle = stream.title?.ifBlank { null }

    Box(
        modifier = modifier
            .width(180.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .focusable(interactionSource = interactionSource)
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header (e.g. "VIDÉO", "AUDIO", "SOUS-TITRES")
                Text(
                    text = streamTypeTitle.uppercase(Locale.ROOT),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = JellyfinTheme.colorScheme.buttonFocused
                    )
                )

                Spacer(Modifier.height(2.dp))

                // Language
                if (!stream.language.isNullOrBlank() && stream.type != MediaStreamType.VIDEO) {
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_language),
                        value = stream.language?.uppercase(Locale.ROOT) ?: "",
                        isFocused = isFocused
                    )
                }

                // Codec
                if (!stream.codec.isNullOrBlank()) {
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_codec),
                        value = stream.codec?.uppercase(Locale.ROOT) ?: "",
                        isFocused = isFocused
                    )
                }

                // Subtitle Titre (below Codec for subtitles)
                if (stream.type == MediaStreamType.SUBTITLE && streamTitle != null) {
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_title),
                        value = streamTitle,
                        isMarquee = true,
                        isFocused = isFocused
                    )
                }

                // Profile / Level
                if (!stream.profile.isNullOrBlank()) {
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_profile),
                        value = stream.profile ?: "",
                        isFocused = isFocused
                    )
                }

                // Plage vidéo (below Profile for video streams)
                if (stream.type == MediaStreamType.VIDEO) {
                    val isHdr = stream.videoRange == VideoRange.HDR ||
                        stream.videoRangeType != VideoRangeType.SDR
                    val videoRangeStr = if (isHdr) "HDR" else "SDR"
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_video_range),
                        value = videoRangeStr,
                        isFocused = isFocused
                    )
                }

                // Specific properties by Stream Type
                when (stream.type) {
                    MediaStreamType.VIDEO -> {
                        if (stream.width != null && stream.height != null) {
                            MediaInfoRowText(
                                label = stringResource(R.string.lbl_resolution),
                                value = "${stream.width}x${stream.height}",
                                isFocused = isFocused
                            )
                        }
                        if (stream.realFrameRate != null && stream.realFrameRate!! > 0f) {
                            MediaInfoRowText(
                                label = stringResource(R.string.lbl_framerate),
                                value = "%.3f fps".format(Locale.US, stream.realFrameRate),
                                isFocused = isFocused
                            )
                        }
                        if (stream.aspectRatio != null) {
                            MediaInfoRowText(
                                label = stringResource(R.string.lbl_aspect_ratio),
                                value = stream.aspectRatio ?: "",
                                isFocused = isFocused
                            )
                        }
                    }

                    MediaStreamType.AUDIO -> {
                        if (!stream.channelLayout.isNullOrBlank()) {
                            MediaInfoRowText(
                                label = stringResource(R.string.lbl_audio_layout),
                                value = stream.channelLayout?.uppercase(Locale.ROOT) ?: "",
                                isFocused = isFocused
                            )
                        } else if (stream.channels != null) {
                            MediaInfoRowText(
                                label = stringResource(R.string.channels),
                                value = "${stream.channels} ch",
                                isFocused = isFocused
                            )
                        }
                    }

                    else -> {}
                }

                // Bitrate
                if (stream.bitRate != null && stream.bitRate!! > 0) {
                    val kbps = stream.bitRate!! / 1024
                    val bitrateStr = if (kbps >= 1000) {
                        "%.1f Mbps".format(Locale.US, kbps / 1024f)
                    } else {
                        "$kbps kbps"
                    }
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_bitrate),
                        value = bitrateStr,
                        isFocused = isFocused
                    )
                }

                // Video and Audio Titre (below Bitrate for video and audio streams)
                if ((stream.type == MediaStreamType.VIDEO || stream.type == MediaStreamType.AUDIO) && streamTitle != null) {
                    MediaInfoRowText(
                        label = stringResource(R.string.lbl_title),
                        value = streamTitle,
                        isMarquee = true,
                        isFocused = isFocused
                    )
                }
            }

            // Flags at bottom of card (Default, Forced, External, SDH)
            val flags = mutableListOf<String>()
            if (stream.isDefault == true) flags.add(stringResource(R.string.indicator_default))
            if (stream.isForced == true) flags.add(stringResource(R.string.indicator_forced))
            if (stream.isExternal == true) flags.add(stringResource(R.string.indicator_external))
            if (stream.isHearingImpaired == true) flags.add(stringResource(R.string.indicator_subtitles_hearing_impaired))

            if (flags.isNotEmpty()) {
                Text(
                    text = flags.joinToString(" • "),
                    style = TextStyle(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaDetailsRow(
    streams: List<MediaStream>,
    modifier: Modifier = Modifier
) {
    val displayStreams = remember(streams) {
        streams
            .filter { it.type == MediaStreamType.VIDEO || it.type == MediaStreamType.AUDIO || it.type == MediaStreamType.SUBTITLE }
            .distinctBy { "${it.type}_${it.index}_${it.codec}_${it.language}" }
    }

    if (displayStreams.isEmpty()) return

    val density = LocalDensity.current
    val horizontalBringIntoViewSpec = rememberHorizontalBringIntoViewSpec(paddingPx = with(density) { 56.dp.toPx() })

    Column(
        modifier = modifier.focusProperties { down = FocusRequester.Cancel }
    ) {
        Text(
            text = stringResource(R.string.lbl_media_details),
            style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
            modifier = Modifier.padding(start = 56.dp, end = 56.dp, bottom = 12.dp)
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                modifier = Modifier
                    .focusRestorer()
                    .focusProperties { down = FocusRequester.Cancel },
                contentPadding = PaddingValues(horizontal = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
                    displayStreams,
                    key = { idx, stream -> "media_stream_${stream.type}_${stream.index}_$idx" }
                ) { index, stream ->
                    val isFirst = index == 0
                    val isLast = index == displayStreams.lastIndex

                    MediaStreamCard(
                        stream = stream,
                        modifier = Modifier.focusProperties {
                            down = FocusRequester.Cancel
                            if (isFirst) left = FocusRequester.Cancel
                            if (isLast) right = FocusRequester.Cancel
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoDetailsScreen(
    state: FullDetailsUiState,
    onPlayClick: () -> Unit,
    onResumeClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onPlayedClick: () -> Unit,
    onTrailerClick: () -> Unit,
    onQueueClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onPreviousEpisodeClick: (UUID) -> Unit,
    onGoToSeriesClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onHomeClick: () -> Unit,
    onItemClick: (BaseRowItem) -> Unit,
    onSelectVersion: (MediaSourceInfo) -> Unit = {},
    onRefreshItem: () -> Unit = {}
) {
    val imageHelper = koinInject<ImageHelper>()
    val item = state.item ?: return

    var showVersionPicker by remember { mutableStateOf(false) }

    val rowData = remember(state.rows) { mutableStateMapOf<Int, List<BaseRowItem>>() }

    LaunchedEffect(state.rows) {
        state.rows.forEachIndexed { rowIndex, rowState ->
            val adapter = rowState.adapter
            val updateLoaded = {
                val loaded = (0 until adapter.size()).mapNotNull { adapter.get(it) as? BaseRowItem }
                if (loaded.isNotEmpty()) {
                    rowData[rowIndex] = loaded
                }
            }
            adapter.setRetrieveFinishedListener {
                updateLoaded()
            }
            updateLoaded()
        }
    }

    val activeContentRows = remember(state.rows, rowData.toMap()) {
        state.rows.mapIndexedNotNull { rowIndex, rowState ->
            val items = rowData[rowIndex] ?: rowState.adapter.dataAsStateList.filterIsInstance<BaseRowItem>()
            if (items.isNotEmpty()) {
                rowState to items
            } else null
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val tvPivotBringIntoViewSpec = rememberTvPivotBringIntoViewSpec()
    val actionButtonsBringIntoViewSpec = rememberHorizontalBringIntoViewSpec(paddingPx = with(density) { 75.dp.toPx() })
    val contentRowsBringIntoViewSpec = rememberHorizontalBringIntoViewSpec(paddingPx = with(density) { 56.dp.toPx() })

    val actionButtonsFocusRequester = remember { FocusRequester() }
    val initialPlayButtonFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        initialFocusRequested = false
        delay(100)
        initialPlayButtonFocusRequester.safeRequestFocus()
    }

    val initialFocusModifier = Modifier
        .focusRequester(initialPlayButtonFocusRequester)
        .onGloballyPositioned {
            if (!initialFocusRequested) {
                if (initialPlayButtonFocusRequester.safeRequestFocus()) {
                    initialFocusRequested = true
                }
            }
        }

    val isPerson = remember(item) {
        item.type == BaseItemKind.PERSON
    }

    val isNonexistentMedia = remember(item) {
        item.isNonexistentOrVirtualMedia()
    }

    var initialFocusAssigned by remember(item.id) { mutableStateOf(false) }
    val getInitialFocusModifier = {
        if (!initialFocusAssigned) {
            initialFocusAssigned = true
            initialFocusModifier
        } else {
            Modifier
        }
    }

    // Smoothly scroll LazyColumn back to top (item 0, offset 0) whenever an action button receives focus from below
    val scrollToHeaderTop = {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(0, 0)
            }
        }
    }

    // Function to navigate down from Home button to Action Buttons LazyRow (restoring previous button focus via focusRestorer)
    val navigateDownFromHome = {
        coroutineScope.launch {
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
                listState.animateScrollToItem(0, 0)
            }
            delay(50)
            actionButtonsFocusRequester.safeRequestFocus()
        }
    }

    // Leanback logic: Try logo image first, fallback to primary poster image
    val rightImageUrl = remember(item) {
        imageHelper.getLogoImageUrl(item, maxWidth = 600)
            ?: imageHelper.getPrimaryImageUrl(item, preferParentThumb = false, fillHeight = if (isPerson) 300 else 200)
    }

    val isMusic = remember(item) {
        item.type == BaseItemKind.MUSIC_ALBUM || item.type == BaseItemKind.MUSIC_ARTIST || item.type == BaseItemKind.AUDIO
    }

    val isFolderOrMusic = remember(item) {
        item.isFolder == true || isMusic
    }

    val hasTrailers = remember(item) {
        (item.localTrailerCount ?: 0) > 0 || !(item.remoteTrailers.isNullOrEmpty())
    }

    val activeSource = state.selectedMediaSource ?: item.mediaSources?.firstOrNull()
    val mediaStreams = activeSource?.mediaStreams?.takeIf { it.isNotEmpty() }
        ?: item.mediaStreams?.takeIf { it.isNotEmpty() }

    val filteredStreams = remember(mediaStreams) {
        mediaStreams?.filter {
            it.type == MediaStreamType.VIDEO || it.type == MediaStreamType.AUDIO || it.type == MediaStreamType.SUBTITLE
        }
    }

    val backgroundService = koinInject<BackgroundService>()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, item) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                backgroundService.setBackground(item, fallbackToSplashscreen = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            backgroundService.setBackground(item, fallbackToSplashscreen = true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .focusRestorer()
    ) {
        // Background
        AppBackground()

        // Top-Right Header with Home Button and Clock
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 40.dp)
                .zIndex(10f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onHomeClick,
                modifier = Modifier
                    .focusProperties {
                        down = actionButtonsFocusRequester
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            navigateDownFromHome()
                            true
                        } else {
                            false
                        }
                    }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_house),
                    contentDescription = stringResource(R.string.lbl_home)
                )
            }
            ToolbarClock()
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides tvPivotBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .focusGroup()
                    .focusRestorer(),
                contentPadding = PaddingValues(top = 64.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // Main Details Row Item matching view_row_details.xml
                item(key = "main_details_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 48.dp)
                    ) {
                        // Title, Info Row & Genres at Top (matching view_row_details.xml guide_main_start = 170dp)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Spacer(Modifier.width(170.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                if ((item.type == BaseItemKind.SEASON || item.type == BaseItemKind.EPISODE) && !item.seriesName.isNullOrBlank()) {
                                    Text(
                                        text = item.seriesName!!,
                                        style = TextStyle(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = Color.White.copy(alpha = 0.7f)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                }

                                val context = LocalContext.current
                                val titleText = remember(item, context) {
                                    if (item.type == BaseItemKind.EPISODE) {
                                        val seCode = item.getSeasonEpisodeName(context)
                                        val epName = item.name.orEmpty()
                                        when {
                                            seCode.isNotBlank() && epName.isNotBlank() -> "$seCode - $epName"
                                            seCode.isNotBlank() -> seCode
                                            else -> epName
                                        }
                                    } else {
                                        item.name ?: ""
                                    }
                                }

                                Text(
                                    text = titleText,
                                    style = TextStyle(
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.Light,
                                        color = Color.White
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (!isPerson) {
                                    Spacer(Modifier.height(4.dp))

                                    BaseItemInfoRow(
                                        item = item,
                                        mediaSource = state.selectedMediaSource,
                                        includeRuntime = true
                                    )

                                    if (!state.genresText.isNullOrBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = state.genresText,
                                            style = TextStyle(
                                                fontSize = 14.sp,
                                                color = Color.White.copy(alpha = 0.8f)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 3 Columns Main Section starting at guide_top (180dp)
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Left Column (guide_left_end = 150dp width, right-aligned) - Hidden for PERSON
                                Column(
                                    modifier = Modifier.width(150.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    if (!isPerson) {
                                        if (!state.directorText.isNullOrBlank()) {
                                            Text(
                                                text = stringResource(R.string.lbl_director),
                                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            )
                                            Text(
                                                text = state.directorText,
                                                style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.End
                                            )
                                            Spacer(Modifier.height(16.dp))
                                        }

                                        if (!state.writerText.isNullOrBlank()) {
                                            Text(
                                                text = stringResource(R.string.lbl_writer),
                                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            )
                                            Text(
                                                text = state.writerText,
                                                style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f)),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.End
                                            )
                                            Spacer(Modifier.height(16.dp))
                                        }

                                        if (!state.endTimeText.isNullOrBlank()) {
                                            Text(
                                                text = stringResource(R.string.lbl_end_time),
                                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            )
                                            Text(
                                                text = state.endTimeText,
                                                style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(20.dp))

                                // Middle Column (Expandable Overview + Action Buttons)
                                // Ends at guide_main_end (240dp from right screen edge = 192dp from 48dp padding)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 192.dp)
                                ) {
                                    ExpandableOverviewText(
                                        overviewText = item.overview,
                                        maxLines = if (isPerson) 9 else 6
                                    )

                                    Spacer(Modifier.height(16.dp))

                                    // Action Buttons Row using TextUnderButton in exact Leanback order:
                                    // 1. Resume -> 2. Play -> 3. Queue -> 4. Shuffle -> 5. Trailers -> 6. Watched -> 7. Favorite -> 8. Previous Episode -> 9. GoToSeries -> 10. Delete
                                    CompositionLocalProvider(LocalBringIntoViewSpec provides actionButtonsBringIntoViewSpec) {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top,
                                            modifier = Modifier
                                                .focusRequester(actionButtonsFocusRequester)
                                                .focusGroup()
                                                .focusRestorer()
                                        ) {
                                            // 1. Resume
                                            if (state.canResume && !isPerson && !isNonexistentMedia) {
                                                item {
                                                    val resumeLabel = if (!state.resumePositionText.isNullOrEmpty()) {
                                                        stringResource(R.string.lbl_resume_from, state.resumePositionText)
                                                    } else {
                                                        stringResource(R.string.home_section_resume)
                                                    }

                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_resume,
                                                        label = resumeLabel,
                                                        onClick = onResumeClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 2. Play
                                            if (!isPerson && !isNonexistentMedia) {
                                                item {
                                                    val playLabel = if (item.type == BaseItemKind.SERIES) {
                                                        stringResource(R.string.lbl_play_next_up)
                                                    } else if (item.isFolder == true) {
                                                        stringResource(R.string.lbl_play_all)
                                                    } else {
                                                        stringResource(R.string.lbl_play)
                                                    }

                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_play,
                                                        label = playLabel,
                                                        onClick = onPlayClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 3. Add to Queue (Music)
                                            if (isMusic && !isPerson && !isNonexistentMedia) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_add,
                                                        label = stringResource(R.string.lbl_add_to_queue),
                                                        onClick = onQueueClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 4. Shuffle (Folder or Music)
                                            if (isFolderOrMusic && !isPerson && !isNonexistentMedia) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_shuffle,
                                                        label = stringResource(R.string.lbl_shuffle_all),
                                                        onClick = onShuffleClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // Version Selection (shown when item has multiple versions/media sources)
                                            val versions = item.mediaSources
                                            if (!isPerson && !isNonexistentMedia && !versions.isNullOrEmpty() && versions.size > 1) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_control_select,
                                                        label = stringResource(R.string.select_version),
                                                        onClick = { showVersionPicker = true },
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 5. Trailers
                                            if (hasTrailers && !isPerson && !isNonexistentMedia) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_trailer,
                                                        label = stringResource(R.string.lbl_play_trailers),
                                                        onClick = onTrailerClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 6. Watched (Vu)
                                            if (!isPerson) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_check,
                                                        label = stringResource(R.string.lbl_watched),
                                                        onClick = onPlayedClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        isActivated = state.isPlayed,
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 7. Favorite (Favori - shown for all including PERSON)
                                            item {
                                                TextUnderButton(
                                                    iconRes = R.drawable.ic_star,
                                                    label = stringResource(R.string.lbl_add_favorite),
                                                    onClick = onFavoriteClick,
                                                    onFocused = { scrollToHeaderTop() },
                                                    isActivated = state.isFavorite,
                                                    modifier = getInitialFocusModifier()
                                                )
                                            }

                                            // 8. Previous Episode (Épisode précédent - matching Leanback mPrevButton)
                                            if (item.type == BaseItemKind.EPISODE && state.prevItemId != null && !isPerson) {
                                                item {
                                                    val prevId = state.prevItemId
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_previous_episode,
                                                        label = stringResource(R.string.lbl_previous_episode),
                                                        onClick = { onPreviousEpisodeClick(prevId) },
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 9. Go to Series (Episode or Season)
                                            if ((item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.SEASON) && item.seriesId != null && !isPerson) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_tv,
                                                        label = stringResource(R.string.lbl_goto_series),
                                                        onClick = onGoToSeriesClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }

                                            // 10. Delete
                                            if (state.canDelete && !isPerson) {
                                                item {
                                                    TextUnderButton(
                                                        iconRes = R.drawable.ic_delete,
                                                        label = stringResource(R.string.lbl_delete),
                                                        onClick = onDeleteClick,
                                                        onFocused = { scrollToHeaderTop() },
                                                        modifier = getInitialFocusModifier()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Right Column (Poster / Logo Image top-aligned with first line of Overview)
                            // Positioned at guide_right_start (220dp from right) to guide_right_end (50dp from right)
                            if (!rightImageUrl.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(end = 2.dp)
                                        .width(170.dp),
                                    contentAlignment = Alignment.TopStart
                                ) {
                                    AsyncImage(
                                        model = rightImageUrl,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.TopStart,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                // Additional Content Rows (Seasons, Episodes, Cast, Extras, Similar)
                this@LazyColumn.itemsIndexed(
                    activeContentRows,
                    key = { idx, (rowState, _) -> "details_content_row_${idx}_${rowState.title.hashCode()}" }
                ) { rowIndex: Int, (rowState, validRowItems) ->
                    Column {
                        Text(
                            text = rowState.title,
                            style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
                            modifier = Modifier.padding(start = 56.dp, end = 56.dp, bottom = 12.dp)
                        )

                        CompositionLocalProvider(LocalBringIntoViewSpec provides contentRowsBringIntoViewSpec) {
                            LazyRow(
                                modifier = Modifier.focusRestorer(),
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(validRowItems, key = { index, item -> "row_item_${rowIndex}_${index}_${item.hashCode()}" }) { index: Int, rowItem: BaseRowItem ->
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val isFocusedState by interactionSource.collectIsFocusedAsState()
                                    var isFocusedInternal by remember { mutableStateOf(false) }
                                    val isFocused = isFocusedState || isFocusedInternal
                                    var showContextMenu by remember { mutableStateOf(false) }

                                    val cardFocusRequester = remember { FocusRequester() }
                                    val cardBringIntoViewRequester = remember { BringIntoViewRequester() }

                                    Box(
                                        modifier = Modifier
                                            .focusRequester(cardFocusRequester)
                                            .zIndex(if (isFocused) 1f else 0f)
                                            .bringIntoViewRequester(cardBringIntoViewRequester)
                                            .onFocusChanged { focusState ->
                                                isFocusedInternal = focusState.isFocused
                                                if (focusState.isFocused) {
                                                    coroutineScope.launch {
                                                        try { cardBringIntoViewRequester.bringIntoView() } catch (e: Exception) {}
                                                    }
                                                }
                                            }
                                            .itemCardClickable(
                                                interactionSource = interactionSource,
                                                onClick = { onItemClick(rowItem) },
                                                onLongClick = { showContextMenu = true }
                                            )
                                    ) {
                                        BaseRowItemCard(
                                            item = rowItem,
                                            focused = isFocused,
                                            showInfo = true
                                        )

                                        if (showContextMenu) {
                                            ItemContextMenu(
                                                item = rowItem,
                                                expanded = showContextMenu,
                                                onDismissRequest = { showContextMenu = false },
                                                focusRequester = cardFocusRequester,
                                                onItemUpdated = { updatedItem ->
                                                    rowState.adapter.set(index, updatedItem)
                                                    val currentList = rowData[rowIndex]?.toMutableList()
                                                    if (currentList != null && index in currentList.indices) {
                                                        currentList[index] = updatedItem
                                                        rowData[rowIndex] = currentList
                                                    }
                                                    onRefreshItem()
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Media Details Streams Rows (matching Leanback addInfoRows / InfoCardPresenter / MyInfoCardView)
                if (!isPerson && !filteredStreams.isNullOrEmpty()) {
                    this@LazyColumn.item(key = "media_details_streams_row") {
                        MediaDetailsRow(streams = filteredStreams)
                    }
                }
            } // end of LazyColumn
        } // end of CompositionLocalProvider

        if (showVersionPicker && !item.mediaSources.isNullOrEmpty()) {
                val versionsList = item.mediaSources!!
                val selectedIndex = versionsList.indexOfFirst {
                    it.id == (state.selectedMediaSource?.id ?: versionsList.firstOrNull()?.id)
                }.coerceAtLeast(0)

                Popover(
                    expanded = showVersionPicker,
                    onDismissRequest = { showVersionPicker = false },
                    alignment = Alignment.Center
                ) {
                    PopoverMenu {
                        Text(
                            text = stringResource(R.string.select_version),
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )

                        versionsList.forEachIndexed { index, mediaSource ->
                            val isSelected = index == selectedIndex
                            PopoverMenuCheckboxItem(
                                selected = isSelected,
                                onClick = {
                                    showVersionPicker = false
                                    onSelectVersion(mediaSource)
                                }
                            ) {
                                Text(
                                    text = mediaSource.name ?: item.name ?: "",
                                    style = TextStyle(color = Color.White, fontSize = 14.sp)
                                )
                            }
                        }
                    }
                }
            }
        } // end of Box
    }
