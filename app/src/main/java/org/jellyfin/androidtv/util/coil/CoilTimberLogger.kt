package org.jellyfin.androidtv.util.coil

import android.util.Log
import coil3.util.Logger
import timber.log.Timber

class CoilTimberLogger(
	override var minLevel: Logger.Level = Logger.Level.Debug,
) : Logger {
	override fun log(tag: String, level: Logger.Level, message: String?, throwable: Throwable?) {
		val isHttpError = throwable is coil3.network.HttpException ||
			message?.contains("HTTP ") == true ||
			throwable?.message?.contains("HTTP ") == true
		val isCancellation = throwable is kotlinx.coroutines.CancellationException ||
			throwable is java.io.InterruptedIOException ||
			message?.contains("cancelled", ignoreCase = true) == true ||
			throwable?.message?.contains("cancelled", ignoreCase = true) == true
		val isNullBitmap = throwable is IllegalStateException &&
			(throwable.message?.contains("BitmapFactory") == true || message?.contains("BitmapFactory") == true)

		val adjustedLevel = if (level >= Logger.Level.Warn && (isHttpError || isCancellation || isNullBitmap)) {
			Logger.Level.Debug
		} else {
			level
		}

		val priority = when (adjustedLevel) {
			Logger.Level.Verbose -> Log.VERBOSE
			Logger.Level.Debug -> Log.DEBUG
			Logger.Level.Info -> Log.INFO
			Logger.Level.Warn -> Log.WARN
			Logger.Level.Error -> Log.ERROR
		}

		Timber.tag("CoilTimberLogger.$tag").log(priority, throwable, message)
	}
}
