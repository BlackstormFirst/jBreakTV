package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.inject;

import android.content.Context;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.UserSettingPreferences;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;

import java.util.List;

import kotlin.Lazy;
import timber.log.Timber;

public class PlaybackIndexManager {

    private Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private Lazy<VideoQueueManager> videoQueueManager = inject(VideoQueueManager.class);

    public Integer getBestAudioIndex(MediaSourceInfo info) {
        var userAudioLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getAudioLangRemoteSetting());
        var userAudioAlwaysDefaultRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getUserAlwaysUseAudioDefault());
        var userSubMode = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubMode());
        Timber.i("Remote user audio lang settings: %s", userAudioLangRemoteSetting);
        Timber.i("Remote user audio always default settings: %s", userAudioAlwaysDefaultRemoteSetting);
        if (info != null && info.getMediaStreams() != null) {
            String lastAudioLanguage = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
            String lastAudioCodec = videoQueueManager.getValue().getLastPlayedAudioCodec();
            List<MediaStream> allAudioStreams = info.getMediaStreams().stream().filter(stream -> stream.getType() == MediaStreamType.AUDIO).toList();
            if ((allAudioStreams != null) && (lastAudioLanguage != null) && (lastAudioCodec != null)) {
                // find the best matching audio stream
                Boolean lastAudioDefaultState = videoQueueManager.getValue().getLastPlayedAudioDefaultState();
                Boolean lastAudioHearingImpairedState = videoQueueManager.getValue().getLastPlayedAudioHearingImpairedState();
                Integer matchingIndex = null;

                if (userSubMode.equals(R.string.subtitle_mode_smart)) {
                    if (!userAudioLangRemoteSetting.equals(lastAudioLanguage)) {
                        for (MediaStream stream : allAudioStreams) {
                            if (userAudioLangRemoteSetting.equals(stream.getLanguage())
                                    && lastAudioCodec.equals(stream.getCodec())
                                    && lastAudioDefaultState.equals(stream.isDefault())
                                    && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                            ) {
                                Timber.d("Best smart audio found ! (ulang+all)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                        if (matchingIndex == null) {
                            // fallback #1: find the first audio stream with the requested language, codec and SDH indicator
                            for (MediaStream stream : allAudioStreams) {
                                if (lastAudioLanguage.equals(stream.getLanguage())
                                        && lastAudioCodec.equals(stream.getCodec())
                                        && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                                ) {
                                    Timber.d("Best audio found on fallback #1 (ulang+codec+SDH)");
                                    matchingIndex = stream.getIndex();
                                    break;
                                }
                            }
                        }
                        if (matchingIndex == null) {
                            // fallback #2: find the first audio stream with the requested language, codec and SDH indicator
                            for (MediaStream stream : allAudioStreams) {
                                if (lastAudioLanguage.equals(stream.getLanguage())
                                        && lastAudioCodec.equals(stream.getCodec())
                                        && lastAudioDefaultState.equals(stream.isDefault())
                                ) {
                                    Timber.d("Best audio found on fallback #2 (ulang+codec+Default)");
                                    matchingIndex = stream.getIndex();
                                    break;
                                }
                            }
                        }
                        if (matchingIndex == null) {
                            // fallback #3: find the first audio stream with the requested language, codec and SDH indicator
                            for (MediaStream stream : allAudioStreams) {
                                if (lastAudioLanguage.equals(stream.getLanguage())
                                        && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                                ) {
                                    Timber.d("Best audio found on fallback #3 (ulang+SDH)");
                                    matchingIndex = stream.getIndex();
                                    break;
                                }
                            }
                        }
                        if (matchingIndex == null) {
                            // fallback #4: find the first audio stream with the requested language, codec and SDH indicator
                            for (MediaStream stream : allAudioStreams) {
                                if (lastAudioLanguage.equals(stream.getLanguage())
                                        && lastAudioDefaultState.equals(stream.isDefault())
                                ) {
                                    Timber.d("Best audio found on fallback #4 (ulang+Default)");
                                    matchingIndex = stream.getIndex();
                                    break;
                                }
                            }
                        }
                    }
                }
                if (matchingIndex == null) {
                    // find the exact audio stream with the requested language, codec & indicators
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioDefaultState.equals(stream.isDefault())
                                && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                        ) {
                            Timber.d("Best audio found ! (lang+all)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #1: find the first audio stream with the requested language, codec and SDH indicator
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                        ) {
                            Timber.d("Best audio found on fallback #1 (lang+codec+SDH)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #2: find the first audio stream with the requested language and codec but with only 'default' indicator
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioDefaultState.equals(stream.isDefault())
                        ) {
                            Timber.d("Best audio found on fallback #2 (lang+codec+default)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #3: find the first audio stream with the requested language and codec
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                        ) {
                            Timber.d("Best audio found on fallback #3 (lang+codec)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #4: find the first audio stream with the requested language and SDH indicator
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioDefaultState.equals(stream.isDefault())
                                && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                        ) {
                            Timber.d("Best audio found on fallback #4 (lang+default+SDH)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #5: find the first audio stream with the requested language and 'default' indicator
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioDefaultState.equals(stream.isDefault())
                        ) {
                            Timber.d("Best audio found on fallback #5 (lang+default)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #6: find the first audio stream with the requested language (fallback to language only)
                    for (MediaStream stream : allAudioStreams) {
                        if (lastAudioLanguage.equals(stream.getLanguage())) {
                            Timber.d("Best audio found on fallback #6 (lang only)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #7: if audio languages are different but other indicators match
                    for (MediaStream stream : allAudioStreams) {
                        if (!lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioDefaultState.equals(stream.isDefault())
                                && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                        ) {
                            Timber.d("Best audio found on fallback #7 (!lang+all)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #8: if audio languages are different
                    for (MediaStream stream : allAudioStreams) {
                        if (!lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioDefaultState.equals(stream.isDefault())
                        ) {
                            Timber.d("Best audio found on fallback #8 (!lang+codec+default)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #9: if audio languages are different
                    for (MediaStream stream : allAudioStreams) {
                        if (!lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                                && lastAudioHearingImpairedState.equals(stream.isHearingImpaired())
                        ) {
                            Timber.d("Best audio found on fallback #9 (!lang+codec+SDH)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    // fallback #10: if audio languages are different
                    for (MediaStream stream : allAudioStreams) {
                        if (!lastAudioLanguage.equals(stream.getLanguage())
                                && lastAudioCodec.equals(stream.getCodec())
                        ) {
                            Timber.d("Best audio found on fallback #10 (!lang+codec)");
                            matchingIndex = stream.getIndex();
                            break;
                        }
                    }
                }
                Timber.i("Best audio found on index: %d for media: '%s'", matchingIndex, info.getName());
                return matchingIndex;
            }
            else {
                Integer matchingIndex = info.getDefaultAudioStreamIndex();
                Timber.i("Best audio found on server with index: %d for media: '%s'", matchingIndex, info.getName());
                return matchingIndex;
            }
        }
        return null;
    }

    public Integer getBestSubtitleIndex(MediaSourceInfo info, Context context) {
        Integer matchingIndex = null;
        var userAudioLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getAudioLangRemoteSetting());
        var userAudioAlwaysDefaultRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getUserAlwaysUseAudioDefault());
        var userSubLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubLangRemoteSetting());
        var userSubMode = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubMode());
        var userSubModeTitle = context.getString(userSubMode);
        Timber.i("Remote user sub lang settings: %s", userSubLangRemoteSetting);
        Timber.i("Remote user sub mod settings: %s", userSubModeTitle);
        String lastSubtitleLanguage = videoQueueManager.getValue().getLastPlayedSubtitleLanguageIsoCode();
        if (lastSubtitleLanguage != null) {
            if (lastSubtitleLanguage.isEmpty()) {
                // User explicitly disabled subtitles
                Timber.i("Best subtitle found on index: -1");
                return -1;
            } else if (info.getMediaStreams() != null) {
                // find the best matching subtitle stream
                String lastAudioLanguageIsoCode = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
                String lastSubtitleCodec = videoQueueManager.getValue().getLastPlayedSubtitleCodec();
                Boolean lastSubtitleDefaultState = videoQueueManager.getValue().getLastPlayedSubtitleDefaultState();
                Boolean lastSubtitleForcedState = videoQueueManager.getValue().getLastPlayedSubtitleForcedState();
                Boolean lastSubtitleHearingImpairedState = videoQueueManager.getValue().getLastPlayedSubtitleHearingImpairedState();
                String lastSubtitleTitle = videoQueueManager.getValue().getLastPlayedSubtitleTitle();
                List<MediaStream> allSubtitleStreams = info.getMediaStreams().stream().filter(s -> s.getType() == MediaStreamType.SUBTITLE).toList();
                if ((allSubtitleStreams != null) && (lastSubtitleCodec != null)) {
                    // find the exact subtitle stream with the requested language, title, codec & indicators
                    if (matchingIndex == null) {
                        if (userSubMode.equals(R.string.subtitle_mode_smart)){
                            if (!userAudioLangRemoteSetting.equals(lastAudioLanguageIsoCode)){
                                for (MediaStream stream : allSubtitleStreams) {
                                    if (userSubLangRemoteSetting.equals(stream.getLanguage())
                                            && lastSubtitleDefaultState.equals(stream.isDefault())
                                            && stream.isForced()
                                            && lastSubtitleCodec.equals(stream.getCodec())
                                            && lastSubtitleHearingImpairedState.equals(stream.isHearingImpaired())
                                    ) {
                                        Timber.d("Best smart subtitle found ! (lang+all)");
                                        matchingIndex = stream.getIndex();
                                        break;
                                    }
                                }
                                if (matchingIndex == null) {
                                    for (MediaStream stream : allSubtitleStreams) {
                                        if (userSubLangRemoteSetting.equals(stream.getLanguage())
                                                && stream.isForced()
                                                && lastSubtitleCodec.equals(stream.getCodec())
                                                && lastSubtitleHearingImpairedState.equals(stream.isHearingImpaired())
                                        ) {
                                            Timber.d("Best smart subtitle found ! (lang+forced+SDH)");
                                            matchingIndex = stream.getIndex();
                                            break;
                                        }
                                    }
                                }
                                else{
                                    Timber.d("Best smart subtitle found ! (subs disabled)");
                                    matchingIndex = -1;
                                }
                            }
                        }
                    }

                    if (matchingIndex == null) {
                        if (lastSubtitleTitle != null) {
                            for (MediaStream stream : allSubtitleStreams) {
                                if (lastSubtitleLanguage.equals(stream.getLanguage())
                                        && lastSubtitleDefaultState.equals(stream.isDefault())
                                        && lastSubtitleForcedState.equals(stream.isForced())
                                        && lastSubtitleCodec.equals(stream.getCodec())
                                        && lastSubtitleHearingImpairedState.equals(stream.isHearingImpaired())
                                        && lastSubtitleTitle.equals(stream.getTitle())
                                ) {
                                    Timber.d("Best subtitle found ! (lang+all)");
                                    matchingIndex = stream.getIndex();
                                    break;
                                }
                            }
                        }
                    }
                    // fallback #1: find the first subtitle stream with the requested language, codec and indicators but without title
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleDefaultState.equals(stream.isDefault())
                                    && lastSubtitleForcedState.equals(stream.isForced())
                                    && lastSubtitleCodec.equals(stream.getCodec())
                                    && lastSubtitleHearingImpairedState.equals(stream.isHearingImpaired())
                            ) {
                                Timber.d("Best subtitle found on fallback #1 (lang+codec+default+forced+SDH)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                    // fallback #2: find the first subtitle stream with the requested language, codec and indicators but without title and SDH
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleDefaultState.equals(stream.isDefault())
                                    && lastSubtitleForcedState.equals(stream.isForced())
                                    && lastSubtitleCodec.equals(stream.getCodec())
                            ) {
                                Timber.d("Best subtitle found on fallback #2 (lang+codec+default+forced)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                    // fallback #3: find the first subtitle stream with the requested language and standard indicators but without title, codec and SDH
                    // without codec, the first default forced
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleDefaultState.equals(stream.isDefault())
                                    && lastSubtitleForcedState.equals(stream.isForced())
                            ) {
                                Timber.d("Best subtitle found on fallback #3 (lang+default+forced)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleDefaultState.equals(stream.isDefault())
                                    && lastSubtitleCodec.equals(stream.getCodec())
                            ) {
                                Timber.d("Best subtitle found on fallback #4 (lang+codec+default)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                    // without codec, the first forced
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleForcedState.equals(stream.isForced())
                            ) {
                                Timber.d("Best subtitle found on fallback #5 (lang+forced)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                    if (matchingIndex == null) {
                        for (MediaStream stream : allSubtitleStreams) {
                            if (lastSubtitleLanguage.equals(stream.getLanguage())
                                    && lastSubtitleDefaultState.equals(stream.isDefault())
                                    && lastSubtitleCodec.equals(stream.getCodec())
                            ) {
                                Timber.d("Best subtitle found on fallback #6 (lang+codec)");
                                matchingIndex = stream.getIndex();
                                break;
                            }
                        }
                    }
                } else { Timber.d("Best subtitle not found: no subtitle stream"); }
            } else { Timber.d("Best subtitle not found: no stream"); }
        }else { Timber.d("Best subtitle: lastsubtitlelanguage is null"); }
        // No saved preference, use server default
        if (matchingIndex == null) {
            Timber.d("Best subtitle found on server side");
            matchingIndex = info.getDefaultSubtitleStreamIndex();
        }
        if(matchingIndex == null) matchingIndex = -1;
        Timber.i("Best subtitle found on index: %d for media: '%s'", matchingIndex, info.getName());
        return matchingIndex;
    }

    public Integer getBestVideoIndex(MediaSourceInfo info) {
        if (info != null && info.getMediaStreams() != null) {
            Boolean lastVideoDefaultState = videoQueueManager.getValue().getLastPlayedVideoDefaultState();
            List<MediaStream> allVideoStreams = info.getMediaStreams().stream().filter(stream -> stream.getType() == MediaStreamType.VIDEO).toList();
            if (allVideoStreams != null) {
                if (lastVideoDefaultState != null) {
                    for (MediaStream stream : allVideoStreams) {
                        if (lastVideoDefaultState.equals(stream.isDefault())) {
                            return stream.getIndex();
                        }
                    }
                }
                // fallback to server default
                for (MediaStream stream : allVideoStreams) {
                    if (stream.isDefault()) {
                        return stream.getIndex();
                    }
                }
                // last resort: first video stream
                if (!allVideoStreams.isEmpty()) return allVideoStreams.get(0).getIndex();
            }
        }
        return null;
    }
}
