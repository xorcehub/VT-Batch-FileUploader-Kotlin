package com.vtbatch.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseControllerTest {

    // ═══════════════════════════════════════════════════════════════════
    //  Initial state
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `initial state is not paused`() {
        val pc = PauseController()
        assertFalse(pc.isPaused)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  State transitions
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `pause sets paused state`() {
        val pc = PauseController()
        pc.pause()
        assertTrue(pc.isPaused)
    }

    @Test
    fun `resume clears paused state`() {
        val pc = PauseController()
        pc.pause()
        assertTrue(pc.isPaused)
        pc.resume()
        assertFalse(pc.isPaused)
    }

    @Test
    fun `toggle switches unpaused to paused`() {
        val pc = PauseController()
        val newState = pc.toggle()
        assertTrue(newState)
        assertTrue(pc.isPaused)
    }

    @Test
    fun `toggle switches paused to unpaused`() {
        val pc = PauseController()
        pc.pause()
        val newState = pc.toggle()
        assertFalse(newState)
        assertFalse(pc.isPaused)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Idempotency
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `double pause keeps paused state`() {
        val pc = PauseController()
        pc.pause()
        pc.pause()
        assertTrue(pc.isPaused)
    }

    @Test
    fun `double resume keeps unpaused state`() {
        val pc = PauseController()
        pc.pause()
        pc.resume()
        pc.resume()
        assertFalse(pc.isPaused)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Callbacks
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `callback fires on pause`() {
        val pc = PauseController()
        var callbackValue: Boolean? = null
        pc.addPauseChangeCallback { callbackValue = it }
        pc.pause()
        assertEquals(true, callbackValue)
    }

    @Test
    fun `callback fires on resume`() {
        val pc = PauseController()
        var callbackValue: Boolean? = null
        pc.addPauseChangeCallback { callbackValue = it }
        pc.pause()
        assertEquals(true, callbackValue)
        pc.resume()
        assertEquals(false, callbackValue)
    }

    @Test
    fun `removed callback does not fire`() {
        val pc = PauseController()
        var fired = false
        val callback: (Boolean) -> Unit = { fired = true }
        pc.addPauseChangeCallback(callback)
        pc.removePauseChangeCallback(callback)
        pc.pause()
        assertFalse(fired)
    }

    @Test
    fun `multiple callbacks all fire`() {
        val pc = PauseController()
        var count = 0
        pc.addPauseChangeCallback { count++ }
        pc.addPauseChangeCallback { count++ }
        pc.pause()
        assertEquals(2, count)
    }

    @Test
    fun `callback does not fire on double pause`() {
        val pc = PauseController()
        var count = 0
        pc.addPauseChangeCallback { count++ }
        pc.pause()
        pc.pause() // second pause should not fire callback
        assertEquals(1, count)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  waitIfPaused
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `waitIfPaused returns immediately when not paused`() = runTest {
        val pc = PauseController()
        pc.waitIfPaused()
        // Should reach here without suspending
        assertTrue(true)
    }

    @Test
    fun `waitIfPaused suspends and resumes after unpause`() = runBlocking {
        val pc = PauseController()
        pc.pause()

        var completed = false
        launch {
            pc.waitIfPaused()
            completed = true
        }

        // The launched coroutine should be suspended
        delay(100)
        assertFalse(completed)

        pc.resume()
        // Give the launched coroutine time to complete on the real dispatcher
        delay(200)

        assertTrue(completed, "waitIfPaused should have completed after resume")
    }

    // ═══════════════════════════════════════════════════════════════════
    //  toString
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `toString reflects paused state`() {
        val pc = PauseController()
        assertTrue(pc.toString().contains("paused=false"))
        pc.pause()
        assertTrue(pc.toString().contains("paused=true"))
    }
}
