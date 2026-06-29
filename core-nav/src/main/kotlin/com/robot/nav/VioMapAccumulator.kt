package com.robot.nav

/**
 * Accumulates ARCore VIO feature points by their persistent ID.
 * Each point's position is a confidence-weighted mean across all observations.
 * Points observed ≥ VioMapConfig.MIN_OBSERVATIONS times are considered structural.
 *
 * Unstable points not re-observed for VioMapConfig.STALE_FRAMES frames are evicted.
 * Stable points are never evicted.
 *
 * Every VioMapConfig.EVICT_INTERVAL_FRAMES frames, a sorted snapshot of stable points
 * is rebuilt (descending by count) so getStablePointsWeighted always returns the most
 * confirmed points first — eliminates per-frame HashMap iteration churn.
 *
 * Call update() and getStablePoints*() from the GL thread only — no synchronisation needed.
 */
class VioMapAccumulator(
    val minObservations: Int = VioMapConfig.MIN_OBSERVATIONS,
    val maxStablePoints: Int = VioMapConfig.MAX_STABLE_POINTS,
    val staleFrames: Int     = VioMapConfig.STALE_FRAMES,
) {
    private class Entry(
        var wx: Float, var wy: Float, var wz: Float,  // sum of (position × confidence)
        var tw: Float,                                  // sum of confidence weights
        var count: Int,                                 // number of observations
        var lastSeen: Int,                              // frame index of last observation
    ) {
        val x get() = wx / tw
        val y get() = wy / tw
        val z get() = wz / tw
    }

    private val byId = HashMap<Int, Entry>(VioMapConfig.INITIAL_MAP_CAPACITY)
    private var frameIndex = 0

    // Sorted snapshot rebuilt every EVICT_INTERVAL_FRAMES — avoids per-frame sort + GC.
    private var sortedSnapshot: List<Entry> = emptyList()

    /**
     * Merge a batch of VIO points keyed by their ARCore IDs.
     * [xyz] stride-3 world-space positions, [ids] and [confidences] are parallel arrays.
     * Evicts stale unstable points and rebuilds the sorted snapshot every
     * VioMapConfig.EVICT_INTERVAL_FRAMES frames.
     */
    fun update(xyz: FloatArray, ids: IntArray, confidences: FloatArray, count: Int) {
        frameIndex++
        for (i in 0 until count) {
            val conf = maxOf(confidences[i], VioMapConfig.MIN_CONFIDENCE)
            val e = byId.getOrPut(ids[i]) { Entry(0f, 0f, 0f, 0f, 0, frameIndex) }
            e.wx += xyz[i * 3] * conf
            e.wy += xyz[i * 3 + 1] * conf
            e.wz += xyz[i * 3 + 2] * conf
            e.tw += conf
            e.count++
            e.lastSeen = frameIndex
        }
        if (frameIndex % VioMapConfig.EVICT_INTERVAL_FRAMES == 0) rebuildSnapshot()
    }

    private fun rebuildSnapshot() {
        val threshold = frameIndex - staleFrames
        val iter = byId.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            if (e.count < minObservations && e.lastSeen < threshold) iter.remove()
        }
        sortedSnapshot = byId.values
            .filter { it.count >= minObservations }
            .sortedByDescending { it.count }
    }

    /**
     * Write stable points (count ≥ minObservations) into [out] (stride 3).
     * Returns the number of points written.
     */
    fun getStablePoints(out: FloatArray): Int {
        val limit = minOf(maxStablePoints, out.size / 3)
        var n = 0
        for (e in sortedSnapshot) {
            if (n >= limit) break
            out[n * 3]     = e.x
            out[n * 3 + 1] = e.y
            out[n * 3 + 2] = e.z
            n++
        }
        return n
    }

    /**
     * Write stable points into [out] (stride 4: x,y,z,observationCount).
     * Uses the pre-sorted snapshot so the most-confirmed points are always rendered
     * when count exceeds maxStablePoints.
     */
    fun getStablePointsWeighted(out: FloatArray): Int {
        val limit = minOf(maxStablePoints, out.size / 4)
        var n = 0
        for (e in sortedSnapshot) {
            if (n >= limit) break
            out[n * 4]     = e.x
            out[n * 4 + 1] = e.y
            out[n * 4 + 2] = e.z
            out[n * 4 + 3] = e.count.toFloat()
            n++
        }
        return n
    }

    fun stableCount(): Int = sortedSnapshot.size
    fun totalCount(): Int  = byId.size
    fun reset() {
        byId.clear()
        sortedSnapshot = emptyList()
        frameIndex = 0
    }
}
