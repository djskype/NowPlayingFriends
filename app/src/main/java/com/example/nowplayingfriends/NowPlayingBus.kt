package com.example.nowplayingfriends

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Simple event bus for broadcasting now-playing updates
 * between the background listener and the composable UI.
 */
object NowPlayingBus {
    private val _flow = MutableSharedFlow<NowPlayingInfo>(replay = 1)
    val flow = _flow.asSharedFlow()

    fun post(info: NowPlayingInfo) {
        CoroutineScope(Dispatchers.Default).launch {
            _flow.emit(info)
        }
    }

    /**
     * Observes new NowPlayingInfo updates.
     */
    fun observe(onUpdate: (NowPlayingInfo) -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            flow.collect { info -> onUpdate(info) }
        }
    }
}