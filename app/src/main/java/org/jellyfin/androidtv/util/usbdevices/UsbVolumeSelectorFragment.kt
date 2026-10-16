package org.jellyfin.androidtv.util.usbdevices

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClassicCard
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.ui.shared.toolbar.Toolbar
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarButtons
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.navigation.Destinations
import androidx.compose.ui.res.painterResource
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject

class UsbVolumeSelectorFragment : Fragment() {

    private val navigationRepository: NavigationRepository by inject()

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
                    UsbVolumeSelectorScreen(
                        navigationRepository = navigationRepository
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigationRepository.canGoBack) {
                    navigationRepository.goBack()
                } else {
                    navigationRepository.reset(Destinations.home, clearHistory = true)
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbStorageManager.mountedVolumes.collect { volumes ->
                    if (volumes.isEmpty()) {
                        navigationRepository.reset(Destinations.home, clearHistory = true)
                    }
                }
            }
        }
    }
}

@Composable
fun UsbVolumeSelectorScreen(
    navigationRepository: NavigationRepository
) {
    val context = LocalContext.current
    val volumes by UsbStorageManager.mountedVolumes.collectAsState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val homeFocusRequester = remember { FocusRequester() }
    var hasRequestedFocus by remember { androidx.compose.runtime.mutableStateOf(false) }
    var isFirstItemFocused by remember { androidx.compose.runtime.mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (volumes.isNotEmpty()) {
                    try { firstItemFocusRequester.requestFocus() } catch (e: Exception) {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 80.dp, bottom = 64.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp)
        ) {
            itemsIndexed(volumes, key = { _, volume -> volume.path.absolutePath }) { index, volume ->
                val isFirst = index == 0
                ClassicCard(
                    onClick = {
                        val destination = Destinations.usbExplorer(
                            currentDir = volume.path.absolutePath,
                            rootPath = volume.path.absolutePath,
                            volumeName = volume.label
                        )
                        navigationRepository.navigate(destination)
                    },
                    image = {},
                    title = {
                        Text(
                            text = volume.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    subtitle = {
                        val freeFormatted = Formatter.formatFileSize(context, volume.freeBytes)
                        val totalFormatted = Formatter.formatFileSize(context, volume.totalBytes)
                        Text(
                            text = "$freeFormatted free of $totalFormatted",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = CardDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xB3747474),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xE6CCCCCC),
                        contentColor = androidx.compose.ui.graphics.Color(0xFFDDDDDD),
                        focusedContentColor = androidx.compose.ui.graphics.Color(0xFF444444)
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                isFirstItemFocused = isFirst
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (isFirst && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                try { homeFocusRequester.requestFocus() } catch (e: Exception) {}
                                true
                            } else false
                        }
                        .then(
                            if (isFirst) Modifier
                                .focusRequester(firstItemFocusRequester)
                                .onGloballyPositioned {
                                    if (!hasRequestedFocus) {
                                        hasRequestedFocus = true
                                        try { firstItemFocusRequester.requestFocus() } catch (e: Exception) {}
                                    }
                                }
                            else Modifier
                        )
                )
            }
        }

        LeanbackTitleBar(
            visible = isFirstItemFocused,
            titleText = "Local or USB Devices Selection",
            onHomeClick = {
                navigationRepository.reset(Destinations.home, clearHistory = true)
            },
            homeFocusRequester = homeFocusRequester,
            firstItemFocusRequester = firstItemFocusRequester,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
        )
    }
}


