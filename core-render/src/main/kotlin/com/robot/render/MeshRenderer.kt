package com.robot.render

import android.opengl.GLES30
import com.robot.common.MeshSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders the TSDF mesh as a lit 3D overlay using OpenGL ES 3.0. */
class MeshRenderer {

    private var program = 0
    private var vboVertices = 0
    private var vboNormals = 0
    private var vao = 0
    private var currentVertexCount = 0

    private val vertSrc = """
        #version 300 es
        uniform mat4 uMVP;
        uniform mat4 uMV;
        in vec3 aPosition;
        in vec3 aNormal;
        out vec3 vNormalView;
        void main() {
            gl_Position = uMVP * vec4(aPosition, 1.0);
            vNormalView = mat3(uMV) * aNormal;
        }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        in vec3 vNormalView;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormalView);
            float diffuse = max(dot(n, vec3(0.0, 0.0, 1.0)), 0.0);
            fragColor = vec4(vec3(0.2 + 0.8 * diffuse) * vec3(0.4, 0.8, 1.0), 0.7);
        }
    """.trimIndent()

    fun init() {
        program = ShaderUtil.createProgram(vertSrc, fragSrc)

        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        vao = vaoArr[0]

        val vboArr = IntArray(2)
        GLES30.glGenBuffers(2, vboArr, 0)
        vboVertices = vboArr[0]
        vboNormals  = vboArr[1]
    }

    /** Upload a new mesh snapshot (double-buffer: swap happens here). Call on GL thread. */
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
        currentVertexCount = snapshot.vertexCount
    }

    fun draw(viewMatrix: FloatArray, projMatrix: FloatArray) {
        if (currentVertexCount == 0) return

        val mvp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(mvp, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(program)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMV"),  1, false, viewMatrix, 0)

        val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
        val norLoc = GLES30.glGetAttribLocation(program, "aNormal")

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboVertices)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboNormals)
        GLES30.glEnableVertexAttribArray(norLoc)
        GLES30.glVertexAttribPointer(norLoc, 3, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, currentVertexCount)
        GLES30.glDisable(GLES30.GL_BLEND)
    }
}
