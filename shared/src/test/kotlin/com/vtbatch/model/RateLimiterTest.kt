package com.vtbatch.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
}
