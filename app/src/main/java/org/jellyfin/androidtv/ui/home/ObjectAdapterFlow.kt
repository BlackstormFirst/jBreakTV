package org.jellyfin.androidtv.ui.home

import androidx.leanback.widget.ObjectAdapter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

fun ObjectAdapter.itemsFlow(): Flow<List<Any>> = callbackFlow {
    val observer = object : ObjectAdapter.DataObserver() {
        private fun sendData() {
            trySend((0 until size()).mapNotNull { get(it) })
        }
        override fun onChanged() = sendData()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = sendData()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = sendData()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = sendData()
        override fun onItemMoved(fromPosition: Int, toPosition: Int) = sendData()
    }
    registerObserver(observer)
    trySend((0 until size()).mapNotNull { get(it) })
    awaitClose { unregisterObserver(observer) }
}
