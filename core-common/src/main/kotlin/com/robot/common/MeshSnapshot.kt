package com.robot.common

import java.nio.FloatBuffer

/**
 * Extracted mesh from TSDF Marching Cubes.
 * Backed by direct FloatBuffers (no GC pressure, zero-copy upload to OpenGL VBO).
 */
data class MeshSnapshot(
    val vertices: FloatBuffer,   // x,y,z per vertex
    val normals: FloatBuffer,    // nx,ny,nz per vertex
    val vertexCount: Int,
)

object EmptyMesh : Any() {
    val snapshot = MeshSnapshot(
        vertices = FloatBuffer.allocate(0),
        normals = FloatBuffer.allocate(0),
        vertexCount = 0,
    )
}
