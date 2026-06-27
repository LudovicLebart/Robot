package com.robot.slam

import android.content.Context
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.robot.common.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ArSessionManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    @Volatile
    private var session: Session? = null

    @Volatile
    var depthModeName: String = "UNKNOWN"
        private set

    private val _state = MutableStateFlow<SessionState>(SessionState.Initializing)
    val state: StateFlow<SessionState> = _state

    /** Call from Activity.onResume(), after camera permission is granted. */
    fun resume() {
        try {
            if (session == null) {
                session = Session(context).also { s ->
                    // Pixel 9 has no ToF sensor — RAW_DEPTH_ONLY is not supported.
                    // Fall back to AUTOMATIC (ML-estimated depth) so depth data is available.
                    val depthMode = when {
                        s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                        s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)       -> Config.DepthMode.AUTOMATIC
                        else                                                      -> Config.DepthMode.DISABLED
                    }
                    depthModeName = depthMode.name
                    onLog("ARCore depth: $depthModeName")

                    val config = Config(s).apply {
                        this.depthMode   = depthMode
                        focusMode        = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        updateMode       = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                    s.configure(config)
                }
            }
            session!!.resume()
            _state.value = SessionState.Tracking
            onLog("ARCore resumed")
        } catch (e: UnavailableArcoreNotInstalledException) {
            _state.value = SessionState.Failed("ARCore not installed")
        } catch (e: UnavailableApkTooOldException) {
            _state.value = SessionState.Failed("ARCore APK too old")
        } catch (e: UnavailableSdkTooOldException) {
            _state.value = SessionState.Failed("ARCore SDK too old")
        } catch (e: CameraNotAvailableException) {
            _state.value = SessionState.Failed("Camera not available")
        }
    }

    fun pause() {
        session?.pause()
        _state.value = SessionState.Paused
    }

    fun close() {
        session?.close()
        session = null
    }

    fun setCameraTextureName(textureId: Int) {
        session?.setCameraTextureName(textureId)
    }

    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
    }

    /** Returns null if session is not ready or if an error occurs during update. */
    fun update(): Frame? = try {
        session?.update()
    } catch (e: CameraNotAvailableException) {
        _state.value = SessionState.Failed("Camera lost during update")
        null
    } catch (e: Exception) {
        null
    }

    fun isDepthSupported(): Boolean =
        session?.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) == true ||
        session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true
}
