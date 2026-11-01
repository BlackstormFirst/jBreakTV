package org.jellyfin.androidtv.ui.composable

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.BlurHashDecoder
import kotlin.math.max
import kotlin.math.round

@Composable
fun AsyncImage(
	modifier: Modifier = Modifier,
	url: String? = null,
	blurHash: String? = null,
	placeholder: Drawable? = null,
	aspectRatio: Float = 1f,
	blurHashResolution: Int = 16,
	scaleType: ImageView.ScaleType? = null,
	errorContent: @Composable (() -> Unit)? = null,
) {
	val context = LocalContext.current

	val contentScale = when (scaleType) {
		ImageView.ScaleType.FIT_CENTER -> ContentScale.Fit
		ImageView.ScaleType.CENTER_CROP -> ContentScale.Crop
		ImageView.ScaleType.CENTER_INSIDE -> ContentScale.Inside
		ImageView.ScaleType.FIT_XY -> ContentScale.FillBounds
		else -> ContentScale.Crop
	}

	val blurPlaceholderState = produceState<Painter?>(initialValue = null, url, blurHash, aspectRatio, blurHashResolution) {
		val hash = blurHash
		if (!hash.isNullOrEmpty() && aspectRatio > 0f) {
			val res = max(16, blurHashResolution)
			val w = max(16, if (aspectRatio > 1) round(res * aspectRatio).toInt() else res)
			val h = max(16, if (aspectRatio < 1) round(res / aspectRatio).toInt() else res)

			val cachedBitmap = BlurHashDecoder.getCached(hash, w, h)
			if (cachedBitmap != null) {
				value = BitmapPainter(cachedBitmap.asImageBitmap())
			} else {
				val decodedBitmap = withContext(Dispatchers.IO) {
					BlurHashDecoder.decode(
						blurHash = hash,
						width = w,
						height = h,
						context = context,
					)
				}
				if (decodedBitmap != null) {
					value = BitmapPainter(decodedBitmap.asImageBitmap())
				}
			}
		}
	}

	val placeholderPainter = blurPlaceholderState.value

	val model = remember(context, url) {
		ImageRequest.Builder(context)
			.data(url ?: placeholder)
			.memoryCacheKey(url)
			.build()
	}

	val painter = rememberAsyncImagePainter(model = model)
	val painterState by painter.state.collectAsState()

	Box(modifier = modifier) {
		when (painterState) {
			is AsyncImagePainter.State.Success -> {
				Image(
					painter = painter,
					contentDescription = null,
					contentScale = contentScale,
					modifier = Modifier.fillMaxSize(),
				)
			}
			is AsyncImagePainter.State.Error, is AsyncImagePainter.State.Empty -> {
				if (errorContent != null) {
					errorContent()
				} else if (placeholderPainter != null) {
					Image(
						painter = placeholderPainter,
						contentDescription = null,
						contentScale = contentScale,
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
			is AsyncImagePainter.State.Loading -> {
				if (placeholderPainter != null) {
					Image(
						painter = placeholderPainter,
						contentDescription = null,
						contentScale = contentScale,
						modifier = Modifier.fillMaxSize(),
					)
				}
			}
		}
	}
}

@Composable
fun blurHashPainter(
	blurHash: String,
	size: IntSize,
	punch: Float = 1f,
): Painter {
	val bitmapState = produceState<Painter>(initialValue = remember { ColorPainter(Color.Transparent) }, blurHash, size, punch) {
		val bitmap = withContext(Dispatchers.Default) {
			BlurHashDecoder.decode(
				blurHash = blurHash,
				width = size.width,
				height = size.height,
				punch = punch,
			)
		}
		if (bitmap != null) {
			value = BitmapPainter(bitmap.asImageBitmap())
		}
	}
	return bitmapState.value
}
