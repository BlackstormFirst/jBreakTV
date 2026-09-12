package org.jellyfin.androidtv.util.usbdevices

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridPresenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject

class UsbVolumeSelectorFragment : VerticalGridSupportFragment() {

    private val navigationRepository: NavigationRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "Local or USB Devices Selection"
        super.onCreate(savedInstanceState)

        val volumes = UsbStorageManager.getMountedVolumes(requireContext())

        // Align USB volume tiles horizontally on a single row
        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            numberOfColumns = volumes.size.coerceAtLeast(1)
        }
        setGridPresenter(gridPresenter)

        val adapter = ArrayObjectAdapter(UsbVolumePresenter())
        for (vol in volumes) {
            adapter.add(vol)
        }
        this.adapter = adapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is UsbVolumeInfo) {
                val destination = Destinations.usbExplorer(
                    currentDir = item.path.absolutePath,
                    rootPath = item.path.absolutePath,
                    volumeName = item.label
                )
                navigationRepository.navigate(destination)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigationRepository.canGoBack) {
                    navigationRepository.goBack()
                } else {
                    navigationRepository.reset(Destinations.home, clearHistory = true)
                }
            }
        })

        view.post {
            val homeBtn = view.findViewById<View>(R.id.home)
                ?: titleView?.findViewById<View>(R.id.home)
                ?: activity?.findViewById<View>(R.id.home)

            homeBtn?.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    if (navigationRepository.canGoBack) {
                        navigationRepository.goBack()
                    } else {
                        navigationRepository.reset(Destinations.home, clearHistory = true)
                    }
                }
            }
        }

        setSelectedPosition(0)
        view.post {
            setSelectedPosition(0)
        }
    }
}

class UsbVolumePresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_usb_volume, parent, false)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        if (item !is UsbVolumeInfo) return
        val view = viewHolder.view
        val txtTitle = view.findViewById<TextView>(R.id.volume_title)
        val txtSubtitle = view.findViewById<TextView>(R.id.volume_subtitle)

        val freeFormatted = Formatter.formatFileSize(view.context, item.freeBytes)
        val totalFormatted = Formatter.formatFileSize(view.context, item.totalBytes)

        txtTitle.text = item.label
        txtSubtitle.text = "$freeFormatted free of $totalFormatted"
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
