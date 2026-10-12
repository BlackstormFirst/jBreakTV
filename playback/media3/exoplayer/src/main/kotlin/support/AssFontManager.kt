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
import org.jellyfin.playback.core.mediastream.MediaStreamAttachmentTrack
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.util.isLocalPath
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.util.Locale

object AssFontManager {
	private const val FONT_CACHE_DIR = "ass_fonts"
	private const val MAX_FONT_CACHE_SIZE_BYTES = 128 * 1024 * 1024L // 128 MB limit
	private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private const val ID_EBML_HEADER = 0x1A45DFA3L
	private const val ID_SEGMENT = 0x18538067L
	private const val ID_CLUSTER = 0x1F43B675L
	private const val ID_ATTACHMENTS = 0x1941A469L
	private const val ID_ATTACHED_FILE = 0x61A7L
	private const val ID_FILE_NAME = 0x466EL
	private const val ID_FILE_MIME_TYPE = 0x4660L
	private const val ID_FILE_DATA = 0x465CL

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
		val maxRenderPixels = 1920 * 1080

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

	/**
	 * Purges the font cache directory if its total size exceeds [maxCacheSizeBytes] (128 MB).
	 * Removes the oldest used files (LRU strategy) until size falls below 75% of the limit (96 MB).
	 */
	@JvmStatic
	fun purgeFontCacheIfNeeded(
		context: Context,
		maxCacheSizeBytes: Long = MAX_FONT_CACHE_SIZE_BYTES,
	) {
		val fontDir = getFontCacheDir(context)
		val fontFiles = fontDir.listFiles()?.filter { it.isFile }.orEmpty()
		var currentSize = fontFiles.sumOf { it.length() }

		if (currentSize > maxCacheSizeBytes) {
			Timber.i(
				"AssFontManager: Cache size (%d MB) exceeds limit (%d MB). Purging oldest fonts...",
				currentSize / (1024 * 1024),
				maxCacheSizeBytes / (1024 * 1024),
			)
			val sortedFiles = fontFiles.sortedBy { it.lastModified() }
			val targetSize = (maxCacheSizeBytes * 0.75).toLong() // Target 96 MB

			for (file in sortedFiles) {
				val fileSize = file.length()
				if (file.delete()) {
					currentSize -= fileSize
					Timber.d("AssFontManager: Deleted cached font: %s (%d KB)", file.name, fileSize / 1024)
				}
				if (currentSize <= targetSize) break
			}
			Timber.i("AssFontManager: Purge completed. New cache size: %d MB", currentSize / (1024 * 1024))
		}
	}

