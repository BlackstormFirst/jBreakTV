package org.jellyfin.playback.media3.exoplayer

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import timber.log.Timber

class ExoPlayerAudioPipeline {
	private var loudnessEnhancer: LoudnessEnhancer? = null
	private var nightModeEffect: AudioEffect? = null

	var normalizationGain: Float? = null
		set(value) {
			Timber.d("Normalization gain changed to $value")
			field = value
			applyGain()
		}

	var enableNightMode: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				applyNightMode()
			}
		}

	private var currentAudioSessionId: Int? = null

	fun setAudioSessionId(audioSessionId: Int) {
		Timber.d("Audio session id changed to $audioSessionId")
		currentAudioSessionId = audioSessionId

		// Re-create loudness enhancer for normalization gain
		loudnessEnhancer?.release()
		loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }
			.onFailure { Timber.w(it, "Failed to create LoudnessEnhancer") }
			.getOrNull()

		// Re-apply current normalization gain
		applyGain()

		// Re-apply night mode effect
		applyNightMode()
	}

	private fun applyGain() {
		if (loudnessEnhancer == null) {
			Timber.d("LoudnessEnhancer is not initialized")
			return
		}

		val targetGain = normalizationGain
			// Convert to millibels
			?.times(100f)
			// Round to integer
			?.toInt()
			// Ignore if zero (so the enhancer will be disabled)
			?.takeIf { it != 0 }

		Timber.d("Applying gain (targetGain=$targetGain)")
		runCatching {
			loudnessEnhancer?.setTargetGain(targetGain ?: 0)
		}.onSuccess {
			loudnessEnhancer?.setEnabled(targetGain != null)
		}.onFailure { error ->
			Timber.e(error, "Failed to apply gain of $targetGain")
			loudnessEnhancer?.setEnabled(false)
		}
	}

	private fun applyNightMode() {
		nightModeEffect?.release()
		nightModeEffect = null

		val sessionId = currentAudioSessionId ?: return
		if (!enableNightMode || sessionId == 0) return

		Timber.i("Enabling audio night mode for session $sessionId")
		runCatching {
			nightModeEffect = when {
				// Use DynamicsProcessing on Android 9 (API 28) and newer
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
					DynamicsProcessing(0, sessionId, null).apply {
						setLimiterAllChannelsTo(
							DynamicsProcessing.Limiter(
								true,
								true,
								1,
								30f,
								300f,
								10f,
								-24f,
								3f
							)
						)
						setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, true, 5).apply {
							getBand(0).gain = 0f
							getBand(1).gain = 0.02f
							getBand(2).gain = 0.03f
							getBand(3).gain = 0.02f
							getBand(4).gain = 0f
						})
						enabled = true
					}
				}

				else -> {
					Equalizer(0, sessionId).apply {
						setBandLevel(0, 0)
						setBandLevel(1, 2)
						setBandLevel(2, 3)
						setBandLevel(3, 2)
						setBandLevel(4, 0)
						enabled = true
					}
				}
			}
		}.onFailure { error ->
			Timber.e(error, "Failed to apply audio night mode effect for session $sessionId")
		}
	}

	fun release() {
		loudnessEnhancer?.release()
		loudnessEnhancer = null
		nightModeEffect?.release()
		nightModeEffect = null
	}
}
