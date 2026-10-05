package org.jellyfin.androidtv.ui.player.video

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.OverlayTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.GuidePagingButton
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView
import org.jellyfin.androidtv.ui.ObservableScrollView
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.ui.ScrollViewListener
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import java.time.LocalDateTime
import java.util.UUID

private const val GUIDE_HOURS = 9
private const val PAGE_SIZE = 8

@Composable
fun LiveTvGuideOverlay(
	visible: Boolean,
	onDismissRequest: () -> Unit,
	onChannelSelected: (UUID) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!visible) return

	val context = LocalContext.current
	val activity = context as? FragmentActivity ?: return
	val coroutineScope = rememberCoroutineScope()

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(ComposeColor.Black.copy(alpha = 0.85f))
			.onKeyEvent { keyEvent ->
				if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
				if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
					onDismissRequest()
					true
				} else {
					false
				}
			}
	) {
		AndroidView(
			factory = { ctx ->
				val binding = OverlayTvGuideBinding.inflate(LayoutInflater.from(ctx))
				val guideController = ComposeLiveTvGuideController(
					activity = activity,
					binding = binding,
					onChannelSelected = { channelId ->
						coroutineScope.launch {
							onChannelSelected(channelId)
							onDismissRequest()
						}
					},
					onDismiss = onDismissRequest
				)
				binding.root.tag = guideController
				guideController.initAndLoad()
				binding.root
			},
			update = { view ->
				val controller = view.tag as? ComposeLiveTvGuideController
				controller?.requestFocus()
			},
			modifier = Modifier.fillMaxSize()
		)
	}
}

