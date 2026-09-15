package org.jellyfin.androidtv.ui.playback.overlay;

import androidx.annotation.NonNull;
import androidx.leanback.media.PlayerAdapter;

import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment;
import org.jellyfin.androidtv.ui.playback.PlaybackController;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.StreamHelper;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.ChapterInfo;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.koin.java.KoinJavaComponent;

import java.util.Collections;
import java.util.List;

public class VideoPlayerAdapter extends PlayerAdapter {

    private final PlaybackController playbackController;
    private CustomPlaybackOverlayFragment customPlaybackOverlayFragment;
    private LeanbackOverlayFragment leanbackOverlayFragment;

    VideoPlayerAdapter(PlaybackController playbackController, LeanbackOverlayFragment leanbackOverlayFragment) {
        this.playbackController = playbackController;
        this.leanbackOverlayFragment = leanbackOverlayFragment;
    }

    @Override
    public void play() {
        playbackController.play(playbackController.getCurrentPosition());
    }

    @Override
    public void pause() {
        playbackController.pause();
    }

    @Override
    public void rewind() {
        playbackController.rewind();
        updateCurrentPosition();
    }

    @Override
    public void fastForward() {
        playbackController.fastForward();
        updateCurrentPosition();
    }

    @Override
    public void seekTo(long positionInMs) {
        playbackController.seek(positionInMs);
        updateCurrentPosition();
    }

    @Override
    public void next() {
        playbackController.next();
    }

    @Override
    public void previous() {
        playbackController.prev();
    }

    @Override
    public long getDuration() {
        Long runTimeTicks = null;
        if (getCurrentMediaSource() != null) runTimeTicks = getCurrentMediaSource().getRunTimeTicks();
        if (runTimeTicks == null && getCurrentlyPlayingItem() != null) runTimeTicks = getCurrentlyPlayingItem().getRunTimeTicks();
        if (runTimeTicks != null) return runTimeTicks / 10000;
        return -1;
    }

    @Override
    public long getCurrentPosition() {
        return playbackController.getCurrentPosition();
    }

    @Override
    public boolean isPlaying() {
        return playbackController.isPlaying();
    }

    @Override
    public long getBufferedPosition() {
        return playbackController.getBufferedPosition();
    }

    void updateCurrentPosition() {
        getCallback().onCurrentPositionChanged(this);
        getCallback().onBufferedPositionChanged(this);
    }

    void updatePlayState() {
        getCallback().onPlayStateChanged(this);
    }

    void updateDuration() {
        getCallback().onDurationChanged(this);
    }

    // Internal helper to retrieve media streams (either via MediaSource or via BaseItemDto)
    private List<MediaStream> getMediaStreamsSafe() {
        MediaSourceInfo mediaSource = playbackController.getCurrentMediaSource();
        if (mediaSource != null && mediaSource.getMediaStreams() != null && !mediaSource.getMediaStreams().isEmpty()) {
            return mediaSource.getMediaStreams();
        }
        BaseItemDto item = getCurrentlyPlayingItem();
        if (item != null && item.getMediaStreams() != null && !item.getMediaStreams().isEmpty()) {
            return item.getMediaStreams();
        }
        return Collections.emptyList();
    }

    public boolean hasSubs() {
        List<MediaStream> streams = getMediaStreamsSafe();
        if (!streams.isEmpty()) {
            for (MediaStream stream : streams) {
                if (stream.getType() == MediaStreamType.SUBTITLE) {
                    return true;
                }
            }
            return false; // Local streams inspected: no subtitles found
        }

        // Server mode: fallback to original StreamHelper
        MediaSourceInfo mediaSource = playbackController.getCurrentMediaSource();
        if (mediaSource != null) {
            return StreamHelper.getSubtitleStreams(mediaSource).size() > 0;
        }
        return false;
    }

    public boolean hasMultiAudio() {
        List<MediaStream> streams = getMediaStreamsSafe();
        if (!streams.isEmpty()) {
            int audioCount = 0;
            for (MediaStream stream : streams) {
                if (stream.getType() == MediaStreamType.AUDIO) {
                    audioCount++;
                }
            }
            return audioCount > 1;
        }

        // Server mode: fallback to original StreamHelper
        MediaSourceInfo mediaSource = playbackController.getCurrentMediaSource();
        if (mediaSource != null) {
            return StreamHelper.getAudioStreams(mediaSource).size() > 1;
        }
        return false;
    }

    public boolean hasMultiVideo() {
        List<MediaStream> streams = getMediaStreamsSafe();
        if (!streams.isEmpty()) {
            int videoCount = 0;
            for (MediaStream stream : streams) {
                if (stream.getType() == MediaStreamType.VIDEO) {
                    videoCount++;
                }
            }
            // Strictly more than 1 track: if only 1 track, return FALSE immediately
            return videoCount > 1;
        }

        // Server mode: fallback to StreamInfo if available
        if (playbackController.getCurrentStreamInfo() != null) {
            return playbackController.getCurrentStreamInfo().getSelectableStreams(MediaStreamType.VIDEO).size() > 1;
        }
        return false;
    }

    boolean hasNextItem() {
        return playbackController.hasNextItem();
    }

    boolean hasPreviousItem() {
        return playbackController.hasPreviousItem();
    }

    boolean canSeek() {
        return playbackController.canSeek();
    }

    boolean isLiveTv() {
        return playbackController.isLiveTv();
    }

    void setMasterOverlayFragment(CustomPlaybackOverlayFragment customPlaybackOverlayFragment) {
        this.customPlaybackOverlayFragment = customPlaybackOverlayFragment;
    }

    @NonNull
    public CustomPlaybackOverlayFragment getMasterOverlayFragment() {
        return customPlaybackOverlayFragment;
    }

    @NonNull
    public LeanbackOverlayFragment getLeanbackOverlayFragment() {
        return leanbackOverlayFragment;
    }

    @Override
    public void onDetachedFromHost() {
        customPlaybackOverlayFragment = null;
        leanbackOverlayFragment = null;
    }

    boolean canRecordLiveTv() {
        BaseItemDto currentlyPlayingItem = getCurrentlyPlayingItem();
        return currentlyPlayingItem != null
                && currentlyPlayingItem.getCurrentProgram() != null
                && Utils.canManageRecordings(KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue());
    }

    public void toggleRecording() {
        BaseItemDto currentlyPlayingItem = getCurrentlyPlayingItem();
        if (currentlyPlayingItem != null) {
            getMasterOverlayFragment().toggleRecording(currentlyPlayingItem);
        }
    }

    boolean isRecording() {
        BaseItemDto currentlyPlayingItem = getCurrentlyPlayingItem();
        if (currentlyPlayingItem == null) return false;
        BaseItemDto currentProgram = currentlyPlayingItem.getCurrentProgram();
        if (currentProgram == null) {
            return false;
        } else {
            return currentProgram.getTimerId() != null;
        }
    }

    BaseItemDto getCurrentlyPlayingItem() {
        return playbackController.getCurrentlyPlayingItem();
    }

    MediaSourceInfo getCurrentMediaSource() {
        return playbackController.getCurrentMediaSource();
    }

    boolean hasChapters() {
        BaseItemDto item = getCurrentlyPlayingItem();
        if (item == null) return false;
        List<ChapterInfo> chapters = item.getChapters();
        return chapters != null && chapters.size() > 0;
    }
}
