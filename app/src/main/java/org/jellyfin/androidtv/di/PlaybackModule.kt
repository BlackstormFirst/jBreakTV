@file:OptIn(UnstableApi::class)

package org.jellyfin.androidtv.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.TypefaceCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.CaptionStyleCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.constant.BufferLength
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.PlaybackIndexManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.AndroidVersion
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.jellyfin.jellyfinPlugin
import org.jellyfin.playback.media3.exoplayer.ExoPlayerOptions
import org.jellyfin.playback.media3.exoplayer.exoPlayerPlugin
import org.jellyfin.playback.media3.session.MediaSessionOptions
import org.jellyfin.playback.media3.session.media3SessionPlugin
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.androidtv.ui.playback.PlaybackManager as LegacyPlaybackManager

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol

val playbackModule = module {
	single { LegacyPlaybackManager(get()) }
	single { VideoQueueManager() }
	single { PlaybackIndexManager() }
	single<MediaManager> { RewriteMediaManager(get(), get()) }

	single { PlaybackLauncher(get(), get(), get(), get()) }

	single<HttpDataSource.Factory> {
		val okHttpFactory = get<OkHttpFactory>()
		val httpClientOptions = get<HttpClientOptions>().copy(
			// Disable request timeout for media playback as this causes issues with Live TV
			requestTimeout = Duration.ZERO
		)

		OkHttpDataSource.Factory(okHttpFactory.createClient(httpClientOptions))
	}

	single { createPlaybackManager() }
}

fun Scope.createPlaybackManager() = playbackManager(androidContext()) {
	val activityIntent = Intent(get(), MainActivity::class.java)
	val pendingIntent = PendingIntent.getActivity(get(), 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)

	val notificationChannelId = "session"
	if (AndroidVersion.isAtLeastO) {
		val channel = NotificationChannel(
			notificationChannelId,
			notificationChannelId,
			NotificationManager.IMPORTANCE_LOW
		)
		channel.setShowBadge(false)
		NotificationManagerCompat.from(get()).createNotificationChannel(channel)
	}

	val userPreferences = get<UserPreferences>()
	val bufferLength = userPreferences[UserPreferences.bufferLength]
	val exoPlayerOptions = ExoPlayerOptions(
		preferFfmpeg = userPreferences[UserPreferences.preferExoPlayerFfmpeg],
		enableLibass = userPreferences[UserPreferences.assDirectPlay],
		enableDebugLogging = userPreferences[UserPreferences.debuggingEnabled],
		enableTunneling = userPreferences[UserPreferences.codecTunneling],
		baseDataSourceFactory = get<HttpDataSource.Factory>(),
		minBufferDuration = bufferLength.minBufferDuration,
		maxBufferDuration = bufferLength.maxBufferDuration,
		bufferForPlaybackDuration = bufferLength.bufferForPlaybackDuration,
		bufferForPlaybackAfterRebufferDuration = bufferLength.bufferForPlaybackAfterRebufferDuration,
		configureSubtitleView = { subtitleView ->
			val strokeColor = userPreferences[UserPreferences.subtitleTextStrokeColor].toInt()
			val textWeight = userPreferences[UserPreferences.subtitlesTextWeight]
			val subtitleStyle = CaptionStyleCompat(
				userPreferences[UserPreferences.subtitlesTextColor].toInt(),
				userPreferences[UserPreferences.subtitlesBackgroundColor].toInt(),
				Color.TRANSPARENT,
				if (Color.alpha(strokeColor) == 0) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
				strokeColor,
				TypefaceCompat.create(androidContext(), Typeface.DEFAULT, textWeight, false)
			)
			subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, userPreferences[UserPreferences.subtitlesTextSize])
			subtitleView.setBottomPaddingFraction(userPreferences[UserPreferences.subtitlesOffsetPosition])
			subtitleView.setStyle(subtitleStyle)
		},
	)
	install(exoPlayerPlugin(get(), exoPlayerOptions))


	val mediaSessionOptions = MediaSessionOptions(
		channelId = notificationChannelId,
		notificationId = 1,
		iconSmall = R.drawable.app_icon_foreground,
		openIntent = pendingIntent,
	)
	install(media3SessionPlugin(get(), mediaSessionOptions))

	val deviceProfileBuilder = { createDeviceProfile(androidContext(), userPreferences, get()) }
	val mediaSegmentRepository = get<MediaSegmentRepository>()
	val dataRefreshService = get<DataRefreshService>()
	val playbackIndexManager = get<PlaybackIndexManager>()
	install(
		jellyfinPlugin(
			api = get(),
			deviceProfileBuilder = deviceProfileBuilder,
			segmentAutoSkipPredicate = { segment -> mediaSegmentRepository.getMediaSegmentAction(segment) == MediaSegmentAction.SKIP },
			lifecycle = ProcessLifecycleOwner.get().lifecycle,
			mediaSegmentProvider = { item -> mediaSegmentRepository.getSegmentsForItem(item) },
			localItemInspector = { file -> LocalVideoManager.inspectAndBuildBaseItemDto(androidContext(), file) },
			streamIndexResolver = { item, mediaSource ->
				val sourceToUse = mediaSource ?: item.mediaSources?.firstOrNull() ?: MediaSourceInfo(
					protocol = MediaProtocol.FILE,
					id = item.id.toString(),
					path = item.path,
					type = MediaSourceType.DEFAULT,
					isRemote = false,
					mediaStreams = item.mediaStreams,
					supportsDirectPlay = true,
					supportsDirectStream = true,
					supportsTranscoding = false,
					supportsProbing = false,
					requiresOpening = false,
					requiresClosing = false,
					requiresLooping = false,
					isInfiniteStream = false,
					ignoreIndex = false,
					ignoreDts = false,
					genPtsInput = false,
					readAtNativeFramerate = false,
					hasSegments = false,
					transcodingSubProtocol = MediaStreamProtocol.HTTP,
				)
				val audioIndex = playbackIndexManager.getBestAudioIndex(sourceToUse)
				val activeAudioLang = sourceToUse.mediaStreams?.firstOrNull { it.index == audioIndex }?.language
				val subIndex = playbackIndexManager.getBestSubtitleIndex(sourceToUse, androidContext(), activeAudioLang)
				Pair(audioIndex, subIndex)
			},
			maxBitrateProvider = {
				val mbps = userPreferences[UserPreferences.maxBitrate].toFloatOrNull() ?: 200f
				(mbps * 1_000_000f).toInt()
			},
			onPlaybackStop = { item ->
				dataRefreshService.lastPlayback = Instant.now()
				dataRefreshService.lastPlayedItem = item
				when (item.type) {
					BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
					BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
					else -> Unit
				}
			},

		)
	)




	// Options
	val userSettingPreferences = get<UserSettingPreferences>()

	defaultRewindAmount = { userSettingPreferences[UserSettingPreferences.skipBackLength].milliseconds }
	defaultFastForwardAmount = { userSettingPreferences[UserSettingPreferences.skipForwardLength].milliseconds }
}
