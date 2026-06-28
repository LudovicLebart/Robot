package com.robot.render

import android.opengl.GLES30
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
 * - AR mode: renders camera background + TSDF mesh.
 * - Plan mode: renders TSDF mesh from above with orthographic projection.
 * - Publishes FrameData to the TSDF channel (CONFLATED — never blocks).
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
    private val floorCeilingDetector = FloorCeilingDetector()
    private val floorCeilingRenderer = FloorCeilingRenderer()

    private var viewMatrix = FloatArray(16)
    private var projMatrix = FloatArray(16)

    @Volatile private var pendingMesh: MeshSnapshot? = null
    @Volatile var planViewEnabled = false
    @Volatile var meshVisible = true

    private var lastKnownPos = floatArrayOf(0f, 0f, 0f)

    // Last logged plane heights, to throttle "floor detected" logs to >5 cm changes.
    private var lastLoggedFloorY: Float? = null
    private var lastLoggedCeilingY: Float? = null

    private var depthSuccessCount = 0
    private var depthFailCount = 0
    private var lastTrackingState: TrackingState? = null

    private val Float.format1 get() = "%.1f".format(this)

    fun onMeshSnapshot(snap: MeshSnapshot) {
        pendingMesh = snap
        onLog("mesh ready: ${snap.vertexCount} verts (pending GL upload)")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textureId = backgroundRenderer.init()
        meshRenderer.init()
        floorCeilingRenderer.init()
        sessionManager.setCameraTextureName(textureId)
        onLog("GL surface created texId=$textureId depthMode=${sessionManager.depthModeName}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val rotation = getDisplayRotation()
        sessionManager.setDisplayGeometry(rotation, width, height)
        onLog("GL surface ${width}x${height} rot=$rotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val frame = sessionManager.update() ?: return

        if (frame.hasDisplayGeometryChanged()) {
            onLog("display geometry changed")
        }

        // Keep camera UVs current regardless of render mode — fixes stale UVs after
        // rotating the device while plan view is active.
        backgroundRenderer.updateUVsIfNeeded(frame)

        // Upload any pending mesh regardless of view mode
        pendingMesh?.let { snap ->
            meshRenderer.uploadMesh(snap)
            pendingMesh = null
            onLog("GL: mesh uploaded ${snap.vertexCount} verts")
        }

        if (planViewEnabled) {
            // Dark background — no camera pass-through
            GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            meshRenderer.drawPlanView(lastKnownPos)
            floorCeilingRenderer.draw(planViewMatrix(), planProjMatrix())
            return
        }

        // ── AR mode ─────────────────────────────────────────────────────────
        backgroundRenderer.draw(frame)

        val camera = frame.camera
        val trackingState = camera.trackingState
        if (trackingState != lastTrackingState) {
            onLog("tracking: $trackingState")
            lastTrackingState = trackingState
        }
        if (trackingState != TrackingState.TRACKING) return

        viewMatrix = PoseExtractor.viewMatrix(frame)
        projMatrix = PoseExtractor.projectionMatrix(frame)

        val c2w = PoseExtractor.cameraToWorld(frame)
        if (c2w != null) {
            lastKnownPos[0] = c2w[12]
            lastKnownPos[1] = c2w[13]
            lastKnownPos[2] = c2w[14]

            val frameData = DepthFrameProvider.extract(frame, c2w) { err ->
                depthFailCount++
                if (depthFailCount == 1 || depthFailCount % 90 == 0) {
                    onLog("depth FAIL #$depthFailCount: $err")
                }
            }
            if (frameData != null) {
                // Floor/ceiling detection runs on the GL thread; cheap pure-Kotlin math.
                floorCeilingDetector.update(frameData)
                floorCeilingRenderer.updatePlanes(
                    floorCeilingDetector.floorY,
                    floorCeilingDetector.ceilingY,
                    lastKnownPos[0],
                    lastKnownPos[2],
                )
                logPlanesIfChanged()

                depthSuccessCount++
                val w = frameData.depthWidth
                val h = frameData.depthHeight
                val total = w * h
                // True image-centre pixel (row=h/2, col=w/2)
                val centerIdx = (h / 2) * w + (w / 2)
                val centerMm = frameData.depthValues[centerIdx].toInt() and 0xFFFF
                val validCount = frameData.depthValues.count { it.toInt() != 0 }
                val validPct = validCount * 100 / total

                // Skip frames where almost no pixels survived the confidence filter —
                // they add negligible TSDF signal but consume pipeline time.
                val sent = if (validPct >= 5) {
                    tsdfVolume.frameChannel.trySend(frameData).isSuccess
                } else {
                    false
                }

                if (depthSuccessCount == 1 || depthSuccessCount % 90 == 0) {
                    val px = c2w[12]; val py = c2w[13]; val pz = c2w[14]
                    if (depthSuccessCount == 1) {
                        onLog("depth FIRST ${w}x${h} " +
                              "fx=${frameData.fx.toInt()} fy=${frameData.fy.toInt()} " +
                              "cx=${frameData.cx.toInt()} cy=${frameData.cy.toInt()}")
                    }
                    onLog("depth #$depthSuccessCount center=${centerMm}mm valid=${validPct}% " +
                          "pos=(${px.format1}/${py.format1}/${pz.format1})m tsdf=$sent")
                }
                onPoseUpdated(c2w)
            }
        }

        if (meshVisible) meshRenderer.draw(viewMatrix, projMatrix)
        floorCeilingRenderer.draw(viewMatrix, projMatrix)
    }

    /** Plan-view camera matrix — mirrors MeshRenderer.drawPlanView. */
    private fun planViewMatrix(): FloatArray {
        val view = FloatArray(16)
        android.opengl.Matrix.setLookAtM(view, 0,
            0f, 8f, 0f,   // eye directly above grid centre
            0f, 0f, 0f,   // look at grid centre
            0f, 0f, -1f,  // north = world −Z
        )
        return view
    }

    /** Plan-view orthographic projection — mirrors MeshRenderer.drawPlanView. */
    private fun planProjMatrix(): FloatArray {
        val proj = FloatArray(16)
        android.opengl.Matrix.orthoM(proj, 0, -3.3f, 3.3f, -3.3f, 3.3f, 0.5f, 16f)
        return proj
    }

    /** Emit a log line whenever floor/ceiling Y shifts by more than 5 cm. */
    private fun logPlanesIfChanged() {
        val floor = floorCeilingDetector.floorY
        val ceiling = floorCeilingDetector.ceilingY
        val floorChanged = floor != null &&
            (lastLoggedFloorY == null || kotlin.math.abs(floor - lastLoggedFloorY!!) > 0.05f)
        val ceilingChanged = ceiling != null &&
            (lastLoggedCeilingY == null || kotlin.math.abs(ceiling - lastLoggedCeilingY!!) > 0.05f)
        if (floorChanged || ceilingChanged) {
            lastLoggedFloorY = floor
            lastLoggedCeilingY = ceiling
            val f = floor?.let { "%.2f".format(it) } ?: "?"
            val c = ceiling?.let { "%.2f".format(it) } ?: "?"
            onLog("floor detected Y=${f}m ceiling=${c}m")
        }
    }
}
