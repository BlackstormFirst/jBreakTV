package org.jellyfin.androidtv.util

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.widget.Toast
import androidx.annotation.PluralsRes
import androidx.core.content.getSystemService
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Get the activity hosting the current context
 */
tailrec fun Context.getActivity(): Activity? = when (this) {
	is Activity -> this
	else -> (this as? ContextWrapper)?.baseContext?.getActivity()
}

/**
 * Wrapper for getQuantityString in [resources] that supports all number types.
 * Note: Adds the [quantity] as first vararg, this is different behavior compared
 * to the platform function!
 */
fun Context.getQuantityString(@PluralsRes id: Int, quantity: Number, vararg args: Any) =
	resources.getQuantityString(id, quantity.toInt(), quantity, *args)

fun Context.isTvDevice(): Boolean {
	val uiModeManager = getSystemService<UiModeManager>()
	val supportedUiModes = setOf(Configuration.UI_MODE_TYPE_TELEVISION, Configuration.UI_MODE_TYPE_UNDEFINED)

	return supportedUiModes.contains(uiModeManager?.currentModeType) or
		packageManager.hasSystemFeature("android.hardware.hdmi.cec") or
		!packageManager.hasSystemFeature("android.hardware.touchscreen")
}

fun showAddToQueueToast(context: Context, item: BaseItemDto?) {
	if (item == null) return
	val messageRes = when (item.type) {
		BaseItemKind.AUDIO -> R.string.msg_song_added_to_queue
		BaseItemKind.MUSIC_ALBUM -> R.string.msg_album_added_to_queue
		else -> null
	}
	if (messageRes != null) {
		Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
	}
}
