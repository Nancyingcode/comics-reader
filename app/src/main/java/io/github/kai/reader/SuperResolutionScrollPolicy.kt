package io.github.kai.reader

private const val NORMAL_SETTLE_MS = 300L
private const val SHORT_READING_PAUSE_MS = 4_000L
private const val MIN_RAPID_SETTLE_MS = 3_500L
private const val RAPID_SETTLE_MARGIN_MS = 750L

internal data class SuperResolutionViewportPosition(val firstStrip: Int, val offsetPx: Int)

internal data class SuperResolutionScrollSample(
    val isMoving: Boolean,
    val position: SuperResolutionViewportPosition? = null,
    val seekRevision: Int = 0,
)

internal class SuperResolutionScrollPolicy {
    private enum class Motion { Scroll, Seek }

    private data class Movement(val motion: Motion, val settleMs: Long)
    private data class Stop(val movement: Movement, val atMs: Long)

    private var previousSample: SuperResolutionScrollSample? = null
    private var movement: Movement? = null
    private var lastStop: Stop? = null

    fun settleDelayMs(sample: SuperResolutionScrollSample, nowMs: Long): Long? {
        val previous = previousSample
        previousSample = sample

        val sought = previous != null && sample.seekRevision != previous.seekRevision
        val startedMoving = sample.isMoving && previous?.isMoving != true
        val jumped = previous?.position != null && sample.position != null && sample.position != previous.position
        if (sought || startedMoving || jumped) {
            val motion = if (sought) Motion.Seek else Motion.Scroll
            movement = Movement(motion, quietPeriodMs(motion, nowMs))
        }

        if (sample.position == null) return null
        val finished = movement ?: return NORMAL_SETTLE_MS
        movement = null
        lastStop = Stop(finished, nowMs)
        return finished.settleMs
    }

    private fun quietPeriodMs(motion: Motion, nowMs: Long): Long {
        val stop = lastStop ?: return NORMAL_SETTLE_MS
        val pause = nowMs - stop.atMs
        val sameMotion = stop.movement.motion == motion
        if (!sameMotion || pause > SHORT_READING_PAUSE_MS) return NORMAL_SETTLE_MS
        // The list may become idle briefly between a drag and its fling, before inference could start.
        if (pause < NORMAL_SETTLE_MS) return stop.movement.settleMs

        // Allow time for the next likely gesture, including variation in the preceding fling duration.
        return (pause + RAPID_SETTLE_MARGIN_MS).coerceAtLeast(MIN_RAPID_SETTLE_MS)
    }
}
