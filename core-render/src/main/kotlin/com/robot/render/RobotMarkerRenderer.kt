package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Renders a filled arrow triangle in the plan view at the robot's current position.
 * The tip points in the camera's horizontal heading direction.
 * Called on the GL thread only.
 */
class RobotMarkerRenderer {

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
    private var hasData     = false

    private val cpuBuf = ByteBuffer.allocateDirect(3 * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

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
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 3 * 3 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Rebuild the arrow triangle from the robot's world position and heading.
     * [fwdX]/[fwdZ] are the horizontal components of the camera-forward vector (need not be normalised).
     * Must be called on the GL thread.
     */
    fun update(posX: Float, posZ: Float, fwdX: Float, fwdZ: Float, floorY: Float) {
        val r   = RenderConfig.ROBOT_MARKER_RADIUS_M
        val y   = floorY + RenderConfig.ROBOT_MARKER_FLOOR_OFFSET_M
        val len = sqrt(fwdX * fwdX + fwdZ * fwdZ).coerceAtLeast(1e-6f)
        // Rotation that takes local (0, -1) → (fwdX, fwdZ)/len
        val cosA = -fwdZ / len   // cos of rotation angle
        val sinA =  fwdX / len   // sin of rotation angle

        // Rotate a local XZ point, then translate.
        fun rx(lx: Float, lz: Float) = cosA * lx - sinA * lz + posX
        fun rz(lx: Float, lz: Float) = sinA * lx + cosA * lz + posZ

        cpuBuf.rewind()
        cpuBuf.put(rx(0f, -r));               cpuBuf.put(y); cpuBuf.put(rz(0f, -r))          // tip
        cpuBuf.put(rx(-r * 0.55f, r * 0.5f)); cpuBuf.put(y); cpuBuf.put(rz(-r * 0.55f, r * 0.5f)) // left
        cpuBuf.put(rx( r * 0.55f, r * 0.5f)); cpuBuf.put(y); cpuBuf.put(rz( r * 0.55f, r * 0.5f)) // right
        cpuBuf.rewind()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 3 * 3 * 4, cpuBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        hasData = true
    }

    fun draw(view: FloatArray, proj: FloatArray) {
        if (!hasData) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glUniform4f(locColor,
            RenderConfig.ROBOT_MARKER_COLOR_R, RenderConfig.ROBOT_MARKER_COLOR_G,
            RenderConfig.ROBOT_MARKER_COLOR_B, RenderConfig.ROBOT_MARKER_COLOR_A)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }
}
