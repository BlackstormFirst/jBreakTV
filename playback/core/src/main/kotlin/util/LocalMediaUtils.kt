package org.jellyfin.playback.core.util

import java.util.Locale

/**
 * Utility functions to identify local physical files/paths on the Android TV device (USB drives, internal storage)
 * that should not trigger remote Jellyfin server HTTP requests.
 */
fun isLocalPath(path: String?): Boolean {
	if (path.isNullOrBlank()) return false
	val lower = path.lowercase(Locale.ROOT)
	if (lower.startsWith("http://") || lower.startsWith("https://")) {
		return false
	}
	return lower.startsWith("local_")
		|| lower.startsWith("/storage/")
		|| lower.startsWith("/mnt/")
		|| lower.startsWith("content://")
		|| lower.startsWith("file:/storage/")
		|| lower.startsWith("file:///storage/")
}
