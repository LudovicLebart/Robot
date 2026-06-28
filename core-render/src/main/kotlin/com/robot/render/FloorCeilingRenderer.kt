package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders axis-aligned wireframe grids for the detected floor and ceiling planes.
 *
 * Each plane is a 10 m × 10 m grid (lines every 0.5 m) centred on the camera's
 * current XZ position. Floor is green, ceiling is blue. Drawn with GL_LINES.
 *
 * Geometry for both planes lives in one VBO: floor vertices first, then ceiling.
 * Two draw calls select the range and supply the tint via a uniform.
 *
 * All methods must be called on the GL thread.
 */
class FloorCeilingRenderer {

    companion object {
        private const val HALF_EXTENT = 5.0f     // 10 m grid → ±5 m
        private const val STEP = 0.5f            // line spacing
        private const val LINES_PER_AXIS = 21    // (2*5 / 0.5) + 1
        // 21 lines ∥X + 21 lines ∥Z = 42 lines, each 2 verts, each vert 3 floats.
        private const val VERTS_PER_PLANE = LINES_PER_AXIS * 2 * 2     // 84
        private const val FLOATS_PER_PLANE = VERTS_PER_PLANE * 3       // 252
    }

    private var program = 0
    private var vbo = 0
    private var vao = 0

    private var locMVP = -1
    private var locTint = -1
    private var locPosition = -1

    private var floorVertCount = 0
    private var ceilingVertCount = 0

    // Scratch CPU buffer for both planes (floor block then ceiling block).
    private val cpuBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FLOATS_PER_PLANE * 2 * 4)
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
        // Reserve worst-case storage; data filled by updatePlanes().
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            FLOATS_PER_PLANE * 2 * 4,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Rebuild grid geometry for the current plane heights and camera XZ centre.
     * Pass null for a plane that has not been detected yet — it is simply skipped.
     */
    fun updatePlanes(floorY: Float?, ceilingY: Float?, centerX: Float, centerZ: Float) {
        cpuBuffer.clear()

        floorVertCount = if (floorY != null) {
            appendGrid(floorY, centerX, centerZ)
            VERTS_PER_PLANE
        } else 0

        ceilingVertCount = if (ceilingY != null) {
            appendGrid(ceilingY, centerX, centerZ)
            VERTS_PER_PLANE
        } else 0

        val totalFloats = (floorVertCount + ceilingVertCount) * 3
        if (totalFloats == 0) return

        cpuBuffer.position(0)
        cpuBuffer.limit(totalFloats)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, totalFloats * 4, cpuBuffer)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Append one plane's 42 grid lines (84 verts) into [cpuBuffer] at its current position. */
    private fun appendGrid(y: Float, cx: Float, cz: Float) {
        var i = 0
        while (i < LINES_PER_AXIS) {
            val offset = -HALF_EXTENT + i * STEP
            // Line parallel to Z (constant X = cx + offset).
            val x = cx + offset
            cpuBuffer.put(x).put(y).put(cz - HALF_EXTENT)
            cpuBuffer.put(x).put(y).put(cz + HALF_EXTENT)
            // Line parallel to X (constant Z = cz + offset).
            val z = cz + offset
            cpuBuffer.put(cx - HALF_EXTENT).put(y).put(z)
            cpuBuffer.put(cx + HALF_EXTENT).put(y).put(z)
            i++
        }
    }

    /** Draw both detected grids. No-op for planes that have no geometry. */
    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (floorVertCount == 0 && ceilingVertCount == 0) return

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)

        // Keep depth test so grids sit correctly in the scene, but do not write
        // depth so the (opaque) mesh is never occluded by an invisible grid plane.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glBindVertexArray(vao)

        if (floorVertCount > 0) {
            GLES30.glUniform3f(locTint, 0.2f, 0.9f, 0.2f)   // floor: green
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, floorVertCount)
        }
        if (ceilingVertCount > 0) {
            GLES30.glUniform3f(locTint, 0.3f, 0.5f, 1.0f)   // ceiling: blue
            GLES30.glDrawArrays(GLES30.GL_LINES, floorVertCount, ceilingVertCount)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
    }
}
