package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders axis-aligned wireframe grids for the detected floor and ceiling planes.
 * Grid geometry and colors come from RenderConfig — no inline literals.
 */
class FloorCeilingRenderer {

    private val linesPerAxis = ((2 * RenderConfig.GRID_HALF_EXTENT_M / RenderConfig.GRID_STEP_M) + 1).toInt()
    private val vertsPerPlane = linesPerAxis * 2 * 2
    private val floatsPerPlane = vertsPerPlane * 3

    private var program = 0
    private var vbo = 0
    private var vao = 0

    private var locMVP = -1
    private var locTint = -1
    private var locPosition = -1

    private var floorVertCount = 0
    private var ceilingVertCount = 0

    private val cpuBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(floatsPerPlane * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val vertSrc = """
        #version 300 es
        uniform mat4 uMVP;
        in vec3 aPosition;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        uniform vec3 uTint;
        out vec4 fragColor;
        void main() {
            fragColor = vec4(uTint, 1.0);
        }
    """.trimIndent()

    fun init() {
        program = ShaderUtil.createProgram(vertSrc, fragSrc)
        locMVP = GLES30.glGetUniformLocation(program, "uMVP")
        locTint = GLES30.glGetUniformLocation(program, "uTint")
        locPosition = GLES30.glGetAttribLocation(program, "aPosition")

        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        vao = vaoArr[0]

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        vbo = vboArr[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            floatsPerPlane * 2 * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun updatePlanes(floorY: Float?, ceilingY: Float?, centerX: Float, centerZ: Float) {
        cpuBuffer.clear()

        floorVertCount = if (floorY != null) {
            appendGrid(floorY, centerX, centerZ)
            vertsPerPlane
        } else 0

        ceilingVertCount = if (ceilingY != null) {
            appendGrid(ceilingY, centerX, centerZ)
            vertsPerPlane
        } else 0

        val totalFloats = (floorVertCount + ceilingVertCount) * 3
        if (totalFloats == 0) return

        cpuBuffer.position(0)
        cpuBuffer.limit(totalFloats)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, totalFloats * 4, cpuBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun appendGrid(y: Float, cx: Float, cz: Float) {
        val half = RenderConfig.GRID_HALF_EXTENT_M
        val step = RenderConfig.GRID_STEP_M
        var i = 0
        while (i < linesPerAxis) {
            val offset = -half + i * step
            val x = cx + offset
            cpuBuffer.put(x).put(y).put(cz - half)
            cpuBuffer.put(x).put(y).put(cz + half)
            val z = cz + offset
            cpuBuffer.put(cx - half).put(y).put(z)
            cpuBuffer.put(cx + half).put(y).put(z)
            i++
        }
    }

    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (floorVertCount == 0 && ceilingVertCount == 0) return

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(vao)

        if (floorVertCount > 0) {
            GLES30.glUniform3f(locTint,
                RenderConfig.GRID_FLOOR_TINT_R, RenderConfig.GRID_FLOOR_TINT_G, RenderConfig.GRID_FLOOR_TINT_B)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, floorVertCount)
        }
        if (ceilingVertCount > 0) {
            GLES30.glUniform3f(locTint,
                RenderConfig.GRID_CEILING_TINT_R, RenderConfig.GRID_CEILING_TINT_G, RenderConfig.GRID_CEILING_TINT_B)
            GLES30.glDrawArrays(GLES30.GL_LINES, floorVertCount, ceilingVertCount)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }
}
