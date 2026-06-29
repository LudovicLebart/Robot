package com.robot.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.TrackingState
import com.robot.depth.DepthFrameProvider
import com.robot.nav.VioMapAccumulator
import com.robot.nav.VioMapConfig
import com.robot.slam.ArSessionManager
import com.robot.slam.PoseExtractor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Central GL thread renderer.
 * - AR mode: camera background + VIO clouds + surface planes + floor/ceiling grid.
 * - Plan mode: overhead orthographic view of the same elements.
 *
 * Every [VioMapConfig.EVICT_INTERVAL_FRAMES] VIO frames, a stride-3 snapshot of the stable
 * point cloud is sent to [snapshotChannel] for async RANSAC plane extraction on Dispatchers.Default.
 * Detected planes arrive back via [pendingPlanes] (set from the main thread) and are uploaded
 * then drawn on the GL thread.
 */
class SlamRenderer(
    private val sessionManager: ArSessionManager,
    private val vioAccumulator: VioMapAccumulator,
    private val getDisplayRotation: () -> Int,
    private val onPoseUpdated: (FloatArray) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : GLSurfaceView.Renderer {

    private val backgroundRenderer    = BackgroundRenderer()
    private val floorCeilingDetector  = FloorCeilingDetector()
    private val floorCeilingRenderer  = FloorCeilingRenderer()
    private val robotMarkerRenderer   = RobotMarkerRenderer()
    private val vioCloudRenderer      = PointCloudRenderer(
        floatArrayOf(RenderConfig.VIO_CLOUD_COLOR_R, RenderConfig.VIO_CLOUD_COLOR_G,
                     RenderConfig.VIO_CLOUD_COLOR_B, RenderConfig.VIO_CLOUD_COLOR_A),
        RenderConfig.VIO_CLOUD_MAX_POINTS,
    )
    private val stableMapRenderer     = StableMapRenderer()

    private var viewMatrix = FloatArray(16)
    private var projMatrix = FloatArray(16)

    @Volatile var planViewEnabled    = false
    @Volatile var vioCloudVisible    = false
    @Volatile var stableCloudVisible = false

    // Plan view pan/zoom — written from the UI thread (touch events), read on GL thread.
    @Volatile var planPanX  = 0f
    @Volatile var planPanZ  = 0f
    @Volatile var planScale = 1f

    private val vioBuf      = FloatArray(RenderConfig.VIO_CLOUD_MAX_POINTS * 3)
    private val vioIds      = IntArray(RenderConfig.VIO_CLOUD_MAX_POINTS)
    private val vioConfs    = FloatArray(RenderConfig.VIO_CLOUD_MAX_POINTS)
    private val stableBuf   = FloatArray(VioMapConfig.MAX_STABLE_POINTS * 4)

    private var vioFrameCount = 0
    private var viewportW = 1
    private var viewportH = 1

    private var lastKnownPos      = floatArrayOf(0f, 0f, 0f)
    private var lastKnownForwardX = 0f    // horizontal camera-forward components
    private var lastKnownForwardZ = -1f   // default: facing -Z
    private var hasValidPose      = false

    /** Floor Y estimated from camera height (stable). Ceiling still comes from the depth detector. */
    private val computedFloorY: Float
        get() = if (hasValidPose) lastKnownPos[1] - RenderConfig.PHONE_HAND_HEIGHT_M
                else floorCeilingDetector.floorY ?: RenderConfig.FALLBACK_FLOOR_Y_M

    private var lastLoggedFloorY: Float?   = null
    private var lastLoggedCeilingY: Float? = null

    private var depthSuccessCount = 0
    private var depthFailCount    = 0
    private var lastTrackingState: TrackingState? = null

    private val Float.format1 get() = "%.1f".format(this)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textureId = backgroundRenderer.init()
        floorCeilingRenderer.init()
        robotMarkerRenderer.init()
        vioCloudRenderer.init()
        stableMapRenderer.init()
        sessionManager.setCameraTextureName(textureId)
        onLog("GL surface created texId=$textureId depthMode=${sessionManager.depthModeName}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportW = width
        viewportH = height
        val rotation = getDisplayRotation()
        sessionManager.setDisplayGeometry(rotation, width, height)
        onLog("GL surface ${width}x${height} rot=$rotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val frame = sessionManager.update() ?: return

        if (frame.hasDisplayGeometryChanged()) onLog("display geometry changed")

        backgroundRenderer.updateUVsIfNeeded(frame)

        if (planViewEnabled) {
            GLES30.glClearColor(RenderConfig.PLAN_BG_R, RenderConfig.PLAN_BG_G, RenderConfig.PLAN_BG_B, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val pv = planViewMatrix(); val pp = planProjMatrix()
            if (vioCloudVisible)     vioCloudRenderer.draw(pv, pp)
            if (stableCloudVisible)  stableMapRenderer.draw(pv, pp,
                computedFloorY,
                floorCeilingDetector.ceilingY ?: RenderConfig.FALLBACK_CEILING_Y_M)
            floorCeilingRenderer.draw(pv, pp)
            robotMarkerRenderer.draw(pv, pp)
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
            // Camera forward in world = -col2 of c2w (column-major: col2 = indices 8,9,10)
            lastKnownForwardX = -c2w[8]
            lastKnownForwardZ = -c2w[10]
            hasValidPose = true

            val frameData = DepthFrameProvider.extract(frame, c2w) { err ->
                depthFailCount++
                if (depthFailCount == 1 || depthFailCount % RenderConfig.LOG_THROTTLE_FRAMES == 0) {
                    onLog("depth FAIL #$depthFailCount: $err")
                }
            }
            if (frameData != null) {
                floorCeilingDetector.update(frameData)
                floorCeilingRenderer.updatePlanes(
                    computedFloorY,
                    floorCeilingDetector.ceilingY,
                    lastKnownPos[0],
                    lastKnownPos[2],
                )
                robotMarkerRenderer.update(
                    lastKnownPos[0], lastKnownPos[2],
                    lastKnownForwardX, lastKnownForwardZ,
                    computedFloorY,
                )
                logPlanesIfChanged()

                depthSuccessCount++
                val w = frameData.depthWidth; val h = frameData.depthHeight
                val centerMm = frameData.depthValues[(h/2)*w + w/2].toInt() and 0xFFFF
                val validPct = frameData.depthValues.count { it.toInt() != 0 } * 100 / (w * h)

                if (depthSuccessCount == 1 || depthSuccessCount % RenderConfig.LOG_THROTTLE_FRAMES == 0) {
                    val px = c2w[12]; val py = c2w[13]; val pz = c2w[14]
                    if (depthSuccessCount == 1) {
                        onLog("depth FIRST ${w}x${h} fx=${frameData.fx.toInt()} fy=${frameData.fy.toInt()} " +
                              "cx=${frameData.cx.toInt()} cy=${frameData.cy.toInt()}")
                    }
                    onLog("depth #$depthSuccessCount center=${centerMm}mm valid=${validPct}% " +
                          "pos=(${px.format1}/${py.format1}/${pz.format1})m")
                }
                onPoseUpdated(c2w)
            }
        }

        // ARCore VIO feature points — always accumulate, render conditionally.
        try {
            frame.acquirePointCloud().use { pc ->
                val buf   = pc.points
                val idBuf = pc.ids
                val n = minOf(buf.remaining() / 4, RenderConfig.VIO_CLOUD_MAX_POINTS)
                for (i in 0 until n) {
                    vioBuf[i * 3]     = buf.get()
                    vioBuf[i * 3 + 1] = buf.get()
                    vioBuf[i * 3 + 2] = buf.get()
                    vioConfs[i]        = buf.get()
                    vioIds[i]          = idBuf.get()
                }
                vioAccumulator.update(vioBuf, vioIds, vioConfs, n)
                vioFrameCount++
                if (vioFrameCount % RenderConfig.LOG_THROTTLE_FRAMES == 0) {
                    onLog("VIO map: total=${vioAccumulator.totalCount()} stable=${vioAccumulator.stableCount()} pts=$n demoted/cycle=${vioAccumulator.demotionCount()}")
                }
                if (vioCloudVisible) vioCloudRenderer.upload(vioBuf, n)
                if (stableCloudVisible) {
                    val sn = vioAccumulator.getStablePointsWeighted(stableBuf)
                    stableMapRenderer.upload(stableBuf, sn)
                }
            }
        } catch (_: Exception) { /* PointCloud not available this frame */ }

        if (vioCloudVisible)     vioCloudRenderer.draw(viewMatrix, projMatrix)
        if (stableCloudVisible)  stableMapRenderer.draw(viewMatrix, projMatrix,
            computedFloorY,
            floorCeilingDetector.ceilingY ?: RenderConfig.FALLBACK_CEILING_Y_M)
        floorCeilingRenderer.draw(viewMatrix, projMatrix)
    }

    private fun planViewMatrix(): FloatArray {
        val view = FloatArray(16)
        android.opengl.Matrix.setLookAtM(view, 0,
            planPanX, RenderConfig.PLAN_VIEW_EYE_HEIGHT_M, planPanZ,
            planPanX, 0f, planPanZ,
            0f, 0f, -1f,
        )
        return view
    }

    private fun planProjMatrix(): FloatArray {
        val proj = FloatArray(16)
        val hZ = RenderConfig.PLAN_VIEW_ORTHO_HALF_EXTENT_M / planScale
        val hX = hZ * viewportW.toFloat() / viewportH.toFloat()
        android.opengl.Matrix.orthoM(proj, 0, -hX, hX, -hZ, hZ,
            RenderConfig.PLAN_VIEW_NEAR_M, RenderConfig.PLAN_VIEW_FAR_M)
        return proj
    }

    private fun logPlanesIfChanged() {
        val floor   = computedFloorY
        val ceiling = floorCeilingDetector.ceilingY
        val floorChanged = lastLoggedFloorY == null ||
            kotlin.math.abs(floor - lastLoggedFloorY!!) > RenderConfig.PLANE_LOG_THRESHOLD_M
        val ceilingChanged = ceiling != null &&
            (lastLoggedCeilingY == null || kotlin.math.abs(ceiling - lastLoggedCeilingY!!) > RenderConfig.PLANE_LOG_THRESHOLD_M)
        if (floorChanged || ceilingChanged) {
            lastLoggedFloorY   = floor
            lastLoggedCeilingY = ceiling
            val c = ceiling?.let { "%.2f".format(it) } ?: "?"
            onLog("floor(cam)=${"%.2f".format(floor)}m ceiling(det)=${c}m")
        }
    }
}
