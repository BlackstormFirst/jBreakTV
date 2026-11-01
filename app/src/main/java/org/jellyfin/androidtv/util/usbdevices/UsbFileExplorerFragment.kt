package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.TextPaint
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.Toolbar
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarButtons
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.koin.android.ext.android.inject
import java.io.File

const val ARG_VOLUME_NAME = "ARG_VOLUME_NAME"
const val ARG_VOLUME_PATH = "ARG_VOLUME_PATH"
const val ARG_CURRENT_DIR = "ARG_CURRENT_DIR"
const val ARG_ROOT_PATH = "ARG_ROOT_PATH"

class UsbFileExplorerFragment : Fragment() {

    private val navigationRepository: NavigationRepository by inject()

    private var volumeName: String = "Périphériques Locaux ou USB"
    private var currentDirPath: String = ""
    private var rootDirPath: String = ""

    private lateinit var currentDir: File
    private lateinit var rootDir: File

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                JellyfinTheme {
                    UsbFileExplorerScreen(
                        navigationRepository = navigationRepository,
                        currentDir = currentDir,
                        rootDir = rootDir,
                        volumeName = volumeName,
                        onNavigate = { dir ->
                            val destination = Destinations.usbExplorer(
                                currentDir = dir.absolutePath,
                                rootPath = rootDir.absolutePath,
                                volumeName = volumeName
                            )
                            navigationRepository.navigate(destination)
                        },
                        onPlayMedia = { mediaFile, allMedia ->
                            UsbPlayerDispatcher.playMedia(requireContext(), mediaFile, allMedia)
                        }
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeName = arguments?.getString(ARG_VOLUME_NAME) ?: "Périphériques Locaux ou USB"
        currentDirPath = arguments?.getString(ARG_CURRENT_DIR)
            ?: arguments?.getString(ARG_VOLUME_PATH) ?: ""
        rootDirPath = arguments?.getString(ARG_ROOT_PATH)
            ?: currentDirPath

        if (currentDirPath.isBlank()) {
            navigationRepository.goBack()
            return
        }

        currentDir = File(currentDirPath)
        rootDir = File(rootDirPath)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                if (navigationRepository.canGoBack) {
                    navigationRepository.goBack()
                } else {
                    activity?.finish()
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbStorageManager.mountedVolumes.collect { volumes ->
                    val isStillMounted = volumes.any { vol ->
                        rootDirPath.startsWith(vol.path.absolutePath) || vol.path.absolutePath == rootDirPath
                    }
                    if (!isStillMounted) {
                        if (navigationRepository.canGoBack) {
                            navigationRepository.goBack()
                        } else {
                            navigationRepository.reset(Destinations.home, clearHistory = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun calculateMaxItemWidthDp(items: List<File>): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(items) {
    val paint = android.text.TextPaint().apply {
            textSize = with(density) { 16.sp.toPx() }
            isAntiAlias = true
        }
        var maxTextWidthPx = 0f
        for (item in items) {
            val textWidth = paint.measureText(item.name)
            if (textWidth > maxTextWidthPx) {
                maxTextWidthPx = textWidth
            }
        }
        val extraPaddingPx = with(density) { 112.dp.toPx() }
        val totalWidthPx = maxTextWidthPx + extraPaddingPx

        val minWidthPx = with(density) { 320.dp.toPx() }
        val maxWidthPx = with(density) { 800.dp.toPx() }

        val finalWidthPx = totalWidthPx.coerceIn(minWidthPx, maxWidthPx)
        with(density) { finalWidthPx.toDp() }
    }
}

@Composable
fun LeanbackTitleBar(
    visible: Boolean,
    titleText: String,
    onHomeClick: () -> Unit,
    homeFocusRequester: FocusRequester? = null,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 32.dp, end = 32.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleText,
                fontSize = 15.sp,
                color = androidx.compose.ui.graphics.Color(0xFFCCCCCC),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onHomeClick,
                    modifier = Modifier
                        .then(if (homeFocusRequester != null) Modifier.focusRequester(homeFocusRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.key == Key.DirectionDown) {
                                    if (firstItemFocusRequester != null) {
                                        firstItemFocusRequester.safeRequestFocus()
                                        true
                                    } else false
                                } else if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight || event.key == Key.DirectionUp) {
                                    true
                                } else false
                            } else false
                        }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_house),
                        contentDescription = stringResource(id = R.string.lbl_home)
                    )
                }
                ToolbarClock()
            }
        }
    }
}

fun getValidChildCount(dir: File): Int {
    val children = dir.listFiles() ?: return 0
    return children.count { child ->
        !child.name.startsWith(".") && (child.isDirectory || UsbMediaHelper.isMediaFile(child))
    }
}

