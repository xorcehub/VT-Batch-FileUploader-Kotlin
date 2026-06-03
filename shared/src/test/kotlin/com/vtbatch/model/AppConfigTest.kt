package com.vtbatch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {

    private fun validConfig(
        timeout: Int = 30,
        shortTimeout: Int = 15,
        longTimeout: Int = 60,
        rateLimitPerMinute: Int = 20,
        largeFileThreshold: Int = 32 * 1024 * 1024,
        analysisPollInterval: Int = 10,
        analysisMaxRetries: Int = 30,
        analysisInitialDelay: Int = 5,
        recheckPollInterval: Int = 30,
        recheckMaxAttempts: Int = 10,
        recheckBatchPollDelay: Int = 300,
        cacheDurationHours: Int = 24,
        refreshThrottleSeconds: Double = 60.0,
        maxConcurrentThreads: Int = 5,
        minHashLength: Int = 32,
        maxHashLength: Int = 64
    ) = AppConfig(
        timeout = timeout, shortTimeout = shortTimeout, longTimeout = longTimeout,
        rateLimitPerMinute = rateLimitPerMinute, largeFileThreshold = largeFileThreshold,
        analysisPollInterval = analysisPollInterval, analysisMaxRetries = analysisMaxRetries,
        analysisInitialDelay = analysisInitialDelay, recheckPollInterval = recheckPollInterval,
        recheckMaxAttempts = recheckMaxAttempts, recheckBatchPollDelay = recheckBatchPollDelay,
        cacheDurationHours = cacheDurationHours, refreshThrottleSeconds = refreshThrottleSeconds,
        maxConcurrentThreads = maxConcurrentThreads, minHashLength = minHashLength,
        maxHashLength = maxHashLength
    )

    // === Default values ===

    @Test
    fun `default config has expected timeout values`() {
        val config = AppConfig.default
        assertEquals(30, config.timeout)
        assertEquals(15, config.shortTimeout)
        assertEquals(60, config.longTimeout)
    }

    @Test
    fun `default config has expected rate limit`() {
        assertEquals(20, AppConfig.default.rateLimitPerMinute)
    }

    // === Validation — positive values required ===

    @Test
    fun `rejects zero timeout`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validConfig(timeout = 0)
        }
        assertTrue(ex.message!!.contains("timeout"))
    }

    @Test
    fun `rejects negative timeout`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(timeout = -1)
        }
    }

    @Test
    fun `rejects zero shortTimeout`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(shortTimeout = 0)
        }
    }

    @Test
    fun `rejects zero longTimeout`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(longTimeout = 0)
        }
    }

    @Test
    fun `rejects zero largeFileThreshold`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(largeFileThreshold = 0)
        }
    }

    @Test
    fun `rejects zero analysisPollInterval`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(analysisPollInterval = 0)
        }
    }

    @Test
    fun `rejects zero analysisMaxRetries`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(analysisMaxRetries = 0)
        }
    }

    @Test
    fun `rejects zero recheckPollInterval`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(recheckPollInterval = 0)
        }
    }

    @Test
    fun `rejects zero recheckMaxAttempts`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(recheckMaxAttempts = 0)
        }
    }

    @Test
    fun `rejects zero cacheDurationHours`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(cacheDurationHours = 0)
        }
    }

    @Test
    fun `rejects zero maxConcurrentThreads`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(maxConcurrentThreads = 0)
        }
    }

    // === Validation — non-negative allowed ===

    @Test
    fun `allows zero rateLimitPerMinute`() {
        val config = validConfig(rateLimitPerMinute = 0)
        assertEquals(0, config.rateLimitPerMinute)
    }

    @Test
    fun `rejects negative rateLimitPerMinute`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(rateLimitPerMinute = -1)
        }
    }

    @Test
    fun `allows zero analysisInitialDelay`() {
        val config = validConfig(analysisInitialDelay = 0)
        assertEquals(0, config.analysisInitialDelay)
    }

    @Test
    fun `rejects negative analysisInitialDelay`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(analysisInitialDelay = -1)
        }
    }

    @Test
    fun `rejects negative refreshThrottleSeconds`() {
        assertFailsWith<IllegalArgumentException> {
            validConfig(refreshThrottleSeconds = -1.0)
        }
    }

    // === Validation — hash length range ===

    @Test
    fun `rejects minHashLength greater than maxHashLength`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validConfig(minHashLength = 64, maxHashLength = 32)
        }
        assertTrue(ex.message!!.contains("minHashLength"))
    }

    @Test
    fun `allows equal min and max hash length`() {
        val config = validConfig(minHashLength = 32, maxHashLength = 32)
        assertEquals(32, config.minHashLength)
        assertEquals(32, config.maxHashLength)
    }

    // === Validation — multiple errors reported ===

    @Test
    fun `reports multiple validation errors`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validConfig(timeout = 0, shortTimeout = -1)
        }
        val msg = ex.message!!
        assertTrue(msg.contains("timeout"))
        assertTrue(msg.contains("shortTimeout"))
    }

    // === Computed properties ===

    @Test
    fun `minRequestInterval computes correctly`() {
        val config = validConfig(rateLimitPerMinute = 20)
        assertEquals(3.0, config.minRequestInterval)
    }

    @Test
    fun `minRequestInterval is zero when rate limit is zero`() {
        val config = validConfig(rateLimitPerMinute = 0)
        assertEquals(0.0, config.minRequestInterval)
    }

    @Test
    fun `cacheDurationSeconds converts hours to seconds`() {
        val config = validConfig(cacheDurationHours = 24)
        assertEquals(86400, config.cacheDurationSeconds)
    }

    // === Successful construction ===

    @Test
    fun `valid config constructs without error`() {
        val config = validConfig()
        assertEquals(30, config.timeout)
        assertEquals(20, config.rateLimitPerMinute)
    }
}
