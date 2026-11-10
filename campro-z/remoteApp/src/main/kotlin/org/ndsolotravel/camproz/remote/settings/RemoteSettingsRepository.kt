package org.ndsolotravel.camproz.remote.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FramingGuideMode {
    NONE,
    THIRDS,
    CROSSHAIR,
    SAFE_AREA_90
}

data class RemoteSettingsState(
    // Remote Monitor Preferences (This Device)
    val scaleModeFit: Boolean = true,
    val framingGuide: FramingGuideMode = FramingGuideMode.NONE,
    val hapticFeedback: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val showRttTelemetry: Boolean = true,
    val showBatteryStorageHud: Boolean = true,
    val autoReconnect: Boolean = true,

    // Host Stream Target (Applied to Camera Host)
    val streamQuality: String = "AUTO", // AUTO, 360P, 540P, 720P
    val streamFps: Int = 30
)

class RemoteSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("camproz_remote_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<RemoteSettingsState> = _settings.asStateFlow()

    private fun loadSettings(): RemoteSettingsState {
        val guideName = prefs.getString("framing_guide", FramingGuideMode.NONE.name) ?: FramingGuideMode.NONE.name
        val guide = try {
            FramingGuideMode.valueOf(guideName)
        } catch (_: Exception) {
            FramingGuideMode.NONE
        }

        return RemoteSettingsState(
            scaleModeFit = prefs.getBoolean("scale_mode_fit", true),
            framingGuide = guide,
            hapticFeedback = prefs.getBoolean("haptic_feedback", true),
            keepScreenAwake = prefs.getBoolean("keep_screen_awake", true),
            showRttTelemetry = prefs.getBoolean("show_rtt_telemetry", true),
            showBatteryStorageHud = prefs.getBoolean("show_battery_storage_hud", true),
            autoReconnect = prefs.getBoolean("auto_reconnect", true),
            streamQuality = prefs.getString("stream_quality", "AUTO") ?: "AUTO",
            streamFps = prefs.getInt("stream_fps", 30)
        )
    }

    fun updateScaleModeFit(fit: Boolean) {
        prefs.edit().putBoolean("scale_mode_fit", fit).apply()
        _settings.value = _settings.value.copy(scaleModeFit = fit)
    }

    fun updateFramingGuide(guide: FramingGuideMode) {
        prefs.edit().putString("framing_guide", guide.name).apply()
        _settings.value = _settings.value.copy(framingGuide = guide)
    }

    fun updateHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean("haptic_feedback", enabled).apply()
        _settings.value = _settings.value.copy(hapticFeedback = enabled)
    }

    fun updateKeepScreenAwake(enabled: Boolean) {
        prefs.edit().putBoolean("keep_screen_awake", enabled).apply()
        _settings.value = _settings.value.copy(keepScreenAwake = enabled)
    }

    fun updateShowRttTelemetry(enabled: Boolean) {
        prefs.edit().putBoolean("show_rtt_telemetry", enabled).apply()
        _settings.value = _settings.value.copy(showRttTelemetry = enabled)
    }

    fun updateShowBatteryStorageHud(enabled: Boolean) {
        prefs.edit().putBoolean("show_battery_storage_hud", enabled).apply()
        _settings.value = _settings.value.copy(showBatteryStorageHud = enabled)
    }

    fun updateAutoReconnect(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reconnect", enabled).apply()
        _settings.value = _settings.value.copy(autoReconnect = enabled)
    }

    fun updateStreamQuality(quality: String) {
        prefs.edit().putString("stream_quality", quality).apply()
        _settings.value = _settings.value.copy(streamQuality = quality)
    }

    fun updateStreamFps(fps: Int) {
        prefs.edit().putInt("stream_fps", fps).apply()
        _settings.value = _settings.value.copy(streamFps = fps)
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _settings.value = loadSettings()
    }
}