private class ComposeLiveTvGuideController(
	private val activity: FragmentActivity,
	private val binding: OverlayTvGuideBinding,
	private val onChannelSelected: (UUID) -> Unit,
	private val onDismiss: () -> Unit,
) : LiveTvGuide, View.OnKeyListener {

	private var currentGuideStart: LocalDateTime = LocalDateTime.now()
		.withMinute(if (LocalDateTime.now().minute >= 30) 30 else 0)
		.withSecond(0)
		.withNano(0)
	private var currentGuideEnd: LocalDateTime = currentGuideStart.plusHours(GUIDE_HOURS.toLong())
	private var currentDisplayChannelStartNdx = 0
	private var currentDisplayChannelEndNdx = 0
	private var allChannels: List<BaseItemDto> = emptyList()
	private var selectedProgramView: View? = null
	private var selectedProgram: BaseItemDto? = null

	fun initAndLoad() {
		binding.channelsStatus.setTextColor(Color.GRAY)
		binding.filterStatus.setTextColor(Color.GRAY)

		binding.programRows.isFocusable = false
		binding.programVScroller.setScrollViewListener(object : ScrollViewListener {
			override fun onScrollChanged(scrollView: ObservableScrollView?, x: Int, y: Int, oldx: Int, oldy: Int) {
				binding.channelScroller.scrollTo(x, y)
			}
		})

		binding.channelScroller.setScrollViewListener(object : ScrollViewListener {
			override fun onScrollChanged(scrollView: ObservableScrollView?, x: Int, y: Int, oldx: Int, oldy: Int) {
				binding.programVScroller.scrollTo(x, y)
			}
		})

		binding.timelineHScroller.isFocusable = false
		binding.timelineHScroller.isFocusableInTouchMode = false
		binding.timeline.isFocusable = false
		binding.timeline.isFocusableInTouchMode = false
		binding.channelScroller.isFocusable = false
		binding.channelScroller.isFocusableInTouchMode = false

		binding.programHScroller.scrollViewListener = HorizontalScrollViewListener { _, x, y, _, _ ->
			binding.timelineHScroller.scrollTo(x, y)
		}
		binding.programHScroller.isFocusable = false
		binding.programHScroller.isFocusableInTouchMode = false

		binding.channels.isFocusable = false
		binding.channelScroller.isFocusable = false

		loadGuide()
	}

	fun requestFocus() {
		if (selectedProgramView != null) {
			selectedProgramView?.requestFocus()
		} else {
			binding.programRows.requestFocus()
		}
	}

	private fun loadGuide() {
		binding.spinner.visibility = View.VISIBLE
		fillTimeLine(GUIDE_HOURS)

		val dummyFragment = activity.supportFragmentManager.fragments.firstOrNull() ?: return

		TvManager.loadAllChannels(dummyFragment) { ndx ->
			val targetNdx = if (ndx != null && ndx >= PAGE_SIZE) {
				ndx - (PAGE_SIZE / 2)
			} else 0

			allChannels = TvManager.getAllChannels()
			if (allChannels.isNotEmpty()) {
				displayChannels(targetNdx, PAGE_SIZE)
			} else {
				binding.spinner.visibility = View.GONE
			}
			null
		}
	}

	private fun fillTimeLine(hours: Int) {
		binding.timeline.removeAllViews()
		var start = currentGuideStart
		val formatter = activity.getTimeFormatter()

		for (i in 0 until hours * 2) {
			val tv = TextView(activity)
			val params = LinearLayout.LayoutParams(
				Utils.convertDpToPixel(activity, 130),
				LinearLayout.LayoutParams.WRAP_CONTENT
			)
			tv.layoutParams = params
			tv.text = formatter.format(start)
			tv.setTextColor(Color.WHITE)
			binding.timeline.addView(tv)
			start = start.plusMinutes(30)
		}
	}

	override fun displayChannels(start: Int, max: Int) {
		var end = start + max
		if (end > allChannels.size) end = allChannels.size

		currentDisplayChannelStartNdx = start
		currentDisplayChannelEndNdx = if (end > 0) end - 1 else 0

		binding.spinner.visibility = View.VISIBLE
		binding.channels.removeAllViews()
		binding.programRows.removeAllViews()
		binding.channelsStatus.text = ""
		binding.filterStatus.text = ""

		val dummyFragment = activity.supportFragmentManager.fragments.firstOrNull() ?: return

		TvManager.getProgramsAsync(
			dummyFragment,
			currentDisplayChannelStartNdx,
			currentDisplayChannelEndNdx,
			currentGuideStart,
			currentGuideEnd,
			object : EmptyResponse(activity.lifecycle) {
				override fun onResponse() {
					renderGuideRows()
				}
			}
		)
	}

	override fun getCurrentLocalStartDate(): LocalDateTime = currentGuideStart

	override fun showProgramOptions() {
		val program = selectedProgram ?: return
		val cell = selectedProgramView as? ProgramGridCell ?: return
		val width = Utils.convertDpToPixel(activity, 400)
		val popup = LiveProgramDetailPopup(activity, activity, this, width, null)
		popup.setContent(program, cell)
		popup.show(cell, 0, 0)
	}

	override fun setSelectedProgram(programView: RelativeLayout) {
		selectedProgramView = programView
		if (programView is ProgramGridCell) {
			selectedProgram = programView.program
		}
	}

	override fun refreshFavorite(channelId: UUID) {
		for (i in 0 until binding.channels.childCount) {
			val gch = binding.channels.getChildAt(i) as? GuideChannelHeader
			if (gch?.channel?.id == channelId) {
				gch.findViewById<View>(R.id.favImage)?.visibility =
					if (gch.channel.userData?.isFavorite == true) View.VISIBLE else View.GONE
				break
			}
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: android.view.KeyEvent?): Boolean {
		if (event?.action != android.view.KeyEvent.ACTION_DOWN) return false

		if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
			if (selectedProgramView is ProgramGridCell && selectedProgram != null) {
				val channelId = selectedProgram?.channelId ?: selectedProgram?.id
				if (channelId != null) {
					onChannelSelected(channelId)
					return true
				}
			} else if (selectedProgramView is GuideChannelHeader) {
				val channel = (selectedProgramView as GuideChannelHeader).channel
				onChannelSelected(channel.id)
				return true
			}
		} else if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
			onDismiss()
			return true
		}
		return false
	}

	private fun renderGuideRows() {
		Handler(Looper.getMainLooper()).post {
			binding.channels.removeAllViews()
			binding.programRows.removeAllViews()

			val lastChannelId = TvManager.getLastLiveTvChannel()
			var firstRow: View? = null

			if (currentDisplayChannelStartNdx > 0) {
				val pageUpStart = currentDisplayChannelStartNdx - PAGE_SIZE
				val placeHolder = View(activity)
				placeHolder.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					Utils.convertDpToPixel(activity, 42)
				)
				binding.channels.addView(placeHolder)

				val channelNum = allChannels.getOrNull(pageUpStart)?.number ?: ""
				val label = activity.getString(R.string.lbl_load_channels) + channelNum
				binding.programRows.addView(
					GuidePagingButton(activity, this@ComposeLiveTvGuideController, pageUpStart, label)
				)
			}

			var prevRow: LinearLayout? = null
			for (i in currentDisplayChannelStartNdx..currentDisplayChannelEndNdx) {
				val channel = TvManager.getChannel(i) ?: continue
				val programs = TvManager.getProgramsForChannel(channel.id)
				val row = getProgramRow(programs, channel.id)

				if (firstRow == null) firstRow = row

				if (channel.id == lastChannelId) {
					selectedProgramView = row.getChildAt(0)
				}

				if (prevRow != null) {
					TvManager.setFocusParams(prevRow, row, false)
					TvManager.setFocusParams(row, prevRow, true)
				}
				prevRow = row

				val header = GuideChannelHeader(activity, this@ComposeLiveTvGuideController, channel)
				binding.channels.addView(header)
				binding.programRows.addView(row)
			}

			if (currentDisplayChannelEndNdx < allChannels.size - 1) {
				val placeHolder = View(activity)
				placeHolder.layoutParams = LinearLayout.LayoutParams(
					LinearLayout.LayoutParams.MATCH_PARENT,
					Utils.convertDpToPixel(activity, 42)
				)
				binding.channels.addView(placeHolder)

				val channelNum = allChannels.getOrNull(currentDisplayChannelEndNdx + 1)?.number ?: ""
				val label = activity.getString(R.string.lbl_load_channels) + channelNum
				binding.programRows.addView(
					GuidePagingButton(activity, this@ComposeLiveTvGuideController, currentDisplayChannelEndNdx + 1, label)
				)
			}

			binding.channelsStatus.text = activity.getString(
				R.string.lbl_tv_channel_status,
				(currentDisplayChannelEndNdx - currentDisplayChannelStartNdx + 1),
				allChannels.size
			)
			binding.filterStatus.text = activity.getString(R.string.lbl_tv_filter_status, GUIDE_HOURS)
			binding.spinner.visibility = View.GONE

			selectedProgramView?.requestFocus() ?: firstRow?.requestFocus()
		}
	}

	private fun getProgramRow(programs: List<BaseItemDto>, channelId: UUID): LinearLayout {
		val row = LinearLayout(activity)
		row.orientation = LinearLayout.HORIZONTAL
		row.layoutParams = LinearLayout.LayoutParams(
			LinearLayout.LayoutParams.WRAP_CONTENT,
			Utils.convertDpToPixel(activity, 42)
		)

		if (programs.isEmpty()) {
			val emptyProgram = BaseItemDto(
				id = channelId,
				channelId = channelId,
				name = activity.getString(R.string.no_program_data),
				startDate = currentGuideStart,
				endDate = currentGuideEnd,
				type = BaseItemKind.PROGRAM,
				mediaType = MediaType.UNKNOWN,
			)
			val cell = ProgramGridCell(activity, this@ComposeLiveTvGuideController, emptyProgram, true)
			cell.setOnKeyListener(this@ComposeLiveTvGuideController)
			row.addView(cell)
		} else {
			for (program in programs) {
				val cell = ProgramGridCell(activity, this@ComposeLiveTvGuideController, program, true)
				cell.setOnKeyListener(this@ComposeLiveTvGuideController)
				row.addView(cell)
			}
		}
		return row
	}
}
