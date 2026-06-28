package com.robot.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object OverlayLogger {
    private val lines = ArrayDeque<String>()

    private val _text = MutableStateFlow("(no logs yet)")
    val text: StateFlow<String> = _text

    fun log(msg: String) {
        android.util.Log.d("RobotOverlay", msg)
        val snapshot = synchronized(lines) {
            if (lines.size >= 80) lines.removeFirst()
            lines.addLast(msg)
            lines.toList()
        }
        _text.value = snapshot.joinToString("\n")
    }
}
