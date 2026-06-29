package com.robot.net

import com.robot.common.IrFrame
import com.robot.common.IrReading
import com.robot.common.IrSensor
import kotlinx.coroutines.CompletableDeferred
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
    private val port: Int = NetConfig.ESP32_PORT,
) {
    private val _readings = MutableSharedFlow<IrReading>(replay = 1)
    val readings: SharedFlow<IrReading> = _readings

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var retryDelayMs = NetConfig.WS_RETRY_DELAY_INITIAL_MS

    init {
        scope.launch { connect() }
    }

    private suspend fun connect() {
        while (true) {
            val closed = CompletableDeferred<Unit>()
            val request = Request.Builder().url("ws://$ip:$port/ir").build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    retryDelayMs = NetConfig.WS_RETRY_DELAY_INITIAL_MS
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
                    closed.complete(Unit)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
            })

            closed.await()
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(NetConfig.WS_RETRY_DELAY_MAX_MS)
        }
    }

    fun close() {
        webSocket?.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
