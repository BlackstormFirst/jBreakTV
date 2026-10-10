package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.util.usbdevices.UsbStorageManager
import org.koin.android.ext.android.get

class JellyfinApplication : Application(), SingletonImageLoader.Factory {
	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		TelemetryService.init(this)
	}

	override fun onCreate() {
		super.onCreate()
		UsbStorageManager.initialize(this)
	}

	override fun newImageLoader(context: PlatformContext): ImageLoader {
		return get<ImageLoader>()
	}
}
