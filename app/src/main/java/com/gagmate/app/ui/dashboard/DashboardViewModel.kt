package com.gagmate.app.ui.dashboard

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.repository.MachineRepository
import com.gagmate.app.data.api.GaggiuinoV3Client
import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.system.SoundManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 * Polls ggboard at 2-second intervals for real-time machine data.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MachineRepository()
    private val wsClient: GaggiuinoV3Client by lazy {
        val url = GgboardApiClient.getCurrentBaseUrl()
        val host = url.removePrefix("http://").removeSuffix("/")
        GaggiuinoV3Client(host).apply {
            setListener(object : GaggiuinoV3Client.Listener {
                override fun onConnected() { /* WS ready for commands */ }
                override fun onDisconnected() { /* WS closed */ }
                override fun onMessage(command: String, payload: ByteArray) {
                    // Handle incoming data (state updates, etc.)
                }
                override fun onSysState(state: GaggiuinoV3Client.SystemState) { }
                override fun onProfileUpdated(name: String) { }
                override fun onSensorSnapshot(snapshot: GaggiuinoV3Client.SensorSnapshot) { }
                override fun onProfileDictReceived(profiles: List<ProfileRef>) { }
                override fun onShotHistoryIndex(shots: List<GaggiuinoV3Client.ShotIndexEntry>) {
                    // Shot sync is handled by SyncManager.syncShots() which fetches full details via REST API.
                    // Do NOT save minimal entities here as they lack duration and datapoints.
                }
                override fun onSettingsReceived(settings: Map<String, String>) { }
                override fun onError(error: String) { }
                override fun onShotSnapshot(snapshot: GaggiuinoV3Client.ShotSnapshot) {
                    val point = ChartPoint(
                        time = snapshot.timeInShot / 1000f,
                        pressure = snapshot.pressure,
                        flowRate = snapshot.flow
                    )
                    _chartData.value = (_chartData.value + point).takeLast(2000)
                    // Update live weight from scale reading
                    _liveWeight.value = snapshot.weight
                }
            })
        }
    }

    private val _machineState = MutableStateFlow<MachineState?>(null)
    val machineState: StateFlow<MachineState?> = _machineState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }

    private val _chartData = MutableStateFlow<List<ChartPoint>>(emptyList())
    val chartData: StateFlow<List<ChartPoint>> = _chartData.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: Job? = null

    companion object {
        private const val POLL_INTERVAL_MS = 2000L
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            _isLoading.value = true
            while (isActive) {
                repository.getMachineState().fold(
                    onSuccess = { state ->
                        val wasDisconnected = !_isConnected.value
                        _machineState.value = state
                        _isConnected.value = true
                        _isLoading.value = false
                        _error.value = null

                        // Trigger a full sync + connect WebSocket once when we first connect
                        if (wasDisconnected) {
                            SoundManager.playConnectionSuccess(getApplication())
                            try { wsClient.connect() } catch (_: Exception) { }
                            launch {
                                AppContainer.syncManager.fullSync()
                            }
                        }
                        
                        // Track brew state changes for live chart
                        val wasBrewing = _machineState.value?.isBrewing ?: false
                        if (state.isBrewing && !wasBrewing) {
                            // Brew just started - clear previous chart data
                            _chartData.value = emptyList()
                        }
                        
                        // Update live weight from machine's weight reading
                        val machineWeight = state.weight.toFloatOrNull() ?: 0f
                        if (machineWeight > 0f || state.isBrewing) {
                            _liveWeight.value = machineWeight
                        }

                        // Live chart data requires WebSocket – not available via REST
                        if (!state.isActive && _chartData.value.isNotEmpty()) {
                            // clear chart when brew stops
                        }
                    },
                    onFailure = { e ->
                        if (_isConnected.value) {
                            _error.value = appString(R.string.dashboard_error_lost, e.message ?: "")
                        } else {
                            _error.value = appString(R.string.dashboard_error_connect, e.message ?: "")
                        }
                        _isConnected.value = false
                        _isLoading.value = false
                    }
                )
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        try { wsClient.disconnect() } catch (_: Exception) { }
    }

    fun startBrew() {
        _error.value = "Brew start requires WebSocket"
    }

    fun stopBrew() {
        _error.value = "Brew stop requires WebSocket"
    }

    fun refresh() {
        _isLoading.value = true
        startPolling()
    }

    fun primePump() {
        _error.value = "Prime pump requires WebSocket"
    }

    private var _currentWeight = MutableStateFlow(40f)
    val currentWeight: StateFlow<Float> = _currentWeight.asStateFlow()

    private val _liveWeight = MutableStateFlow(0f)
    val liveWeight: StateFlow<Float> = _liveWeight.asStateFlow()

    private val _targetWeight = MutableStateFlow(40f)
    val targetWeight: StateFlow<Float> = _targetWeight.asStateFlow()

    /** Set the target brew weight (globalStopConditions.weight). */
    fun setWeight(grams: Float) {
        val name = _machineState.value?.profileName ?: ""
        if (name.isBlank()) { _error.value = "No active profile"; return }
        _targetWeight.value = grams
        _currentWeight.value = grams
        try {
            wsClient.updateActiveProfile(name, weight = grams)
        } catch (_: Exception) {
            _error.value = "WebSocket not connected"
        }
    }

    fun flush() {
        wsClient.setFlush(true)
        _error.value = "Flush started"
    }

    fun toggleSteam(on: Boolean) {
        _error.value = if (on) "Steam on (via WebSocket)" else "Steam off"
    }

    fun tare() {
        try {
            wsClient.tareScale()
        } catch (_: Exception) {
            _error.value = "WebSocket not connected"
        }
    }

    fun setSetpoint(temperature: Float) {
        val name = _machineState.value?.profileName ?: ""
        if (name.isBlank()) { _error.value = "No active profile"; return }
        try {
            wsClient.updateActiveProfile(name, temp = temperature)
        } catch (_: Exception) {
            _error.value = "WebSocket not connected"
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        try { wsClient.disconnect() } catch (_: Exception) { }
    }
}
