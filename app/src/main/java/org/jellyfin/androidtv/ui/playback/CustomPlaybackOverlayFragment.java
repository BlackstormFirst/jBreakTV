package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.lifecycle.Lifecycle;

import java.util.Locale;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.data.service.BackgroundService;
import androidx.compose.ui.platform.ComposeView;
import org.jellyfin.androidtv.databinding.VlcPlayerInterfaceBinding;
import org.jellyfin.androidtv.ui.itemhandling.ChapterItemInfoBaseRowItem;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragmentHelperKt;
import org.jellyfin.androidtv.ui.livetv.TvManager;
import org.jellyfin.androidtv.ui.navigation.Destinations;
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager;
import org.jellyfin.androidtv.ui.navigation.NavigationRepository;
import org.jellyfin.androidtv.ui.playback.overlay.LeanbackOverlayFragment;
import org.jellyfin.androidtv.ui.playback.overlay.presentation.CardPresenter;
import org.jellyfin.androidtv.ui.playback.overlay.presentation.ChannelCardPresenter;
import org.jellyfin.androidtv.ui.playback.overlay.presentation.PositionableListRowPresenter;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.TextUtilsKt;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.sdk.BaseItemExtensionsKt;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.ChapterInfo;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import kotlin.Lazy;
import timber.log.Timber;

public class CustomPlaybackOverlayFragment extends Fragment implements View.OnKeyListener {
    protected VlcPlayerInterfaceBinding binding;
    public ComposeView mGuideComposeView;

    private RowsSupportFragment mPopupRowsFragment;
    private ListRow mChapterRow;
    private ArrayObjectAdapter mPopupRowAdapter;
    private PositionableListRowPresenter mPopupRowPresenter;

    //Live guide items
    private static final int PAGE_SIZE = 75;
    private static final int GUIDE_HOURS = 9;

    BaseItemDto mSelectedProgram;
    RelativeLayout mSelectedProgramView;
    private boolean mGuideVisible = false;
    private LocalDateTime mCurrentGuideStart;
    private LocalDateTime mCurrentGuideEnd;
    private int mCurrentDisplayChannelStartNdx = 0;
    private int mCurrentDisplayChannelEndNdx = 0;
    private List<BaseItemDto> mAllChannels;
    private UUID mFirstFocusChannelId;

    private List<BaseItemDto> mItemsToPlay;

    private Animation fadeOut;
    private Animation slideUp;
    private Animation slideDown;
    private Animation showPopup;
    private Animation hidePopup;
    private final Handler mHandler = new Handler();
    private Runnable mHideTask;

    private AudioManager mAudioManager;

    private boolean mFadeEnabled = false;
    private boolean mIsVisible = false;
    private boolean mPopupPanelVisible = false;
    private boolean navigating = false;

    protected LeanbackOverlayFragment leanbackOverlayFragment;

