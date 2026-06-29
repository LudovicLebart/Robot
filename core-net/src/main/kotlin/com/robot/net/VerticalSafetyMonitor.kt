package com.robot.net

import com.robot.common.IrReading
import com.robot.common.IrSensor
import com.robot.common.SafetyState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

class VerticalSafetyMonitor(
    private val obstacleUpThresholdMm: Int = NetConfig.SAFETY_OBSTACLE_UP_THRESHOLD_MM,
    private val voidDownThresholdMm: Int   = NetConfig.SAFETY_VOID_DOWN_THRESHOLD_MM,
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
