package com.robot.slam

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

object PoseExtractor {

    fun cameraToWorld(frame: Frame): FloatArray? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val mat = FloatArray(16)
        camera.pose.toMatrix(mat, 0)
        return mat
    }

    fun viewMatrix(frame: Frame): FloatArray {
        val mat = FloatArray(16)
        frame.camera.getViewMatrix(mat, 0)
        return mat
    }

    fun projectionMatrix(
        frame: Frame,
        near: Float = SlamConfig.PROJ_NEAR_M,
        far: Float  = SlamConfig.PROJ_FAR_M,
    ): FloatArray {
        val mat = FloatArray(16)
        frame.camera.getProjectionMatrix(mat, 0, near, far)
        return mat
    }
}
