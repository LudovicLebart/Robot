package com.robot.nav

import kotlin.math.floor

/**
 * Accumulates ARCore VIO feature points (world-space XYZ) into a voxel grid.
 * Points observed in ≥minObservations frames are considered structural.
 *
 * Call update() and getStablePoints() from the GL thread only — no synchronisation needed.
 */
class VioMapAccumulator(
    val voxelSize: Float = 0.05f,
    val minObservations: Int = 8,
    val maxStablePoints: Int = 10_000,
) {
    private class VoxelEntry(var count: Int, var x: Float, var y: Float, var z: Float)

    private val grid = HashMap<Long, VoxelEntry>(4096)

    /** Merge a batch of world-space XYZ points (stride 3). */
    fun update(xyz: FloatArray, count: Int) {
        for (i in 0 until count) {
            val wx = xyz[i * 3]
            val wy = xyz[i * 3 + 1]
            val wz = xyz[i * 3 + 2]
            val key = voxelKey(wx, wy, wz)
            val e = grid.getOrPut(key) { VoxelEntry(0, wx, wy, wz) }
            e.count++
            // Welford running mean — numerically stable, no extra allocation
            val inv = 1f / e.count
            e.x += (wx - e.x) * inv
            e.y += (wy - e.y) * inv
            e.z += (wz - e.z) * inv
        }
    }

    /**
     * Write stable points (count ≥ minObservations) into [out] (stride 3).
     * Returns the number of points written.
     */
    fun getStablePoints(out: FloatArray): Int {
        val limit = minOf(maxStablePoints, out.size / 3)
        var n = 0
        for (e in grid.values) {
            if (n >= limit) break
            if (e.count >= minObservations) {
                out[n * 3]     = e.x
                out[n * 3 + 1] = e.y
                out[n * 3 + 2] = e.z
                n++
            }
        }
        return n
    }

    fun stableCount(): Int = grid.values.count { it.count >= minObservations }

    fun reset() = grid.clear()

    /**
     * Pack voxel grid indices into a Long key.
     * 21 bits per axis → range ±52 m at 5 cm resolution, sufficient for indoor navigation.
     */
    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val ix = floor((x / voxelSize).toDouble()).toInt()
        val iy = floor((y / voxelSize).toDouble()).toInt()
        val iz = floor((z / voxelSize).toDouble()).toInt()
        return ((ix.toLong() and 0x1FFFFF) shl 42) or
               ((iy.toLong() and 0x1FFFFF) shl 21) or
                (iz.toLong() and 0x1FFFFF)
    }
}
