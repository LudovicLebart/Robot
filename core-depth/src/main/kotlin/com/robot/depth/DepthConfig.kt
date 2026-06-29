package com.robot.depth

object DepthConfig {
    /**
     * Minimum DEPTH16 confidence to accept a pixel (0–7 scale, bits[2:0]).
     * Values below this threshold are treated as invalid (written as 0 mm).
     */
    const val MIN_CONFIDENCE = 3
}
