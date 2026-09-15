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

    private boolean areLanguagesEqual(String lang1, String lang2) {
        if (lang1 == null || lang2 == null) return false;
        if (lang1.equalsIgnoreCase(lang2)) return true;
        if (lang1.length() >= 2 && lang2.length() >= 2) {
            if (lang1.substring(0, 2).equalsIgnoreCase(lang2.substring(0, 2))) {
                return true;
            }
        }
        String l1 = lang1.toLowerCase();
        String l2 = lang2.toLowerCase();
        return (l1.equals("fre") && l2.equals("fra")) || (l1.equals("fra") && l2.equals("fre"))
                || (l1.equals("ger") && l2.equals("deu")) || (l1.equals("deu") && l2.equals("ger"))
                || (l1.equals("chi") && l2.equals("zho")) || (l1.equals("zho") && l2.equals("chi"));
    }

    private Integer matchAudioStream(List<MediaStream> streams, String targetLang, String targetCodec, Boolean targetDefault, Boolean targetHI) {
        if (streams == null || streams.isEmpty()) return null;

        // 1. targetLang + codec + default + HI
        if (targetLang != null && targetCodec != null && targetDefault != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best audio found ! (lang+codec+default+HI)");
                    return s.getIndex();
                }
            }
        }

        // 2. targetLang + codec + HI
        if (targetLang != null && targetCodec != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best audio found ! (lang+codec+HI)");
                    return s.getIndex();
                }
            }
        }

        // 3. targetLang + codec + default
        if (targetLang != null && targetCodec != null && targetDefault != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())) {
                    Timber.d("Best audio found ! (lang+codec+default)");
                    return s.getIndex();
                }
            }
        }

        // 4. targetLang + codec
        if (targetLang != null && targetCodec != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())) {
                    Timber.d("Best audio found ! (lang+codec)");
                    return s.getIndex();
                }
            }
        }

        // 5. targetLang + default + HI
        if (targetLang != null && targetDefault != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetDefault.equals(s.isDefault())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best audio found ! (lang+default+HI)");
                    return s.getIndex();
                }
            }
        }

        // 6. targetLang + default
        if (targetLang != null && targetDefault != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetDefault.equals(s.isDefault())) {
                    Timber.d("Best audio found ! (lang+default)");
                    return s.getIndex();
                }
            }
        }

        // 7. targetLang + HI
        if (targetLang != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best audio found ! (lang+HI)");
                    return s.getIndex();
                }
            }
        }

        // 8. targetLang only
        if (targetLang != null && !targetLang.isEmpty()) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())) {
                    Timber.d("Best audio found ! (lang only)");
                    return s.getIndex();
                }
            }
        }

        // --- PHASE 2 : Codec priority over another language (if target language was not found) ---
        if (targetCodec != null && !targetCodec.isEmpty()) {

            // 9. !targetLang + codec + default + HI
            if (targetDefault != null && targetHI != null) {
                for (MediaStream s : streams) {
                    if (targetCodec.equalsIgnoreCase(s.getCodec())
                            && targetDefault.equals(s.isDefault())
                            && targetHI.equals(s.isHearingImpaired())) {
                        Timber.d("Best audio found on codec fallback (!lang+codec+default+HI)");
                        return s.getIndex();
                    }
                }
            }

            // 10. !targetLang + codec + HI
            if (targetHI != null) {
                for (MediaStream s : streams) {
                    if (targetCodec.equalsIgnoreCase(s.getCodec())
                            && targetHI.equals(s.isHearingImpaired())) {
                        Timber.d("Best audio found on codec fallback (!lang+codec+HI)");
                        return s.getIndex();
                    }
                }
            }

            // 11. !targetLang + codec + default
            if (targetDefault != null) {
                for (MediaStream s : streams) {
                    if (targetCodec.equalsIgnoreCase(s.getCodec())
                            && targetDefault.equals(s.isDefault())) {
                        Timber.d("Best audio found on codec fallback (!lang+codec+default)");
                        return s.getIndex();
                    }
                }
            }

            // 12. !targetLang + codec seul
            for (MediaStream s : streams) {
                if (targetCodec.equalsIgnoreCase(s.getCodec())) {
                    Timber.d("Best audio found on codec fallback (!lang+codec)");
                    return s.getIndex();
                }
            }
        }

        return null;
    }

    public Integer getBestAudioIndex(MediaSourceInfo info) {
        if (info == null || info.getMediaStreams() == null) return null;

        List<MediaStream> allAudioStreams = info.getMediaStreams().stream().filter(stream -> stream.getType() == MediaStreamType.AUDIO).toList();
        if (allAudioStreams == null || allAudioStreams.isEmpty()) return null;

        var userAudioLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getAudioLangRemoteSetting());
        boolean userAudioAlwaysDefaultRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getUserAlwaysUseAudioDefault());
        var userSubMode = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubMode());

        Timber.i("Remote user audio lang settings: %s", userAudioLangRemoteSetting);
        Timber.i("Remote user audio always default settings: %b", userAudioAlwaysDefaultRemoteSetting);

        String lastAudioLanguage = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        String lastAudioCodec = videoQueueManager.getValue().getLastPlayedAudioCodec();
        Boolean lastAudioDefaultState = videoQueueManager.getValue().getLastPlayedAudioDefaultState();
        Boolean lastAudioHearingImpairedState = videoQueueManager.getValue().getLastPlayedAudioHearingImpairedState();

        Integer matchingIndex = null;

        // Mode SMART or User prefers default audio track
        if (userSubMode.equals(R.string.subtitle_mode_smart)) {
            if (userAudioAlwaysDefaultRemoteSetting) {
                for (MediaStream stream : allAudioStreams) {
                    if (stream.isDefault()) {
                        Timber.d("Best smart audio found (Always Use Default)!");
                        matchingIndex = stream.getIndex();
                        break;
                    }
                }
            }

            if (matchingIndex == null && !userAudioLangRemoteSetting.isEmpty()) {
                matchingIndex = matchAudioStream(allAudioStreams, userAudioLangRemoteSetting, lastAudioCodec, lastAudioDefaultState, lastAudioHearingImpairedState);
            }
        }

        // Mode DEFAULT / Queue persistence (or fallback if SMART didn't find matching language)
        if (matchingIndex == null && lastAudioLanguage != null && !lastAudioLanguage.isEmpty()) {
            matchingIndex = matchAudioStream(allAudioStreams, lastAudioLanguage, lastAudioCodec, lastAudioDefaultState, lastAudioHearingImpairedState);
        }

        // General Fallbacks
        if (matchingIndex == null) {
            // Fallback 1: Stream marked default
            for (MediaStream stream : allAudioStreams) {
                if (stream.isDefault()) {
                    matchingIndex = stream.getIndex();
                    Timber.d("Best audio found on fallback (stream default)");
                    break;
                }
            }
        }

        if (matchingIndex == null) {
            // Fallback 2: Server default audio index
            matchingIndex = info.getDefaultAudioStreamIndex();
            if (matchingIndex != null) {
                Timber.d("Best audio found on fallback (server default index: %d)", matchingIndex);
            }
        }

        if (matchingIndex == null && !allAudioStreams.isEmpty()) {
            // Fallback 3: First audio stream
            matchingIndex = allAudioStreams.get(0).getIndex();
            Timber.d("Best audio found on fallback (first audio stream)");
        }

        Timber.i("Best audio found on index: %s for media: '%s'", matchingIndex, info.getName());
        return matchingIndex;
    }

    private Integer matchSubtitleStream(List<MediaStream> streams, String targetLang, String targetTitle, String targetCodec, Boolean targetDefault, Boolean targetForced, Boolean targetHI) {
        if (streams == null || streams.isEmpty() || targetLang == null || targetLang.isEmpty()) return null;

        // 1. targetLang + title + codec + default + forced + HI
        if (targetTitle != null && targetCodec != null && targetDefault != null && targetForced != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetTitle.equalsIgnoreCase(s.getTitle())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())
                        && targetForced.equals(s.isForced())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best sub found ! (lang+title+codec+default+forced+HI)");
                    return s.getIndex();
                }
            }
        }

        // 2. targetLang + codec + default + forced + HI
        if (targetCodec != null && targetDefault != null && targetForced != null && targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())
                        && targetForced.equals(s.isForced())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best sub found ! (lang+codec+default+forced+HI)");
                    return s.getIndex();
                }
            }
        }

        // 3. targetLang + codec + default + forced
        if (targetCodec != null && targetDefault != null && targetForced != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())
                        && targetForced.equals(s.isForced())) {
                    Timber.d("Best sub found ! (lang+codec+default+forced)");
                    return s.getIndex();
                }
            }
        }

        // 4. targetLang + codec + default
        if (targetCodec != null && targetDefault != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetDefault.equals(s.isDefault())) {
                    Timber.d("Best sub found ! (lang+codec+default)");
                    return s.getIndex();
                }
            }
        }

        // 5. targetLang + codec + forced
        if (targetCodec != null && targetForced != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())
                        && targetForced.equals(s.isForced())) {
                    Timber.d("Best sub found ! (lang+codec+forced)");
                    return s.getIndex();
                }
            }
        }

        // 6. targetLang + codec
        if (targetCodec != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetCodec.equalsIgnoreCase(s.getCodec())) {
                    Timber.d("Best sub found ! (lang+codec)");
                    return s.getIndex();
                }
            }
        }

        // 7. targetLang + default + forced
        if (targetDefault != null && targetForced != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetDefault.equals(s.isDefault())
                        && targetForced.equals(s.isForced())) {
                    Timber.d("Best sub found ! (lang+default+forced)");
                    return s.getIndex();
                }
            }
        }

        // 8. targetLang + default
        if (targetDefault != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetDefault.equals(s.isDefault())) {
                    Timber.d("Best sub found ! (lang+default)");
                    return s.getIndex();
                }
            }
        }

        // 9. targetLang + forced
        if (targetForced != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetForced.equals(s.isForced())) {
                    Timber.d("Best sub found ! (lang+forced)");
                    return s.getIndex();
                }
            }
        }

        // 10. targetLang + HI
        if (targetHI != null) {
            for (MediaStream s : streams) {
                if (areLanguagesEqual(targetLang, s.getLanguage())
                        && targetHI.equals(s.isHearingImpaired())) {
                    Timber.d("Best sub found ! (lang+HI)");
                    return s.getIndex();
                }
            }
        }

        // 11. targetLang only
        for (MediaStream s : streams) {
            if (areLanguagesEqual(targetLang, s.getLanguage())) {
                Timber.d("Best sub found ! (lang only)");
                return s.getIndex();
            }
        }

        return null;
    }

    public Integer getBestSubtitleIndex(MediaSourceInfo info, Context context) {
        return getBestSubtitleIndex(info, context, null);
    }

    public Integer getBestSubtitleIndex(MediaSourceInfo info, Context context, String currentAudioLanguage) {
        if (info == null || info.getMediaStreams() == null) {
            return -1;
        }

        List<MediaStream> allSubtitleStreams = info.getMediaStreams().stream().filter(s -> s.getType() == MediaStreamType.SUBTITLE).toList();
        if (allSubtitleStreams == null || allSubtitleStreams.isEmpty()) {
            return -1;
        }

        String audioLang = currentAudioLanguage;

        // Determine current audio language if not explicitly provided
        if (audioLang == null) {
            Integer currentAudioIndex = getBestAudioIndex(info);
            if (currentAudioIndex != null) {
                for (MediaStream stream : info.getMediaStreams()) {
                    if (stream.getType() == MediaStreamType.AUDIO && stream.getIndex() == currentAudioIndex) {
                        audioLang = stream.getLanguage();
                        break;
                    }
                }
            }
        }
        if (audioLang == null) {
            audioLang = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        }

        var userAudioLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getAudioLangRemoteSetting());
        var userSubLangRemoteSetting = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubLangRemoteSetting());
        var userSubMode = userPreferences.getValue().get(UserSettingPreferences.Companion.getSubMode());
        var userSubModeTitle = context != null ? context.getString(userSubMode) : "Unknown";

        Timber.i("Remote user sub lang settings: %s", userSubLangRemoteSetting);
        Timber.i("Remote user sub mode settings: %s", userSubModeTitle);
        Timber.i("Current audio language: %s", audioLang);

        String lastSubtitleLanguage = videoQueueManager.getValue().getLastPlayedSubtitleLanguageIsoCode();
        String lastSubtitleCodec = videoQueueManager.getValue().getLastPlayedSubtitleCodec();
        Boolean lastSubtitleDefaultState = videoQueueManager.getValue().getLastPlayedSubtitleDefaultState();
        Boolean lastSubtitleForcedState = videoQueueManager.getValue().getLastPlayedSubtitleForcedState();
        Boolean lastSubtitleHearingImpairedState = videoQueueManager.getValue().getLastPlayedSubtitleHearingImpairedState();
        String lastSubtitleTitle = videoQueueManager.getValue().getLastPlayedSubtitleTitle();

        Integer matchingIndex = null;

        // MODE NONE
        if (userSubMode.equals(R.string.subtitle_mode_none)) {
            Timber.i("Subtitle mode is NONE -> disabling subtitles");
            return -1;
        }

        // MODE ONLY_FORCED
        if (userSubMode.equals(R.string.subtitle_mode_only_forced)) {
            for (MediaStream stream : allSubtitleStreams) {
                if (stream.isForced() && (areLanguagesEqual(userSubLangRemoteSetting, stream.getLanguage()) || areLanguagesEqual(audioLang, stream.getLanguage()))) {
                    Timber.d("Best sub found (ONLY_FORCED in preferred language)");
                    return stream.getIndex();
                }
            }
            return -1;
        }

        // MODE ALWAYS
        if (userSubMode.equals(R.string.subtitle_mode_always)) {
            matchingIndex = matchSubtitleStream(allSubtitleStreams, userSubLangRemoteSetting, lastSubtitleTitle, lastSubtitleCodec, lastSubtitleDefaultState, lastSubtitleForcedState, lastSubtitleHearingImpairedState);
            if (matchingIndex == null) {
                matchingIndex = matchSubtitleStream(allSubtitleStreams, lastSubtitleLanguage, lastSubtitleTitle, lastSubtitleCodec, lastSubtitleDefaultState, lastSubtitleForcedState, lastSubtitleHearingImpairedState);
            }
            if (matchingIndex == null) {
                for (MediaStream s : allSubtitleStreams) {
                    if (s.isDefault()) { matchingIndex = s.getIndex(); break; }
                }
            }
            if (matchingIndex == null && !allSubtitleStreams.isEmpty()) {
                matchingIndex = allSubtitleStreams.get(0).getIndex();
            }
            return matchingIndex != null ? matchingIndex : -1;
        }

        // MODE SMART
        if (userSubMode.equals(R.string.subtitle_mode_smart)) {
            boolean audioMatchesUserPreference = areLanguagesEqual(audioLang, userAudioLangRemoteSetting);

            if (audioMatchesUserPreference) {
                // Audio is in user's native/preferred language -> Subtitles NOT needed unless FORCED
                Timber.d("SMART mode: Audio matches user preference (%s) -> Checking for forced subtitles only", audioLang);
                for (MediaStream s : allSubtitleStreams) {
                    if (s.isForced() && areLanguagesEqual(userSubLangRemoteSetting, s.getLanguage())) {
                        matchingIndex = s.getIndex();
                        Timber.d("SMART mode: Found forced subtitle in user sub lang (%d)", matchingIndex);
                        break;
                    }
                }
                if (matchingIndex == null) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (s.isForced() && areLanguagesEqual(audioLang, s.getLanguage())) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode: Found forced subtitle in audio lang (%d)", matchingIndex);
                            break;
                        }
                    }
                }
                if (matchingIndex == null) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (s.isForced()) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode: Found generic forced subtitle (%d)", matchingIndex);
                            break;
                        }
                    }
                }
                // If no forced subtitle, disable subtitles in SMART mode when audio is native
                if (matchingIndex == null) {
                    Timber.d("SMART mode: No forced subtitle found -> disabling subtitles (-1)");
                    return -1;
                }
            } else {
                // Audio is foreign -> Full subtitles ARE needed in userSubLangRemoteSetting
                Timber.d("SMART mode: Audio (%s) differs from user preference (%s) -> Full subtitles required", audioLang, userAudioLangRemoteSetting);
                matchingIndex = matchSubtitleStream(allSubtitleStreams, userSubLangRemoteSetting, lastSubtitleTitle, lastSubtitleCodec, lastSubtitleDefaultState, false, lastSubtitleHearingImpairedState);

                if (matchingIndex == null && lastSubtitleLanguage != null && !lastSubtitleLanguage.isEmpty()) {
                    matchingIndex = matchSubtitleStream(allSubtitleStreams, lastSubtitleLanguage, lastSubtitleTitle, lastSubtitleCodec, lastSubtitleDefaultState, false, lastSubtitleHearingImpairedState);
                }

                // Fallbacks before totally disabling:
                // 0. Any full subtitle matching preferred user language
                if (matchingIndex == null && userSubLangRemoteSetting != null && !userSubLangRemoteSetting.isEmpty()) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (areLanguagesEqual(userSubLangRemoteSetting, s.getLanguage()) && !s.isForced()) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode fallback: Found full subtitle in user sub lang (%d)", matchingIndex);
                            break;
                        }
                    }
                }

                // 1. Any default full subtitle
                if (matchingIndex == null) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (s.isDefault() && !s.isForced()) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode fallback: Default full subtitle found (%d)", matchingIndex);
                            break;
                        }
                    }
                }
                // 2. Any default subtitle
                if (matchingIndex == null) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (s.isDefault()) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode fallback: Default subtitle found (%d)", matchingIndex);
                            break;
                        }
                    }
                }
                // 3. First full subtitle
                if (matchingIndex == null) {
                    for (MediaStream s : allSubtitleStreams) {
                        if (!s.isForced()) {
                            matchingIndex = s.getIndex();
                            Timber.d("SMART mode fallback: First full subtitle found (%d)", matchingIndex);
                            break;
                        }
                    }
                }
                // 4. First available subtitle
                if (matchingIndex == null && !allSubtitleStreams.isEmpty()) {
                    matchingIndex = allSubtitleStreams.get(0).getIndex();
                    Timber.d("SMART mode fallback: First available subtitle found (%d)", matchingIndex);
                }
            }
            return matchingIndex != null ? matchingIndex : -1;
        }

        // MODE DEFAULT / Queue persistence
        if ("".equals(lastSubtitleLanguage)) {
            Timber.i("User explicitly disabled subtitles on previous video -> keeping disabled (-1)");
            return -1;
        }

        if (lastSubtitleLanguage != null && !lastSubtitleLanguage.isEmpty()) {
            matchingIndex = matchSubtitleStream(allSubtitleStreams, lastSubtitleLanguage, lastSubtitleTitle, lastSubtitleCodec, lastSubtitleDefaultState, lastSubtitleForcedState, lastSubtitleHearingImpairedState);
        }

        // General Fallbacks for DEFAULT mode
        if (matchingIndex == null) {
            matchingIndex = info.getDefaultSubtitleStreamIndex();
            if (matchingIndex != null && matchingIndex != -1) {
                Timber.d("Best subtitle found on server default index: %d", matchingIndex);
            } else {
                matchingIndex = null;
            }
        }

        if (matchingIndex == null) {
            for (MediaStream s : allSubtitleStreams) {
                if (s.isDefault() && !s.isForced()) {
                    matchingIndex = s.getIndex();
                    Timber.d("Best subtitle found on default full stream: %d", matchingIndex);
                    break;
                }
            }
        }

        if (matchingIndex == null) {
            for (MediaStream s : allSubtitleStreams) {
                if (s.isDefault()) {
                    matchingIndex = s.getIndex();
                    Timber.d("Best subtitle found on default stream: %d", matchingIndex);
                    break;
                }
            }
        }

        if (matchingIndex == null) {
            for (MediaStream s : allSubtitleStreams) {
                if (!s.isForced()) {
                    matchingIndex = s.getIndex();
                    Timber.d("Best subtitle found on first full stream: %d", matchingIndex);
                    break;
                }
            }
        }

        if (matchingIndex == null) {
            matchingIndex = -1;
        }

        Timber.i("Best subtitle found on index: %d for media: '%s'", matchingIndex, info.getName());
        return matchingIndex;
    }

    public Integer getBestVideoIndex(MediaSourceInfo info) {
        boolean isLocal = PlaybackController.isLocalSource(info);
        if (isLocal && info != null && info.getMediaStreams() != null) {
            List<MediaStream> allVideoStreams = info.getMediaStreams().stream().filter(stream -> stream.getType() == MediaStreamType.VIDEO).toList();
            if (allVideoStreams != null && !allVideoStreams.isEmpty()) {
                Integer videoIndex = allVideoStreams.get(0).getIndex();
                for (MediaStream stream : allVideoStreams) {
                    if (stream.isDefault()) {
                        videoIndex = stream.getIndex();
                        break;
                    }
                }
                Timber.i("Best local video found on index: %d", videoIndex);
                return videoIndex;
            }
        }

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
