package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber

object UsbHomeDecorator {

    @JvmOverloads
    fun handleUsbTileClick(
        context: Context,
        navigationRepository: NavigationRepository = get(NavigationRepository::class.java)
    ) {
        try {
            Timber.d("UsbDebug: handleUsbTileClick invoked")
            if (!UsbEnvironmentInspector.hasStoragePermissions(context)) {
                Toast.makeText(context, "Storage permission required", Toast.LENGTH_LONG).show()
                val intent = UsbEnvironmentInspector.getManageStorageIntent(context).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }

            val volumes = UsbStorageManager.getMountedVolumes(context)
            Timber.d("UsbDebug: handleUsbTileClick mountedVolumes count=${volumes.size}")
            if (volumes.isEmpty()) {
                Toast.makeText(context, "No USB drive detected", Toast.LENGTH_SHORT).show()
                return
            }

            if (volumes.size == 1) {
                val rootPath = volumes[0].path.absolutePath
                val destination = Destinations.usbExplorer(
                    currentDir = rootPath,
                    rootPath = rootPath,
                    volumeName = volumes[0].label
                )
                navigationRepository.navigate(destination)
            } else {
                navigationRepository.navigate(Destinations.usbVolumeSelector)
            }
        } catch (t: Throwable) {
            Timber.e(t, "UsbDebug: Error launching USB Explorer")
            Toast.makeText(
                context,
                "Error opening USB: ${t.javaClass.simpleName} - ${t.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
