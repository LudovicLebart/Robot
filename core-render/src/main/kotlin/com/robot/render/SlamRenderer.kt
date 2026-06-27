package com.robot.render

import android.opengl.GLSurfaceView
import com.google.ar.core.TrackingState
import com.robot.common.MeshSnapshot
import com.robot.depth.DepthFrameProvider
import com.robot.slam.ArSessionManager
import com.robot.slam.PoseExtractor
import com.robot.tsdf.TsdfVolume
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Central GL thread.
 * - Calls session.update() once per frame.
 * - Renders the camera background + TSDF mesh.
 * - Publishes FrameData to the TSDF channel (CONFLATED — never blocks).
 *
 * @param getDisplayRotation  Returns the current display rotation (Surface.ROTATION_*).
 *   Must be supplied by the Activity/View — ARCore needs it to orient the camera texture.
 */
class SlamRenderer(
    private val sessionManager: ArSessionManager,
    private val tsdfVolume: TsdfVolume,
    private val getDisplayRotation: () -> Int,
    private val onPoseUpdated: (FloatArray) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : GLSurfaceView.Renderer {

    private val backgroundRenderer = BackgroundRenderer()
    private val meshRenderer = MeshRenderer()

    private var viewMatrix = FloatArray(16)
    private var projMatrix = FloatArray(16)

    @Volatile private var pendingMesh: MeshSnapshot? = null

    fun onMeshSnapshot(snap: MeshSnapshot) {
        pendingMesh = snap
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textureId = backgroundRenderer.init()
        meshRenderer.init()
        sessionManager.setCameraTextureName(textureId)
        onLog("onSurfaceCreated: textureId=$textureId")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        android.opengl.GLES30.glViewport(0, 0, width, height)
        val rotation = getDisplayRotation()
        sessionManager.setDisplayGeometry(rotation, width, height)
        onLog("onSurfaceChanged: ${width}x${height} rotation=$rotation")
    }

    private var lastTrackingState: TrackingState? = null

    override fun onDrawFrame(gl: GL10?) {
        android.opengl.GLES30.glClear(
            android.opengl.GLES30.GL_COLOR_BUFFER_BIT or android.opengl.GLES30.GL_DEPTH_BUFFER_BIT
        )

        val frame = sessionManager.update() ?: return

        if (frame.hasDisplayGeometryChanged()) {
            onLog("displayGeometryChanged")
        }

        backgroundRenderer.draw(frame)

        val camera = frame.camera
        val trackingState = camera.trackingState
        if (trackingState != lastTrackingState) {
            onLog("trackingState: $trackingState")
            lastTrackingState = trackingState
        }
        if (trackingState != TrackingState.TRACKING) return

        viewMatrix = PoseExtractor.viewMatrix(frame)
        projMatrix = PoseExtractor.projectionMatrix(frame)

        val c2w = PoseExtractor.cameraToWorld(frame)
        if (c2w != null) {
            val frameData = DepthFrameProvider.extract(frame, c2w)
            if (frameData != null) {
                tsdfVolume.frameChannel.trySend(frameData)
                onPoseUpdated(c2w)
            } else {
                onLog("depth: no data this frame")
            }
        }

        pendingMesh?.let { snap ->
            meshRenderer.uploadMesh(snap)
            pendingMesh = null
        }

        meshRenderer.draw(viewMatrix, projMatrix)
    }
}
