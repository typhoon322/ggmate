package com.gagmate.app.ui.dashboard

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.session.ConnectionState
import com.gagmate.app.data.protocol.Commands
import com.gagmate.app.data.system.SoundManager
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 *
 * All real-time data comes from [MachineSessionManager] via WebSocket.
 * No HTTP polling. REST is only used for non-real-time queries.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val session = AppContainer.machineSession

    // ── Observable state ──────────────────────────────────────────

    private val _machineState = MutableStateFlow(MachineState())
    val machineState: StateFlow<MachineState> = _machineState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Transient info/success messages (shown as a Toast/Snackbar, never as an error takeover). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _flushActive = MutableStateFlow(false)
    val flushActive: StateFlow<Boolean> = _flushActive.asStateFlow()

    /** Live chart data from the brew shot. */
    val chartData: StateFlow<List<ChartPoint>> = AppContainer.shotRepo.chartData

    // ── Weight management ────────────────────────────────────────

    private val _currentWeight = MutableStateFlow(40f)
    val currentWeight: StateFlow<Float> = _currentWeight.asStateFlow()

    private val _liveWeight = MutableStateFlow(0f)
    val liveWeight: StateFlow<Float> = _liveWeight.asStateFlow()

    private val _targetWeight = MutableStateFlow(40f)
    val targetWeight: StateFlow<Float> = _targetWeight.asStateFlow()

    // ── Initialisation ────────────────────────────────────────────

    init {
        // Connection state
        viewModelScope.launch {
            session.connectionState.collect { state ->
                _isConnected.value = state == ConnectionState.CONNECTED
                _isLoading.value = state == ConnectionState.CONNECTING
                // Only a terminal ERROR keeps the error takeover; transient drops
                // (RECONNECTING) and normal states clear it so the dashboard stays visible.
                if (state != ConnectionState.ERROR) _error.value = null
            }
        }

        // Build machine state from WS data — individual collectors (avoid combine issue)
        viewModelScope.launch {
            session.sensorSnapshot.collect { sensor ->
                _machineState.value = _machineState.value.copy(
                    targetTemperatureStr = sensor.targetTemperature.toString(),
                    temperatureStr = sensor.temperature.toString(),
                    pressureStr = sensor.pressure.toString(),
                    waterLevel = sensor.waterLevel.toString()
                )
            }
        }
        viewModelScope.launch {
            session.machineState.collect { sysState ->
                _machineState.value = _machineState.value.copy(
                    upTime = sysState.uptime.toString()
                )
            }
        }
        // Live shot weight — the rolling chart buffer itself is now fed
        // directly by ShotRepository (survives navigation to the curve screen).
        viewModelScope.launch {
            AppContainer.shotRepo.liveWeight.collect { _liveWeight.value = it }
        }

        // Brew state from WS
        viewModelScope.launch {
            session.brewActive.collect { active ->
                _machineState.value = _machineState.value.copy(
                    brewSwitchState = if (active) "true" else "false"
                )
            }
        }

        // Errors — only surface a takeover for a terminal ERROR, not during auto-reconnect.
        viewModelScope.launch {
            session.errorMessage.collect { err ->
                if (err != null && session.connectionState.value == ConnectionState.ERROR) {
                    _error.value = appString(R.string.dashboard_error_connect, err)
                } else if (err == null) {
                    _error.value = null
                }
            }
        }

        // Profile name from WebSocket d_prof_dict (active profile)
        viewModelScope.launch {
            session.selectedProfileName.collect { name ->
                if (name.isNotEmpty()) {
                    _machineState.value = _machineState.value.copy(profileName = name)
                }
            }
        }

        // Machine mode tracking (for flush/descale/tare active state)
        viewModelScope.launch {
            session.machineMode.collect { mode ->
                _flushActive.value = mode == 2
            }
        }
    }

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }

    // ── Commands (all via WebSocket) ─────────────────────────────

    fun startPolling() {
        // No-op — WS session is always active via MachineSessionManager
    }

    fun stopPolling() {
        // No-op
    }

    fun refresh() {
        // No-op — auto-connected via session
    }

    fun flush() {
        try {
            session.setOpMode(Commands.MODE_FLUSH)
            _message.value = "Flush started"
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun toggleSteam(on: Boolean) {
        // Gaggiuino v3 has no steam WebSocket command — it is driven on the machine.
        _message.value = if (on) "Steam is controlled on the machine" else "Steam off"
    }

    fun tare() {
        try {
            session.tareScale()
            _message.value = "Scale tared"
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun setSetpoint(temperature: Float) {
        try {
            session.updateActiveProfileTemperature(temperature)
            _message.value = "Setpoint ${temperature.toInt()}\u00B0C sent"
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun setWeight(grams: Float) {
        _targetWeight.value = grams
        _currentWeight.value = grams
        try {
            session.updateActiveProfileWeight(grams)
            _message.value = "Target weight ${grams.toInt()}g sent"
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun primePump() {
        // No dedicated WS command in the Gaggiuino v3 opmode set.
        _message.value = "Prime pump: use Flush mode on the machine"
    }

    fun clearMessage() { _message.value = null }
}
