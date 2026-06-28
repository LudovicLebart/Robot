package com.robot.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robot.common.SafetyState
import com.robot.common.SessionState
import com.robot.net.VerticalSafetyMonitor
import com.robot.net.WebSocketEsp32Client
import com.robot.slam.ArSessionManager
import com.robot.tsdf.PlyExporter
import com.robot.tsdf.TsdfVolume
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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

    fun saveMesh(onDone: (String) -> Unit) {
        val snap = tsdfVolume.mesh.value
        if (snap.vertexCount == 0) { onDone("No mesh yet"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val dir = getApplication<Application>().getExternalFilesDir(null)!!
            val file = File(dir, "mesh_${System.currentTimeMillis()}.ply")
            val r = PlyExporter.write(snap, file)
            val kb = r.sizeBytes / 1024
            val msg = "PLY: ${file.name} ${snap.vertexCount}v ${kb}KB " +
                "X[%.2f,%.2f] Y[%.2f,%.2f] Z[%.2f,%.2f]".format(
                    r.minX, r.maxX, r.minY, r.maxY, r.minZ, r.maxZ)
            OverlayLogger.log(msg)
            withContext(Dispatchers.Main) { onDone(file.absolutePath) }
        }
    }

    override fun onCleared() {
        tsdfVolume.close()
        esp32Client.close()
        sessionManager.close()
    }
}
