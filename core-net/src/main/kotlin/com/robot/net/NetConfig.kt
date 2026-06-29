package com.robot.net

object NetConfig {
    /** ESP32 WebSocket IP address. */
    const val ESP32_IP   = "192.168.1.100"

    /** ESP32 WebSocket port. */
    const val ESP32_PORT = 8080

    /** Initial WebSocket reconnect delay in milliseconds. */
    const val WS_RETRY_DELAY_INITIAL_MS = 1_000L

    /** Maximum WebSocket reconnect delay in milliseconds (exponential backoff cap). */
    const val WS_RETRY_DELAY_MAX_MS = 30_000L

    /** IR sensor distance below which the upward sensor triggers an obstacle alert (mm). */
    const val SAFETY_OBSTACLE_UP_THRESHOLD_MM = 300

    /** IR sensor distance above which the downward sensor triggers a void alert (mm). */
    const val SAFETY_VOID_DOWN_THRESHOLD_MM = 400
}
