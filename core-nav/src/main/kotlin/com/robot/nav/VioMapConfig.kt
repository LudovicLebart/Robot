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
}
