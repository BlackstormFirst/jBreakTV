package org.jellyfin.playback.media3.exoplayer.support

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import timber.log.Timber
import java.io.File

object AssFontManager {
	private const val FONT_CACHE_DIR = "ass_fonts"
	private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	@Volatile
	private var activePreloadJob: Job? = null

	@JvmStatic
	val defaultAssHandlerConfig: AssHandlerConfig = AssHandlerConfig(
		glyphSize = 10000,
		cacheSize = 128,
		maxRenderPixels = 1920 * 1080,
	)

	@JvmStatic
	fun getAssHandlerConfig(context: Context): AssHandlerConfig {
		val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
		val isLowRam = am?.isLowRamDevice == true
		val isApi30Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

		val glyphSize = when {
			isLowRam -> 8000
			isApi30Plus -> 35000
			else -> 10000
		}
		val cacheSize = when {
			isLowRam -> 64
			isApi30Plus -> 256
			else -> 128
		}
		val maxRenderPixels =  1920 * 1080

		return AssHandlerConfig(
			glyphSize = glyphSize,
			cacheSize = cacheSize,
			maxRenderPixels = maxRenderPixels,
		)
	}

	fun getFontCacheDir(context: Context): File {
		val dir = File(context.cacheDir, FONT_CACHE_DIR)
		if (!dir.exists()) {
			dir.mkdirs()
		}
		return dir
	}

	@JvmStatic
	fun cancelPreload() {
		activePreloadJob?.cancel()
		activePreloadJob = null
	}

	@JvmStatic
	fun preloadFonts(
		context: Context,
		url: String?,
		assHandler: AssHandler?,
	) {
		if (assHandler == null || url.isNullOrEmpty()) return
		cancelPreload()
		activePreloadJob = preloadScope.launch {
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
				it.extension.lowercase() in listOf("ttf", "otf", "ttc") && it.length() in 1..(15 * 1024 * 1024)
			}.orEmpty()

			if (fontFiles.isNotEmpty()) {
				Timber.d("Preloading ${fontFiles.size} cached ASS fonts into libass for: $url")
				var totalLoadedBytes = 0L
				val maxTotalBytes = 30 * 1024 * 1024L // Max 30MB total preloaded fonts

				for (fontFile in fontFiles) {
					if (!coroutineContext.isActive) break
					if (totalLoadedBytes + fontFile.length() > maxTotalBytes) {
						Timber.w("Reached max font preload limit (30MB), skipping remaining fonts")
						break
					}
					runCatching {
						val fontBytes = fontFile.readBytes()
						synchronized(assHandler) {
							if (coroutineContext.isActive) {
								assHandler.addFont(fontFile.name, fontBytes)
							}
						}
						totalLoadedBytes += fontBytes.size
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
