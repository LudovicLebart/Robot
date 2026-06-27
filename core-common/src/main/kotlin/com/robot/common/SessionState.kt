package com.robot.common

sealed class SessionState {
    object Initializing : SessionState()
    object Tracking : SessionState()
    object Paused : SessionState()
    data class Failed(val reason: String) : SessionState()
}
