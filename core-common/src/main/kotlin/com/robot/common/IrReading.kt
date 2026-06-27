package com.robot.common

import kotlinx.serialization.Serializable

enum class IrSensor { UP, DOWN }

@Serializable
data class IrFrame(
    val up: Int,    // mm, 0 = error/no reading
    val down: Int,  // mm, 0 = error/no reading
)

data class IrReading(
    val sensor: IrSensor,
    val distanceMm: Int,
    val timestampMs: Long,
)
