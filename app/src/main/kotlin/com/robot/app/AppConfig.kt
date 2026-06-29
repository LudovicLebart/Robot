package com.robot.app

import android.graphics.Color

object AppConfig {
    /** Maximum number of log lines kept in the overlay. */
    const val MAX_LOG_LINES = 80

    /** Log overlay text size in scaled pixels. */
    const val LOG_TEXT_SIZE_SP = 11f

    /** Log overlay background color. */
    val LOG_BG_COLOR = Color.argb(180, 0, 0, 0)

    /** Default (inactive) button background color. */
    val BTN_DEFAULT_BG_COLOR = Color.argb(180, 0, 0, 80)

    /** PLAN button active background color. */
    val BTN_PLAN_ACTIVE_COLOR = Color.argb(220, 0, 100, 0)

    /** MESH button hidden-state background color. */
    val BTN_MESH_HIDDEN_COLOR = Color.argb(220, 80, 0, 0)

    /** VIO button active background color (cyan). */
    val BTN_VIO_ACTIVE_COLOR = Color.argb(220, 0, 180, 180)

    /** MAP button active background color (white-ish). */
    val BTN_MAP_ACTIVE_COLOR = Color.argb(220, 200, 200, 200)

    /** Button row top/end margin in pixels. */
    const val BTN_ROW_MARGIN_PX = 16

    /** Button horizontal padding in pixels. */
    const val BTN_PADDING_H_PX = 16

    /** Button vertical padding in pixels. */
    const val BTN_PADDING_V_PX = 8
}
