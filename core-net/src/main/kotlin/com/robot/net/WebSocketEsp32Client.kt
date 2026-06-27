package com.robot.net

import com.robot.common.IrFrame
import com.robot.common.IrReading
import com.robot.common.IrSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketEsp32Client(
    private val scope: CoroutineScope,
    private val ip: String,
    private val port: Int = 8080,
) {
    private val _readings = MutableSharedFlow<IrReading>(replay = 1)
    val readings: SharedFlow<IrReading> = _readings

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for WS
        .build()

    private var webSocket: WebSocket? = null
    private var retryDelayMs = 1_000L

    init {
        scope.launch { connect() }
    }

    private suspend fun connect() {
        while (true) {
            val request = Request.Builder().url("ws://$ip:$port/ir").build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    retryDelayMs = 1_000L
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    runCatching {
                        val frame = Json.decodeFromString<IrFrame>(text)
                        val now = System.currentTimeMillis()
                        if (frame.up > 0) _readings.tryEmit(IrReading(IrSensor.UP, frame.up, now))
                        if (frame.down > 0) _readings.tryEmit(IrReading(IrSensor.DOWN, frame.down, now))
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    ws.cancel()
                }
            })

            // Wait for socket to fail then retry with backoff
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        }
    }

    fun close() {
        webSocket?.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
