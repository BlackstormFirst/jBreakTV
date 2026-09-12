package org.jellyfin.androidtv.util.usbdevices

import java.io.File
import java.io.Serializable

data class UsbVolumeInfo(
    val id: String,
    val label: String,
    val path: File,
    val totalBytes: Long,
    val freeBytes: Long
) : Serializable
