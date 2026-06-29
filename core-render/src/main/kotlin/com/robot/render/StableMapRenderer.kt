package com.robot.render

import android.opengl.GLES30
import android.opengl.Matrix
import com.robot.nav.VioMapConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the accumulated stable VIO map as GL_POINTS with:
 *  - Color gradient by height Y: green (floor) → red (mid) → blue (ceiling)
 *    anchored to actual floorY/ceilingY passed at draw time.
 *  - Point size by observation count: small=newly stable, large=highly confirmed.
 *
 * Upload format: stride-4 FloatArray [x, y, z, observationCount].
 * All numeric parameters come from RenderConfig — no inline literals.
 */
class StableMapRenderer(private val maxPoints: Int = VioMapConfig.MAX_STABLE_POINTS) {

    private val vertSrc = """
        #version 300 es
        #define SIZE_MIN    ${RenderConfig.STABLE_POINT_SIZE_MIN_PX}
        #define SIZE_MAX    ${RenderConfig.STABLE_POINT_SIZE_MAX_PX}
        #define SIZE_SCALE  ((SIZE_MAX - SIZE_MIN) / ${RenderConfig.STABLE_POINT_SIZE_MAX_COUNT})
        #define HEIGHT_MIN  ${RenderConfig.STABLE_HEIGHT_RANGE_MIN_M}
        uniform mat4  uMVP;
        uniform float uFloorY;
        uniform float uCeilingY;
        in vec4  aPositionW;
        out vec3 vColor;
        void main() {
            gl_Position = uMVP * vec4(aPositionW.xyz, 1.0);
            float range = max(uCeilingY - uFloorY, HEIGHT_MIN);
            float t = clamp((aPositionW.y - uFloorY) / range, 0.0, 1.0);
            vec3 green = vec3(${RenderConfig.STABLE_COLOR_FLOOR_R},   ${RenderConfig.STABLE_COLOR_FLOOR_G},   ${RenderConfig.STABLE_COLOR_FLOOR_B});
            vec3 red   = vec3(${RenderConfig.STABLE_COLOR_MID_R},     ${RenderConfig.STABLE_COLOR_MID_G},     ${RenderConfig.STABLE_COLOR_MID_B});
            vec3 blue  = vec3(${RenderConfig.STABLE_COLOR_CEILING_R}, ${RenderConfig.STABLE_COLOR_CEILING_G}, ${RenderConfig.STABLE_COLOR_CEILING_B});
            vColor = t < 0.5
                ? mix(green, red,  t * 2.0)
                : mix(red,   blue, (t - 0.5) * 2.0);
            gl_PointSize = clamp(SIZE_MIN + aPositionW.w * SIZE_SCALE, SIZE_MIN, SIZE_MAX);
        }
    """.trimIndent()

    private val fragSrc = """
        #version 300 es
        precision mediump float;
        in vec3 vColor;
        out vec4 fragColor;
        void main() {
            vec2 c = gl_PointCoord - vec2(0.5);
            if (dot(c, c) > ${RenderConfig.POINT_CIRCLE_DISCARD_R2}) discard;
            fragColor = vec4(vColor, 1.0);
        }
    """.trimIndent()

    private var program     = 0
    private var locMVP      = -1
    private var locPosW     = -1
    private var locFloorY   = -1
    private var locCeilingY = -1
    private var vao         = 0
    private var vbo         = 0
    private var pointCount  = 0

    private val cpuBuf = ByteBuffer.allocateDirect(maxPoints * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun init() {
        program     = ShaderUtil.createProgram(vertSrc, fragSrc)
        locMVP      = GLES30.glGetUniformLocation(program, "uMVP")
        locFloorY   = GLES30.glGetUniformLocation(program, "uFloorY")
        locCeilingY = GLES30.glGetUniformLocation(program, "uCeilingY")
        locPosW     = GLES30.glGetAttribLocation(program, "aPositionW")

        val arr = IntArray(1)
        GLES30.glGenVertexArrays(1, arr, 0); vao = arr[0]
        GLES30.glGenBuffers(1, arr, 0);      vbo = arr[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxPoints * 4 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(locPosW)
        GLES30.glVertexAttribPointer(locPosW, 4, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Upload stride-4 points [x,y,z,count]. Must be called on the GL thread. */
    fun upload(pts: FloatArray, count: Int) {
        pointCount = minOf(count, maxPoints)
        if (pointCount == 0) return
        cpuBuf.clear()
        cpuBuf.put(pts, 0, pointCount * 4)
        cpuBuf.position(0)
        cpuBuf.limit(pointCount * 4)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, pointCount * 4 * 4, cpuBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw(view: FloatArray, proj: FloatArray, floorY: Float, ceilingY: Float) {
        if (pointCount == 0) return
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(locMVP, 1, false, mvp, 0)
        GLES30.glUniform1f(locFloorY,   floorY)
        GLES30.glUniform1f(locCeilingY, ceilingY)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        GLES30.glBindVertexArray(0)
    }
}
