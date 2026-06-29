package com.robot.nav

/**
 * Accumulates ARCore VIO feature points by their persistent ID.
 * Each point's position is a confidence-weighted mean across all observations.
 * Points observed ≥ VioMapConfig.MIN_OBSERVATIONS times are considered structural.
 *
 * Unstable points not re-observed for VioMapConfig.STALE_FRAMES frames are evicted.
 *
 * Moving object detection (per frame, two-pass):
 *  1. Count how many currently-observed stable points jump > MOVE_THRESHOLD_M from their mean.
 *  2. If the fraction exceeds DRIFT_GUARD_FRACTION → global ARCore drift, skip demotion.
 *     Otherwise, demote stable movers (reset count to 1 at new position) so they must
 *     re-accumulate before re-entering the stable cloud, and remove unstable movers outright.
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

    // Diagnostic: demotions applied during the last eviction cycle (reset at each rebuildSnapshot).
    private var demotionsLastCycle = 0

    // Reused each frame to avoid per-frame HashSet allocation on the GL thread.
    private val jumperIds = HashSet<Int>()

    // Squared distance threshold — product of two constants, computed once.
    private val moveThresh2 = VioMapConfig.MOVE_THRESHOLD_M * VioMapConfig.MOVE_THRESHOLD_M

    /**
     * Merge a batch of VIO points keyed by their ARCore IDs.
     * [xyz] stride-3 world-space positions, [ids] and [confidences] are parallel arrays.
     *
     * Two-pass movement detection: stable points that jump farther than MOVE_THRESHOLD_M
     * are demoted (unless the simultaneous jump fraction exceeds DRIFT_GUARD_FRACTION,
     * which signals a global ARCore loop-closure rather than real object movement).
     */
    fun update(xyz: FloatArray, ids: IntArray, confidences: FloatArray, count: Int) {
        frameIndex++

        // Pass 1 — identify stable points that exceed the movement threshold.
        var stableObserved = 0
        jumperIds.clear()
        for (i in 0 until count) {
            val e = byId[ids[i]] ?: continue
            if (e.count < minObservations) continue
            stableObserved++
            val mx = e.wx / e.tw; val my = e.wy / e.tw; val mz = e.wz / e.tw
            val dx = xyz[i * 3] - mx
            val dy = xyz[i * 3 + 1] - my
            val dz = xyz[i * 3 + 2] - mz
            if (dx * dx + dy * dy + dz * dz > moveThresh2) jumperIds.add(ids[i])
        }

        // Drift guard: if too many stable points jump at once it is a global coordinate
        // correction, not individual object movement — skip demotion entirely.
        val isGlobalDrift = stableObserved >= VioMapConfig.DRIFT_GUARD_MIN_STABLE &&
            jumperIds.size.toFloat() / stableObserved > VioMapConfig.DRIFT_GUARD_FRACTION

        // Pass 2 — apply updates, demote / remove movers when not global drift.
        for (i in 0 until count) {
            val id  = ids[i]
            val nx  = xyz[i * 3]; val ny = xyz[i * 3 + 1]; val nz = xyz[i * 3 + 2]
            val conf = maxOf(confidences[i], VioMapConfig.MIN_CONFIDENCE)

            if (!isGlobalDrift && id in jumperIds) {
                // All IDs in jumperIds have count ≥ minObservations (pass-1 filter).
                // Demote: restart accumulation at the new observed position so the point
                // must re-confirm before re-entering the stable cloud.
                val e = byId[id]!!
                e.wx = nx * conf; e.wy = ny * conf; e.wz = nz * conf
                e.tw = conf; e.count = 1; e.lastSeen = frameIndex
                demotionsLastCycle++
                continue
            }

            val e = byId.getOrPut(id) { Entry(0f, 0f, 0f, 0f, 0, frameIndex) }
            e.wx += nx * conf; e.wy += ny * conf; e.wz += nz * conf
            e.tw += conf; e.count++; e.lastSeen = frameIndex
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
        demotionsLastCycle = 0
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

    fun stableCount(): Int   = sortedSnapshot.size
    fun totalCount(): Int    = byId.size
    /** Number of stable points demoted as moved since the last eviction cycle. */
    fun demotionCount(): Int = demotionsLastCycle

    fun reset() {
        byId.clear()
        sortedSnapshot = emptyList()
        frameIndex = 0
        demotionsLastCycle = 0
    }
}
