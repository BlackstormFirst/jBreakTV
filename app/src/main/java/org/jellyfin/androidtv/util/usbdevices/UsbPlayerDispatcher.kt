package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import java.io.File

object UsbPlayerDispatcher {

	fun playMedia(context: Context, clickedFile: File, mediaFiles: List<File>) {
		if (!clickedFile.exists() || !clickedFile.canRead()) {
			Toast.makeText(context, "File unreadable or not found", Toast.LENGTH_SHORT).show()
			return
		}

		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Main) {
			try {
				val (localItems, selectedIndex) = withContext(Dispatchers.IO) {
					val sortedMedia = mediaFiles
						.filter { UsbMediaHelper.isMediaFile(it) }
						.sortedWith(UsbMediaHelper.naturalFileComparator)

					val targetIndex = sortedMedia.indexOfFirst { it.absolutePath == clickedFile.absolutePath }.coerceAtLeast(0)

					val items = sortedMedia.mapIndexed { index, file ->
						if (index == targetIndex) {
							// Full inspection of the active file selected on click
							LocalVideoManager.inspectAndBuildBaseItemDto(context, file)
						} else {
							// Lightweight model for queue items
							LocalVideoManager.buildMinimalBaseItemDto(context, file)
						}
					}
					Pair(items, targetIndex)
				}

				val activeItem = localItems[selectedIndex]
				val streamsCount = activeItem.mediaSources?.firstOrNull()?.mediaStreams?.size ?: 0
				Timber.d("UsbDebug: Active item enriched: name=${activeItem.name}, runTimeTicks=${activeItem.runTimeTicks}, streamsCount=$streamsCount")

				if (localItems.isEmpty()) {
					Toast.makeText(context, "No valid media in folder", Toast.LENGTH_SHORT).show()
					return@launch
				}

				val playbackLauncher = get<PlaybackLauncher>(PlaybackLauncher::class.java)
				playbackLauncher.launch(
					context = context,
					items = localItems,
					itemsPosition = selectedIndex,
					replace = false
				)
			} catch (t: Throwable) {
				Timber.e(t, "UsbDebug: Error launching player queue")
				Toast.makeText(
					context,
					"Playback error: ${t.javaClass.simpleName} - ${t.message}",
					Toast.LENGTH_LONG
				).show()
			}
		}
	}
}
