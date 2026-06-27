package com.robot.render

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders the ARCore camera feed as a full-screen background quad. */
class BackgroundRenderer {

    private var positionVbo = 0
    private var uvVbo = 0
    private var quadVao = 0
    private var program = 0
    private var textureId = 0

    private var locPos = -1
    private var locTex = -1
    private var locTexture = -1

    // Fixed NDC positions for the full-screen quad (triangle strip order)
    private val positions = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )

    // These NDC coords are fed to transformCoordinates2d to produce correct UVs
    private val ndcForTransform = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f,
    )
    private val transformedUvs = FloatArray(8)

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
        locPos     = GLES30.glGetAttribLocation(program, "aPos")
        locTex     = GLES30.glGetAttribLocation(program, "aTexCoord")
        locTexture = GLES30.glGetUniformLocation(program, "uTexture")

        val vboArr = IntArray(2)
        GLES30.glGenBuffers(2, vboArr, 0)
        positionVbo = vboArr[0]
        uvVbo       = vboArr[1]

        // Upload static vertex positions
        val posBuf = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        posBuf.put(positions).rewind()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, posBuf.capacity() * 4, posBuf, GLES30.GL_STATIC_DRAW)

        // Allocate UV buffer — content filled by transformCoordinates2d on first draw
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, null, GLES30.GL_DYNAMIC_DRAW)

        // Set up VAO once
        val vaoArr = IntArray(1)
        GLES30.glGenVertexArrays(1, vaoArr, 0)
        quadVao = vaoArr[0]
        GLES30.glBindVertexArray(quadVao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionVbo)
        GLES30.glEnableVertexAttribArray(locPos)
        GLES30.glVertexAttribPointer(locPos, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
        GLES30.glEnableVertexAttribArray(locTex)
        GLES30.glVertexAttribPointer(locTex, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        textureId = texArr[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        return textureId
    }

    fun draw(frame: Frame) {
        // Recompute UVs whenever display geometry changes (rotation, resize)
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                ndcForTransform,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedUvs,
            )
            val uvBuf = ByteBuffer.allocateDirect(transformedUvs.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            uvBuf.put(transformedUvs).rewind()
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, uvBuf.capacity() * 4, uvBuf, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(locTexture, 0)

        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }
}
