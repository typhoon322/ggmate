package com.gagmate.app.ui.dashboard

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.session.ConnectionState
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

    /** Live chart data from the brew shot. */
    val chartData: StateFlow<List<ChartPoint>> = AppContainer.shotRepo.chartData

    // ── Weight management ────────────────────────────────────────

    private val _currentWeight = MutableStateFlow(40f)
    val currentWeight: StateFlow<Float> = _currentWeight.asStateFlow()

    private val _liveWeight = MutableStateFlow(0f)
    val liveWeight: StateFlow<Float> = _liveWeight.asStateFlow()

    private val _targetWeight = MutableStateFlow(40f)
    val targetWeight: StateFlow<Float> = _targetWeight.asStateFlow()

    companion object {
        /** Threshold in ms: timeInShot below this means a new shot. */
        private const val NEW_SHOT_THRESHOLD_MS = 100
    }

    // ── Initialisation ────────────────────────────────────────────

    init {
        // Connection state
        viewModelScope.launch {
            session.connectionState.collect { state ->
                val wasConnecting = _isLoading.value
                when (state) {
                    ConnectionState.CONNECTED -> {
                        _isConnected.value = true
                        // Sound played once by MachineSessionManager.onOpen
                        _isLoading.value = false
                        _error.value = null
                    }
                    ConnectionState.CONNECTING -> {
                        _isLoading.value = true
                    }
                    ConnectionState.DISCONNECTED -> {
                        _isConnected.value = false
                        _isLoading.value = false
                    }
                    ConnectionState.ERROR -> {
                        _isConnected.value = false
                        _isLoading.value = false
                    }
                }
            }
        }

        // Build machine state from WS data
        viewModelScope.launch {
            combine(
                session.sensorSnapshot,
                session.machineState,
                session.shotSnapshot
            ) { sensor, sysState, shot ->
                MachineState(
                    upTime = sysState.uptime.toString(),
                    profileName = _machineState.value.profileName, // preserve from REST
                    targetTemperatureStr = sensor.targetTemperature.toString(),
                    temperatureStr = sensor.temperature.toString(),
                    pressureStr = sensor.pressure.toString(),
                    waterLevel = sensor.waterLevel.toString(),
                    weight = (shot?.weight ?: 0f).toString(),
                    brewSwitchState = "false",
                    steamSwitchState = "false"
                )
            }.collect { state ->
                val wasBrewing = _machineState.value.isBrewing
                _machineState.value = state

                // Brew just started — clear previous chart
                if (state.isBrewing && !wasBrewing) {
                    AppContainer.shotRepo.clearChart()
                }
            }
        }

        // Shot snapshots → chart data
        viewModelScope.launch {
            session.shotSnapshot.collect { snapshot ->
                if (snapshot != null) {
                    if (snapshot.timeInShot < NEW_SHOT_THRESHOLD_MS) {
                        AppContainer.shotRepo.clearChart()
                    }
                    AppContainer.shotRepo.appendShotPoint(snapshot)
                    _liveWeight.value = snapshot.weight
                }
            }
        }

        // Brew state from WS
        viewModelScope.launch {
            session.brewActive.collect { active ->
                _machineState.value = _machineState.value.copy(
                    brewSwitchState = if (active) "true" else "false"
                )
            }
        }

        // Errors
        viewModelScope.launch {
            session.errorMessage.collect { err ->
                if (err != null) {
                    _error.value = if (_isConnected.value) {
                        appString(R.string.dashboard_error_lost, err)
                    } else {
                        appString(R.string.dashboard_error_connect, err)
                    }
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

    fun startBrew() {
        _error.value = "Brew start requires WebSocket command"
    }

    fun stopBrew() {
        _error.value = "Brew stop requires WebSocket command"
    }

    fun flush() {
        try {
            session.setOpMode(com.gagmate.app.data.protocol.Commands.MODE_FLUSH)
            _error.value = "Flush started"
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun toggleSteam(on: Boolean) {
        _error.value = if (on) "Steam via WS" else "Steam off"
    }

    fun tare() {
        try {
            session.setOpMode(com.gagmate.app.data.protocol.Commands.MODE_TARE)
        } catch (_: Exception) {
            _error.value = appString(R.string.dashboard_error_connect, "WS not connected")
        }
    }

    fun setSetpoint(temperature: Float) {
        // Handled via WS updateActiveProfile — requires Profile comms
        _error.value = "Setpoint via WS (profile update)"
    }

    fun setWeight(grams: Float) {
        _targetWeight.value = grams
        _currentWeight.value = grams
        // Handled via WS updateActiveProfile
    }

    fun primePump() {
        _error.value = "Prime pump requires WebSocket"
    }
}
