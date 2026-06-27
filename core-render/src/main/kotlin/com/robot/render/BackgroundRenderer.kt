package com.robot.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Frame

/** Renders the ARCore camera feed as a full-screen background quad. */
class BackgroundRenderer {

    private var quadVao = 0
    private var quadVbo = 0
    private var program = 0
    private var textureId = 0

    private val vertices = floatArrayOf(
        // X,    Y,   U,    V
        -1f, -1f, 0f, 1f,
         1f, -1f, 1f, 1f,
        -1f,  1f, 0f, 0f,
         1f,  1f, 1f, 0f,
    )

    private val vertexSrc = """
        #version 300 es
        in vec2 aPos;
        in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentSrc = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        in vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    fun init(): Int {
        program = ShaderUtil.createProgram(vertexSrc, fragmentSrc)

        val vboArr = IntArray(1)
        GLES30.glGenBuffers(1, vboArr, 0)
        quadVbo = vboArr[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        val buf = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(vertices).rewind()
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, buf.capacity() * 4, buf, GLES30.GL_STATIC_DRAW)

        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        quadVao = vaoArr[0]

        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        textureId = texArr[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        return textureId
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            // ARCore has updated the display geometry — nothing extra needed here
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)

        val stride = 4 * 4
        val posLoc = GLES30.glGetAttribLocation(program, "aPos")
        val texLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }
}