    private final Lazy<ApiClient> api = inject(ApiClient.class);
    private final Lazy<MediaManager> mediaManager = inject(MediaManager.class);
    private final Lazy<VideoQueueManager> videoQueueManager = inject(VideoQueueManager.class);
    private final Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);
    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<NavigationRepository> navigationRepository = inject(NavigationRepository.class);
    private final Lazy<BackgroundService> backgroundService = inject(BackgroundService.class);
    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);

    private final PlaybackOverlayFragmentHelper helper = new PlaybackOverlayFragmentHelper(this);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // stop any audio that may be playing
        mediaManager.getValue().stopAudio(true);

        mAudioManager = (AudioManager) requireContext().getSystemService(Context.AUDIO_SERVICE);
        if (mAudioManager == null) {
            Timber.e("Unable to get audio manager");
            Utils.showToast(requireContext(), R.string.msg_cannot_play_time);
            return;
        }

        requireActivity().setVolumeControlStream(AudioManager.STREAM_MUSIC);

        mItemsToPlay = videoQueueManager.getValue().getCurrentVideoQueue();
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        int mediaPosition = videoQueueManager.getValue().getCurrentMediaPosition();

        playbackControllerContainer.getValue().setPlaybackController(new PlaybackController(mItemsToPlay, this, mediaPosition));

        // setup fade task
        mHideTask = () -> {
            if (mIsVisible) {
                leanbackOverlayFragment.hideOverlay();
            }
        };

        backgroundService.getValue().disable();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = VlcPlayerInterfaceBinding.inflate(inflater, container, false);
        binding.textClock.setVideoPlayer(true);

        // inject the RowsSupportFragment in the popup container
        if (getChildFragmentManager().findFragmentById(R.id.rows_area) == null) {
            mPopupRowsFragment = new RowsSupportFragment();
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.rows_area, mPopupRowsFragment).commit();
        } else {
            mPopupRowsFragment = (RowsSupportFragment) getChildFragmentManager()
                    .findFragmentById(R.id.rows_area);
        }

        mPopupRowPresenter = new PositionableListRowPresenter();
        mPopupRowAdapter = new ArrayObjectAdapter(mPopupRowPresenter);
        mPopupRowsFragment.setAdapter(mPopupRowAdapter);
        mPopupRowsFragment.setOnItemViewClickedListener(itemViewClickedListener);

        // And the Live Guide element
        mGuideComposeView = new ComposeView(requireContext());
        mGuideComposeView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mGuideComposeView.setVisibility(View.GONE);
        binding.getRoot().addView(mGuideComposeView);
        CustomPlaybackOverlayFragmentHelperKt.setupComposeGuide(this, mGuideComposeView);

        binding.getRoot().setOnTouchListener((v, event) -> {
            //and then manage our fade timer
            if (mFadeEnabled) startFadeTimer();

            Timber.d("Got touch event.");
            return false;
        });

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        CustomPlaybackOverlayFragmentHelperKt.setupBufferingOverlay(this);
        CustomPlaybackOverlayFragmentHelperKt.setupToastOverlay(this);

        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) {
            Utils.showToast(requireContext(), getString(R.string.msg_no_playable_items));
            closePlayer();
            return;
        }

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();

        if (playbackController != null) {
            playbackController.init(new VideoManager(requireActivity(), view, helper), this);
        }

        binding.skipOverlay.setOnSkipRequested(() -> {
            PlaybackController pc = playbackControllerContainer.getValue().getPlaybackController();
            if (pc != null && binding.skipOverlay.getTargetPositionMs() != null) {
                pc.seek(binding.skipOverlay.getTargetPositionMs(), true);
            }
            clearSkipOverlay();
            focusPlayer();
        });

        binding.skipOverlay.setOnFocusPlayerRequested(this::focusPlayer);

        binding.skipOverlay.setOnOpenOsdRequested(this::show);

        binding.skipOverlay.setOnVisibilityChangedListener(visible -> {
            if (visible && !mIsVisible && !mGuideVisible && !mPopupPanelVisible) {
                binding.skipOverlay.focusSkipButton();
            }
        });
    }

    public void focusPlayer() {
        if (binding != null && binding.getRoot() != null) {
            binding.getRoot().requestFocus();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        binding = null;
        // To fix race condition in hide timer
        mIsVisible = false;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        prepareOverlayFragment();

        //pre-load animations
        fadeOut = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out);
        fadeOut.setAnimationListener(hideAnimationListener);
        slideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_top_in);
        slideUp = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_bottom_in);
        slideDown.setAnimationListener(showAnimationListener);
        setupPopupAnimations();

        // register to receive message from popup
        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.ActionComplete.INSTANCE)) dismissProgramOptions();
            return null;
        });

        int startPos = getArguments().getInt("Position", 0);

        // start playing
        playbackControllerContainer.getValue().getPlaybackController().play(startPos);
        leanbackOverlayFragment.updatePlayState();

        // Set initial skip overlay state
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        requireActivity().getOnBackPressedDispatcher().addCallback(this, backPressedCallback);
    }

    private void prepareOverlayFragment() {
        leanbackOverlayFragment = (LeanbackOverlayFragment) getChildFragmentManager().findFragmentById(R.id.leanback_fragment);
        if (leanbackOverlayFragment != null) {
            leanbackOverlayFragment.initFromView(this);
            leanbackOverlayFragment.mediaInfoChanged();
            leanbackOverlayFragment.setOnKeyInterceptListener(keyListener);
        }
    }

    private void setupPopupAnimations() {
        showPopup = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_bottom_in);
        showPopup.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                binding.popupArea.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                binding.popupArea.requestFocus();
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        hidePopup = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out);
        hidePopup.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                binding.popupArea.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
    }

    private AudioManager.OnAudioFocusChangeListener mAudioFocusChanged = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    playbackControllerContainer.getValue().getPlaybackController().pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    // We don't do anything here on purpose
                    // On the Nexus we get this notification erroneously when first starting up
                    // and in any instance that we navigate away from our page, we already handle
                    // stopping video and handing back audio focus
                    break;
            }
        }
    };

    private OnItemViewClickedListener itemViewClickedListener = new OnItemViewClickedListener() {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof ChapterItemInfoBaseRowItem) {
                ChapterItemInfoBaseRowItem rowItem = (ChapterItemInfoBaseRowItem) item;
                Long start = rowItem.getChapterInfo().getStartPositionTicks() / 10000;
                playbackControllerContainer.getValue().getPlaybackController().seek(start);
                hidePopupPanel();
            } else if (item instanceof BaseItemDto) {
                hidePopupPanel();
                switchChannel(((BaseItemDto) item).getId());
            }
        }
    };

    private OnBackPressedCallback backPressedCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (mPopupPanelVisible) {
                // back should just hide the popup panel
                hidePopupPanel();
                leanbackOverlayFragment.hideOverlay();

                // also close this if live tv
                if (playbackControllerContainer.getValue().getPlaybackController() != null && playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) hide();
            } else if (mGuideVisible) {
                hideGuide();
            } else if (mIsVisible) {
                hide();
            } else {
                closePlayer();
            }
        }
    };

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.isLongPress()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                event.startTracking();
                return true;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (keyListener.onKey(v, keyCode, event)) return true;

            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return false;
            }

            PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();

            if (playbackController != null) {
                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                    playbackController.play(0);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    playbackController.pause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    playbackController.playPause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                    playbackController.fastForward();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                    playbackController.rewind();
                    return true;
                }
            }
        }

        return false;
    }

    public void refreshFavorite(UUID channelId) {
    }

    public void dismissProgramOptions() {
    }

    public void showProgramOptions() {
    }

    private View.OnKeyListener keyListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();

                if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                    closePlayer();
                    return true;
                }

                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (mPopupPanelVisible) {
                        hidePopupPanel();
                        leanbackOverlayFragment.hideOverlay();
                        if (playbackController != null && playbackController.isLiveTv()) hide();
                        return true;
                    } else if (mGuideVisible) {
                        hideGuide();
                        return true;
                    } else if (mIsVisible) {
                        hide();
                        return true;
                    } else {
                        closePlayer();
                        return true;
                    }
                }

                if (playbackController != null && playbackController.isLiveTv() && !mPopupPanelVisible && !mGuideVisible && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!leanbackOverlayFragment.isControlsOverlayVisible()) {
                        leanbackOverlayFragment.setShouldShowOverlay(false);
                        leanbackOverlayFragment.hideOverlay();
                        showQuickChannelChanger();
                        return true;
                    }
                }

                if (mPopupPanelVisible && !mGuideVisible && keyCode == KeyEvent.KEYCODE_DPAD_LEFT && mPopupRowPresenter.getPosition() == 0) {
                    mPopupRowsFragment.requireView().requestFocus();
                    mPopupRowPresenter.setPosition(0);
                    return true;
                }

                if (mGuideVisible) {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        hideGuide();
                        return true;
                    } else if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) &&
                            (mSelectedProgram != null && mSelectedProgram.getChannelId() != null)) {
                        switchChannel(mSelectedProgram.getChannelId());
                        return true;
                    } else {
                        return true;
                    }
                }

                if (playbackController != null && playbackController.isLiveTv() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y)) {
                    showGuide();
                    return true;
                }

                if (mPopupPanelVisible) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        hidePopupPanel();
                        if (playbackController != null && playbackController.isLiveTv()) hide();
                        return true;
                    } else {
                        return false;
                    }
                }

                if (!mIsVisible) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        leanbackOverlayFragment.setShouldShowOverlay(true);
                        show();
                        leanbackOverlayFragment.showControlsOverlay(true);
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        leanbackOverlayFragment.setShouldShowOverlay(false);
                        if (playbackController != null) {
                            playbackController.playPause();
                        }
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (playbackController != null) playbackController.playPause();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                        if (playbackController != null) playbackController.play(0);
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        if (playbackController != null) playbackController.pause();
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
                        leanbackOverlayFragment.setShouldShowOverlay(false);
                        if (binding.skipOverlay.getVisible()) {
                            binding.skipOverlay.focusSkipButton();
                        } else if (playbackController != null) {
                            playbackController.fastForward();
                            setFadingEnabled(true);
                        }
                        return true;
                    }

                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
                        leanbackOverlayFragment.setShouldShowOverlay(false);
                        if (binding.skipOverlay.getVisible()) {
                            return true;
                        } else if (playbackController != null) {
                            playbackController.rewind();
                            setFadingEnabled(true);
                        }
                        return true;
                    }
                } else {
                    leanbackOverlayFragment.setShouldShowOverlay(true);
                }

                if (mFadeEnabled) startFadeTimer();
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    leanbackOverlayFragment.getPlayerGlue().setInjectedViewsVisibility();
            }

            return false;
        }
    };

    public LocalDateTime getCurrentLocalStartDate() {
        return mCurrentGuideStart;
    }

    public LocalDateTime getCurrentLocalEndDate() {
        return mCurrentGuideEnd;
    }

    public void switchChannel(UUID id) {
        switchChannel(id, true);
    }

    public void switchChannel(UUID id, boolean hideGuide) {
        if (playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem().getId().equals(id)) {
            // same channel, just dismiss overlay
            if (hideGuide)
                hideGuide();
        } else {
            playbackControllerContainer.getValue().getPlaybackController().stop();
            if (hideGuide)
                hideGuide();

            CustomPlaybackOverlayFragmentHelperKt.playChannel(this, id);
        }
    }

    private void startFadeTimer() {
        mFadeEnabled = true;
        mHandler.removeCallbacks(mHideTask);
        mHandler.postDelayed(mHideTask, 6000);
        if (binding != null && binding.skipOverlay != null) {
            binding.skipOverlay.resetAutoHideTimer();
        }
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), false);
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView()).hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onResume() {
        super.onResume();

        // Close player when resuming without a valid playback controller
        if (playbackControllerContainer.getValue().getPlaybackController() == null || !playbackControllerContainer.getValue().getPlaybackController().hasFragment()) {
            closePlayer();

            return;
        }

        // Hide system bars
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), false);
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        if (mAudioManager.requestAudioFocus(mAudioFocusChanged, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.e("Unable to get audio focus");
            Utils.showToast(requireContext(), R.string.msg_cannot_play_time);
            return;
        }

        if (leanbackOverlayFragment != null) {
            mIsVisible = false;
            leanbackOverlayFragment.setShouldShowOverlay(false);
            leanbackOverlayFragment.hideOverlay();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mItemsToPlay == null || mItemsToPlay.isEmpty()) return;

        PlaybackController pc = playbackControllerContainer.getValue().getPlaybackController();
        boolean isExiting = isRemoving() || (getActivity() != null && getActivity().isFinishing()) || (pc != null && (pc.getPlaybackState() == PlaybackController.PlaybackState.IDLE || pc.getPlaybackState() == PlaybackController.PlaybackState.UNDEFINED));

        if (pc != null && !isExiting) {
            pc.pause();
        }

        setPlayPauseActionState(0);

        // give back audio focus
        mAudioManager.abandonAudioFocus(mAudioFocusChanged);
    }

    @Override
    public void onStop() {
        super.onStop();
        Timber.i("Stopping!");

        if (leanbackOverlayFragment != null)
            leanbackOverlayFragment.setOnKeyInterceptListener(null);
    }

    public void show() {
        // Already showing!
        if (mIsVisible) return;

        binding.topPanel.startAnimation(slideDown);
        mIsVisible = true;
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
        binding.skipOverlay.resetAutoHideTimer();
    }

    public void hideOverlayAndFocusPlayer() {
        if (leanbackOverlayFragment != null) {
            leanbackOverlayFragment.hideOverlay();
        }
        if (mIsVisible) {
            hide();
        } else if (binding != null && binding.skipOverlay != null && binding.skipOverlay.getVisible()) {
            binding.skipOverlay.focusSkipButton();
        } else {
            focusPlayer();
        }
    }

    public void hide() {
        // Can't hide what's already hidden
        if (!mIsVisible) return;

        mIsVisible = false;
        binding.topPanel.startAnimation(fadeOut);
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
        binding.skipOverlay.resetAutoHideTimer();
        if (binding.skipOverlay.getVisible()) {
            binding.skipOverlay.focusSkipButton();
        } else {
            focusPlayer();
        }
    }

    private void showChapterPanel() {
        setFadingEnabled(false);
        binding.popupArea.startAnimation(showPopup);
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
    }

    private void hidePopupPanel() {
        startFadeTimer();
        binding.popupArea.startAnimation(hidePopup);
        mPopupPanelVisible = false;
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
    }

    public boolean isGuideVisible() {
        return mGuideVisible;
    }

    public void showGuide() {
        hide();
        leanbackOverlayFragment.setShouldShowOverlay(false);
        leanbackOverlayFragment.hideOverlay();
        playbackControllerContainer.getValue().getPlaybackController().mVideoManager.contractVideo(Utils.convertDpToPixel(requireContext(), 300));
        mGuideComposeView.setVisibility(View.VISIBLE);
        mGuideVisible = true;
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
    }

    public void hideGuide() {
        mGuideComposeView.setVisibility(View.GONE);
        playbackControllerContainer.getValue().getPlaybackController().mVideoManager.setVideoFullSize(true);
        mGuideVisible = false;
        binding.skipOverlay.setSkipUiEnabled(!mGuideVisible && !mPopupPanelVisible);
    }

    private Animation.AnimationListener hideAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            binding.topPanel.setVisibility(View.GONE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    private Animation.AnimationListener showAnimationListener = new Animation.AnimationListener() {
        @Override
        public void onAnimationStart(Animation animation) {
        }

        @Override
        public void onAnimationEnd(Animation animation) {
            binding.topPanel.setVisibility(View.VISIBLE);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }
    };

    public void showQuickChannelChanger() {
        showChapterPanel();
        mHandler.postDelayed(() -> {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

            int ndx = TvManager.getAllChannelsIndex(TvManager.getLastLiveTvChannel());
            if (ndx > 0) {
                mPopupRowPresenter.setPosition(ndx);
            }
            mPopupPanelVisible = true;
        }, 500);
    }

    public void showChapterSelector() {
        showChapterPanel();
        mHandler.postDelayed(() -> {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

            int ndx = getCurrentChapterIndex(playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem(), playbackControllerContainer.getValue().getPlaybackController().getCurrentPosition() * 10000);
            if (ndx > 0) {
                mPopupRowPresenter.setPosition(ndx);
            }
            mPopupPanelVisible = true;
        }, 500);
    }

    private int getCurrentChapterIndex(BaseItemDto item, long pos) {
        int ndx = 0;
        Timber.d("*** looking for chapter at pos: %d", pos);
        if (item.getChapters() != null) {
            for (ChapterInfo chapter : item.getChapters()) {
                Timber.d("*** chapter %d has pos: %d", ndx, chapter.getStartPositionTicks());
                if (chapter.getStartPositionTicks() > pos) return ndx - 1;
                ndx++;
            }
        }
        return ndx - 1;
    }

    public void toggleRecording(BaseItemDto item) {
        final BaseItemDto program = item.getCurrentProgram();

        if (program != null) {
            if (program.getTimerId() != null) {
                // cancel
                if (program.getSeriesTimerId() != null) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_cancel_recording)
                            .setMessage(R.string.msg_cancel_entire_series)
                            .setPositiveButton(R.string.lbl_cancel_series, (dialog, which) -> cancelRecording(program, true))
                            .setNegativeButton(R.string.just_one, (dialog, which) -> cancelRecording(program, false))
                            .show();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_cancel_recording)
                            .setPositiveButton(R.string.lbl_yes, (dialog, which) -> cancelRecording(program, false))
                            .setNegativeButton(R.string.lbl_no, null)
                            .show();
                }
            } else {
                if (Utils.isTrue(program.isSeries())) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.lbl_record_series)
                            .setMessage(R.string.msg_record_entire_series)
                            .setPositiveButton(R.string.lbl_record_series, (dialog, which) -> CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, true))
                            .setNegativeButton(R.string.lbl_just_this_once, (dialog, which) -> CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, false))
                            .show();
                } else {
                    CustomPlaybackOverlayFragmentHelperKt.recordProgram(this, program, false);
                }
            }
        }
    }

    private void cancelRecording(BaseItemDto program, boolean series) {
        if (program != null) {
            if (series) {
                CustomPlaybackOverlayFragmentHelperKt.cancelSeriesTimer(this, program.getSeriesTimerId());
            } else {
                CustomPlaybackOverlayFragmentHelperKt.cancelTimer(this, program.getTimerId());
            }
        }
    }

    public void setCurrentTime(long time) {
        binding.skipOverlay.setCurrentPositionMs(time);
        if (leanbackOverlayFragment != null)
            leanbackOverlayFragment.updateCurrentPosition();
    }

    public void setSecondaryTime(long time) {
    }

    public void setFadingEnabled(boolean value) {
        mFadeEnabled = value;
        if (mFadeEnabled) {
            startFadeTimer();
        } else {
            mHandler.removeCallbacks(mHideTask);
        }
    }

    public void resetPlayStateToast() {
        CustomPlaybackOverlayFragmentHelperKt.resetPlayStateToast(this);
    }

    public void setPlayPauseActionState(final int state) {
        leanbackOverlayFragment.updatePlayState();
        PlaybackController pc = playbackControllerContainer.getValue().getPlaybackController();
        if (pc != null) {
            CustomPlaybackOverlayFragmentHelperKt.onPlayStateChanged(this, pc.isPlaying());
        }
    }

    public void updateDisplay() {
        if (getContext() == null || !isAdded()) return;
        BaseItemDto current = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
        if (current != null && getContext() != null) {
            leanbackOverlayFragment.mediaInfoChanged();
            leanbackOverlayFragment.onFullyInitialized();
            leanbackOverlayFragment.recordingStateChanged();
            // set progress to match duration
            // set other information

            // Update the title and subtitle
            boolean isLocal = LocalVideoManager.isLocalItem(current);
            String titleName = current.getName();
            if (titleName != null) {
                int lastDot = titleName.lastIndexOf('.');
                if (lastDot > 0) {
                    String ext = titleName.substring(lastDot + 1).toLowerCase(Locale.ROOT);
                    if (ext.equals("mkv") || ext.equals("mp4") || ext.equals("avi") || ext.equals("mov") || ext.equals("wmv") || ext.equals("flv") || ext.equals("webm") || ext.equals("ts") || ext.equals("m2ts") || ext.equals("m4v")) {
                        titleName = titleName.substring(0, lastDot);
                    }
                }
            }

            if (isLocal) {
                binding.itemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
                binding.itemTitle.setText(titleName);
                binding.itemSubtitle.setText("");
                binding.itemSubtitle.setVisibility(View.GONE);
            } else if (current.getType() == BaseItemKind.EPISODE) {
                binding.itemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
                binding.itemTitle.setText(current.getSeriesName());
                binding.itemSubtitle.setText(BaseItemExtensionsKt.getDisplayName(current, requireContext()));
                binding.itemSubtitle.setVisibility(View.VISIBLE);
            } else {
                binding.itemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
                binding.itemTitle.setText(titleName);
                binding.itemSubtitle.setText("");
                binding.itemSubtitle.setVisibility(View.GONE);
            }
            // Update the logo
            String imageUrl = imageHelper.getValue().getLogoImageUrl(current, 440);
            if (imageUrl != null) {
                binding.itemLogo.setVisibility(View.VISIBLE);
                binding.itemTitle.setVisibility(View.GONE);
                binding.itemLogo.setContentDescription(current.getName());
                binding.itemLogo.load(imageUrl, null, null, 1.0, 0);
            } else {
                binding.itemLogo.setVisibility(View.GONE);
                binding.itemTitle.setVisibility(View.VISIBLE);
            }

            if (playbackControllerContainer.getValue().getPlaybackController().isLiveTv()) {
                prepareChannelAdapter();
            } else {
                prepareChapterAdapter();
            }
        }
    }

    public void clearSkipOverlay() {
        binding.skipOverlay.setTargetPositionMs(null);
    }

    private void prepareChapterAdapter() {
        BaseItemDto item = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item.getChapters();

        if (chapters != null && !chapters.isEmpty()) {
            // create chapter row for later use
            ArrayObjectAdapter chapterAdapter = new ArrayObjectAdapter(new CardPresenter(true, 80));
            for (org.jellyfin.androidtv.data.model.ChapterItemInfo chapter : BaseItemExtensionsKt.buildChapterItems(item)) {
                chapterAdapter.add(new ChapterItemInfoBaseRowItem(chapter));
            }
            if (mChapterRow != null) mPopupRowAdapter.remove(mChapterRow);
            mChapterRow = new ListRow(new HeaderItem(requireContext().getString(R.string.chapters)), chapterAdapter);
            mPopupRowAdapter.add(mChapterRow);
        }

    }

    private void prepareChannelAdapter() {
        // create quick channel change row
        TvManager.loadAllChannels(this, response -> {
            List<BaseItemDto> channels = TvManager.getAllChannels();
            if (channels == null) return null;
            ArrayObjectAdapter channelAdapter = new ArrayObjectAdapter(new ChannelCardPresenter());
            channelAdapter.addAll(0, channels);
            if (mChapterRow != null) mPopupRowAdapter.remove(mChapterRow);
            mChapterRow = new ListRow(new HeaderItem(requireContext().getString(R.string.channels)), channelAdapter);
            mPopupRowAdapter.add(mChapterRow);
            return null;
        });
    }

    public void closePlayer() {
        if (navigating) return;
        navigating = true;

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController != null) {
            playbackController.stop();
        }

        BaseItemDto lastPlayedItem = playbackController != null ? playbackController.getCurrentlyPlayingItem() : null;
        if (lastPlayedItem == null) {
            List<BaseItemDto> queue = videoQueueManager.getValue().getCurrentVideoQueue();
            int pos = videoQueueManager.getValue().getCurrentMediaPosition();
            if (pos >= 0 && pos < queue.size()) {
                lastPlayedItem = queue.get(pos);
            }
        }

        if (lastPlayedItem != null && lastPlayedItem.getType() != BaseItemKind.TRAILER) {
            navigationRepository.getValue().navigate(Destinations.INSTANCE.itemDetails(lastPlayedItem.getId()), true);
        } else if (navigationRepository.getValue().getCanGoBack()) {
            navigationRepository.getValue().goBack();
        } else {
            navigationRepository.getValue().reset(Destinations.INSTANCE.getHome());
        }
    }

    public void showNextUp(@NonNull UUID id) {
        if (navigating) return;
        navigating = true;

        navigationRepository.getValue().navigate(Destinations.INSTANCE.nextUp(id), true);
    }

    public void showStillWatching(@NonNull UUID id) {
        if (navigating) return;
        navigating = true;

        navigationRepository.getValue().navigate(Destinations.INSTANCE.stillWatching(id), true);
    }

    public void showBuffering(boolean show) {
        if (binding == null) return;
        CustomPlaybackOverlayFragmentHelperKt.showBuffering(this, show);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mHandler.removeCallbacksAndMessages(null);

        // end playback from here if this fragment belongs to the current session.
        if (playbackControllerContainer.getValue().getPlaybackController() != null && playbackControllerContainer.getValue().getPlaybackController().getFragment() == this) {
            Timber.i("Destroying fragment: ending playback session");
            playbackControllerContainer.getValue().getPlaybackController().endPlayback();
        }

        // Show system bars
        WindowCompat.setDecorFitsSystemWindows(requireActivity().getWindow(), true);
        WindowCompat.getInsetsController(requireActivity().getWindow(), requireActivity().getWindow().getDecorView()).show(WindowInsetsCompat.Type.systemBars());

        // Reset display mode
        WindowManager.LayoutParams params = getActivity().getWindow().getAttributes();
        params.preferredDisplayModeId = 0;
        getActivity().getWindow().setAttributes(params);
    }
}
