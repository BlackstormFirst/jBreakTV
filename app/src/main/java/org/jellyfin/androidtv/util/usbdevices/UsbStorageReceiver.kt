package org.jellyfin.androidtv.util.usbdevices

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class UsbStorageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("UsbStorageReceiver: Intent received ${intent.action}")
        UsbStorageManager.updateVolumes(context)
    }
}
