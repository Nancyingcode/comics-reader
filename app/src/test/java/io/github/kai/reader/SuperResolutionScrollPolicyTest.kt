package io.github.kai.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperResolutionScrollPolicyTest {
    @Test
    fun initialViewportAndIsolatedScrollsUseTheUsualDelay() {
        val policy = SuperResolutionScrollPolicy()
        assertNull(policy.settleDelayMs(SuperResolutionScrollSample(isMoving = false), 0))
        assertEquals(300L, policy.settleDelayMs(stationary(0), 50))

        repeat(18) { swipe ->
            val start = 1_000L + swipe * 10_000L
            assertNull(policy.settleDelayMs(moving(), start))
            assertEquals(300L, policy.settleDelayMs(stationary(swipe + 1), start + 850))
        }
    }

    @Test
    fun fastSwipesKeepMovingTheDeadlineUntilTheReaderPauses() {
        val policy = SuperResolutionScrollPolicy()
        policy.settleDelayMs(stationary(0), 0)

        repeat(60) { swipe ->
            val start = 1_000L + swipe * 3_000L
            val stop = start + 650 + (swipe % 4) * 100
            assertNull(policy.settleDelayMs(moving(), start))
            val waitMs = checkNotNull(policy.settleDelayMs(stationary(swipe + 1), stop))
            assertEquals(swipe == 0, stop + waitMs < start + 3_000)
            assertTrue(waitMs <= 4_750L)
        }

        assertNull(policy.settleDelayMs(moving(), 195_000))
        assertEquals(300L, policy.settleDelayMs(stationary(61), 195_700))
    }

    @Test
    fun firstExplicitSeekGetsTheUsualDelayAndRepeatedSeeksIncreaseIt() {
        val policy = SuperResolutionScrollPolicy()
        policy.settleDelayMs(stationary(0), 0)
        policy.settleDelayMs(moving(), 1_000)
        policy.settleDelayMs(stationary(1), 1_900)
        policy.settleDelayMs(moving(), 4_000)
        assertEquals(3_500L, policy.settleDelayMs(stationary(2), 4_800))

        assertEquals(300L, policy.settleDelayMs(stationary(20, seekRevision = 1), 5_000))
        assertNull(policy.settleDelayMs(moving(seekRevision = 2), 8_900))
        assertEquals(4_650L, policy.settleDelayMs(stationary(24, seekRevision = 2), 9_000))
    }

    @Test
    fun positionChangesCanBeObservedWithoutAnIntermediateScrollingSample() {
        val policy = SuperResolutionScrollPolicy()
        policy.settleDelayMs(stationary(0), 0)

        assertEquals(300L, policy.settleDelayMs(stationary(0, offsetPx = 60), 1_000))
        assertEquals(4_550L, policy.settleDelayMs(stationary(1), 4_800))
    }

    @Test
    fun aBriefGapBetweenDragAndFlingPreservesTheCurrentDelay() {
        val policy = SuperResolutionScrollPolicy()
        policy.settleDelayMs(stationary(0), 0)

        policy.settleDelayMs(moving(), 1_000)
        assertEquals(300L, policy.settleDelayMs(stationary(1), 1_500))
        policy.settleDelayMs(moving(), 1_550)
        assertEquals(300L, policy.settleDelayMs(stationary(2), 2_000))

        policy.settleDelayMs(moving(), 4_000)
        assertEquals(3_500L, policy.settleDelayMs(stationary(3), 4_500))
        policy.settleDelayMs(moving(), 4_550)
        assertEquals(3_500L, policy.settleDelayMs(stationary(4), 5_000))
    }

    private fun stationary(strip: Int, offsetPx: Int = 0, seekRevision: Int = 0) = SuperResolutionScrollSample(
        isMoving = false,
        position = SuperResolutionViewportPosition(strip, offsetPx),
        seekRevision = seekRevision,
    )

    private fun moving(seekRevision: Int = 0) = SuperResolutionScrollSample(isMoving = true, seekRevision = seekRevision)
}
