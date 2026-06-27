package com.robot.depth

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.robot.common.FrameData

object DepthFrameProvider {

    private const val CONFIDENCE_THRESHOLD = 50 // 0-255

    /**
     * Extracts depth data from the current ARCore frame and bundles it with the camera pose.
     * Returns null if tracking is lost, depth is unavailable, or the confidence image is missing.
     *
     * MUST be called on the GL thread. The returned [FrameData] is a safe copy (ShortArray),
     * so the caller can pass it to any thread.
     */
    fun extract(frame: Frame, cameraToWorld: FloatArray): FrameData? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null

        return try {
            frame.acquireRawDepthImage16Bits().use { depthImage ->
                val width = depthImage.width
                val height = depthImage.height

                // ShortBuffer backed by the Image plane — copy before closing
                val plane = depthImage.planes[0]
                val rawBuffer = plane.buffer.asShortBuffer()
                val depthValues = ShortArray(width * height).also { rawBuffer.get(it) }

                val intrinsics = frame.camera.textureIntrinsics
                val focalLength = intrinsics.focalLength
                val principalPoint = intrinsics.principalPoint

                // Scale intrinsics from texture resolution to depth image resolution
                val texSize = intrinsics.imageDimensions
                val scaleX = width.toFloat() / texSize[0]
                val scaleY = height.toFloat() / texSize[1]

                FrameData(
                    depthValues = depthValues,
                    depthWidth = width,
                    depthHeight = height,
                    fx = focalLength[0] * scaleX,
                    fy = focalLength[1] * scaleY,
                    cx = principalPoint[0] * scaleX,
                    cy = principalPoint[1] * scaleY,
                    cameraToWorld = cameraToWorld.copyOf(),
                    timestampNs = frame.timestamp,
                )
            }
        } catch (e: Exception) {
            // Depth image not available for this frame — normal at session start
            null
        }
    }
}
