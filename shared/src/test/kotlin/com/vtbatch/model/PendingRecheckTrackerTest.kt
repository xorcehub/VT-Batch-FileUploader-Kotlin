package com.vtbatch.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PendingRecheckTrackerTest {

    private fun createTracker(): PendingRecheckTracker =
        PendingRecheckTracker(pollDelaySeconds = 300.0)

    // ═══════════════════════════════════════════════════════════════════
    //  CRUD — add / get / clear
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `initially empty`() {
        val tracker = createTracker()
        assertEquals(0, tracker.getPendingCount())
        assertTrue(tracker.getAllPending().isEmpty())
        tracker.close()
    }

    @Test
    fun `addPending increases count`() {
        val tracker = createTracker()
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        assertEquals(1, tracker.getPendingCount())
        tracker.addPending("/test/b.exe", "md5_bbb", 1700000000L)
        assertEquals(2, tracker.getPendingCount())
        tracker.close()
    }

    @Test
    fun `addPending stores correct data`() {
        val tracker = createTracker()
        tracker.addPending("/test/file.exe", "md5_abc123", 1700000000L)
        val all = tracker.getAllPending()
        assertEquals(1, all.size)
        assertEquals("/test/file.exe", all[0].filePath)
        assertEquals("md5_abc123", all[0].md5Hash)
        assertEquals(1700000000L, all[0].originalAnalysisDate)
        tracker.close()
    }

    @Test
    fun `addPending overwrites same hash`() {
        val tracker = createTracker()
        tracker.addPending("/test/old.exe", "md5_same", null)
        tracker.addPending("/test/new.exe", "md5_same", 1700000000L)
        assertEquals(1, tracker.getPendingCount())
        val all = tracker.getAllPending()
        assertEquals("/test/new.exe", all[0].filePath)
        tracker.close()
    }

    @Test
    fun `clearPending removes specific entry`() {
        val tracker = createTracker()
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.addPending("/test/b.exe", "md5_bbb", null)
        tracker.clearPending("md5_aaa")
        assertEquals(1, tracker.getPendingCount())
        assertEquals("md5_bbb", tracker.getAllPending()[0].md5Hash)
        tracker.close()
    }

    @Test
    fun `clearPending on nonexistent hash is no-op`() {
        val tracker = createTracker()
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.clearPending("nonexistent")
        assertEquals(1, tracker.getPendingCount())
        tracker.close()
    }

    @Test
    fun `clearAll removes all entries`() {
        val tracker = createTracker()
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.addPending("/test/b.exe", "md5_bbb", null)
        tracker.clearAll()
        assertEquals(0, tracker.getPendingCount())
        assertTrue(tracker.getAllPending().isEmpty())
        tracker.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Timer lifecycle
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `startTimer does nothing when no pending entries`() {
        val tracker = createTracker()
        tracker.startTimer()
        assertFalse(tracker.isTimerActive())
        tracker.close()
    }

    @Test
    fun `stopTimer when no timer is no-op`() {
        val tracker = createTracker()
        tracker.stopTimer() // should not throw
        assertFalse(tracker.isTimerActive())
        tracker.close()
    }

    @Test
    fun `close stops timer without error`() {
        val tracker = createTracker()
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.startTimer()
        tracker.close() // should not throw, timer should be stopped
    }

    // ═══════════════════════════════════════════════════════════════════
    //  triggerImmediatePoll
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `triggerImmediatePoll fires callback with pending entries`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val tracker = PendingRecheckTracker(pollDelaySeconds = 300.0, scope = scope)
        val latch = CountDownLatch(1)
        var polledEntries: List<PendingRecheck>? = null

        tracker.setOnPollCallback { entries ->
            polledEntries = entries
            latch.countDown()
        }

        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.addPending("/test/b.exe", "md5_bbb", 1700000000L)
        tracker.triggerImmediatePoll()

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Poll callback should fire within 5s")
        assertNotNull(polledEntries)
        assertEquals(2, polledEntries!!.size)

        tracker.close() // also cancels scope
    }

    @Test
    fun `triggerImmediatePoll does not fire callback when empty`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val tracker = PendingRecheckTracker(pollDelaySeconds = 300.0, scope = scope)
        var callbackFired = false

        tracker.setOnPollCallback { callbackFired = true }
        tracker.triggerImmediatePoll()

        Thread.sleep(500) // wait a bit to ensure no callback
        assertFalse(callbackFired, "Callback should not fire when no pending entries")

        tracker.close()
    }

    @Test
    fun `triggerImmediatePoll stops active timer`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val tracker = PendingRecheckTracker(pollDelaySeconds = 300.0, scope = scope)
        val latch = CountDownLatch(1)

        tracker.setOnPollCallback { latch.countDown() }
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.startTimer()
        assertTrue(tracker.isTimerActive())

        tracker.triggerImmediatePoll()
        assertFalse(tracker.isTimerActive())

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback should still fire")
        tracker.close()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Timer update callback
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `setOnTimerUpdate callback is invoked`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val tracker = PendingRecheckTracker(pollDelaySeconds = 1.0, scope = scope)
        val latch = CountDownLatch(1)
        var receivedRemaining: Int? = null
        var receivedCount: Int? = null

        tracker.setOnTimerUpdate { remaining, count ->
            receivedRemaining = remaining
            receivedCount = count
            latch.countDown()
        }
        tracker.addPending("/test/a.exe", "md5_aaa", null)
        tracker.startTimer()

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timer update callback should fire")
        assertNotNull(receivedRemaining)
        assertEquals(1, receivedCount)

        tracker.close()
    }
}
