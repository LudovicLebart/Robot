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
    // Neural Depth back-projection — yellow, high density
    private val pointCloudRenderer = PointCloudRenderer(floatArrayOf(1f, 0.85f, 0f, 1f), 12_000)
    // ARCore VIO feature points — cyan, sparse but stable
    private val vioCloudRenderer   = PointCloudRenderer(floatArrayOf(0f, 1f, 1f, 1f), 500)

    private var viewMatrix = FloatArray(16)
    private var projMatrix = FloatArray(16)

    @Volatile private var pendingMesh: MeshSnapshot? = null
    @Volatile var planViewEnabled = false
    @Volatile var meshVisible = false
    @Volatile var pointCloudVisible = false
    @Volatile var vioCloudVisible = false

    private val ptBuf  = FloatArray(12_000 * 3)  // Neural Depth XYZ scratch
    private val vioBuf = FloatArray(500 * 3)      // VIO XYZ scratch

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
        pointCloudRenderer.init()
        vioCloudRenderer.init()
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
            val pv = planViewMatrix(); val pp = planProjMatrix()
            if (meshVisible) meshRenderer.drawPlanView(lastKnownPos)
            if (pointCloudVisible) pointCloudRenderer.draw(pv, pp)
            if (vioCloudVisible)   vioCloudRenderer.draw(pv, pp)
            floorCeilingRenderer.draw(pv, pp)
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
                if (pointCloudVisible) {
                    val count = backProjectToWorld(frameData, c2w, ptBuf)
                    pointCloudRenderer.upload(ptBuf, count)
                }

                onPoseUpdated(c2w)
            }
        }

        // ARCore VIO feature points — stable, world-anchored, sparse
        if (vioCloudVisible) {
            try {
                frame.acquirePointCloud().use { pc ->
                    val buf = pc.points  // (x, y, z, confidence) quads
                    val n = minOf(buf.remaining() / 4, 500)
                    for (i in 0 until n) {
                        vioBuf[i * 3]     = buf.get()
                        vioBuf[i * 3 + 1] = buf.get()
                        vioBuf[i * 3 + 2] = buf.get()
                        buf.get()  // skip confidence
                    }
                    vioCloudRenderer.upload(vioBuf, n)
                }
            } catch (_: Exception) { /* PointCloud not available this frame */ }
        }

        if (meshVisible) meshRenderer.draw(viewMatrix, projMatrix)
        if (pointCloudVisible) pointCloudRenderer.draw(viewMatrix, projMatrix)
        if (vioCloudVisible)   vioCloudRenderer.draw(viewMatrix, projMatrix)
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

    /**
     * Back-project subsampled depth pixels to world XYZ (stride=2).
     * Column-major c2w: world = R*cam + t where col0=[c2w[0..2]], col1=[c2w[4..6]], etc.
     */
    private fun backProjectToWorld(frame: com.robot.common.FrameData, c2w: FloatArray, out: FloatArray): Int {
        val w = frame.depthWidth; val h = frame.depthHeight
        val fx = frame.fx; val fy = frame.fy; val cx = frame.cx; val cy = frame.cy
        val depth = frame.depthValues
        val r00=c2w[0]; val r10=c2w[4]; val r20=c2w[8]
        val r01=c2w[1]; val r11=c2w[5]; val r21=c2w[9]
        val r02=c2w[2]; val r12=c2w[6]; val r22=c2w[10]
        val tx=c2w[12]; val ty=c2w[13]; val tz=c2w[14]
        var n = 0
        var v = 0
        while (v < h) {
            var u = 0
            while (u < w) {
                val raw = depth[v * w + u].toInt() and 0xFFFF
                if (raw != 0) {
                    val d = raw / 1000f
                    if (d in 0.3f..8.0f) {
                        val camX = (u - cx) / fx * d
                        val camY = -(v - cy) / fy * d
                        val camZ = -d
                        out[n * 3]     = r00*camX + r10*camY + r20*camZ + tx
                        out[n * 3 + 1] = r01*camX + r11*camY + r21*camZ + ty
                        out[n * 3 + 2] = r02*camX + r12*camY + r22*camZ + tz
                        n++
                        if (n >= 12_000) return n
                    }
                }
                u += 2
            }
            v += 2
        }
        return n
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
