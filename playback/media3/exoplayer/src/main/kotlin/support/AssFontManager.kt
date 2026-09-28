package org.jellyfin.playback.media3.exoplayer.support

import android.content.Context
import io.github.peerless2012.ass.media.AssHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import timber.log.Timber
import java.io.File

object AssFontManager {
	private const val FONT_CACHE_DIR = "ass_fonts"

	fun getFontCacheDir(context: Context): File {
		val dir = File(context.cacheDir, FONT_CACHE_DIR)
		if (!dir.exists()) {
			dir.mkdirs()
		}
		return dir
	}

	suspend fun preloadFontsForEntry(
		context: Context,
		entry: QueueEntry,
		assHandler: AssHandler?,
	) = withContext(Dispatchers.IO) {
		if (assHandler == null) return@withContext

		val url = entry.mediaStream?.url ?: return@withContext
		val isLocalFile = url.startsWith("file://") || url.startsWith("/")

		runCatching {
			val fontDir = getFontCacheDir(context)
			if (isLocalFile) {
				Timber.d("Preloading ASS fonts for local media: $url")
				val filePath = url.removePrefix("file://")
				val localFile = File(filePath)
				if (localFile.exists()) {
					val existingFonts = fontDir.listFiles()?.filter {
						it.extension.lowercase() in listOf("ttf", "otf", "ttc")
					}.orEmpty()

					Timber.d("Local font cache contains ${existingFonts.size} fonts")
				}
			} else {
				Timber.d("Preloading ASS fonts for remote media: $url")
				val existingFonts = fontDir.listFiles()?.filter {
					it.extension.lowercase() in listOf("ttf", "otf", "ttc")
				}.orEmpty()

				Timber.d("Remote font cache contains ${existingFonts.size} fonts")
			}
		}.onFailure { error ->
			Timber.w(error, "Failed to preload ASS fonts")
		}
	}
}
