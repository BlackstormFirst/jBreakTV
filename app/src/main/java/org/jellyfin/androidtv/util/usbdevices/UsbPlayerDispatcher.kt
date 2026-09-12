package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.widget.Toast
import java.io.File

object UsbPlayerDispatcher {

    fun playMedia(context: Context, clickedFile: File, mediaFiles: List<File> = emptyList()) {
        if (!clickedFile.exists() || !clickedFile.canRead()) {
            Toast.makeText(context, "Fichier illisible ou introuvable", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(
            context,
            "Fichier sélectionné : ${clickedFile.name}",
            Toast.LENGTH_SHORT
        ).show()
    }
}
