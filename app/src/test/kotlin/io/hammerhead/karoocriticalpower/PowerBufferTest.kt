package io.hammerhead.karoocriticalpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerBufferTest {

    @Test
    fun `no averages until buffer is full`() {
        val buffer = PowerBuffer(durationSeconds = 5)
        repeat(4) { buffer.addSample(200.0) }
        assertNull(buffer.getCurrentAverage())
        assertNull(buffer.getBestAverage())
        assertFalse(buffer.isReady())
    }

    @Test
    fun `average computed once buffer is full`() {
        val buffer = PowerBuffer(durationSeconds = 5)
        repeat(5) { buffer.addSample(200.0) }
        assertTrue(buffer.isReady())
        assertEquals(200.0, buffer.getCurrentAverage()!!, 0.001)
        assertEquals(200.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `rolling window drops oldest samples`() {
        val buffer = PowerBuffer(durationSeconds = 5)
        repeat(5) { buffer.addSample(100.0) }
        repeat(5) { buffer.addSample(300.0) }
        // Window now contains only the 300W samples
        assertEquals(300.0, buffer.getCurrentAverage()!!, 0.001)
    }

    @Test
    fun `best average tracks the best window, not the current one`() {
        val buffer = PowerBuffer(durationSeconds = 5)
        repeat(5) { buffer.addSample(300.0) }
        repeat(5) { buffer.addSample(100.0) }
        assertEquals(100.0, buffer.getCurrentAverage()!!, 0.001)
        assertEquals(300.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `best average uses mixed windows correctly`() {
        val buffer = PowerBuffer(durationSeconds = 4)
        listOf(100.0, 200.0, 300.0, 400.0, 500.0).forEach { buffer.addSample(it) }
        // Best window is [200, 300, 400, 500] = 350
        assertEquals(350.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `all-zero window yields no best average`() {
        val buffer = PowerBuffer(durationSeconds = 3)
        repeat(3) { buffer.addSample(0.0) }
        assertNull(buffer.getBestAverage())
    }

    @Test
    fun `reset clears everything`() {
        val buffer = PowerBuffer(durationSeconds = 3)
        repeat(3) { buffer.addSample(250.0) }
        buffer.reset()
        assertNull(buffer.getCurrentAverage())
        assertNull(buffer.getBestAverage())
        assertFalse(buffer.isReady())
    }

    @Test
    fun `clearWindow keeps best but clears the rolling window`() {
        val buffer = PowerBuffer(durationSeconds = 3)
        repeat(3) { buffer.addSample(250.0) }
        buffer.clearWindow()
        assertNull(buffer.getCurrentAverage())
        assertFalse(buffer.isReady())
        assertEquals(250.0, buffer.getBestAverage()!!, 0.001)
        // Window restarts cleanly after the clear
        repeat(3) { buffer.addSample(100.0) }
        assertEquals(100.0, buffer.getCurrentAverage()!!, 0.001)
        assertEquals(250.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `zero-fill after gap prevents stitching efforts together`() {
        val buffer = PowerBuffer(durationSeconds = 4)
        // 2s at 300W, a 2s dropout (zero-filled by the manager), 2s at 300W
        repeat(2) { buffer.addSample(300.0) }
        repeat(2) { buffer.addSample(0.0) }
        repeat(2) { buffer.addSample(300.0) }
        // Best 4s window is [0, 0, 300, 300] or [300, 300, 0, 0] = 150, not 300
        assertEquals(150.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `restoreBestAverage keeps the higher value`() {
        val buffer = PowerBuffer(durationSeconds = 3)
        buffer.restoreBestAverage(280.0)
        assertEquals(280.0, buffer.getBestAverage()!!, 0.001)
        // A lower live best does not overwrite the restored one
        repeat(3) { buffer.addSample(250.0) }
        assertEquals(280.0, buffer.getBestAverage()!!, 0.001)
        // A lower restore does not overwrite a higher live best
        buffer.restoreBestAverage(100.0)
        assertEquals(280.0, buffer.getBestAverage()!!, 0.001)
    }

    @Test
    fun `long buffer wraparound stays numerically correct`() {
        val buffer = PowerBuffer(durationSeconds = 60)
        // Fill several times over with varying values
        repeat(300) { i -> buffer.addSample((i % 250).toDouble()) }
        val current = buffer.getCurrentAverage()!!
        // Recompute expected average of the last 60 samples directly
        val expected = (240 until 300).map { (it % 250).toDouble() }.average()
        assertEquals(expected, current, 0.0001)
    }
}
