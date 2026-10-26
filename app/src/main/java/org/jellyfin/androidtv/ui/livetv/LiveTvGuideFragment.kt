package org.jellyfin.androidtv.ui.livetv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.time.LocalDateTime
import java.util.UUID

import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box

class LiveTvGuideFragment : Fragment() {

    companion object {
        const val GUIDE_ROW_HEIGHT_DP = 55
        const val GUIDE_ROW_WIDTH_PER_MIN_DP = 7
        const val PAGE_SIZE = 75
        const val NORMAL_HOURS = 9
        const val FILTERED_HOURS = 4
    }

    private val customMessageRepository: CustomMessageRepository by inject()
    private val playbackHelper: PlaybackHelper by inject()
    private val imageHelper: ImageHelper by inject()
    private val playbackLauncher: PlaybackLauncher by inject()

    private val viewModel: LiveTvGuideViewModel by viewModel()

    val showOptions = MutableStateFlow(false)
    val showFilterOptions = MutableStateFlow(false)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val isOptionsVisible by showOptions.collectAsState()
                val isFiltersVisible by showFilterOptions.collectAsState()

                Box {
                    LiveTvGuideScreen(
                        viewModel = viewModel,
                        getImageUrl = { channel ->
                            imageHelper.getPrimaryImageUrl(channel, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
                        },
                        onProgramSelected = { program ->
                            // Optional: Show summary or background change based on selection
                        },
                        onProgramClicked = { program ->
                            // Playback or show options
                            program.channelId?.let {
                                playbackHelper.retrieveAndPlay(it, false, requireContext())
                            }
                        },
                        onOptionsClicked = { showOptions.value = true },
                        onFiltersClicked = { showFilterOptions.value = true }
                    )

                    ProvideRouter(routes, Routes.LIVETV_GUIDE_OPTIONS) {
                        SettingsDialog(
                            visible = isOptionsVisible,
                            onDismissRequest = {
                                showOptions.value = false
                                viewModel.load()
                            }
                        ) {
                            SettingsRouterContent()
                        }
                    }

                    ProvideRouter(routes, Routes.LIVETV_GUIDE_FILTERS) {
                        SettingsDialog(
                            visible = isFiltersVisible,
                            onDismissRequest = {
                                showFilterOptions.value = false
                                viewModel.load()
                            }
                        ) {
                            SettingsRouterContent()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    fun displayChannels(start: Int, max: Int) {
        viewModel.displayChannels(start, max)
    }

    fun getCurrentLocalStartDate(): LocalDateTime {
        return viewModel.state.value.guideStart
    }

    fun refreshFavorite(channelId: UUID) {
        viewModel.load()
    }
}
