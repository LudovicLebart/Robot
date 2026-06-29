package com.robot.nav

object VioMapConfig {
    /** Minimum observations before a point is considered structural. */
    const val MIN_OBSERVATIONS = 8

    /** Maximum stable points exported for rendering. */
    const val MAX_STABLE_POINTS = 10_000

    /** Unstable points not seen for this many frames are evicted (~5 s at 30 fps). */
    const val STALE_FRAMES = 150

    /** Eviction and sorted-snapshot rebuild runs every this many frames. */
    const val EVICT_INTERVAL_FRAMES = 60

    /** Minimum confidence weight floor (prevents division-by-zero on zero-confidence points). */
    const val MIN_CONFIDENCE = 0.01f

    /** Initial HashMap capacity for VIO point ID map. */
    const val INITIAL_MAP_CAPACITY = 4096

    // ── Moving object detection ───────────────────────────────────────────────

    /** Distance from current weighted mean above which a stable point is flagged as moved (metres).
     *  VIO noise is typically < 5 cm; 15 cm detects real movement while ignoring oscillation. */
    const val MOVE_THRESHOLD_M = 0.15f

    /** Fraction of currently-observed stable points that must simultaneously exceed
     *  MOVE_THRESHOLD_M before treating the event as global ARCore drift rather than
     *  individual object movement. */
    const val DRIFT_GUARD_FRACTION = 0.30f

    /** Minimum number of stable points that must be visible in the current frame before the
     *  drift guard can activate (prevents false drift on frames with sparse stable coverage).
     *  Edge case: if exactly MIN_STABLE points are visible and all move, the guard still fires.
     *  In practice, hundreds of stable points are visible so the fraction stays well below
     *  DRIFT_GUARD_FRACTION unless it really is a global coordinate correction. */
    const val DRIFT_GUARD_MIN_STABLE = 5
}
