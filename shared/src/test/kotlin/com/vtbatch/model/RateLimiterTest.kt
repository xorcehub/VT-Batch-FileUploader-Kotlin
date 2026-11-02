package com.vtbatch.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `initial wait time is near zero`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 60)
        val waitTime = limiter.getWaitTime()
        assertTrue(waitTime <= 1.0, "Initial wait time should be near zero, was $waitTime")
    }

    @Test
    fun `tryAcquire succeeds when under limit`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 10)
        assertTrue(limiter.tryAcquire(), "First request should be allowed")
    }

    @Test
    fun `acquire returns non-negative wait time`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 60)
        val wait = limiter.acquire()
        assertTrue(wait >= 0, "Wait time should be non-negative")
    }

    @Test
    fun `reset clears tracked requests`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 2)
        limiter.acquire()
        limiter.acquire()
        limiter.reset()
        val waitTime = limiter.getWaitTime()
        assertTrue(waitTime <= 1.0, "After reset, wait should be near zero, was $waitTime")
    }

    @Test
    fun `works with pause controller`() = runTest {
        val pauseController = PauseController()
        val limiter = RateLimiter(requestsPerMinute = 60, pauseController = pauseController)
        assertTrue(limiter.tryAcquire())
    }

    private fun TestScope.virtualClock(): () -> Double = { testScheduler.currentTime / 1000.0 }

    private fun assertWithinLimits(fireTimes: List<Double>, perMinute: Int, minInterval: Double) {
        val sorted = fireTimes.sorted()
        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            assertTrue(gap >= minInterval - 1e-3, "Requests ${i - 1} and $i only ${gap}s apart: $sorted")
        }
        for (i in sorted.indices) {
            val inWindow = sorted.count { it > sorted[i] - 60.0 && it <= sorted[i] }
            assertTrue(inWindow <= perMinute, "$inWindow requests in the 60 s window ending at ${sorted[i]}: $sorted")
        }
    }

    @Test
    fun `concurrent acquire respects min interval and per-minute window`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 4, clock = virtualClock())
        val fireTimes = List(9) {
            async {
                limiter.acquire()
                testScheduler.currentTime / 1000.0
            }
        }.awaitAll()
        assertWithinLimits(fireTimes, perMinute = 4, minInterval = 15.0)
    }

    @Test
    fun `concurrent acquire respects window when min interval is shorter`() = runTest {
        // minInterval 1 s: the per-minute window, not the interval, is the binding constraint.
        val limiter = RateLimiter(requestsPerMinute = 4, minInterval = 1.0, clock = virtualClock())
        val fireTimes = List(10) {
            async {
                limiter.acquire()
                testScheduler.currentTime / 1000.0
            }
        }.awaitAll()
        assertWithinLimits(fireTimes, perMinute = 4, minInterval = 1.0)
    }

    @Test
    fun `tryAcquire refuses while a slot is reserved by a waiting acquire`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 4, clock = virtualClock())
        limiter.acquire()                       // fires at t=0
        val waiter = launch { limiter.acquire() } // reserves t=15
        runCurrent()
        advanceTimeBy(20_000)                   // t=20: waiter has fired at 15
        assertTrue(waiter.isCompleted)
        // Next free slot is t=30, so tryAcquire at t=20 must refuse.
        assertEquals(false, limiter.tryAcquire())
    }

    @Test
    fun `cancelled acquire releases its reserved slot`() = runTest {
        val limiter = RateLimiter(requestsPerMinute = 4, clock = virtualClock())
        limiter.acquire()                       // t=0, next slot t=15
        val cancelled = launch { limiter.acquire() } // reserves t=15
        runCurrent()
        advanceTimeBy(5_000)
        cancelled.cancel(CancellationException("test"))
        runCurrent()
        // With the reservation released, the next slot is back to t=15 (10 s from now), not t=30.
        val wait = limiter.getWaitTime()
        assertEquals(10.0, wait, 1e-6)
    }
}
