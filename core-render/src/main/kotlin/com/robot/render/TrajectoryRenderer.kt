package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the camera path as a sequence of world-space positions and renders it
 * as a white GL_LINE_STRIP in the plan view.
 *
 * A new point is appended only when the camera has moved at least
 * [RenderConfig.TRAJECTORY_MIN_DIST_M] from the previous recorded position.
 * The buffer is fixed-size; recording stops silently when full.
 *
 * All methods must be called on the GL thread.
 */
class TrajectoryRenderer {

    private val vertSrc = """
        #version 300 es
        uniform mat4 uMVP;
        in vec3 aPosition;
        void main() { gl_Position = uMVP * vec4(aPosition, 1.0); }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() { fragColor = uColor; }
    """.trimIndent()

    private var program     = 0
    private var locMVP      = 0
    private var locColor    = 0
    private var locPosition = 0
    private var vao         = 0
    private var vbo         = 0

    private val maxPts = RenderConfig.TRAJECTORY_MAX_POINTS
    private val minDist2 = RenderConfig.TRAJECTORY_MIN_DIST_M * RenderConfig.TRAJECTORY_MIN_DIST_M

    /** CPU-side ring of (x,y,z) positions, stride 3. */
    private val cpuBuf = ByteBuffer.allocateDirect(maxPts * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var count = 0   // number of valid points written so far

    fun init() {
        program     = ShaderUtil.createProgram(vertSrc, fragSrc)
        locMVP      = GLES30.glGetUniformLocation(program, "uMVP")
        locColor    = GLES30.glGetUniformLocation(program, "uColor")
        locPosition = GLES30.glGetAttribLocation(program, "aPosition")

        val arr = IntArray(1)
        GLES30.glGenVertexArrays(1, arr, 0); vao = arr[0]
        GLES30.glGenBuffers(1, arr, 0);      vbo = arr[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxPts * 3 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Append a new camera position if it is far enough from the previous one.
     * Skips silently when the buffer is full.
     * Must be called on the GL thread.
     */
    fun addPoint(x: Float, y: Float, z: Float) {
        if (count >= maxPts) return
        if (count > 0) {
            val px = cpuBuf[(count - 1) * 3]
            val pz = cpuBuf[(count - 1) * 3 + 2]
            val dx = x - px; val dz = z - pz
            if (dx * dx + dz * dz < minDist2) return
        }
        val offset = count * 3
        cpuBuf.put(offset,     x)
        cpuBuf.put(offset + 1, y)
        cpuBuf.put(offset + 2, z)

        // Upload only the new point (incremental, avoids re-uploading the whole buffer).
        cpuBuf.position(offset).limit(offset + 3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, offset * 4, 3 * 4, cpuBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        cpuBuf.clear()

        count++
    }

    fun draw(view: FloatArray, proj: FloatArray) {
        if (count < 2) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glUniform4f(locColor,
            RenderConfig.TRAJECTORY_COLOR_R, RenderConfig.TRAJECTORY_COLOR_G,
            RenderConfig.TRAJECTORY_COLOR_B, RenderConfig.TRAJECTORY_COLOR_A)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
        GLES30.glBindVertexArray(0)
    }
}
