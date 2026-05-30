package com.vtbatch.model

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

data class PendingRecheck(
    val filePath: String,
    val md5Hash: String,
    val originalAnalysisDate: Long?,
    val submittedAtMs: Long = System.currentTimeMillis()
)

/**
 * Tracks files submitted for re-analysis. Uses Kotlin coroutines
 * instead of Python threading for the countdown timer.
 */
class PendingRecheckTracker(
    private val pollDelaySeconds: Double = 300.0,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : Closeable {

    private val pending = ConcurrentHashMap<String, PendingRecheck>() // md5 -> recheck
    private var timerJob: Job? = null
    private var timerStartTimeMs = 0L

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private var _onPollCallback: suspend (List<PendingRecheck>) -> Unit = {}
    private var _onTimerUpdate: (remainingSeconds: Int, count: Int) -> Unit = { _, _ -> }

    fun setOnPollCallback(callback: suspend (List<PendingRecheck>) -> Unit) { _onPollCallback = callback }
    fun setOnTimerUpdate(callback: (remainingSeconds: Int, count: Int) -> Unit) { _onTimerUpdate = callback }

    fun addPending(filePath: String, md5Hash: String, originalAnalysisDate: Long?) {
        pending[md5Hash] = PendingRecheck(filePath, md5Hash, originalAnalysisDate)
        _pendingCount.value = pending.size
        logger.debug { "Added pending recheck for ${md5Hash.take(8)}..." }
    }

    fun getPendingCount(): Int = pending.size

    fun getAllPending(): List<PendingRecheck> = pending.values.toList()

    fun clearPending(md5Hash: String) {
        pending.remove(md5Hash)
        _pendingCount.value = pending.size
    }

    fun clearAll() {
        pending.clear()
        _pendingCount.value = 0
    }

    fun startTimer() {
        if (timerJob?.isActive == true || pending.isEmpty()) return

        timerStartTimeMs = System.currentTimeMillis()
        timerJob = scope.launch {
            logger.info { "Started recheck timer for ${pending.size} files" }
            while (isActive && pending.isNotEmpty()) {
                val elapsed = (System.currentTimeMillis() - timerStartTimeMs) / 1000.0
                val remaining = maxOf(0, (pollDelaySeconds - elapsed).toInt())
                _remainingSeconds.value = remaining
                _onTimerUpdate(remaining, pending.size)

                if (remaining <= 0) {
                    triggerPoll()
                    break
                }
                delay(1000)
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _remainingSeconds.value = 0
    }

    fun triggerImmediatePoll() {
        logger.info { "User triggered immediate poll" }
        stopTimer()
        scope.launch { triggerPoll() }
    }

    private suspend fun triggerPoll() {
        val pendingFiles = pending.values.toList()
        if (pendingFiles.isNotEmpty()) {
            try { _onPollCallback(pendingFiles) }
            catch (e: Exception) { logger.error { "Poll callback error: $e" } }
        }
    }

    fun isTimerActive(): Boolean = timerJob?.isActive == true

    override fun close() {
        stopTimer()
        scope.cancel()
    }
}
