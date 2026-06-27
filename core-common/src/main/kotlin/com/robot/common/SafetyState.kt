package com.robot.common

sealed class SafetyState {
    object Ok : SafetyState()
    /** IR bas > threshold → vide sous le robot (escalier, bord) */
    data class VoidDetectedDown(val distanceMm: Int) : SafetyState()
    /** IR haut < threshold → obstacle en hauteur */
    data class ObstacleUp(val distanceMm: Int) : SafetyState()
}
