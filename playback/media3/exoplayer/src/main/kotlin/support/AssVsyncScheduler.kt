package org.jellyfin.playback.media3.exoplayer.support

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import androidx.media3.common.C
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.max

/**
 * High-priority VSYNC Choreographer Scheduler & Timestamp RingBuffer for libass TextureRenderer.
 *
 * Runs Choreographer on a dedicated HandlerThread with THREAD_PRIORITY_URGENT_DISPLAY
 * to prevent Android TV UI thread latency from delaying VSYNC ticks.
 *
 * Key Architecture:
 * 1. Dedicated HandlerThread: "SubtitleVsyncDispatcher" (Process.THREAD_PRIORITY_URGENT_DISPLAY).
 * 2. Fixed Integer VSYNC Lead Steps: targetReleaseTimeNs = frameTimeNanos + (vsyncLeadSteps * vsyncPeriodNs).
 *    Prevents sliding drift during karaoke animations.
 * 3. Centered VSYNC Capture Window: [targetReleaseTimeNs - 0.5*vsyncPeriodNs, targetReleaseTimeNs + 0.5*vsyncPeriodNs].
 * 4. Pure Monotonic Timestamps: Transmits clean (presentationTimeUs + 500L) / 1000L timestamps.
 * 5. Anti-Flicker Protection: Renders at most once per VSYNC cycle, only when ptsMs > lastRenderedTimeMs.
 */
