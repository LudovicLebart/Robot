package com.robot.render

import com.robot.common.FrameData

/**
 * Stateful, pure-Kotlin accumulator that estimates the floor and ceiling
 * planes from the depth point cloud. All tuning parameters come from RenderConfig.
 *
 * Strategy: back-project a sub-sample of valid depth pixels to world XYZ,
 * accumulate their world Y into a fixed histogram, locate peaks, and smooth
 * with an exponential moving average.
 *
 * Runs on the GL thread inside update() — arithmetic is cheap (~225 samples/frame).
 */
class FloorCeilingDetector {

    private val binCount = ((RenderConfig.DETECTOR_Y_MAX_M - RenderConfig.DETECTOR_Y_MIN_M) /
                            RenderConfig.DETECTOR_BIN_SIZE_M).toInt()

    private val histogram = IntArray(binCount)

    private var smoothedFloor: Float? = null
    private var smoothedCeiling: Float? = null

    val floorY: Float?   get() = smoothedFloor
    val ceilingY: Float? get() = smoothedCeiling

    fun reset() {
        histogram.fill(0)
        smoothedFloor = null
        smoothedCeiling = null
    }

    fun update(frame: FrameData) {
        accumulate(frame)

        val cameraY = frame.cameraToWorld[13]
        val rawFloor   = peakBelow(cameraY - RenderConfig.DETECTOR_FLOOR_MARGIN_M)
        val rawCeiling = peakAbove(cameraY + RenderConfig.DETECTOR_CEILING_MARGIN_M)

        if (rawFloor   != null) smoothedFloor   = ema(smoothedFloor,   rawFloor)
        if (rawCeiling != null) smoothedCeiling = ema(smoothedCeiling, rawCeiling)
    }

    private fun ema(prev: Float?, sample: Float): Float =
        if (prev == null) sample
        else prev + RenderConfig.DETECTOR_EMA_ALPHA * (sample - prev)

    private fun accumulate(frame: FrameData) {
        val w = frame.depthWidth; val h = frame.depthHeight
        val depth = frame.depthValues
        val fx = frame.fx; val fy = frame.fy
        val cx = frame.cx; val cy = frame.cy
        val m = frame.cameraToWorld

        val r01 = m[1]; val r11 = m[5]; val r21 = m[9]; val ty = m[13]

        val stride = RenderConfig.DETECTOR_PIXEL_STRIDE
        var v = 0
        while (v < h) {
            val rowBase = v * w
            var u = 0
            while (u < w) {
                val raw = depth[rowBase + u].toInt() and 0xFFFF
                if (raw != 0) {
                    val d = raw / 1000f
                    val camX = (u - cx) / fx * d
                    val camY = -(v - cy) / fy * d
                    val camZ = -d
                    val worldY = r01 * camX + r11 * camY + r21 * camZ + ty
                    val bin = ((worldY - RenderConfig.DETECTOR_Y_MIN_M) /
                               RenderConfig.DETECTOR_BIN_SIZE_M).toInt()
                    if (bin in 0 until binCount) histogram[bin]++
                }
                u += stride
            }
            v += stride
        }
    }

    private fun peakBelow(limitY: Float): Float? {
        var bestBin = -1
        var bestCount = RenderConfig.DETECTOR_MIN_VOTES - 1
        for (bin in 0 until binCount) {
            val binTopY = RenderConfig.DETECTOR_Y_MIN_M + (bin + 1) * RenderConfig.DETECTOR_BIN_SIZE_M
            if (binTopY <= limitY && histogram[bin] > bestCount) {
                bestCount = histogram[bin]; bestBin = bin
            }
        }
        return if (bestBin >= 0) binCenterY(bestBin) else null
    }

    private fun peakAbove(limitY: Float): Float? {
        var bestBin = -1
        var bestCount = RenderConfig.DETECTOR_MIN_VOTES - 1
        for (bin in 0 until binCount) {
            val binBottomY = RenderConfig.DETECTOR_Y_MIN_M + bin * RenderConfig.DETECTOR_BIN_SIZE_M
            if (binBottomY >= limitY && histogram[bin] > bestCount) {
                bestCount = histogram[bin]; bestBin = bin
            }
        }
        return if (bestBin >= 0) binCenterY(bestBin) else null
    }

    private fun binCenterY(bin: Int): Float =
        RenderConfig.DETECTOR_Y_MIN_M + (bin + 0.5f) * RenderConfig.DETECTOR_BIN_SIZE_M
}
