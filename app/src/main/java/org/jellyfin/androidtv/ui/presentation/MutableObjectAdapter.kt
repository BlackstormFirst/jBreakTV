package org.jellyfin.androidtv.ui.presentation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * A Kotlin list-backed adapter wrapping a SnapshotStateList for Jetpack Compose reactivity.
 * Implements Iterable to allow collection operations and uses generics for strong typing.
 */
open class MutableObjectAdapter<T : Any>() : Iterable<T> {
	private val data = mutableStateListOf<T>()
	val dataAsStateList: SnapshotStateList<T> get() = data

	// List access methods
	open fun size(): Int = data.size
	open fun get(index: Int): T? = data.getOrNull(index)

	// Iterable
	override fun iterator(): Iterator<T> = data.iterator()

	// Mutation methods
	fun add(element: T) {
		data.add(element)
	}

	fun add(index: Int, element: T) {
		data.add(index, element)
	}

	fun set(index: Int, element: T) {
		data[index] = element
	}

	fun replaceAll(items: List<T>) {
		data.clear()
		data.addAll(items)
	}

	fun clear() {
		data.clear()
	}

	open fun remove(element: T): Boolean {
		return data.remove(element)
	}

	open fun removeAt(index: Int, length: Int = 1): Boolean {
		if (index < 0 || index >= data.size) return false
		data.subList(index, index + length).clear()
		return true
	}

	fun indexOf(item: T) = data.indexOf(item)
}
