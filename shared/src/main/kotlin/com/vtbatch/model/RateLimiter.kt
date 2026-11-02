package com.vtbatch.model

import kotlinx.coroutines.delay
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Sliding window rate limiter using Kotlin coroutines.
 *
 * Enforces both a minimum interval between requests and at most
 * [requestsPerMinute] requests in any 60 s window, across concurrent callers.
 *
 * [clock] returns the current time in seconds; it is injectable so tests can
 * drive the limiter on virtual time.
 */
class RateLimiter(
    private val requestsPerMinute: Int = VT_FREE_TIER_REQUESTS_PER_MINUTE,
    minInterval: Double? = null,
    private val pauseController: PauseController? = null,
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }
) {
    private val minIntervalSec = minInterval ?: (60.0 / requestsPerMinute)

    // Every critical section below is short and NON-suspending, so a plain
    // monitor is enough. That matters for cancellation: releasing a
    // reservation from a `catch` must not suspend (acquiring a suspending
    // Mutex there throws CancellationException and corrupts the lock state —
    // see the coroutines pitfall in AGENTS.md, fix 4051afd).
    private val lock = Any()

    /** Granted request slots (seconds), ascending; may include future reservations. */
    private val requestTimes = ArrayDeque<Double>(requestsPerMinute)
    private var lastRequestTime = 0.0

    /**
     * Acquire permission to make a request, suspending if needed.
     * Returns the time waited in seconds.
     *
     * The slot is computed AND reserved in one critical section, so
     * concurrent callers each get a distinct slot that respects both the
     * minimum interval and the per-minute window. The suspension happens
     * outside the lock; if it is cancelled, the reservation is released.
     */
    suspend fun acquire(): Double {
        // Wait if paused
        if (pauseController != null) {
            while (pauseController.isPaused) {
                delay(500)
            }
        }

        val now: Double
        val slot: Double
        val previousLast: Double
        synchronized(lock) {
            now = clock()
            previousLast = lastRequestTime
            slot = nextSlot(now)
            reserve(slot)
        }

        val wait = slot - now
        if (wait > 0) {
            logger.debug { "Rate limiter: waiting ${"%.2f".format(wait)}s" }
            try {
                delay((wait * 1000).toLong())
            } catch (e: CancellationException) {
                synchronized(lock) { release(slot, previousLast) }
                throw e
            }
        }
        return maxOf(0.0, wait)
    }

    /** Try to acquire without waiting. Returns true if allowed. */
    suspend fun tryAcquire(): Boolean = synchronized(lock) {
        val now = clock()
        val slot = nextSlot(now)
        if (slot > now) return@synchronized false
        reserve(slot)
        true
    }

    /** Get wait time without acquiring */
    suspend fun getWaitTime(): Double = synchronized(lock) {
        val now = clock()
        maxOf(0.0, nextSlot(now) - now)
    }

    suspend fun reset() = synchronized(lock) {
        requestTimes.clear()
        lastRequestTime = 0.0
    }

    /**
     * Earliest time (seconds) a new request may fire, given all granted and
     * reserved slots. Caller must hold [lock].
     */
    private fun nextSlot(now: Double): Double {
        val cutoff = now - 60.0
        while (requestTimes.isNotEmpty() && requestTimes.first() < cutoff) {
            requestTimes.removeFirst()
        }

        var slot = maxOf(now, lastRequestTime + minIntervalSec)

        // The deque may hold future reservations, so it can exceed the limit.
        // The new request is the newest in its window, so it fits once only
        // (limit - 1) older entries remain within 60 s of it — i.e. once
        // requestTimes[size - limit] (entries sorted ascending) has aged out.
        if (requestTimes.size >= requestsPerMinute) {
            val blocker = requestTimes[requestTimes.size - requestsPerMinute]
            slot = maxOf(slot, blocker + 60.0)
        }
        return slot
    }

    /** Record a granted slot. Slots are non-decreasing, so the deque stays sorted. Caller must hold [lock]. */
    private fun reserve(slot: Double) {
        lastRequestTime = slot
        requestTimes.addLast(slot)
    }

    /** Undo a reservation whose wait was cancelled. Caller must hold [lock]. */
    private fun release(slot: Double, previousLast: Double) {
        val idx = requestTimes.lastIndexOf(slot)
        if (idx >= 0) requestTimes.removeAt(idx)
        // Only roll back the interval anchor if no later reservation built on ours.
        if (lastRequestTime == slot) lastRequestTime = previousLast
    }
}
