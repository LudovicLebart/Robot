package com.robot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.robot.common.SafetyState
import com.robot.common.SessionState
import com.robot.render.SlamGLSurfaceView
import com.robot.render.SlamRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: SlamViewModel by viewModels()
    private lateinit var glView: SlamGLSurfaceView
    private lateinit var renderer: SlamRenderer

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startArSession()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = SlamGLSurfaceView(this)
        setContentView(glView)

        renderer = SlamRenderer(
            sessionManager = viewModel.sessionManager,
            tsdfVolume = viewModel.tsdfVolume,
            getDisplayRotation = { display?.rotation ?: Surface.ROTATION_0 },
        )
        glView.setRenderer(renderer)

        // Collect new mesh snapshots from TSDF and forward to GL renderer
        viewModel.tsdfVolume.mesh.onEach { snap ->
            renderer.onMeshSnapshot(snap)
        }.launchIn(lifecycleScope)

        // Observe session state
        viewModel.sessionState.onEach { state ->
            if (state is SessionState.Failed) {
                Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)

        // Observe safety state
        viewModel.safetyState.onEach { safety ->
            when (safety) {
                is SafetyState.VoidDetectedDown ->
                    Toast.makeText(this, "VOID BELOW: ${safety.distanceMm}mm", Toast.LENGTH_SHORT).show()
                is SafetyState.ObstacleUp ->
                    Toast.makeText(this, "OBSTACLE ABOVE: ${safety.distanceMm}mm", Toast.LENGTH_SHORT).show()
                SafetyState.Ok -> { /* no alert */ }
            }
        }.launchIn(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startArSession()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        viewModel.sessionManager.pause()
    }

    private fun startArSession() {
        viewModel.sessionManager.resume()
    }
}
