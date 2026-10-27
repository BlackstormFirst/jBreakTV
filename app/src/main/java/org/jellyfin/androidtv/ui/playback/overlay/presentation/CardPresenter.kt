package org.jellyfin.androidtv.ui.playback.overlay.presentation

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.androidtv.util.KeyProcessor
import androidx.fragment.app.FragmentActivity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.androidtv.preference.UserPreferences
import androidx.recyclerview.widget.RecyclerView
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.findViewTreeCompositionContext
import org.jellyfin.androidtv.ui.presentation.composable.BaseRowItemCard

class CardPresenter(
	val showInfo: Boolean,
	val imageType: ImageType,
	val staticHeight: Int,
	val uniformAspect: Boolean,
) : Presenter(), KoinComponent {
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()

	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int) : this(showInfo, imageType, staticHeight, false)
	constructor(showInfo: Boolean, staticHeight: Int) : this(showInfo, ImageType.POSTER, staticHeight)
	constructor(showInfo: Boolean) : this(showInfo, 150)
	constructor() : this(true)

	fun prewarm(parent: ViewGroup, pool: RecyclerView.RecycledViewPool, count: Int = 10, viewType: Int = 0) {
		runCatching {
			val adapter = ArrayObjectAdapter(this@CardPresenter).apply {
				add("dummy")
			}
			val itemBridgeAdapter = ItemBridgeAdapter().apply {
				setAdapter(adapter)
			}
			val computedViewType = if (adapter.size() > 0) itemBridgeAdapter.getItemViewType(0) else viewType
			val currentCount = pool.getRecycledViewCount(computedViewType)
			if (currentCount >= count) return
			repeat(count - currentCount) {
				val rvh = itemBridgeAdapter.onCreateViewHolder(parent, computedViewType)
				pool.putRecycledView(rvh)
			}
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true

			setOnLongClickListener {
				context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}

		return CardViewHolder(view)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return
		if (item !is BaseRowItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	private val displayCirclePersonCardsPref by lazy {
		userPreferences[UserPreferences.displayCirclePersonCards]
	}

	private inner class CardViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		private val _item = MutableStateFlow<BaseRowItem?>(null)
		private val _focused = MutableStateFlow(false)
		private val _displayCirclePersonCards = MutableStateFlow(false)

		init {
			composeView.setContent {
				val item by _item.collectAsState()
				val focused by _focused.collectAsState()
				val displayCirclePersonCards by _displayCirclePersonCards.collectAsState()

				BaseRowItemCard(
					item = item,
					focused = focused,
					showInfo = showInfo,
					imageType = imageType,
					staticHeight = staticHeight,
					uniformAspect = uniformAspect
				)
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
			composeView.setOnLongClickListener {
				val activity = composeView.context.getActivity() as? FragmentActivity
				val item = _item.value
				if (activity != null && item != null) {
					val keyProcessor: KeyProcessor by org.koin.java.KoinJavaComponent.inject(KeyProcessor::class.java)
					keyProcessor.createItemMenu(item, item.baseItem?.userData, activity)
					true
				} else {
					composeView.context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
				}
			}
		}

		fun bind(item: BaseRowItem) {
			val circleSetting = displayCirclePersonCardsPref
			if (_displayCirclePersonCards.value != circleSetting) {
				_displayCirclePersonCards.value = circleSetting
			}
			if (_item.value != item) {
				_item.value = item
			}
			val isFocused = view.isFocused
			if (_focused.value != isFocused) {
				_focused.value = isFocused
			}
		}

		fun unbind() {
			// Avoid setting _item.value = null to prevent destroying composition nodes
			// during Leanback view recycling/rebinding.
			_focused.value = false
		}
	}
}
