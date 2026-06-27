package com.robot.slam

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

object PoseExtractor {

    /**
     * Returns the column-major 4x4 camera-to-world matrix when tracking,
     * or null if the camera is not currently tracked.
     */
    fun cameraToWorld(frame: Frame): FloatArray? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val mat = FloatArray(16)
        // getPose() returns camera-to-world (model matrix)
        camera.pose.toMatrix(mat, 0)
        return mat
    }

    /**
     * Returns the column-major view matrix (world-to-camera) for rendering.
     */
    fun viewMatrix(frame: Frame): FloatArray {
        val mat = FloatArray(16)
        frame.camera.getViewMatrix(mat, 0)
        return mat
    }

    /**
     * Returns the projection matrix.
     */
    fun projectionMatrix(frame: Frame, near: Float = 0.1f, far: Float = 20f): FloatArray {
        val mat = FloatArray(16)
        frame.camera.getProjectionMatrix(mat, 0, near, far)
        return mat
    }
}
