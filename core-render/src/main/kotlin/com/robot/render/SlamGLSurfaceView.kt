package com.robot.render

import android.content.Context
import android.opengl.GLSurfaceView

class SlamGLSurfaceView(context: Context) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
    }

    fun setRenderer(renderer: SlamRenderer) {
        super.setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
