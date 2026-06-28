package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.robot.common.MeshSnapshot

/** Renders the TSDF mesh as a lit 3D overlay using OpenGL ES 3.0. */
class MeshRenderer {

    private var program = 0
    private var vboVertices = 0
    private var vboNormals = 0
    private var vao = 0
    private var currentVertexCount = 0

    private var locMVP      = -1
    private var locMV       = -1
    private var locTint     = -1
    private var locAlpha    = -1
    private var locPosition = -1
    private var locNormal   = -1

    private val vertSrc = """
        #version 300 es
        uniform mat4 uMVP;
        uniform mat4 uMV;
        in vec3 aPosition;
        in vec3 aNormal;
        out vec3 vNormalView;
        void main() {
            gl_Position  = uMVP * vec4(aPosition, 1.0);
            vNormalView  = mat3(uMV) * aNormal;
        }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        uniform vec3  uTint;
        uniform float uAlpha;
        in vec3 vNormalView;
        out vec4 fragColor;
        void main() {
            vec3  n       = normalize(vNormalView);
            float diffuse = max(dot(n, vec3(0.0, 0.0, 1.0)), 0.15);
            fragColor     = vec4(uTint * diffuse, uAlpha);
        }
    """.trimIndent()

    fun init() {
        program = ShaderUtil.createProgram(vertSrc, fragSrc)

        locMVP      = GLES30.glGetUniformLocation(program, "uMVP")
        locMV       = GLES30.glGetUniformLocation(program, "uMV")
        locTint     = GLES30.glGetUniformLocation(program, "uTint")
        locAlpha    = GLES30.glGetUniformLocation(program, "uAlpha")
        locPosition = GLES30.glGetAttribLocation(program, "aPosition")
        locNormal   = GLES30.glGetAttribLocation(program, "aNormal")

        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        vao = vaoArr[0]

        val vboArr = IntArray(2)
        GLES30.glGenBuffers(2, vboArr, 0)
        vboVertices = vboArr[0]
        vboNormals  = vboArr[1]

        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboVertices)
        GLES30.glEnableVertexAttribArray(locPosition)
        GLES30.glVertexAttribPointer(locPosition, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glEnableVertexAttribArray(locNormal)
        GLES30.glVertexAttribPointer(locNormal, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Upload a new mesh snapshot. Call on the GL thread. */
    fun uploadMesh(snapshot: MeshSnapshot) {
        if (snapshot.vertexCount == 0) {
            currentVertexCount = 0
            return
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboVertices)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            snapshot.vertexCount * 3 * 4,
            snapshot.vertices.rewind() as java.nio.FloatBuffer,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            snapshot.vertexCount * 3 * 4,
            snapshot.normals.rewind() as java.nio.FloatBuffer,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        currentVertexCount = snapshot.vertexCount
    }

    /**
     * Draw the mesh in AR (camera pass-through) mode.
     * Opaque cyan tint so it's clearly visible over the camera feed.
     */
    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray) {
        drawInternal(viewMatrix, projMatrix,
            tintR = 0.3f, tintG = 0.9f, tintB = 0.6f, alpha = 1.0f)
    }

    /**
     * Draw the mesh for the top-down plan view.
     * Always shows the full TSDF grid (±3 m in X/Z) from a fixed overhead camera
     * so the map remains visible even when the robot walks outside the grid bounds.
     * [cameraPos] is currently unused but kept for a future "locate robot" marker.
     */
    fun drawPlanView(@Suppress("UNUSED_PARAMETER") cameraPos: FloatArray) {
        if (currentVertexCount == 0) return

        val view = FloatArray(16)
        // Eye fixed 8 m above the grid centre (0, 0, 0), looking straight down.
        Matrix.setLookAtM(view, 0,
            0f, 8f, 0f,   // eye: directly above grid centre
            0f, 0f, 0f,   // look at grid centre
            0f, 0f, -1f,  // north = world –Z
        )

        val proj = FloatArray(16)
        // Tight ortho covering the full TSDF grid extent (6 m × 6 m + 10 % margin)
        Matrix.orthoM(proj, 0, -3.3f, 3.3f, -3.3f, 3.3f, 0.5f, 16f)

        drawInternal(view, proj,
            tintR = 0.2f, tintG = 1.0f, tintB = 0.4f, alpha = 1.0f)
    }

    private fun drawInternal(
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        tintR: Float, tintG: Float, tintB: Float,
        alpha: Float,
    ) {
        if (currentVertexCount == 0) return

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)

        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(locMV,  1, false, viewMatrix, 0)
        GLES30.glUniform3f(locTint, tintR, tintG, tintB)
        GLES30.glUniform1f(locAlpha, alpha)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, currentVertexCount)
        GLES30.glBindVertexArray(0)
    }
}
