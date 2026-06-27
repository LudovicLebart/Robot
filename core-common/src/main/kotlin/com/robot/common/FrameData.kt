package com.robot.common

/**
 * Immutable snapshot of one ARCore frame.
 * Created on the GL thread; consumed on the TSDF dispatcher.
 */
data class FrameData(
    val depthValues: ShortArray,
    val depthWidth: Int,
    val depthHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    /** Column-major 4x4 camera-to-world matrix from ARCore. */
    val cameraToWorld: FloatArray,
    val timestampNs: Long,
) {
    override fun equals(other: Any?): Boolean = other is FrameData && timestampNs == other.timestampNs
    override fun hashCode(): Int = timestampNs.hashCode()
}
