package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.TextPaint
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import java.io.File

const val ARG_VOLUME_NAME = "ARG_VOLUME_NAME"
const val ARG_VOLUME_PATH = "ARG_VOLUME_PATH"
const val ARG_CURRENT_DIR = "ARG_CURRENT_DIR"
const val ARG_ROOT_PATH = "ARG_ROOT_PATH"

class UsbFileExplorerFragment : VerticalGridSupportFragment() {

    private val navigationRepository: NavigationRepository by inject()

    private var volumeName: String = "Périphériques Locaux ou USB"
    private var currentDirPath: String = ""
    private var rootDirPath: String = ""

    private lateinit var currentDir: File
    private lateinit var rootDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        volumeName = arguments?.getString(ARG_VOLUME_NAME) ?: "Périphériques Locaux ou USB"
        currentDirPath = arguments?.getString(ARG_CURRENT_DIR)
            ?: arguments?.getString(ARG_VOLUME_PATH) ?: ""
        rootDirPath = arguments?.getString(ARG_ROOT_PATH)
            ?: currentDirPath

        if (currentDirPath.isBlank()) {
            super.onCreate(savedInstanceState)
            navigationRepository.goBack()
            return
        }

        currentDir = File(currentDirPath)
        rootDir = File(rootDirPath)

        val relativePath = currentDir.absolutePath.removePrefix(rootDir.absolutePath).trim('/')
        val breadcrumbStr = if (relativePath.isBlank()) {
            volumeName
        } else {
            "$volumeName > " + relativePath.replace("/", " > ")
        }

        title = breadcrumbStr
        super.onCreate(savedInstanceState)

        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            numberOfColumns = 1
        }
        setGridPresenter(gridPresenter)

        loadFiles()

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is File) {
                if (item.isDirectory) {
                    val destination = Destinations.usbExplorer(
                        currentDir = item.absolutePath,
                        rootPath = rootDir.absolutePath,
                        volumeName = volumeName
                    )
                    navigationRepository.navigate(destination)
                } else if (UsbMediaHelper.isMediaFile(item)) {
                    val allMediaInFolder = (currentDir.listFiles() ?: emptyArray())
                        .filter { UsbMediaHelper.isMediaFile(it) }
                        .sortedWith(UsbMediaHelper.naturalFileComparator)

                    UsbPlayerDispatcher.playMedia(requireContext(), item, allMediaInFolder)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                if (navigationRepository.canGoBack) {
                    navigationRepository.goBack()
                } else {
                    activity?.finish()
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
                    navigationRepository.reset(Destinations.home, clearHistory = true)
                }
            }
        }

        setSelectedPosition(0)
        view.post {
            setSelectedPosition(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbStorageManager.mountedVolumes.collect { volumes ->
                    val isStillMounted = volumes.any { vol ->
                        rootDirPath.startsWith(vol.path.absolutePath) || vol.path.absolutePath == rootDirPath
                    }
                    if (!isStillMounted) {
                        if (navigationRepository.canGoBack) {
                            navigationRepository.goBack()
                        } else {
                            navigationRepository.reset(Destinations.home, clearHistory = true)
                        }
                    }
                }
            }
        }
    }

    private fun loadFiles() {
        val rawFiles = currentDir.listFiles() ?: emptyArray()

        val directories = rawFiles.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedWith(UsbMediaHelper.naturalFileComparator)

        val mediaFiles = rawFiles.filter { UsbMediaHelper.isMediaFile(it) }
            .sortedWith(UsbMediaHelper.naturalFileComparator)

        val allItems = buildList {
            addAll(directories)
            addAll(mediaFiles)
        }

        val uniformWidthPx = calculateMaxItemWidthPx(requireContext(), allItems)
        val adapter = ArrayObjectAdapter(FilePresenter(uniformWidthPx))

        for (item in allItems) {
            adapter.add(item)
        }

        this.adapter = adapter
    }

    private fun calculateMaxItemWidthPx(context: Context, items: List<File>): Int {
        val paint = TextPaint().apply {
            textSize = 16f * context.resources.displayMetrics.scaledDensity
            isAntiAlias = true
        }

        var maxTextWidthPx = 0f
        for (item in items) {
            val textWidth = paint.measureText(item.name)
            if (textWidth > maxTextWidthPx) {
                maxTextWidthPx = textWidth
            }
        }

        val extraPaddingPx = (112 * context.resources.displayMetrics.density).toInt()
        val totalWidthPx = (maxTextWidthPx + extraPaddingPx).toInt()

        val minWidthPx = (320 * context.resources.displayMetrics.density).toInt()
        val maxWidthPx = (800 * context.resources.displayMetrics.density).toInt()

        return totalWidthPx.coerceIn(minWidthPx, maxWidthPx)
    }

    private class FilePresenter(private val uniformWidthPx: Int) : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_usb_file, parent, false)
            view.isFocusable = true
            view.isFocusableInTouchMode = true

            val lp = view.layoutParams ?: ViewGroup.LayoutParams(uniformWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.width = uniformWidthPx
            view.layoutParams = lp

            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            if (item !is File) return
            val view = viewHolder.view

            val lp = view.layoutParams
            if (lp != null && lp.width != uniformWidthPx) {
                lp.width = uniformWidthPx
                view.layoutParams = lp
            }

            val imgIcon = view.findViewById<ImageView>(R.id.file_icon)
            val txtName = view.findViewById<TextView>(R.id.file_name)
            val txtSize = view.findViewById<TextView>(R.id.file_size)

            txtName.text = item.name

            if (item.isDirectory) {
                imgIcon.setImageResource(R.drawable.ic_folder)
                imgIcon.setColorFilter(Color.parseColor("#FFC107"))
                val count = item.listFiles()?.size ?: 0
                txtSize.text = "$count éléments"
            } else {
                imgIcon.clearColorFilter()
                if (UsbMediaHelper.isVideoFile(item)) {
                    imgIcon.setImageResource(R.drawable.ic_video)
                } else {
                    imgIcon.setImageResource(R.drawable.ic_movie)
                }
                val formattedSize = Formatter.formatFileSize(view.context, item.length())
                txtSize.text = formattedSize
            }
        }

        override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
    }
}
