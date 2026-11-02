package org.jellyfin.androidtv

import android.content.Context
import androidx.startup.Initializer
import timber.log.Timber

class LogInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		// Enable improved logging for leaking resources
		// https://wh0.github.io/2020/08/12/closeguard.html
		if (BuildConfig.DEBUG) {
			try {
				Class.forName("dalvik.system.CloseGuard")
					.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
					.invoke(null, true)
			} catch (e: ReflectiveOperationException) {
				@Suppress("TooGenericExceptionThrown")
				throw RuntimeException(e)
			}
		}

		// Initialize the logging library
		Timber.plant(object : Timber.DebugTree() {
			override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
				val isWebSocketEof = (message.contains("WebSocket has failed") || tag?.contains("Connection") == true) &&
					(t is java.io.EOFException || t is java.net.SocketException || t?.cause is java.io.EOFException)

				val adjustedPriority = if (isWebSocketEof && priority >= android.util.Log.WARN) {
					android.util.Log.DEBUG
				} else {
					priority
				}

				super.log(adjustedPriority, tag, message, t)
			}
		})
		Timber.i("Debug tree planted")
	}

	override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
