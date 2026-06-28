package com.robot.render

import com.robot.common.FrameData

/**
 * Stateful, pure-Kotlin accumulator that estimates the floor and ceiling
 * planes from the depth point cloud.
 *
 * Strategy (deliberately simple, no RANSAC):
 *  - Back-project a sub-sample of valid depth pixels to world XYZ.
 *  - Accumulate their world Y into a fixed 5 cm histogram.
 *  - Floor  = strongest histogram peak below (cameraY − 0.3 m).
 *  - Ceiling = strongest histogram peak above (cameraY + 0.5 m).
 *  - Smooth both with an exponential moving average so the rendered grids
 *    do not jitter frame to frame.
 *
 * Everything runs on the GL thread inside [update]; the arithmetic is cheap
 * (≈225 samples/frame at 120×90), so no JNI / background thread is needed.
 */
class FloorCeilingDetector {

    companion object {
        private const val Y_MIN = -1.5f          // metres, absolute world Y
        private const val Y_MAX = 3.5f
        private const val BIN_SIZE = 0.05f       // 5 cm bins
        private const val BIN_COUNT = ((Y_MAX - Y_MIN) / BIN_SIZE).toInt()  // 100
        private const val MIN_POINTS = 500       // points in a bin to confirm a plane
        private const val FLOOR_MARGIN = 0.3f    // floor must be below cameraY − margin
        private const val CEILING_MARGIN = 0.5f  // ceiling must be above cameraY + margin
        private const val EMA_ALPHA = 0.1f       // smoothing factor
        private const val PIXEL_STRIDE = 4       // sample every 4th row / 4th column
    }

    private val histogram = IntArray(BIN_COUNT)

    private var smoothedFloor: Float? = null
    private var smoothedCeiling: Float? = null

    /** Smoothed floor world-Y in metres, or null until enough data has accumulated. */
    val floorY: Float? get() = smoothedFloor

    /** Smoothed ceiling world-Y in metres, or null until enough data has accumulated. */
    val ceilingY: Float? get() = smoothedCeiling

    /** Drop all accumulated evidence and smoothed estimates. */
    fun reset() {
        histogram.fill(0)
        smoothedFloor = null
        smoothedCeiling = null
    }

    /**
     * Fold one frame's depth into the histogram and recompute the plane estimates.
     * Call on the GL thread.
     */
    fun update(frame: FrameData) {
        accumulate(frame)

        val cameraY = frame.cameraToWorld[13]

        // Floor: search bins strictly below cameraY − FLOOR_MARGIN.
        val floorLimit = cameraY - FLOOR_MARGIN
        val rawFloor = peakBelow(floorLimit)
        // Ceiling: search bins strictly above cameraY + CEILING_MARGIN.
        val ceilingLimit = cameraY + CEILING_MARGIN
        val rawCeiling = peakAbove(ceilingLimit)

        if (rawFloor != null) {
            smoothedFloor = ema(smoothedFloor, rawFloor)
        }
        if (rawCeiling != null) {
            smoothedCeiling = ema(smoothedCeiling, rawCeiling)
        }
    }

    private fun ema(prev: Float?, sample: Float): Float =
        if (prev == null) sample else prev + EMA_ALPHA * (sample - prev)

    /** Back-project sub-sampled depth pixels and bin their world Y. */
    private fun accumulate(frame: FrameData) {
        val w = frame.depthWidth
        val h = frame.depthHeight
        val depth = frame.depthValues
        val fx = frame.fx
        val fy = frame.fy
        val cx = frame.cx
        val cy = frame.cy
        val m = frame.cameraToWorld

        // Column-major rotation rows for the world-Y component:
        //   worldY = r01*camX + r11*camY + r21*camZ + ty
        val r01 = m[1]; val r11 = m[5]; val r21 = m[9]; val ty = m[13]

        var v = 0
        while (v < h) {
            val rowBase = v * w
            var u = 0
            while (u < w) {
                val raw = depth[rowBase + u].toInt() and 0xFFFF
                if (raw != 0) {
                    val d = raw / 1000f                       // mm → metres
                    val camX = (u - cx) / fx * d
                    val camY = -(v - cy) / fy * d             // V+ is down in image, Y+ is up
                    val camZ = -d                             // camera looks down −Z
                    val worldY = r01 * camX + r11 * camY + r21 * camZ + ty

                    val bin = ((worldY - Y_MIN) / BIN_SIZE).toInt()
                    if (bin in 0 until BIN_COUNT) {
                        histogram[bin]++
                    }
                }
                u += PIXEL_STRIDE
            }
            v += PIXEL_STRIDE
        }
    }

    /** World-Y centre of the strongest qualifying bin below [limitY], or null. */
    private fun peakBelow(limitY: Float): Float? {
        var bestBin = -1
        var bestCount = MIN_POINTS - 1
        var bin = 0
        while (bin < BIN_COUNT) {
            val binTopY = Y_MIN + (bin + 1) * BIN_SIZE
            if (binTopY <= limitY && histogram[bin] > bestCount) {
                bestCount = histogram[bin]
                bestBin = bin
            }
            bin++
        }
        return if (bestBin >= 0) binCenterY(bestBin) else null
    }

    /** World-Y centre of the strongest qualifying bin above [limitY], or null. */
    private fun peakAbove(limitY: Float): Float? {
        var bestBin = -1
        var bestCount = MIN_POINTS - 1
        var bin = 0
        while (bin < BIN_COUNT) {
            val binBottomY = Y_MIN + bin * BIN_SIZE
            if (binBottomY >= limitY && histogram[bin] > bestCount) {
                bestCount = histogram[bin]
                bestBin = bin
            }
            bin++
        }
        return if (bestBin >= 0) binCenterY(bestBin) else null
    }

    private fun binCenterY(bin: Int): Float = Y_MIN + (bin + 0.5f) * BIN_SIZE
}