	/**
	 * Downloads font attachments for an entry if available and not already cached.
	 */
	suspend fun fetchAttachmentFontsForEntry(
		context: Context,
		entry: QueueEntry,
	) = withContext(Dispatchers.IO) {
		runCatching {
			val stream = entry.mediaStream ?: return@withContext
			if (isLocalPath(stream.url)) {
				extractLocalMkvAttachmentFonts(context, stream.url)
				return@withContext
			}

			val attachmentTracks = stream.tracks.filterIsInstance<MediaStreamAttachmentTrack>()
				.filter { track ->
					val url = track.deliveryUrl.orEmpty()
					val name = track.fileName.orEmpty().lowercase()
					url.isNotEmpty() || name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc")
				}

			if (attachmentTracks.isEmpty()) return@withContext

			val fontDir = getFontCacheDir(context)
			purgeFontCacheIfNeeded(context)

			for (track in attachmentTracks) {
				if (!coroutineContext.isActive) break
				val fontUrl = track.deliveryUrl ?: continue
				val fileName = track.fileName?.takeIf { it.isNotBlank() }
					?: "font_${stream.identifier}_${track.index}.ttf"
				val targetFile = File(fontDir, fileName)

				if (targetFile.exists() && targetFile.length() > 0L) {
					// Mark as recently used for LRU eviction
					targetFile.setLastModified(System.currentTimeMillis())
				} else {
					Timber.d("AssFontManager: Downloading attachment font: %s from %s", fileName, fontUrl)
					runCatching {
						val connection = URL(fontUrl).openConnection()
						connection.connectTimeout = 5000
						connection.readTimeout = 10000
						connection.getInputStream().use { input ->
							targetFile.outputStream().use { output ->
								input.copyTo(output)
							}
						}
						targetFile.setLastModified(System.currentTimeMillis())
						Timber.d("AssFontManager: Downloaded attachment font: %s (%d KB)", fileName, targetFile.length() / 1024)
					}.onFailure { error ->
						targetFile.delete()
						Timber.w(error, "AssFontManager: Failed to download font: %s", fileName)
					}
				}
			}
		}.onFailure { error ->
			Timber.w(error, "AssFontManager: Failed fetching attachment fonts")
		}
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

	@JvmStatic
	fun preloadFontsForEntry(
		context: Context,
		entry: QueueEntry,
		assHandler: AssHandler?,
	) {
		if (assHandler == null) return
		cancelPreload()
		activePreloadJob = preloadScope.launch {
			fetchAttachmentFontsForEntry(context, entry)
			preloadFontsForUrl(context, entry.mediaStream?.url, assHandler)
		}
	}

	suspend fun preloadFontsForUrl(
		context: Context,
		url: String?,
		assHandler: AssHandler?,
	) = withContext(Dispatchers.IO) {
		if (assHandler == null || url.isNullOrEmpty()) return@withContext

		runCatching {
			if (isLocalPath(url)) {
				extractLocalMkvAttachmentFonts(context, url)
			}

			purgeFontCacheIfNeeded(context)

			val fontDir = getFontCacheDir(context)
			val fontFiles = fontDir.listFiles()?.filter {
				it.extension.lowercase() in listOf("ttf", "otf", "ttc") && it.length() in 1..(15 * 1024 * 1024)
			}.orEmpty()

			if (fontFiles.isNotEmpty()) {
				Timber.d("Preloading ${fontFiles.size} cached ASS fonts into libass for: $url")
				var totalLoadedBytes = 0L
				val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
				val isLowRam = am?.isLowRamDevice == true
				val maxTotalBytes = if (isLowRam) 15 * 1024 * 1024L else 45 * 1024 * 1024L

				for (fontFile in fontFiles) {
					if (!coroutineContext.isActive) break
					if (totalLoadedBytes + fontFile.length() > maxTotalBytes) {
						Timber.w("Reached max font preload limit (${maxTotalBytes / (1024 * 1024)}MB), skipping remaining fonts")
						break
					}
					runCatching {
						val fontBytes = fontFile.readBytes()
						synchronized(assHandler) {
							if (coroutineContext.isActive) {
								runCatching {
									assHandler.addFont(fontFile.name, fontBytes)
								}.onFailure { error ->
									Timber.w(error, "AssFontManager: AssHandler was released or failed while adding font ${fontFile.name}")
								}
							}
						}
						fontFile.setLastModified(System.currentTimeMillis())
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

	/**
	 * Extracts font attachments (.ttf, .otf, .ttc) directly from a local MKV container
	 * and caches them in [FONT_CACHE_DIR] for future playback.
	 */
	suspend fun extractLocalMkvAttachmentFonts(
		context: Context,
		pathString: String?,
	) = withContext(Dispatchers.IO) {
		if (pathString.isNullOrBlank()) return@withContext

		runCatching {
			var filePath = pathString
			if (filePath.startsWith("file://")) {
				filePath = filePath.substring(7)
			} else if (filePath.startsWith("file:")) {
				filePath = filePath.substring(5)
			}

			val file = File(filePath)
			if (!file.exists() || !file.canRead() || file.length() < 100) return@withContext

			val ext = file.extension.lowercase(Locale.ROOT)
			if (ext != "mkv" && ext != "mka" && ext != "webm") return@withContext

			val fontDir = getFontCacheDir(context)
			purgeFontCacheIfNeeded(context)

			RandomAccessFile(file, "r").use { raf ->
				parseEbmlForFonts(raf, fontDir, file.length())
			}
		}.onFailure { error ->
			Timber.w(error, "AssFontManager: Failed extracting local MKV font attachments from: %s", pathString)
		}
	}

	private fun parseEbmlForFonts(
		raf: RandomAccessFile,
		fontDir: File,
		fileLength: Long,
	) {
		while (raf.filePointer < fileLength) {
			val id = readEbmlId(raf)
			if (id < 0) break
			val size = readEbmlVint(raf)
			if (size < 0) break

			val elementEnd = if (size == 0x01FFFFFFFFFFFFFFL) fileLength else (raf.filePointer + size).coerceAtMost(fileLength)

			when (id) {
				ID_EBML_HEADER -> {
					raf.seek(elementEnd)
				}
				ID_SEGMENT -> {
					parseEbmlContainer(raf, fontDir, elementEnd)
				}
				else -> {
					if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
			}
		}
	}

	private fun parseEbmlContainer(
		raf: RandomAccessFile,
		fontDir: File,
		containerEnd: Long,
	) {
		while (raf.filePointer < containerEnd) {
			val id = readEbmlId(raf)
			if (id < 0) break
			val size = readEbmlVint(raf)
			if (size < 0) break

			val elementEnd = if (size == 0x01FFFFFFFFFFFFFFL) containerEnd else (raf.filePointer + size).coerceAtMost(containerEnd)

			when (id) {
				ID_ATTACHMENTS -> {
					parseAttachments(raf, fontDir, elementEnd)
				}
				ID_CLUSTER -> {
					// Fast skip video/audio cluster frames!
					raf.seek(elementEnd)
				}
				ID_ATTACHED_FILE -> {
					parseAttachedFile(raf, fontDir, elementEnd)
				}
				else -> {
					if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
			}
		}
	}

	private fun parseAttachments(
		raf: RandomAccessFile,
		fontDir: File,
		attachmentsEnd: Long,
	) {
		while (raf.filePointer < attachmentsEnd) {
			val id = readEbmlId(raf)
			if (id < 0) break
			val size = readEbmlVint(raf)
			if (size < 0) break

			val elementEnd = (raf.filePointer + size).coerceAtMost(attachmentsEnd)

			if (id == ID_ATTACHED_FILE) {
				parseAttachedFile(raf, fontDir, elementEnd)
			} else {
				if (elementEnd > raf.filePointer) {
					raf.seek(elementEnd)
				}
			}
		}
	}

	private fun parseAttachedFile(
		raf: RandomAccessFile,
		fontDir: File,
		attachedFileEnd: Long,
	) {
		var fileName: String? = null
		var mimeType: String? = null
		var fileDataBytes: ByteArray? = null

		while (raf.filePointer < attachedFileEnd) {
			val id = readEbmlId(raf)
			if (id < 0) break
			val size = readEbmlVint(raf)
			if (size < 0) break

			val elementEnd = (raf.filePointer + size).coerceAtMost(attachedFileEnd)

			when (id) {
				ID_FILE_NAME -> {
					if (size in 1..4096) {
						val bytes = ByteArray(size.toInt())
						raf.readFully(bytes)
						fileName = String(bytes, Charsets.UTF_8).trim()
					} else if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
				ID_FILE_MIME_TYPE -> {
					if (size in 1..4096) {
						val bytes = ByteArray(size.toInt())
						raf.readFully(bytes)
						mimeType = String(bytes, Charsets.UTF_8).trim().lowercase(Locale.ROOT)
					} else if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
				ID_FILE_DATA -> {
					if (size in 100..(15 * 1024 * 1024L)) {
						val bytes = ByteArray(size.toInt())
						raf.readFully(bytes)
						fileDataBytes = bytes
					} else if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
				else -> {
					if (elementEnd > raf.filePointer) {
						raf.seek(elementEnd)
					}
				}
			}
		}

		val name = fileName.orEmpty().lowercase(Locale.ROOT)
		val mime = mimeType.orEmpty()
		val isFont = name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc") ||
			mime.contains("font") || mime.contains("opentype") || mime.contains("truetype")

		if (isFont && fileDataBytes != null && !fileName.isNullOrBlank()) {
			val fontFile = File(fontDir, fileName)
			if (fontFile.exists() && fontFile.length() > 0L) {
				fontFile.setLastModified(System.currentTimeMillis())
			} else {
				runCatching {
					fontFile.writeBytes(fileDataBytes)
					fontFile.setLastModified(System.currentTimeMillis())
					Timber.d("AssFontManager: Extracted local MKV font attachment: %s (%d KB)", fileName, fontFile.length() / 1024)
				}.onFailure { error ->
					fontFile.delete()
					Timber.w(error, "AssFontManager: Failed writing extracted font: %s", fileName)
				}
			}
		}
	}

	private fun readEbmlId(raf: RandomAccessFile): Long {
		if (raf.filePointer >= raf.length()) return -1L
		val b0 = raf.readUnsignedByte()
		var mask = 0x80
		var length = 1
		while (length <= 8 && (b0 and mask) == 0) {
			mask = mask ushr 1
			length++
		}
		if (length > 8) return -1L
		var id = b0.toLong()
		repeat(length - 1) {
			if (raf.filePointer >= raf.length()) return -1L
			id = (id shl 8) or raf.readUnsignedByte().toLong()
		}
		return id
	}

	private fun readEbmlVint(raf: RandomAccessFile): Long {
		if (raf.filePointer >= raf.length()) return -1L
		val b0 = raf.readUnsignedByte()
		var mask = 0x80
		var length = 1
		while (length <= 8 && (b0 and mask) == 0) {
			mask = mask ushr 1
			length++
		}
		if (length > 8) return -1L
		var value = (b0 and mask.inv()).toLong()
		repeat(length - 1) {
			if (raf.filePointer >= raf.length()) return -1L
			value = (value shl 8) or raf.readUnsignedByte().toLong()
		}
		return value
	}
}