@Composable
fun UsbFileExplorerScreen(
    navigationRepository: NavigationRepository,
    currentDir: File,
    rootDir: File,
    volumeName: String,
    onNavigate: (File) -> Unit,
    onPlayMedia: (File, List<File>) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var lastClickedPath by rememberSaveable { mutableStateOf<String?>(null) }
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun getFocusRequester(path: String): FocusRequester = itemFocusRequesters.getOrPut(path) { FocusRequester() }

    val homeFocusRequester = remember { FocusRequester() }
    var hasRequestedFocus by remember { mutableStateOf(false) }
    var isFirstItemFocused by remember { mutableStateOf(true) }

    val rawFiles = remember(currentDir) { currentDir.listFiles() ?: emptyArray() }
    val directories = remember(rawFiles) {
        rawFiles.filter { (it.isDirectory || (!it.isFile && it.exists())) && !it.name.startsWith(".") }
            .sortedWith(UsbMediaHelper.naturalFileComparator)
    }
    val mediaFiles = remember(rawFiles) {
        rawFiles.filter { UsbMediaHelper.isMediaFile(it) }
            .sortedWith(UsbMediaHelper.naturalFileComparator)
    }
    val allItems = remember(directories, mediaFiles) { directories + mediaFiles }
    val dynamicWidth = calculateMaxItemWidthDp(allItems)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (allItems.isNotEmpty()) {
                    val targetPath = lastClickedPath ?: allItems.firstOrNull()?.absolutePath
                    val targetIndex = if (targetPath != null) {
                        allItems.indexOfFirst { it.absolutePath == targetPath }.coerceAtLeast(0)
                    } else 0

                    coroutineScope.launch {
                        if (targetIndex in allItems.indices) {
                            listState.scrollToItem(targetIndex)
                            delay(50)
                            if (targetPath != null) {
                                getFocusRequester(targetPath).safeRequestFocus()
                            }
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val relativePath = currentDir.absolutePath.removePrefix(rootDir.absolutePath).trim('/')
    val breadcrumbStr = if (relativePath.isBlank()) {
        volumeName
    } else {
        "$volumeName > " + relativePath.replace("/", " > ")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(dynamicWidth)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 80.dp, bottom = 64.dp)
            ) {
                itemsIndexed(allItems, key = { _, item -> item.absolutePath }) { index, item ->
                    val isFirst = index == 0
                    val itemFocusRequester = getFocusRequester(item.absolutePath)

                    val iconRes = if (item.isDirectory) {
                        R.drawable.ic_folder
                    } else if (UsbMediaHelper.isVideoFile(item)) {
                        R.drawable.ic_video
                    } else {
                        R.drawable.ic_movie
                    }

                    val count = remember(item) { if (item.isDirectory) getValidChildCount(item) else 0 }
                    val subtitle = if (item.isDirectory) {
                        if (count <= 1) "$count élément" else "$count éléments"
                    } else {
                        Formatter.formatFileSize(context, item.length())
                    }

                    ListButton(
                        onClick = {
                            if (item.isDirectory) {
                                lastClickedPath = item.absolutePath
                                onNavigate(item)
                            } else if (UsbMediaHelper.isMediaFile(item)) {
                                val allMediaInFolder = (currentDir.listFiles() ?: emptyArray())
                                    .filter { UsbMediaHelper.isMediaFile(it) }
                                    .sortedWith(UsbMediaHelper.naturalFileComparator)
                                onPlayMedia(item, allMediaInFolder)
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = ImageVector.vectorResource(id = iconRes),
                                contentDescription = null,
                                tint = if (item.isDirectory) androidx.compose.ui.graphics.Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        headingContent = {
                            Text(
                                text = item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = androidx.compose.ui.graphics.Color(0xFFAAAAAA)
                                )
                            )
                        },
                        colors = org.jellyfin.androidtv.ui.base.list.ListControlDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color(0xB3747474),
                            focusedContainerColor = androidx.compose.ui.graphics.Color(0xE6CCCCCC)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    isFirstItemFocused = isFirst
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (isFirst && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                    homeFocusRequester.safeRequestFocus()
                                    true
                                } else false
                            }
                            .focusRequester(itemFocusRequester)
                            .onGloballyPositioned {
                                if (!hasRequestedFocus) {
                                    hasRequestedFocus = true
                                    val targetPath = lastClickedPath ?: allItems.firstOrNull()?.absolutePath
                                    val targetIndex = if (targetPath != null) {
                                        allItems.indexOfFirst { it.absolutePath == targetPath }.coerceAtLeast(0)
                                    } else 0

                                    coroutineScope.launch {
                                        if (targetIndex in allItems.indices) {
                                            listState.scrollToItem(targetIndex)
                                            delay(50)
                                            if (targetPath != null) {
                                                getFocusRequester(targetPath).safeRequestFocus()
                                            }
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }

        LeanbackTitleBar(
            visible = isFirstItemFocused,
            titleText = breadcrumbStr,
            onHomeClick = {
                navigationRepository.reset(Destinations.home, clearHistory = true)
            },
            homeFocusRequester = homeFocusRequester,
            firstItemFocusRequester = getFocusRequester(allItems.firstOrNull()?.absolutePath ?: ""),
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
