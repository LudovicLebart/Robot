package com.robot.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private var logVisible = false

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

        renderer = SlamRenderer(
            sessionManager = viewModel.sessionManager,
            tsdfVolume = viewModel.tsdfVolume,
            getDisplayRotation = { display?.rotation ?: Surface.ROTATION_0 },
            onLog = { msg -> OverlayLogger.log(msg) },
        )
        glView.setRenderer(renderer)

        // Log overlay views
        logTextView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(8, 8, 8, 8)
        }
        logScrollView = ScrollView(this).apply {
            addView(logTextView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            visibility = android.view.View.GONE
        }

        val toggleBtn = Button(this).apply {
            text = "LOG"
            textSize = 11f
            setBackgroundColor(Color.argb(180, 0, 0, 80))
            setTextColor(Color.WHITE)
            setOnClickListener {
                logVisible = !logVisible
                logScrollView.visibility =
                    if (logVisible) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        val root = FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                logScrollView,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
            addView(
                toggleBtn,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = 16
                    marginEnd = 16
                }
            )
        }
        setContentView(root)

        // Collect log text and update overlay
        OverlayLogger.text.onEach { text ->
            logTextView.text = text
            if (logVisible) logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }.launchIn(lifecycleScope)

        // Collect new mesh snapshots from TSDF and forward to GL renderer
        viewModel.tsdfVolume.mesh.onEach { snap ->
            renderer.onMeshSnapshot(snap)
        }.launchIn(lifecycleScope)

        // Observe session state
        viewModel.sessionState.onEach { state ->
            if (state is SessionState.Failed) {
                OverlayLogger.log("SessionState.Failed: ${state.reason}")
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
