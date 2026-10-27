package org.jellyfin.androidtv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.leanback.widget.ObjectAdapter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

import androidx.compose.runtime.derivedStateOf

@Composable
fun <T : Any> MutableObjectAdapter<T>.collectAsState(): State<List<T>> {
	return remember(this) { derivedStateOf { dataAsStateList } }
}

@Composable
fun ObjectAdapter.collectAsStateAny(): State<List<Any>> {
	if (this is MutableObjectAdapter<*>) {
		@Suppress("UNCHECKED_CAST")
		return remember(this) { derivedStateOf { (this as MutableObjectAdapter<Any>).dataAsStateList } }
	}
    val state = remember { mutableStateOf((0 until size()).mapNotNull { get(it) }) }
    DisposableEffect(this) {
        val observer = object : ObjectAdapter.DataObserver() {
            private fun update() { state.value = (0 until size()).mapNotNull { get(it) } }
            override fun onChanged() = update()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
            override fun onItemMoved(fromPosition: Int, toPosition: Int) = update()
        }
        registerObserver(observer)
        onDispose { unregisterObserver(observer) }
    }
    return state
}
