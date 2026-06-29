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
    sizeX: Int     = TsdfConfig.LEGACY_GRID_SIZE_X,
    sizeY: Int     = TsdfConfig.LEGACY_GRID_SIZE_Y,
    sizeZ: Int     = TsdfConfig.LEGACY_GRID_SIZE_Z,
    voxelSizeM: Float  = TsdfConfig.VOXEL_SIZE_M,
    truncationM: Float = TsdfConfig.TRUNCATION_M,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        init { System.loadLibrary("robot_tsdf") }
    }

    private val handle: Long = nativeCreate(sizeX, sizeY, sizeZ, voxelSizeM, truncationM)

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

    suspend fun processLoop() = withContext(Dispatchers.Default) {
        onLog("TSDF loop started handle=$handle")
        try {
            for (frame in frameChannel) {
                nativeIntegrate(
                    handle,
                    frame.depthValues, frame.depthWidth, frame.depthHeight,
                    frame.fx, frame.fy, frame.cx, frame.cy,
                    frame.cameraToWorld,
                )
                frameCount++
                if (frameCount == 1) onLog("TSDF first frame integrated ${frame.depthWidth}x${frame.depthHeight}")

                if (frameCount % TsdfConfig.MESH_EXTRACT_INTERVAL == 0) {
                    val raw = nativeExtractMesh(handle)
                    if (raw == null || raw.isEmpty()) {
                        onLog("TSDF mesh #${frameCount / TsdfConfig.MESH_EXTRACT_INTERVAL}: 0 vertices (TSDF not dense enough yet)")
                        continue
                    }
                    val n = raw.size / 6
                    onLog("TSDF mesh #${frameCount / TsdfConfig.MESH_EXTRACT_INTERVAL}: $n vertices after $frameCount frames")

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
        } finally {
            onLog("TSDF loop ended after $frameCount frames")
            nativeDestroy(handle)
        }
    }

    fun reset() {
        frameCount = 0
        nativeReset(handle)
    }

    fun close() {
        frameChannel.close()
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
