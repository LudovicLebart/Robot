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
    private lateinit var btnSave: Button
    private var logVisible = false
    private var meshVisible = false
    private var vioCloudVisible = false
    private var stableCloudVisible = false

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
            sessionManager  = viewModel.sessionManager,
            tsdfVolume      = viewModel.tsdfVolume,
            vioAccumulator  = viewModel.vioAccumulator,
            getDisplayRotation = { display?.rotation ?: Surface.ROTATION_0 },
            onLog           = { msg -> OverlayLogger.log(msg) },
        )
        glView.setRenderer(renderer)

        // ── Log overlay ─────────────────────────────────────────────────────
        logTextView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = AppConfig.LOG_TEXT_SIZE_SP
            setTextColor(Color.GREEN)
            setBackgroundColor(AppConfig.LOG_BG_COLOR)
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
            runOnUiThread {
                btnPlan.setBackgroundColor(
                    if (next) AppConfig.BTN_PLAN_ACTIVE_COLOR
                    else AppConfig.BTN_DEFAULT_BG_COLOR
                )
            }
            OverlayLogger.log(if (next) "Plan view ON" else "Plan view OFF (AR mode)")
        }

        lateinit var btnMesh: Button
        btnMesh = makeButton("MESH") {
            meshVisible = !meshVisible
            renderer.meshVisible = meshVisible
            btnMesh.setBackgroundColor(
                if (meshVisible) AppConfig.BTN_DEFAULT_BG_COLOR
                else AppConfig.BTN_MESH_HIDDEN_COLOR
            )
        }
        btnMesh.post { btnMesh.setBackgroundColor(AppConfig.BTN_MESH_HIDDEN_COLOR) }

        lateinit var btnVio: Button
        btnVio = makeButton("VIO") {
            vioCloudVisible = !vioCloudVisible
            renderer.vioCloudVisible = vioCloudVisible
            btnVio.setBackgroundColor(
                if (vioCloudVisible) AppConfig.BTN_VIO_ACTIVE_COLOR
                else AppConfig.BTN_DEFAULT_BG_COLOR
            )
        }

        lateinit var btnMap: Button
        btnMap = makeButton("MAP") {
            stableCloudVisible = !stableCloudVisible
            renderer.stableCloudVisible = stableCloudVisible
            btnMap.setBackgroundColor(
                if (stableCloudVisible) AppConfig.BTN_MAP_ACTIVE_COLOR
                else AppConfig.BTN_DEFAULT_BG_COLOR
            )
        }

        btnSave = makeButton("SAVE") {
            btnSave.isEnabled = false
            viewModel.saveMesh { path ->
                btnSave.isEnabled = true
                Toast.makeText(this, "adb pull \"$path\"", Toast.LENGTH_LONG).show()
            }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnLog,  LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnPlan, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnMesh, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnVio,  LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnMap,  LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginEnd = 8 })
            addView(btnSave, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        // ── Root layout ──────────────────────────────────────────────────────
        val root = FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(logScrollView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                buttonRow,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = AppConfig.BTN_ROW_MARGIN_PX
                    marginEnd = AppConfig.BTN_ROW_MARGIN_PX
                },
            )
        }
        setContentView(root)

        OverlayLogger.text.onEach { text ->
            logTextView.text = text
            if (logVisible) logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }.launchIn(lifecycleScope)

        viewModel.tsdfVolume.mesh.onEach { snap ->
            renderer.onMeshSnapshot(snap)
        }.launchIn(lifecycleScope)

        viewModel.sessionState.onEach { state ->
            if (state is SessionState.Failed) {
                OverlayLogger.log("SessionState.Failed: ${state.reason}")
                Toast.makeText(this, state.reason, Toast.LENGTH_LONG).show()
            }
        }.launchIn(lifecycleScope)

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
        text     = label
        textSize = AppConfig.LOG_TEXT_SIZE_SP
        setBackgroundColor(AppConfig.BTN_DEFAULT_BG_COLOR)
        setTextColor(Color.WHITE)
        setPadding(AppConfig.BTN_PADDING_H_PX, AppConfig.BTN_PADDING_V_PX,
                   AppConfig.BTN_PADDING_H_PX, AppConfig.BTN_PADDING_V_PX)
        setOnClickListener { onClick() }
    }
}
