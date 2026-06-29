package com.robot.render

object RenderConfig {

    // ── Plan view camera (shared by SlamRenderer + MeshRenderer) ─────────────
    const val PLAN_VIEW_EYE_HEIGHT_M         = 8f
    const val PLAN_VIEW_ORTHO_HALF_EXTENT_M  = 3.3f
    const val PLAN_VIEW_NEAR_M               = 0.5f
    const val PLAN_VIEW_FAR_M                = 16f

    // ── Stable map point rendering (StableMapRenderer) ────────────────────────
    const val STABLE_POINT_SIZE_MIN_PX    = 3.0f
    const val STABLE_POINT_SIZE_MAX_PX    = 12.0f
    const val STABLE_POINT_SIZE_MAX_COUNT = 60.0f   // obs count that maps to max size
    const val STABLE_HEIGHT_RANGE_MIN_M   = 0.1f    // guard: prevent division by zero
    const val STABLE_COLOR_FLOOR_R        = 0.2f
    const val STABLE_COLOR_FLOOR_G        = 1.0f
    const val STABLE_COLOR_FLOOR_B        = 0.35f
    const val STABLE_COLOR_MID_R          = 1.0f
    const val STABLE_COLOR_MID_G          = 0.15f
    const val STABLE_COLOR_MID_B          = 0.1f
    const val STABLE_COLOR_CEILING_R      = 0.15f
    const val STABLE_COLOR_CEILING_G      = 0.55f
    const val STABLE_COLOR_CEILING_B      = 1.0f
    const val POINT_CIRCLE_DISCARD_R2     = 0.25f   // = 0.5² — circular point shape

    // ── VIO raw point cloud (PointCloudRenderer in SlamRenderer) ─────────────
    const val VIO_CLOUD_COLOR_R    = 0f
    const val VIO_CLOUD_COLOR_G    = 1f
    const val VIO_CLOUD_COLOR_B    = 1f
    const val VIO_CLOUD_COLOR_A    = 1f
    const val VIO_CLOUD_MAX_POINTS = 500

    // ── Generic PointCloudRenderer GLSL ──────────────────────────────────────
    const val POINT_CLOUD_SIZE_SCALE  = 6.0f
    const val POINT_CLOUD_SIZE_MIN_PX = 2.0f
    const val POINT_CLOUD_SIZE_MAX_PX = 12.0f

    // ── Mesh tints (MeshRenderer) ─────────────────────────────────────────────
    const val MESH_AR_TINT_R   = 0.3f
    const val MESH_AR_TINT_G   = 0.9f
    const val MESH_AR_TINT_B   = 0.6f
    const val MESH_PLAN_TINT_R = 0.2f
    const val MESH_PLAN_TINT_G = 1.0f
    const val MESH_PLAN_TINT_B = 0.4f
    const val MESH_MIN_DIFFUSE = 0.15f   // ambient floor in fragment shader

    // ── Floor/ceiling grid (FloorCeilingRenderer) ────────────────────────────
    const val GRID_HALF_EXTENT_M  = 5.0f
    const val GRID_STEP_M         = 0.5f
    const val GRID_FLOOR_TINT_R   = 0.2f
    const val GRID_FLOOR_TINT_G   = 0.9f
    const val GRID_FLOOR_TINT_B   = 0.2f
    const val GRID_CEILING_TINT_R = 0.3f
    const val GRID_CEILING_TINT_G = 0.5f
    const val GRID_CEILING_TINT_B = 1.0f

    // ── Floor/ceiling detector (FloorCeilingDetector) ────────────────────────
    const val DETECTOR_Y_MIN_M          = -1.5f
    const val DETECTOR_Y_MAX_M          = 3.5f
    const val DETECTOR_BIN_SIZE_M       = 0.05f
    const val DETECTOR_MIN_VOTES        = 500
    const val DETECTOR_FLOOR_MARGIN_M   = 0.3f
    const val DETECTOR_CEILING_MARGIN_M = 1.2f   // skip tables/shelves
    const val DETECTOR_EMA_ALPHA        = 0.1f
    const val DETECTOR_PIXEL_STRIDE     = 4

    // ── SlamRenderer misc ────────────────────────────────────────────────────
    const val LOG_THROTTLE_FRAMES   = 90        // ~3 s at 30 fps
    const val MIN_VALID_DEPTH_PCT   = 5         // skip frames with less than 5 % valid pixels
    const val PLAN_BG_R             = 0.05f
    const val PLAN_BG_G             = 0.05f
    const val PLAN_BG_B             = 0.08f
    const val PLANE_LOG_THRESHOLD_M = 0.05f     // log floor/ceiling only when shift > 5 cm

    // ── Fallbacks (until FloorCeilingDetector converges) ────────────────────
    const val FALLBACK_FLOOR_Y_M   = 0.0f
    const val FALLBACK_CEILING_Y_M = 2.5f
}
