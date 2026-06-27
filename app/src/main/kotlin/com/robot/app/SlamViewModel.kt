package com.robot.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robot.common.SafetyState
import com.robot.common.SessionState
import com.robot.net.VerticalSafetyMonitor
import com.robot.net.WebSocketEsp32Client
import com.robot.slam.ArSessionManager
import com.robot.tsdf.TsdfVolume
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SlamViewModel @Inject constructor(
    application: Application,
    val sessionManager: ArSessionManager,
    val tsdfVolume: TsdfVolume,
    private val esp32Client: WebSocketEsp32Client,
    private val safetyMonitor: VerticalSafetyMonitor,
) : AndroidViewModel(application) {

    val sessionState: StateFlow<SessionState> = sessionManager.state

    val safetyState: StateFlow<SafetyState> = safetyMonitor.safetyState

    init {
        viewModelScope.launch { tsdfVolume.processLoop() }
        viewModelScope.launch { safetyMonitor.observe(esp32Client.readings) }
    }

    override fun onCleared() {
        tsdfVolume.close()
        esp32Client.close()
        sessionManager.close()
    }
}
