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
import android.widget.LinearLayout
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
    private lateinit var btnLog: Button
    private lateinit var btnPlan: Button
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
            tsdfVolume     = viewModel.tsdfVolume,
            getDisplayRotation = { display?.rotation ?: Surface.ROTATION_0 },
            onLog          = { msg -> OverlayLogger.log(msg) },
        )
        glView.setRenderer(renderer)

        // ── Log overlay ─────────────────────────────────────────────────────
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

        // ── Buttons ──────────────────────────────────────────────────────────
        btnLog = makeButton("LOG") {
            logVisible = !logVisible
            logScrollView.visibility =
                if (logVisible) android.view.View.VISIBLE else android.view.View.GONE
        }

        btnPlan = makeButton("PLAN") {
            val next = !renderer.planViewEnabled
            renderer.planViewEnabled = next
            // Update button appearance to reflect state
            glView.queueEvent {
                // runs on GL thread — nothing to do, just use the volatile flag
            }
            runOnUiThread {
                btnPlan.setBackgroundColor(
                    if (next) Color.argb(220, 0, 100, 0)
                    else Color.argb(180, 0, 0, 80)
                )
            }
            OverlayLogger.log(if (next) "Plan view ON" else "Plan view OFF (AR mode)")
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnLog,  LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnPlan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        // ── Root layout ──────────────────────────────────────────────────────
        val root = FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(logScrollView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                buttonRow,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin  = 16
                    marginEnd  = 16
                },
            )
        }
        setContentView(root)

        // Collect log text → overlay
        OverlayLogger.text.onEach { text ->
            logTextView.text = text
            if (logVisible) logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }.launchIn(lifecycleScope)

        // Forward mesh snapshots to GL renderer
        viewModel.tsdfVolume.mesh.onEach { snap ->
            renderer.onMeshSnapshot(snap)
        }.launchIn(lifecycleScope)

        // Session state
        viewModel.sessionState.onEach { state ->
            if (state is SessionState.Failed) {
                OverlayLogger.log("SessionState.Failed: ${state.reason}")
                Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)

        // Safety alerts
        viewModel.safetyState.onEach { safety ->
            when (safety) {
                is SafetyState.VoidDetectedDown ->
                    Toast.makeText(this, "VOID BELOW: ${safety.distanceMm}mm", Toast.LENGTH_SHORT).show()
                is SafetyState.ObstacleUp ->
                    Toast.makeText(this, "OBSTACLE ABOVE: ${safety.distanceMm}mm", Toast.LENGTH_SHORT).show()
                SafetyState.Ok -> {}
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

    private fun makeButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text         = label
        textSize     = 11f
        setBackgroundColor(Color.argb(180, 0, 0, 80))
        setTextColor(Color.WHITE)
        setPadding(16, 8, 16, 8)
        setOnClickListener { onClick() }
    }
}
