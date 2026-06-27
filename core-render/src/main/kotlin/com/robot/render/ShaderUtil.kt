package com.robot.render

import android.opengl.GLES30
import android.util.Log

object ShaderUtil {

    private const val TAG = "ShaderUtil"

    fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vert = compileShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vert)
        GLES30.glAttachShader(prog, frag)
        GLES30.glLinkProgram(prog)
        GLES30.glDeleteShader(vert)
        GLES30.glDeleteShader(frag)

        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val info = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            error("Shader program link failed: $info")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES30.GL_FALSE) {
            val info = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed (type=$type): $info")
        }
        return shader
    }
}
