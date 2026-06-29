package com.robot.tsdf

object TsdfConfig {
    /** Voxel side length in metres. */
    const val VOXEL_SIZE_M = 0.03f

    /** TSDF truncation distance in metres (3 × voxel size). */
    const val TRUNCATION_M = 0.09f

    /** Run Marching Cubes every N integrated frames. */
    const val MESH_EXTRACT_INTERVAL = 30

    // Legacy grid dimensions passed to JNI — ignored by the voxel-hashing C++,
    // kept only to satisfy the nativeCreate signature.
    const val LEGACY_GRID_SIZE_X = 300
    const val LEGACY_GRID_SIZE_Y = 150
    const val LEGACY_GRID_SIZE_Z = 300

    /** PLY write chunk size in bytes (avoids a single large allocation). */
    const val PLY_CHUNK_BYTES = 64 * 1024

    /** PLY output stream buffer size in bytes. */
    const val PLY_STREAM_BUFFER_BYTES = 256 * 1024
}
