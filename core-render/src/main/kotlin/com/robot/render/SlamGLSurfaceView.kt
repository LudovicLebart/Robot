package com.robot.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class SlamGLSurfaceView(context: Context) : GLSurfaceView(context) {

    private var renderer: SlamRenderer? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val r = renderer ?: return false
                r.planScale = (r.planScale * detector.scaleFactor)
                    .coerceIn(RenderConfig.PLAN_VIEW_SCALE_MIN, RenderConfig.PLAN_VIEW_SCALE_MAX)
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float,
            ): Boolean {
                val r = renderer ?: return false
                // hZ = world half-extent in Z (= height direction).
                // After aspect correction, both axes have the same world-per-pixel ratio.
                val worldPerPx = 2f * RenderConfig.PLAN_VIEW_ORTHO_HALF_EXTENT_M / (r.planScale * height)
                r.planPanX -= distanceX * worldPerPx
                r.planPanZ -= distanceY * worldPerPx
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
    }

    fun setRenderer(renderer: SlamRenderer) {
        this.renderer = renderer
        super.setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (renderer?.planViewEnabled != true) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
