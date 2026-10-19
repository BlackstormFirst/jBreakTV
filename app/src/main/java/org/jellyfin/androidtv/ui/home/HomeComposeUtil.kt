package org.jellyfin.androidtv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.leanback.widget.ObjectAdapter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

@Composable
fun <T : Any> MutableObjectAdapter<T>.collectAsState(): State<List<T>> {
    val state = remember { mutableStateOf(this.toList()) }
    DisposableEffect(this) {
        val observer = object : ObjectAdapter.DataObserver() {
            override fun onChanged() { state.value = this@collectAsState.toList() }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { state.value = this@collectAsState.toList() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { state.value = this@collectAsState.toList() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { state.value = this@collectAsState.toList() }
            override fun onItemMoved(fromPosition: Int, toPosition: Int) { state.value = this@collectAsState.toList() }
        }
        registerObserver(observer)
        onDispose { unregisterObserver(observer) }
    }
    return state
}

@Composable
fun ObjectAdapter.collectAsStateAny(): State<List<Any>> {
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
