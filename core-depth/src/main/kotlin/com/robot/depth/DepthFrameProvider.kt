package com.robot.depth

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.robot.common.FrameData

object DepthFrameProvider {

    /**
     * Extracts depth data from the current ARCore frame.
     *
     * Tries RAW_DEPTH_ONLY first (hardware ToF, e.g. some Samsung devices), then falls
     * back to AUTOMATIC (ML-estimated depth, used on Pixel 9 and most phones).
     * Returns null when no depth is available for this frame.
     *
     * [onError] receives a short description of the failure for overlay logging; called
     * at most once per invocation and only when both APIs fail.
     *
     * MUST be called on the GL thread. The returned [FrameData] is a safe copy (ShortArray).
     */
    fun extract(
        frame: Frame,
        cameraToWorld: FloatArray,
        onError: ((String) -> Unit)? = null,
    ): FrameData? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null

        // Try RAW_DEPTH_ONLY (requires hardware sensor), then AUTOMATIC (ML depth)
        val depthImage = try {
            frame.acquireRawDepthImage16Bits()
        } catch (rawEx: Exception) {
            try {
                frame.acquireDepthImage16Bits()
            } catch (autoEx: Exception) {
                onError?.invoke("${rawEx.javaClass.simpleName}/${autoEx.javaClass.simpleName}")
                return null
            }
        }

        return try {
            depthImage.use { img ->
                val width  = img.width
                val height = img.height

                val rawBuffer  = img.planes[0].buffer.asShortBuffer()
                val depthValues = ShortArray(width * height).also { rawBuffer.get(it) }

                val intrinsics    = frame.camera.textureIntrinsics
                val focalLength   = intrinsics.focalLength
                val principalPoint = intrinsics.principalPoint
                val texSize       = intrinsics.imageDimensions
                val scaleX        = width.toFloat()  / texSize[0]
                val scaleY        = height.toFloat() / texSize[1]

                FrameData(
                    depthValues   = depthValues,
                    depthWidth    = width,
                    depthHeight   = height,
                    fx            = focalLength[0]    * scaleX,
                    fy            = focalLength[1]    * scaleY,
                    cx            = principalPoint[0] * scaleX,
                    cy            = principalPoint[1] * scaleY,
                    cameraToWorld = cameraToWorld.copyOf(),
                    timestampNs   = frame.timestamp,
                )
            }
        } catch (e: Exception) {
            onError?.invoke("process:${e.javaClass.simpleName}")
            null
        }
    }
}
