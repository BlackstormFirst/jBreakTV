package org.jellyfin.androidtv.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnAttach
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.max
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds

/**
 * An extension to the [ImageView] that makes it easy to load images from the network.
 * The [load] function takes a url, blurhash and placeholder to asynchronously load the image
 * using the lifecycle of the current fragment or activity.
 */
class AsyncImageView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr), KoinComponent {
	private val lifeCycleOwner get() = findViewTreeLifecycleOwner()
	private val imageLoader by inject<ImageLoader>()
	private var loadJob: Job? = null

	/**
	 * The duration of the crossfade when switching the images of the url, blurhash and
	 * placeholder.
	 */
	@Suppress("MagicNumber")
	var crossFadeDuration = 0.milliseconds

	/**
	 * Shape the image to a circle and remove all corners.
	 */
	var circleCrop = false

	init {
		if (attrs != null) {
			val styledAttributes = context.obtainStyledAttributes(attrs, R.styleable.AsyncImageView, defStyleAttr, 0)
			try {
				crossFadeDuration = styledAttributes.getInt(R.styleable.AsyncImageView_crossfadeDuration, 0).milliseconds
				circleCrop = styledAttributes.getBoolean(R.styleable.AsyncImageView_circleCrop, false)
			} finally {
				styledAttributes.recycle()
			}
		}
	}

	/**
	 * Load an image from the network using [url]. When the [url] is null or returns a bad response
	 * the [placeholder] is shown. A [blurHash] is shown while loading the image. An aspect ratio is
	 * required when using a BlurHash or the sizing will be incorrect.
	 */
	fun load(
		url: String? = null,
		blurHash: String? = null,
		placeholder: Drawable? = null,
		aspectRatio: Double = 1.0,
		blurHashResolution: Int = 16,
	) = doOnAttach {
		loadJob?.cancel()

		if (url == null) {
			val request = ImageRequest.Builder(context).apply {
				target(this@AsyncImageView)
				data(placeholder)
				if (circleCrop) transformations(CircleCropTransformation())
			}.build()
			imageLoader.enqueue(request)
			return@doOnAttach
		}

		loadJob = lifeCycleOwner?.lifecycleScope?.launch(Dispatchers.Main.immediate) {
			val isLowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
			var initialPlaceholder = placeholder

			// Skip BlurHash decoding if image is already cached in memory
			val isInMemoryCache = imageLoader.memoryCache?.get(MemoryCache.Key(url)) != null

			// Decode BlurHash asynchronously on IO dispatcher if available and not cached in memory
			if (blurHash != null && !isLowRamDevice && aspectRatio > 0 && !isInMemoryCache) {
				val res = max(16, blurHashResolution)
				val w = max(16, if (aspectRatio > 1) round(res * aspectRatio).toInt() else res)
				val h = max(16, if (aspectRatio < 1) round(res / aspectRatio).toInt() else res)

				val blurHashDrawable = withContext(Dispatchers.IO) {
					BlurHashDecoder.decode(
						blurHash = blurHash,
						width = w,
						height = h,
						context = context,
					)?.toDrawable(resources)
				}
				if (blurHashDrawable != null) {
					initialPlaceholder = blurHashDrawable
				}
			}

			val request = ImageRequest.Builder(context).apply {
				val crossFadeDurationMs = crossFadeDuration.inWholeMilliseconds.toInt()
				if (crossFadeDurationMs > 0) crossfade(crossFadeDurationMs)
				else crossfade(false)

				target(this@AsyncImageView)
				data(url)
				memoryCacheKey(url)
				if (initialPlaceholder != null) placeholder(initialPlaceholder.asImage())
				if (placeholder != null) error(placeholder.asImage())
				if (circleCrop) transformations(CircleCropTransformation())
			}.build()

			imageLoader.enqueue(request)
		}
	}
}
