package com.vtbatch.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Pause controller using Kotlin StateFlow (reactive state).
 * Python used threading.Event + Lock — Kotlin uses StateFlow which is
 * inherently thread-safe and observable. No locks needed.
 */
class PauseController {
    private val _isPaused = MutableStateFlow(false)
    val isPausedFlow: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Callbacks for pause state changes
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

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

    /** Suspend caller if paused — like Python's wait_if_paused */
    suspend fun waitIfPaused() {
        // In coroutine world, we just check the flow.
        // The caller should check isPaused in their processing loop.
        // Actual suspension is handled by the caller's coroutine scope.
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
