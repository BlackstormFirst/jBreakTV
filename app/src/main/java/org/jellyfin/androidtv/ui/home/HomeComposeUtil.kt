package org.jellyfin.androidtv.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter

@Composable
fun <T : Any> MutableObjectAdapter<T>.collectAsState(): State<List<T>> {
	return remember(this) { derivedStateOf { dataAsStateList } }
}

@Composable
fun MutableObjectAdapter<*>.collectAsStateAny(): State<List<Any>> {
	@Suppress("UNCHECKED_CAST")
	return remember(this) { derivedStateOf { (this as MutableObjectAdapter<Any>).dataAsStateList } }
}
