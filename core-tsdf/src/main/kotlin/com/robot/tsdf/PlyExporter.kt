package com.robot.tsdf

import com.robot.common.MeshSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PlyExporter {

    data class Result(
        val sizeBytes: Long,
        val minX: Float, val maxX: Float,
        val minY: Float, val maxY: Float,
        val minZ: Float, val maxZ: Float,
    )

    fun write(snap: MeshSnapshot, file: File): Result {
        val n = snap.vertexCount
        require(n > 0) { "empty mesh" }
        val faces = n / 3

        val header = buildString {
            appendLine("ply")
            appendLine("format binary_little_endian 1.0")
            appendLine("element vertex $n")
            appendLine("property float x")
            appendLine("property float y")
            appendLine("property float z")
            appendLine("property float nx")
            appendLine("property float ny")
            appendLine("property float nz")
            appendLine("element face $faces")
            appendLine("property list uchar int vertex_indices")
            append("end_header\n")
        }.toByteArray(Charsets.US_ASCII)

        val verts = snap.vertices.duplicate().apply { rewind() }
        val norms = snap.normals.duplicate().apply { rewind() }

        // Bounding box pass
        val vertsForBounds = snap.vertices.duplicate().apply { rewind() }
        var minX = Float.MAX_VALUE;  var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE;  var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE;  var maxZ = -Float.MAX_VALUE
        repeat(n) {
            val x = vertsForBounds.get(); val y = vertsForBounds.get(); val z = vertsForBounds.get()
            if (x < minX) minX = x;  if (x > maxX) maxX = x
            if (y < minY) minY = y;  if (y > maxY) maxY = y
            if (z < minZ) minZ = z;  if (z > maxZ) maxZ = z
        }

        // Write binary PLY using a fixed 64KB chunk buffer to avoid one large allocation
        val buf = ByteBuffer.allocate(TsdfConfig.PLY_CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)

        file.outputStream().buffered(TsdfConfig.PLY_STREAM_BUFFER_BYTES).use { out ->
            out.write(header)

            // Vertex block: 24 bytes per vertex (6 floats)
            repeat(n) {
                if (buf.remaining() < 24) { out.write(buf.array(), 0, buf.position()); buf.clear() }
                buf.putFloat(verts.get()); buf.putFloat(verts.get()); buf.putFloat(verts.get())
                buf.putFloat(norms.get()); buf.putFloat(norms.get()); buf.putFloat(norms.get())
            }
            if (buf.position() > 0) { out.write(buf.array(), 0, buf.position()); buf.clear() }

            // Face block: 13 bytes per face (1 byte count + 3 ints)
            for (i in 0 until faces) {
                if (buf.remaining() < 13) { out.write(buf.array(), 0, buf.position()); buf.clear() }
                buf.put(3.toByte())
                buf.putInt(i * 3); buf.putInt(i * 3 + 1); buf.putInt(i * 3 + 2)
            }
            if (buf.position() > 0) out.write(buf.array(), 0, buf.position())
        }

        return Result(file.length(), minX, maxX, minY, maxY, minZ, maxZ)
    }
}