class AssVsyncScheduler(
    private var refreshRateHz: Double = 23.976,
    private val queueCapacity: Int = 16,
) {
    data class FrameTimestamp(
        var releaseTimeNs: Long = 0L,
        var pureTimestampUs: Long = 0L,
        var ptsMs: Long = 0L,
    )

    private val ringBuffer = ConcurrentLinkedQueue<FrameTimestamp>()
    private val itemPool = ArrayDeque<FrameTimestamp>(queueCapacity)

    private var lastPtsMediaUs: Long = C.TIME_UNSET
    private var lastPureTimestampUs: Long = C.TIME_UNSET
    private var lastRenderedTimeMs: Long = -1L

    // Fixed Integer VSYNC Lead Steps (1L for Shield Pro / Android 11+, 2L for Player Pop / Android 10)
    private var vsyncLeadSteps: Long = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) 2L else 1L
    private var pendingVsyncLeadSteps: Long? = null

    // Dedicated real-time HandlerThread for Choreographer
    @Volatile
    private var vsyncThread: HandlerThread? = null
    @Volatile
    private var vsyncHandler: Handler? = null

    @Volatile
    private var isVsyncRunning = false

    private var timeCallback: ((Long) -> Unit)? = null

    init {
        synchronized(this) {
            repeat(queueCapacity) {
                itemPool.add(FrameTimestamp())
            }
        }
    }

    /**
     * Updates display refresh rate dynamically.
     */
    fun setRefreshRate(refreshRate: Double) {
        if (refreshRate > 0.0) {
            this.refreshRateHz = refreshRate
        }
    }

    /**
     * Attaches callback to trigger libass TextureRenderer.
     */
    fun setTimeCallback(callback: ((Long) -> Unit)?) {
        this.timeCallback = callback
    }

    /**
     * Called on ExoPlayer's video thread via VideoFrameMetadataListener.
     */
    @Synchronized
    fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        activeStreamOffsetUs: Long,
        timeCallback: ((Long) -> Unit)? = null,
    ): Long {
        if (presentationTimeUs < 0L) return 0L

        timeCallback?.let { this.timeCallback = it }

        val ptsMediaUs = (presentationTimeUs - activeStreamOffsetUs).coerceAtLeast(0L)

        // 1. Detect seek or discontinuity
        if (lastPtsMediaUs != C.TIME_UNSET) {
            val deltaUs = ptsMediaUs - lastPtsMediaUs
            if ((abs(deltaUs) > 500_000L) || (deltaUs < -100_000L)) {
                reset()
            }
        }

        // 2. Pure rounded timestamp (nearest millisecond)
        val ptsMs = (ptsMediaUs + 500L) / 1000L
        var pureTimestampUs = ptsMs * 1000L

        // Enforce strict monotonicity (now_ms[k] >= now_ms[k-1])
        if ((lastPureTimestampUs != C.TIME_UNSET) && (pureTimestampUs < lastPureTimestampUs)) {
            pureTimestampUs = lastPureTimestampUs
        }

        lastPtsMediaUs = ptsMediaUs
        lastPureTimestampUs = pureTimestampUs

        // 3. Queue into RingBuffer with effective release time
        val nowNs = System.nanoTime()
        val effectiveReleaseNs = if (releaseTimeNs > 0L) releaseTimeNs else nowNs + 30_000_000L

        val item = obtainPoolItem()
        item.releaseTimeNs = effectiveReleaseNs
        item.pureTimestampUs = pureTimestampUs
        item.ptsMs = ptsMs

        ringBuffer.offer(item)

        // Ensure Choreographer VSYNC loop is running on dedicated HandlerThread
        startVsyncLoop()

        return pureTimestampUs
    }

    private val vsyncFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isVsyncRunning) return

            processVsyncFrame(frameTimeNanos)

            if (isVsyncRunning) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    @Synchronized
    fun startVsyncLoop() {
        if ((isVsyncRunning) && (vsyncThread != null)) return

        if (vsyncThread == null) {
            val thread = HandlerThread("SubtitleVsyncDispatcher", Process.THREAD_PRIORITY_URGENT_DISPLAY)
            thread.start()
            vsyncThread = thread
            val handler = Handler(thread.looper)
            vsyncHandler = handler

            handler.post {
                isVsyncRunning = true
                Choreographer.getInstance().postFrameCallback(vsyncFrameCallback)
            }
        } else {
            isVsyncRunning = true
            vsyncHandler?.post {
                Choreographer.getInstance().postFrameCallback(vsyncFrameCallback)
            }
        }
    }

    @Synchronized
    fun stopVsyncLoop() {
        isVsyncRunning = false
        val thread = vsyncThread
        vsyncThread = null
        vsyncHandler = null
        thread?.quitSafely()
    }

    private fun processVsyncFrame(vsyncTimeNs: Long) {
        val vsyncPeriodNs = (1_000_000_000.0 / max(1.0, refreshRateHz)).toLong()
        val halfVsyncNs = vsyncPeriodNs / 2L

        // Apply pending vsyncLeadSteps change ONLY during cold transition / dialogue gap > 500ms
        pendingVsyncLeadSteps?.let { pendingSteps ->
            if ((lastRenderedTimeMs < 0L) || ringBuffer.isEmpty()) {
                vsyncLeadSteps = pendingSteps
                pendingVsyncLeadSteps = null
            }
        }

        val targetReleaseTimeNs = vsyncTimeNs + (vsyncLeadSteps * vsyncPeriodNs)
        val lowerBoundNs = targetReleaseTimeNs - halfVsyncNs
        val upperBoundNs = targetReleaseTimeNs + halfVsyncNs

        var selectedEntry: FrameTimestamp? = null
        var bestDiffNs = Long.MAX_VALUE
        var fallbackOlderEntry: FrameTimestamp? = null

        // Scan RingBuffer for closest frame in [lowerBoundNs, upperBoundNs]
        val iterator = ringBuffer.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val releaseNs = item.releaseTimeNs

            if (releaseNs in lowerBoundNs..upperBoundNs) {
                val diffNs = abs(releaseNs - targetReleaseTimeNs)
                if (diffNs < bestDiffNs) {
                    bestDiffNs = diffNs
                    selectedEntry = item
                }
            } else if (releaseNs < lowerBoundNs) {
                fallbackOlderEntry = item
            } else {
                break
            }
        }

        val finalEntry = selectedEntry ?: fallbackOlderEntry

        finalEntry?.let { entry ->
            val targetPureUs = entry.pureTimestampUs
            val targetPtsMs = entry.ptsMs

            // Purge items from RingBuffer up to and including finalEntry
            val purgeIterator = ringBuffer.iterator()
            while (purgeIterator.hasNext()) {
                val item = purgeIterator.next()
                purgeIterator.remove()
                recyclePoolItem(item)
                if (item.pureTimestampUs == targetPureUs) {
                    break
                }
            }

            // Render ONLY if ptsMs > lastRenderedTimeMs
            if (targetPtsMs > lastRenderedTimeMs) {
                val renderStartNs = System.nanoTime()
                timeCallback?.invoke(targetPureUs)
                val renderDurationNs = System.nanoTime() - renderStartNs

                // Check for slow GL render duration on heavy karaokés (> 70% of VSYNC period)
                if ((renderDurationNs > (vsyncPeriodNs * 0.7)) && (vsyncLeadSteps < 2L)) {
                    pendingVsyncLeadSteps = 2L
                }

                lastRenderedTimeMs = targetPtsMs
            }
        }
    }

    @Synchronized
    private fun obtainPoolItem(): FrameTimestamp {
        return if (itemPool.isNotEmpty()) {
            itemPool.removeFirst()
        } else {
            FrameTimestamp()
        }
    }

    @Synchronized
    private fun recyclePoolItem(item: FrameTimestamp) {
        if (itemPool.size < queueCapacity) {
            item.releaseTimeNs = 0L
            item.pureTimestampUs = 0L
            item.ptsMs = 0L
            itemPool.addLast(item)
        }
    }

    @Synchronized
    fun reset() {
        ringBuffer.clear()
        lastPtsMediaUs = C.TIME_UNSET
        lastPureTimestampUs = C.TIME_UNSET
        lastRenderedTimeMs = -1L
        pendingVsyncLeadSteps = null
    }

    fun release() {
        stopVsyncLoop()
        reset()
        synchronized(this) {
            itemPool.clear()
        }
        timeCallback = null
    }
}
