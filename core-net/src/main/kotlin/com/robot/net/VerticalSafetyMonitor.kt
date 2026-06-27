package com.robot.net

import com.robot.common.IrReading
import com.robot.common.IrSensor
import com.robot.common.SafetyState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

class VerticalSafetyMonitor(
    /** Distance below which the up sensor triggers obstacle alert (mm). */
    private val obstacleUpThresholdMm: Int = 300,
    /** Distance above which the down sensor triggers void alert (mm). */
    private val voidDownThresholdMm: Int = 400,
) {
    private val _safetyState = MutableStateFlow<SafetyState>(SafetyState.Ok)
    val safetyState: StateFlow<SafetyState> = _safetyState

    suspend fun observe(readings: Flow<IrReading>) {
        readings.collect { reading ->
            _safetyState.value = when {
                reading.sensor == IrSensor.UP && reading.distanceMm < obstacleUpThresholdMm ->
                    SafetyState.ObstacleUp(reading.distanceMm)
                reading.sensor == IrSensor.DOWN && reading.distanceMm > voidDownThresholdMm ->
                    SafetyState.VoidDetectedDown(reading.distanceMm)
                else -> SafetyState.Ok
            }
        }
    }
}
