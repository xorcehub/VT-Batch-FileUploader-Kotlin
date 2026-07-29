package com.vtbatch.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Sliding window rate limiter using Kotlin coroutines.
 * Python used threading.Lock + deque — Kotlin uses Mutex + ArrayDeque.
 * "suspend" = this function can pause without blocking a thread (efficient).
 */
class RateLimiter(
    private val requestsPerMinute: Int = VT_FREE_TIER_REQUESTS_PER_MINUTE,
    minInterval: Double? = null,
    private val pauseController: PauseController? = null
) {
    private val minIntervalSec = minInterval ?: (60.0 / requestsPerMinute)
    private val mutex = Mutex()
    private val requestTimes = ArrayDeque<Double>(requestsPerMinute)
    private var lastRequestTime = 0.0

    private fun currentTime(): Double = System.currentTimeMillis() / 1000.0

    /**
     * Acquire permission to make a request, suspending if needed.
     * Returns the time waited in seconds.
     *
     * Wait is computed and the request recorded under the mutex, but the
     * suspension itself happens WITHOUT holding it. This is deliberate: we
     * must never acquire a suspending Mutex inside a `finally` — if the
     * coroutine is cancelled during [delay], a `mutex.lock()` there throws
     * CancellationException, and `withLock`'s own finally then `unlock()`s a
     * mutex we no longer hold -> IllegalStateException("Mutex is not locked").
     */
    suspend fun acquire(): Double {
        // Wait if paused
        if (pauseController != null) {
            while (pauseController.isPaused) {
                delay(500)
            }
        }

        val wait = mutex.withLock { computeWaitSeconds() }
        if (wait > 0) {
            logger.debug { "Rate limiter: waiting ${"%.2f".format(wait)}s" }
            delay((wait * 1000).toLong())
        }
        mutex.withLock { recordRequest() }
        return wait
    }

    /** Prune expired entries and return how long (seconds) this caller must wait. */
    private fun computeWaitSeconds(): Double {
        val now = currentTime()
        var wait = 0.0

        val timeSinceLast = now - lastRequestTime
        if (timeSinceLast < minIntervalSec) wait = minIntervalSec - timeSinceLast

        val cutoff = now - 60.0
        while (requestTimes.isNotEmpty() && requestTimes.first() < cutoff) {
            requestTimes.removeFirst()
        }

        if (requestTimes.size >= requestsPerMinute) {
            val oldest = requestTimes.first()
            val additionalWait = (oldest + 60.0) - now
            if (additionalWait > 0) wait = maxOf(wait, additionalWait)
        }
        return wait
    }

    /** Record this request's timestamp against the sliding window. */
    private fun recordRequest() {
        lastRequestTime = currentTime()
        requestTimes.addLast(lastRequestTime)
    }

    /** Try to acquire without waiting. Returns true if allowed. */
    suspend fun tryAcquire(): Boolean = mutex.withLock {
        val now = currentTime()
        val timeSinceLast = now - lastRequestTime
        if (timeSinceLast < minIntervalSec) return@withLock false

        val cutoff = now - 60.0
        while (requestTimes.isNotEmpty() && requestTimes.first() < cutoff) {
            requestTimes.removeFirst()
        }
        if (requestTimes.size >= requestsPerMinute) return@withLock false

        lastRequestTime = currentTime()
        requestTimes.addLast(lastRequestTime)
        true
    }

    /** Get wait time without acquiring */
    suspend fun getWaitTime(): Double = mutex.withLock {
        val now = currentTime()
        val timeSinceLast = now - lastRequestTime
        if (timeSinceLast < minIntervalSec) return@withLock minIntervalSec - timeSinceLast

        val cutoff = now - 60.0
        while (requestTimes.isNotEmpty() && requestTimes.first() < cutoff) {
            requestTimes.removeFirst()
        }
        if (requestTimes.size >= requestsPerMinute) {
            val oldest = requestTimes.first()
            return@withLock maxOf(0.0, (oldest + 60.0) - now)
        }
        0.0
    }

    suspend fun reset() = mutex.withLock {
        requestTimes.clear()
        lastRequestTime = 0.0
    }
}
