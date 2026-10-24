package org.jellyfin.androidtv.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.GridButtonCard
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.presentation.BaseRowItemCard
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.sdk.model.api.BaseItemKind

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberHorizontalBringIntoViewSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    val paddingPx = with(density) { 56.dp.toPx() }
    return remember(paddingPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberPivotBringIntoViewSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    val headerHeightPx = with(density) { 38.dp.toPx() }
    return remember(headerHeightPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                return offset - headerHeightPx
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeComposeScreen(
    rows: List<HomeRowState>,
    initialFocusedItemKey: String? = null,
    initialFocusedRowIndex: Int = 0,
    focusTrigger: Long = 0L,
    onItemClick: (item: Any, rowIndex: Int) -> Unit,
    onItemFocus: (item: Any?, rowIndex: Int) -> Unit
) {
    val listState = rememberLazyListState()
    val pivotBringIntoViewSpec = rememberPivotBringIntoViewSpec()
    val horizontalBringIntoViewSpec = rememberHorizontalBringIntoViewSpec()

    val (targetItemKey, targetRowIndex) = remember(rows, initialFocusedItemKey, initialFocusedRowIndex) {
        if (!initialFocusedItemKey.isNullOrBlank()) {
            var foundRowIdx = -1
            var foundKey: String? = null
            for ((rIdx, row) in rows.withIndex()) {
                for (item in row.items) {
                    if (getItemKey(item) == initialFocusedItemKey) {
                        foundRowIdx = rIdx
                        foundKey = initialFocusedItemKey
                        break
                    }
                }
                if (foundKey != null) break
            }
            if (foundKey != null) {
                foundKey to foundRowIdx
            } else if (initialFocusedRowIndex in rows.indices && rows[initialFocusedRowIndex].items.isNotEmpty()) {
                val firstInRow = rows[initialFocusedRowIndex].items.first()
                getItemKey(firstInRow) to initialFocusedRowIndex
            } else {
                findFirstLibraryItemKey(rows)
            }
        } else {
            findFirstLibraryItemKey(rows)
        }
    }

    var selectedItem by remember { mutableStateOf<Any?>(null) }
    var focusedRowIndex by remember { mutableIntStateOf(0) }
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    LaunchedEffect(focusTrigger, rows, targetItemKey) {
        if (rows.isNotEmpty() && targetItemKey != null) {
            if (targetRowIndex > 0) {
                try { listState.scrollToItem(targetRowIndex) } catch (_: Exception) {}
            }
            delay(150)
            val requester = focusRequesters[targetItemKey]
            if (requester != null) {
                try {
                    requester.requestFocus()
                    Timber.d("HomeComposeScreen: focus set on $targetItemKey (trigger=$focusTrigger)")
                } catch (e: Exception) {
                    Timber.e(e, "HomeComposeScreen: failed initial focus on $targetItemKey")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
            .focusRestorer()
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotBringIntoViewSpec) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .focusGroup()
                    .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 64.dp)
            ) {
                itemsIndexed(rows, key = { _, rowState -> rowState.id }) { rowIndex, rowState ->
                    val headerText = rowState.title ?: ""
                    val items = rowState.items

                    if (items.isNotEmpty()) {
                        Column {
                            if (headerText.isNotEmpty()) {
                                Text(
                                    text = headerText,
                                    style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
                                    modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 6.dp, bottom = 12.dp)
                                )
                            }

                            CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
                                LazyRow(
                                    modifier = Modifier.focusRestorer(),
                                    contentPadding = PaddingValues(horizontal = 56.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    itemsIndexed(items, key = { _, item ->
                                        getItemKey(item)
                                    }) { index, item ->
                                        val interactionSource = remember { MutableInteractionSource() }
                                        val isFocusedState by interactionSource.collectIsFocusedAsState()
                                        var isFocusedInternal by remember { mutableStateOf(false) }
                                        val isFocused = isFocusedState || isFocusedInternal
                                        var showContextMenu by remember { mutableStateOf(false) }
                                        val itemKey = remember(item) { getItemKey(item) }

                                        val focusRequester = remember(itemKey) {
                                            focusRequesters.getOrPut(itemKey) { FocusRequester() }
                                        }

                                        val isViewOrUsbOrGridItem = item is GridButton ||
                                            item is GridButtonBaseRowItem ||
                                            item is UsbBaseRowItem ||
                                            (item is BaseRowItem && (item.baseItem?.type == BaseItemKind.USER_VIEW || item.baseItem?.type == BaseItemKind.COLLECTION_FOLDER)) ||
                                            rowState.sectionType == HomeSectionType.LIBRARY_TILES_SMALL ||
                                            rowState.sectionType == HomeSectionType.LIBRARY_BUTTONS

                                        val shouldShowInfo = !isViewOrUsbOrGridItem

                                        Box(
                                            modifier = Modifier
                                                .focusRequester(focusRequester)
                                                .zIndex(if (isFocused) 1f else 0f)
                                                .onFocusChanged { focusState ->
                                                    isFocusedInternal = focusState.isFocused
                                                    if (focusState.isFocused) {
                                                        selectedItem = item
                                                        if (focusedRowIndex != rowIndex) {
                                                            focusedRowIndex = rowIndex
                                                        }
                                                        onItemFocus(item, rowIndex)
                                                    }
                                                }
                                                .itemCardClickable(
                                                    interactionSource = interactionSource,
                                                    onClick = {
                                                        onItemClick(item, rowIndex)
                                                    },
                                                    onLongClick = {
                                                        if (item is BaseRowItem) {
                                                            showContextMenu = true
                                                        }
                                                    }
                                                )
                                        ) {
                                            when (item) {
                                                is GridButtonBaseRowItem -> GridButtonCard(item = item, focused = isFocused)
                                                is GridButton -> GridButtonCard(item = GridButtonBaseRowItem(item), focused = isFocused)
                                                is BaseRowItem -> BaseRowItemCard(item = item, focused = isFocused, showInfo = shouldShowInfo)
                                                is AppNotification -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(if (isFocused) JellyfinTheme.colorScheme.buttonFocused else Color.DarkGray)
                                                            .padding(16.dp)
                                                    ) {
                                                        Text(
                                                            text = item.message,
                                                            color = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else Color.White
                                                        )
                                                    }
                                                }
                                            }

                                            if (showContextMenu && item is BaseRowItem) {
                                                ItemContextMenu(
                                                    item = item,
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

private fun findFirstLibraryItemKey(rows: List<HomeRowState>): Pair<String?, Int> {
    val libraryRowIndex = rows.indexOfFirst { row ->
        row.sectionType == HomeSectionType.LIBRARY_TILES_SMALL ||
        row.sectionType == HomeSectionType.LIBRARY_BUTTONS ||
        row.items.any { it is GridButton || it is GridButtonBaseRowItem || (it is BaseRowItem && it.baseItem?.type == BaseItemKind.USER_VIEW) }
    }
    if (libraryRowIndex != -1) {
        val libraryRow = rows[libraryRowIndex]
        val firstItem = libraryRow.items.firstOrNull()
        if (firstItem != null) {
            return getItemKey(firstItem) to libraryRowIndex
        }
    }
    for ((rIdx, row) in rows.withIndex()) {
        val firstItem = row.items.firstOrNull()
        if (firstItem != null) {
            return getItemKey(firstItem) to rIdx
        }
    }
    return null to 0
}
