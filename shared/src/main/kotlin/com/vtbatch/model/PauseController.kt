package com.vtbatch.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * Pause controller using Kotlin StateFlow (reactive state).
 * Python used threading.Event + Lock — Kotlin uses StateFlow which is
 * inherently thread-safe and observable. No locks needed.
 */
class PauseController {
    private val _isPaused = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPaused.asStateFlow()

    // CopyOnWriteArrayList for thread-safe callback management
    private val callbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val isPaused: Boolean get() = _isPaused.value

    fun pause() {
        if (!_isPaused.value) {
            _isPaused.value = true
            logger.info { "API operations paused" }
            notifyCallbacks(true)
        }
    }

    fun resume() {
        if (_isPaused.value) {
            _isPaused.value = false
            logger.info { "API operations resumed" }
            notifyCallbacks(false)
        }
    }

    /** Toggle pause state. Returns new state. */
    fun toggle(): Boolean {
        if (_isPaused.value) resume() else pause()
        return _isPaused.value
    }

    /** Suspend caller until unpaused — instant response via StateFlow. */
    suspend fun waitIfPaused() {
        if (_isPaused.value) {
            isPausedFlow.first { !it }
        }
    }

    fun addPauseChangeCallback(callback: (Boolean) -> Unit) {
        callbacks.add(callback)
    }

    fun removePauseChangeCallback(callback: (Boolean) -> Unit) {
        callbacks.remove(callback)
    }

    private fun notifyCallbacks(paused: Boolean) {
        callbacks.forEach { callback ->
            try { callback(paused) }
            catch (e: Exception) { logger.warn { "Pause callback error: $e" } }
        }
    }

    override fun toString(): String = "PauseController(paused=${_isPaused.value})"
}
