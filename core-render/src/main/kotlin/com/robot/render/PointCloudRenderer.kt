package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a world-space point cloud as GL_POINTS.
 * Color and capacity are set at construction time so the same class serves
 * both Neural-Depth back-projection (yellow, 12 000 pts) and ARCore VIO
 * feature points (cyan, 500 pts).
 */
class PointCloudRenderer(
    private val color: FloatArray = floatArrayOf(1f, 0.85f, 0f, 1f),  // default yellow
    private val maxPoints: Int    = 12_000,
) {

    private val vertSrc = """
        #version 300 es
        uniform mat4 uMVP;
        in vec3 aPosition;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            gl_PointSize = clamp(6.0 / gl_Position.w, 2.0, 12.0);
        }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    """.trimIndent()

    private var program = 0
    private var locMVP = -1
    private var locColor = -1
    private var locPosition = -1
    private var vao = 0
    private var vbo = 0
    private var pointCount = 0

    private val cpuBuf = ByteBuffer.allocateDirect(maxPoints * 3 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun init() {
        program = ShaderUtil.createProgram(vertSrc, fragSrc)
        locMVP      = GLES30.glGetUniformLocation(program, "uMVP")
        locColor    = GLES30.glGetUniformLocation(program, "uColor")
        locPosition = GLES30.glGetAttribLocation(program, "aPosition")

        val arr = IntArray(1)
        GLES30.glGenVertexArrays(1, arr, 0); vao = arr[0]
        GLES30.glGenBuffers(1, arr, 0);      vbo = arr[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxPoints * 3 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Upload world-space XYZ points. Must be called on the GL thread. */
    fun upload(pts: FloatArray, count: Int) {
        pointCount = minOf(count, maxPoints)
        if (pointCount == 0) return
        cpuBuf.clear()
        cpuBuf.put(pts, 0, pointCount * 3)
        cpuBuf.position(0)
        cpuBuf.limit(pointCount * 3)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, pointCount * 3 * 4, cpuBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw(view: FloatArray, proj: FloatArray) {
        if (pointCount == 0) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glUniform4fv(locColor, 1, color, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        GLES30.glBindVertexArray(0)
    }
}
