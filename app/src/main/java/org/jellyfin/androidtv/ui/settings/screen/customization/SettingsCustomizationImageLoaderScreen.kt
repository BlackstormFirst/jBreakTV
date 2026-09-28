package org.jellyfin.androidtv.ui.settings.screen.customization

import android.text.format.Formatter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RangeControl
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControl
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject

@Composable
fun SettingsCustomizationImageLoaderScreen() {
	val userPreferences = koinInject<UserPreferences>()
	val context = LocalContext.current
	var diskMaxCacheSize by rememberPreference(userPreferences, UserPreferences.diskMaxCacheSize)
	var memoryMaxCachePercent by rememberPreference(userPreferences, UserPreferences.memoryMaxCachePercent)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_customization).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_image_loader_management)) },
			)
		}

		item {
			// Image disk cache
			val interactionSource = remember { MutableInteractionSource() }

			ListControl(
				headingContent = { Text(stringResource(R.string.image_disk_cache_size)) },
				interactionSource = interactionSource,
				modifier = Modifier.focusKey("disk_cache_size")
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
				) {
					RangeControl(
						modifier = Modifier
							.height(4.dp)
							.weight(1f),
						interactionSource = interactionSource,
						min = 250f,
						max = 2000f,
						stepForward = 10f,
						value = diskMaxCacheSize.toFloat(),
						onValueChange = { diskMaxCacheSize = it.toLong() }
					)

					Spacer(Modifier.width(Tokens.Space.spaceSm))

					Box(
						modifier = Modifier.sizeIn(minWidth = 32.dp),
						contentAlignment = Alignment.CenterEnd
					) {
						Text(diskMaxCacheSize.toString() + "MB")
					}
				}
			}
		}

		item {
			// Image memory cache
			val interactionSource = remember { MutableInteractionSource() }

			ListControl(
				headingContent = { Text(stringResource(R.string.image_memory_cache_size)) },
				interactionSource = interactionSource,
				modifier = Modifier.focusKey("memory_cache_size")
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
				) {
					RangeControl(
						modifier = Modifier
							.height(4.dp)
							.weight(1f),
						interactionSource = interactionSource,
						min = 20f,
						max = 40f,
						stepForward = 1f,
						value = memoryMaxCachePercent.toFloat(),
						onValueChange = { memoryMaxCachePercent = it.toInt() }
					)

					Spacer(Modifier.width(Tokens.Space.spaceSm))

					Box(
						modifier = Modifier.sizeIn(minWidth = 32.dp),
						contentAlignment = Alignment.CenterEnd
					) {
						Text("$memoryMaxCachePercent%")
					}
				}
			}
		}

		item {
			// Image cache
			val imageLoader = koinInject<ImageLoader>()
			var imageCacheSize by remember { mutableLongStateOf(imageLoader.diskCache?.size ?: 0L) }
			ListButton(
				headingContent = { Text(stringResource(R.string.clear_image_cache)) },
				captionContent = {
					Text(
						stringResource(
							R.string.clear_image_cache_content,
							formatArgs = arrayOf(Formatter.formatFileSize(context, imageCacheSize))
						) + " / " + Formatter.formatFileSize(context, imageLoader.diskCache?.maxSize ?: (250L * 1024 * 1024))
					)
				},
				onClick = {
					imageLoader.memoryCache?.clear()
					imageLoader.diskCache?.clear()
					imageCacheSize = imageLoader.diskCache?.size ?: 0L
				},
				modifier = Modifier.focusKey("clear_image_cache")
			)
		}
	}
}
