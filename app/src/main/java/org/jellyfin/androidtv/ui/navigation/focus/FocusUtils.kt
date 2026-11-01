package org.jellyfin.androidtv.ui.navigation.focus

import androidx.compose.ui.focus.FocusRequester

private val focusRequesterNodesField by lazy {
	runCatching {
		FocusRequester::class.java.getDeclaredField("focusRequesterNodes").apply {
			isAccessible = true
		}
	}.getOrNull()
}

/**
 * Returns true if this [FocusRequester] is initialized and attached to at least one layout node.
 */
val FocusRequester.isInitialized: Boolean
	get() {
		if (this === FocusRequester.Default || this === FocusRequester.Cancel) return false
		val nodes = focusRequesterNodesField?.get(this) ?: return false
		return runCatching {
			val isNotEmptyMethod = runCatching { nodes.javaClass.getMethod("isNotEmpty") }.getOrNull()
			if (isNotEmptyMethod != null) {
				isNotEmptyMethod.invoke(nodes) as? Boolean ?: false
			} else {
				val getSizeMethod = runCatching { nodes.javaClass.getMethod("getSize") }.getOrNull()
				if (getSizeMethod != null) {
					(getSizeMethod.invoke(nodes) as? Int ?: 0) > 0
				} else {
					false
				}
			}
		}.getOrDefault(false)
	}

/**
 * Requests focus safely, verifying that this [FocusRequester] is initialized and attached
 * to a layout node before calling [FocusRequester.requestFocus].
 *
 * This prevents Compose from logging "FocusRelatedWarning: FocusRequester is not initialized".
 */
fun FocusRequester?.safeRequestFocus(): Boolean {
	if (this == null || !isInitialized) return false
	return runCatching { requestFocus() }.getOrDefault(false)
}
