package org.jellyfin.androidtv.util.usbdevices

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.Locale

object UsbStorageManager {
    private val _mountedVolumes = MutableStateFlow<List<UsbVolumeInfo>>(emptyList())
    val mountedVolumes: StateFlow<List<UsbVolumeInfo>> = _mountedVolumes.asStateFlow()

    private var initialized = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext

        // Register storage volume callback on Android 11+
        val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                storageManager.registerStorageVolumeCallback(
                    appContext.mainExecutor,
                    object : StorageManager.StorageVolumeCallback() {
                        override fun onStateChanged(volume: StorageVolume) {
                            runCatching {
                                val label = volume.getDescription(appContext)
                                Timber.d("UsbDebug: StorageVolumeCallback state changed for $label")
                            }.onFailure { e ->
                                Timber.w(e, "UsbDebug: StorageVolumeCallback state changed for volume")
                            }
                            updateVolumes(appContext)
                        }
                    }
                )
            } catch (e: Exception) {
                Timber.w(e, "UsbDebug: Could not register StorageVolumeCallback")
            }
        }

        // Register broadcast receiver for media mounted/unmounted intents (both with and without scheme)
        val filterNoScheme = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
        }

        val filterFileScheme = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addDataScheme("file")
        }

        try {
            val receiver = UsbStorageReceiver()
            ContextCompat.registerReceiver(appContext, receiver, filterNoScheme, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(appContext, receiver, filterFileScheme, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Timber.w(e, "UsbDebug: Could not register UsbStorageReceiver dynamically")
        }

        updateVolumes(appContext)
    }

    fun hasMountedUsbVolumes(context: Context? = null): Boolean {
        val volumes = getMountedVolumes(context)
        return volumes.isNotEmpty()
    }

    fun getMountedVolumes(context: Context? = null): List<UsbVolumeInfo> {
        val current = _mountedVolumes.value
        if (current.isNotEmpty()) {
            val validCurrent = current.filter { it.path.exists() }
            if (validCurrent.isNotEmpty()) return validCurrent
        }

        if (context != null) {
            val syncList = scanVolumesSync(context)
            _mountedVolumes.value = syncList
            return syncList
        }
        return emptyList()
    }

    fun scanVolumesSync(context: Context): List<UsbVolumeInfo> {
        val list = mutableListOf<UsbVolumeInfo>()

        // Tier 1: StorageManager API
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val usbVolumes = storageManager.storageVolumes.filter { volume ->
                    !volume.isPrimary &&
                    !volume.isEmulated &&
                    volume.isRemovable &&
                    volume.state == Environment.MEDIA_MOUNTED
                }

                for (volume in usbVolumes) {
                    val path = getVolumeDirectory(volume) ?: continue
                    if (!path.exists()) continue

                    val absPath = path.absolutePath.lowercase(Locale.ROOT)
                    if (absPath.contains("/emulated") ||
                        absPath.contains("/self") ||
                        absPath == "/storage/0" ||
                        absPath == "/storage"
                    ) {
                        continue
                    }

                    val (total, free) = getStorageStats(path)

                    val label = getVolumeLabel(context, volume, path)
                    val id = volume.uuid ?: path.name

                    list.add(UsbVolumeInfo(id, label, path, total, free))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "UsbDebug: Error scanning volumes via StorageManager")
        }

        // Tier 2: Direct /storage/ filesystem scan for removable volume mounts
        if (list.isEmpty()) {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                val subDirs = storageDir.listFiles() ?: emptyArray()
                for (dir in subDirs) {
                    val name = dir.name.lowercase(Locale.ROOT)
                    if (dir.isDirectory &&
                        name != "emulated" &&
                        name != "self" &&
                        name != "0" &&
                        name != "sdcard0"
                    ) {
                        val (total, free) = getStorageStats(dir)
                        val id = dir.name
                        val label = "USB Drive ($id)"
                        list.add(UsbVolumeInfo(id, label, dir, total, free))
                    }
                }
            }
        }

        // Tier 3: Persistence Safeguard
        if (list.isEmpty() && _mountedVolumes.value.isNotEmpty()) {
            val validPrevious = _mountedVolumes.value.filter { vol -> vol.path.exists() }
            if (validPrevious.isNotEmpty()) {
                return validPrevious
            }
        }

        return list
    }

    fun updateVolumes(context: Context) {
        scope.launch {
            val initial = scanVolumesSync(context)
            if (initial != _mountedVolumes.value) {
                _mountedVolumes.value = initial
            }

            // Retry scans with delay to catch USB devices as OS mount completes
            val delays = listOf(300L, 800L, 1500L, 3000L)
            for (d in delays) {
                delay(d)
                val updated = scanVolumesSync(context)
                if (updated != _mountedVolumes.value) {
                    _mountedVolumes.value = updated
                    break
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getVolumeDirectory(volume: StorageVolume): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            try {
                val getPathMethod = volume.javaClass.getMethod("getPath")
                val pathString = getPathMethod.invoke(volume) as? String
                pathString?.let { File(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getVolumeLabel(context: Context, volume: StorageVolume, path: File): String {
        return try {
            val userLabel = volume.getDescription(context)
            if (!userLabel.isNullOrEmpty()) userLabel else "USB Drive (${path.name})"
        } catch (e: Exception) {
            "USB Drive (${path.name})"
        }
    }

    private fun getStorageStats(file: File): Pair<Long, Long> {
        return try {
            val stat = StatFs(file.absolutePath)
            val totalBytes = stat.totalBytes
            val freeBytes = stat.availableBytes
            Pair(totalBytes, freeBytes)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }
}
