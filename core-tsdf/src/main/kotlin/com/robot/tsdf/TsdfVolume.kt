package com.robot.tsdf

import com.robot.common.FrameData
import com.robot.common.MeshSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TsdfVolume(
    sizeX: Int = 300,
    sizeY: Int = 150,
    sizeZ: Int = 300,
    voxelSizeM: Float = 0.02f,
    truncationM: Float = 0.04f,
) {
    companion object {
        init { System.loadLibrary("robot_tsdf") }
    }

    private val handle: Long = nativeCreate(sizeX, sizeY, sizeZ, voxelSizeM, truncationM)

    /** CONFLATED channel: always holds the latest frame, drops old ones if the TSDF is busy. */
    val frameChannel = Channel<FrameData>(Channel.CONFLATED)

    private val _mesh = MutableStateFlow(
        MeshSnapshot(
            vertices = ByteBuffer.allocateDirect(0).asFloatBuffer(),
            normals  = ByteBuffer.allocateDirect(0).asFloatBuffer(),
            vertexCount = 0,
        )
    )
    val mesh: StateFlow<MeshSnapshot> = _mesh

    private var frameCount = 0
    private val meshExtractInterval = 30   // extract mesh every N integrated frames

    /** Run this in a dedicated coroutine on Dispatchers.Default. */
    suspend fun processLoop() = withContext(Dispatchers.Default) {
        for (frame in frameChannel) {
            nativeIntegrate(
                handle,
                frame.depthValues, frame.depthWidth, frame.depthHeight,
                frame.fx, frame.fy, frame.cx, frame.cy,
                frame.cameraToWorld,
            )
            frameCount++
            if (frameCount % meshExtractInterval == 0) {
                val raw = nativeExtractMesh(handle) ?: continue
                // raw is interleaved [vx,vy,vz,nx,ny,nz,...]
                val n = raw.size / 6
                val vbuf = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val nbuf = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until n) {
                    vbuf.put(raw[i * 6 + 0]); vbuf.put(raw[i * 6 + 1]); vbuf.put(raw[i * 6 + 2])
                    nbuf.put(raw[i * 6 + 3]); nbuf.put(raw[i * 6 + 4]); nbuf.put(raw[i * 6 + 5])
                }
                vbuf.rewind(); nbuf.rewind()
                _mesh.value = MeshSnapshot(vbuf, nbuf, n)
            }
        }
    }

    fun reset() = nativeReset(handle)

    fun close() {
        frameChannel.close()
        nativeDestroy(handle)
    }

    private external fun nativeCreate(sx: Int, sy: Int, sz: Int, voxelSize: Float, truncation: Float): Long
    private external fun nativeIntegrate(
        handle: Long,
        depth: ShortArray, w: Int, h: Int,
        fx: Float, fy: Float, cx: Float, cy: Float,
        cameraToWorld: FloatArray,
    )
    private external fun nativeExtractMesh(handle: Long): FloatArray?
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
