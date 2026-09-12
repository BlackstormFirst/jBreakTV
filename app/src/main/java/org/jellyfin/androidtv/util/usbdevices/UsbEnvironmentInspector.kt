package org.jellyfin.androidtv.util.usbdevices

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

object UsbEnvironmentInspector {
    val isAndroid11OrHigher: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val isAndroid13OrHigher: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasStoragePermissions(context: Context): Boolean {
        return if (isAndroid11OrHigher) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getManageStorageIntent(context: Context): Intent {
        return if (isAndroid11OrHigher) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
