package org.jellyfin.playback.media3.exoplayer.support

import android.content.Context
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import timber.log.Timber
import java.io.File

object AssFontManager {
	private const val FONT_CACHE_DIR = "ass_fonts"

	/**
	 * Optimized configuration for libass rendering across all player backends:
	 * - glyphSize: Expanded glyph outline cache (20,000) to avoid re-rasterizing characters.
	 * - cacheSize: 128MB bitmap glyph cache.
	 * - maxRenderPixels: Downscaling canvas limit for 4K displays (1080p canvas = 1920x1080 = 2,073,600 px).
	 *   OpenGL then hardware-scales to native 4K, eliminating 4K rasterization CPU bottlenecks and 1-frame lags.
	 */
	@JvmStatic
	val defaultAssHandlerConfig: AssHandlerConfig = AssHandlerConfig(
		glyphSize = 20000,
		cacheSize = 128,
		maxRenderPixels = 1920 * 1080,
	)

	fun getFontCacheDir(context: Context): File {
		val dir = File(context.cacheDir, FONT_CACHE_DIR)
		if (!dir.exists()) {
			dir.mkdirs()
		}
		return dir
	}

	@JvmStatic
	fun preloadFonts(
		context: Context,
		url: String?,
		assHandler: AssHandler?,
	) {
		if (assHandler == null || url.isNullOrEmpty()) return
		CoroutineScope(Dispatchers.IO).launch {
			preloadFontsForUrl(context, url, assHandler)
		}
	}

	suspend fun preloadFontsForUrl(
		context: Context,
		url: String?,
		assHandler: AssHandler?,
	) = withContext(Dispatchers.IO) {
		if (assHandler == null || url.isNullOrEmpty()) return@withContext

		runCatching {
			val fontDir = getFontCacheDir(context)
			val fontFiles = fontDir.listFiles()?.filter {
				it.extension.lowercase() in listOf("ttf", "otf", "ttc")
			}.orEmpty()

			if (fontFiles.isNotEmpty()) {
				Timber.d("Preloading ${fontFiles.size} cached ASS fonts into libass for: $url")
				for (fontFile in fontFiles) {
					runCatching {
						val fontBytes = fontFile.readBytes()
						assHandler.addFont(fontFile.name, fontBytes)
					}.onFailure { error ->
						Timber.w(error, "Failed to load font ${fontFile.name} into libass")
					}
				}
			}
		}.onFailure { error ->
			Timber.w(error, "Failed to preload ASS fonts")
		}
	}

	suspend fun preloadFontsForEntry(
		context: Context,
		entry: QueueEntry,
		assHandler: AssHandler?,
	) = preloadFontsForUrl(context, entry.mediaStream?.url, assHandler)
}
