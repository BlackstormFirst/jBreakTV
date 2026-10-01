package org.jellyfin.androidtv.util

import android.app.Activity
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

class RefreshRateHelper(
	private val activity: Activity,
	private val userPreferences: UserPreferences,
) {
	private val refreshRateSwitchingBehavior: RefreshRateSwitchingBehavior
		get() = userPreferences[UserPreferences.refreshRateSwitchingBehavior]

	fun updateRefreshRate(
		frameRate: Float?,
		videoWidth: Int = 0,
		videoHeight: Int = 0,
		surfaceView: SurfaceView? = null,
	): Boolean {
		if (refreshRateSwitchingBehavior == RefreshRateSwitchingBehavior.DISABLED) {
			Timber.d("Refresh rate switching is disabled in user preferences")
			return false
		}

		if (frameRate == null || frameRate <= 0f) {
			Timber.w("Invalid frame rate ($frameRate) for refresh rate switching")
			return false
		}

		// Also set surface.setFrameRate on Android 11+ (API 30+)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surfaceView != null) {
			try {
				val surface = surfaceView.holder.surface
				if (surface != null && surface.isValid) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
						surface.setFrameRate(
							frameRate,
							Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
							Surface.CHANGE_FRAME_RATE_ALWAYS
						)
					} else {
						surface.setFrameRate(
							frameRate,
							Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
						)
					}
					Timber.i("Set surface frame rate to $frameRate")
				}
			} catch (e: Exception) {
				Timber.w(e, "Failed to set frame rate directly on Surface")
			}
		}

		val window = activity.window ?: return false
		@Suppress("DEPRECATION")
		val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			activity.display
		} else {
			window.windowManager.defaultDisplay
		} ?: return false

		val supportedModes = display.supportedModes ?: return false
		val currentMode = display.mode ?: return false
		val bestMode = findBestDisplayMode(
			supportedModes = supportedModes,
			defaultMode = currentMode,
			frameRate = frameRate,
			videoWidth = videoWidth,
			videoHeight = videoHeight,
			behavior = refreshRateSwitchingBehavior,
		)

		if (bestMode != null && currentMode.modeId != bestMode.modeId) {
			Timber.i(
				"Changing display mode from %s (%dx%d@%fHz) to %s (%dx%d@%fHz)",
				currentMode.modeId, currentMode.physicalWidth, currentMode.physicalHeight, currentMode.refreshRate,
				bestMode.modeId, bestMode.physicalWidth, bestMode.physicalHeight, bestMode.refreshRate
			)
			val params = window.attributes
			params.preferredDisplayModeId = bestMode.modeId
			window.attributes = params
			return true
		} else if (bestMode != null) {
			Timber.i("Display is already in best mode (%s - %fHz)", bestMode.modeId, bestMode.refreshRate)
		}

		return false
	}

	fun resetRefreshRate(surfaceView: SurfaceView? = null) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surfaceView != null) {
				try {
					val surface = surfaceView.holder.surface
					if (surface != null && surface.isValid) {
						surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
						Timber.i("Reset surface frame rate to default (0)")
					}
				} catch (e: Exception) {
					Timber.w(e, "Failed to reset frame rate on Surface")
				}
			}

			val window = activity.window ?: return
			val params = window.attributes
			if (params.preferredDisplayModeId != 0) {
				Timber.i("Resetting preferredDisplayModeId to default (0)")
				params.preferredDisplayModeId = 0
				window.attributes = params
			}
		} catch (e: Exception) {
			Timber.w(e, "Error resetting refresh rate")
		}
	}

	private fun findBestDisplayMode(
		supportedModes: Array<Display.Mode>,
		defaultMode: Display.Mode,
		frameRate: Float,
		videoWidth: Int,
		videoHeight: Int,
		behavior: RefreshRateSwitchingBehavior,
	): Display.Mode? {
		var curWeight = 0
		var bestMode: Display.Mode? = null

		for (mode in supportedModes) {
			// Skip non-HD modes
			if (mode.physicalWidth < 1280 || mode.physicalHeight < 720) continue

			// Disallow resolution downgrade if video dimensions are known
			if (videoWidth > 0 && mode.physicalWidth < videoWidth) continue
			if (videoHeight > 0 && mode.physicalHeight < videoHeight) continue

			if (!isFrameRateCompatible(mode.refreshRate, frameRate)) continue

			var resolutionDifference = -1
			if (behavior == RefreshRateSwitchingBehavior.SCALE_ON_DEVICE) {
				if (!(mode.physicalWidth == defaultMode.physicalWidth && mode.physicalHeight == defaultMode.physicalHeight)) {
					resolutionDifference = if (videoWidth > 0) abs(mode.physicalWidth - videoWidth) else 0
				}
			} else if (behavior == RefreshRateSwitchingBehavior.SCALE_ON_TV) {
				resolutionDifference = if (videoWidth > 0) abs(mode.physicalWidth - videoWidth) else 0
			}

			val refreshRateDifference = (abs(mode.refreshRate - frameRate) * 100).roundToInt()
			@Suppress("MagicNumber")
			val weight = 100000 - refreshRateDifference + 100000 - resolutionDifference

			if (weight > curWeight) {
				curWeight = weight
				bestMode = mode
			}
		}

		return bestMode
	}

	private fun isFrameRateCompatible(modeRefreshRate: Float, videoFrameRate: Float): Boolean {
		if (modeRefreshRate <= 0f || videoFrameRate <= 0f) return false

		// Check direct match or multipliers (1x, 2x, 2.5x, 3x, 4x, 5x)
		val multipliers = floatArrayOf(1f, 2f, 2.5f, 3f, 4f, 5f)
		for (m in multipliers) {
			val target = videoFrameRate * m
			if (abs(modeRefreshRate - target) < 0.08f) {
				return true
			}
		}

		// Fractional cross-matching for common refresh rates (e.g., 23.976 <-> 24.0, 29.97 <-> 30.0, 59.94 <-> 60.0)
		val isCloseTo24Group = (videoFrameRate in 23.9f..24.1f) && (modeRefreshRate in 23.9f..24.1f)
		val isCloseTo30Group = (videoFrameRate in 29.9f..30.1f) && (modeRefreshRate in 29.9f..30.1f)
		val isCloseTo60Group = (videoFrameRate in 59.9f..60.1f) && (modeRefreshRate in 59.9f..60.1f)
		return isCloseTo24Group || isCloseTo30Group || isCloseTo60Group
	}
}
