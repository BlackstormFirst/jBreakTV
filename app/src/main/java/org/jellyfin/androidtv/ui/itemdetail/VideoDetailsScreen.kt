package org.jellyfin.androidtv.ui.itemdetail

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
import org.jellyfin.androidtv.ui.home.collectAsStateAny
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.presentation.BaseRowItemCard
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.UUID

/**
 * Unified BringIntoViewSpec for 10-foot TV navigation matching Leanback VerticalGridView & HorizontalGridView:
 * - For horizontal pass (containerSize > 1200px): keeps focused cards within horizontal padding bounds.
 * - For vertical pass (Header at offset 0): anchors offset at 0 while focus is on action buttons.
 * - For vertical pass (Content Rows): aligns focused row at fixed ideal pivot (35% from top of screen).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberTvPivotBringIntoViewSpec(listState: LazyListState): BringIntoViewSpec {
    val density = LocalDensity.current
    val horizontalPaddingPx = with(density) { 56.dp.toPx() }

    return remember(listState, horizontalPaddingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float
            ): Float {
                // Horizontal LazyRow scroll distance pass
                if (containerSize > 1200f) {
                    val trailingEdge = offset + size
                    val minVisible = horizontalPaddingPx
                    val maxVisible = containerSize - horizontalPaddingPx
                    return when {
                        offset < minVisible -> offset - minVisible
                        trailingEdge > maxVisible -> trailingEdge - maxVisible
                        else -> 0f
                    }
                }

                // Vertical LazyColumn scroll distance pass for Item 0 (Header / Action Buttons when at top):
                // Do NOT scroll if currently at top offset 0 and focusing header/buttons
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && offset < containerSize * 0.45f) {
                    return 0f
                }

                // Align content rows (Seasons, Episodes, Cast/Crew) at ideal Leanback pivot (35% from top of screen)
                val targetPivot = containerSize * 0.35f
                return offset - targetPivot
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
                        .padding(6.dp)
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
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { showDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(600.dp)
                        .height(400.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JellyfinTheme.colorScheme.surface)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = overviewText,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Light,
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
    onItemClick: (BaseRowItem) -> Unit
) {
    val imageHelper = koinInject<ImageHelper>()
    val item = state.item ?: return

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val tvPivotBringIntoViewSpec = rememberTvPivotBringIntoViewSpec(listState)

    val actionButtonsFocusRequester = remember { FocusRequester() }
    val initialPlayButtonFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    val initialFocusModifier = Modifier
        .focusRequester(initialPlayButtonFocusRequester)
        .onGloballyPositioned {
            if (!initialFocusRequested) {
                initialFocusRequested = true
                try { initialPlayButtonFocusRequester.requestFocus() } catch (e: Exception) {}
            }
        }

    val isPerson = remember(item) {
        item.type == BaseItemKind.PERSON
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
            try { initialPlayButtonFocusRequester.requestFocus() } catch (e: Exception) {}
            try { actionButtonsFocusRequester.requestFocus() } catch (e: Exception) {}
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
                contentPadding = PaddingValues(top = 80.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                // Main Details Row Item matching view_row_details.xml
                item {
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
                                Text(
                                    text = item.name ?: "",
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
                                        mediaSource = null,
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
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
                                            text = "RÉALISATEUR",
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
                                            text = "SCÉNARISTE",
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
                                            text = "HEURE DE FIN",
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
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                ExpandableOverviewText(
                                    overviewText = item.overview,
                                    maxLines = if (isPerson) 9 else 6
                                )

                                Spacer(Modifier.height(16.dp))

                                // Action Buttons Row using TextUnderButton in exact Leanback order:
                                // 1. Resume -> 2. Play -> 3. Queue -> 4. Shuffle -> 5. Trailers -> 6. Watched -> 7. Favorite -> 8. Previous Episode -> 9. GoToSeries -> 10. Delete
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier
                                        .focusRequester(actionButtonsFocusRequester)
                                        .focusGroup()
                                        .focusRestorer()
                                ) {
                                    // 1. Resume
                                    if (state.canResume && !isPerson) {
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
                                                modifier = initialFocusModifier
                                            )
                                        }
                                    }

                                    // 2. Play
                                    if (!isPerson) {
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
                                                modifier = if (!state.canResume) initialFocusModifier else Modifier
                                            )
                                        }
                                    }

                                    // 3. Add to Queue (Music)
                                    if (isMusic && !isPerson) {
                                        item {
                                            TextUnderButton(
                                                iconRes = R.drawable.ic_add,
                                                label = stringResource(R.string.lbl_add_to_queue),
                                                onClick = onQueueClick,
                                                onFocused = { scrollToHeaderTop() }
                                            )
                                        }
                                    }

                                    // 4. Shuffle (Folder or Music)
                                    if (isFolderOrMusic && !isPerson) {
                                        item {
                                            TextUnderButton(
                                                iconRes = R.drawable.ic_shuffle,
                                                label = stringResource(R.string.lbl_shuffle_all),
                                                onClick = onShuffleClick,
                                                onFocused = { scrollToHeaderTop() }
                                            )
                                        }
                                    }

                                    // 5. Trailers
                                    if (hasTrailers && !isPerson) {
                                        item {
                                            TextUnderButton(
                                                iconRes = R.drawable.ic_trailer,
                                                label = stringResource(R.string.lbl_play_trailers),
                                                onClick = onTrailerClick,
                                                onFocused = { scrollToHeaderTop() }
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
                                                isActivated = state.isPlayed
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
                                            modifier = if (isPerson) initialFocusModifier else Modifier
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
                                                onFocused = { scrollToHeaderTop() }
                                            )
                                        }
                                    }

                                    // 9. Go to Series (Episode)
                                    if (item.type == BaseItemKind.EPISODE && item.seriesId != null && !isPerson) {
                                        item {
                                            TextUnderButton(
                                                iconRes = R.drawable.ic_tv,
                                                label = stringResource(R.string.lbl_goto_series),
                                                onClick = onGoToSeriesClick,
                                                onFocused = { scrollToHeaderTop() }
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
                                                onFocused = { scrollToHeaderTop() }
                                            )
                                        }
                                    }
                                }

                            }

                            Spacer(Modifier.width(20.dp))

                            // Right Column (Poster / Logo Image top-aligned with first line of Overview)
                            if (!rightImageUrl.isNullOrEmpty()) {
                                Box(
                                    modifier = Modifier.width(200.dp),
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
                itemsIndexed(state.rows, key = { idx, row -> "${row.title}_$idx" }) { rowIndex: Int, rowState: DetailsRowState ->
                    val items by rowState.adapter.collectAsStateAny()

                    if (items.isNotEmpty()) {
                        Column {
                            Text(
                                text = rowState.title,
                                style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
                                modifier = Modifier.padding(start = 56.dp, end = 56.dp, bottom = 12.dp)
                            )

                            LazyRow(
                                modifier = Modifier.focusRestorer(),
                                contentPadding = PaddingValues(horizontal = 56.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                itemsIndexed(items, key = { index, item -> "${rowIndex}_${item.hashCode()}_$index" }) { index: Int, rowItem: Any ->
                                        var isFocused by remember { mutableStateOf(false) }
                                    var showContextMenu by remember { mutableStateOf(false) }

                                    val cardBringIntoViewRequester = remember { BringIntoViewRequester() }

                                    Box(
                                        modifier = Modifier
                                            .zIndex(if (isFocused) 1f else 0f)
                                            .bringIntoViewRequester(cardBringIntoViewRequester)
                                            .onFocusChanged { focusState ->
                                                isFocused = focusState.isFocused
                                                if (focusState.isFocused) {
                                                    coroutineScope.launch {
                                                        try { cardBringIntoViewRequester.bringIntoView() } catch (e: Exception) {}
                                                    }
                                                }
                                            }
                                            .itemCardClickable(
                                                onClick = {
                                                    if (rowItem is BaseRowItem) onItemClick(rowItem)
                                                },
                                                onLongClick = {
                                                    if (rowItem is BaseRowItem) {
                                                        showContextMenu = true
                                                    }
                                                }
                                            )
                                    ) {
                                        if (rowItem is BaseRowItem) {
                                            BaseRowItemCard(
                                                item = rowItem,
                                                focused = isFocused,
                                                showInfo = true
                                            )

                                            if (showContextMenu) {
                                                ItemContextMenu(
                                                    item = rowItem,
                                                    expanded = showContextMenu,
                                                    onDismissRequest = { showContextMenu = false }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
