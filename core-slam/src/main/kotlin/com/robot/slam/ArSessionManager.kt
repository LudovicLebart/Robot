package com.robot.slam

import android.content.Context
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.robot.common.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ArSessionManager(private val context: Context) {

    @Volatile
    private var session: Session? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Initializing)
    val state: StateFlow<SessionState> = _state

    /** Call from Activity.onResume(), after camera permission is granted. */
    fun resume() {
        try {
            if (session == null) {
                session = Session(context).also { s ->
                    val config = Config(s).apply {
                        if (s.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                            depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                        }
                        focusMode = Config.FocusMode.AUTO
                        planeFindingMode = Config.PlaneFindingMode.DISABLED
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                    s.configure(config)
                }
            }
            session!!.resume()
            _state.value = SessionState.Tracking
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
        session?.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) == true
}
