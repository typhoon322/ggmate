package com.gagmate.app.ui.dashboard

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.R
import com.gagmate.app.data.repository.MachineRepository
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 * Polls ggboard at 2-second intervals for real-time machine data.
 */
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MachineRepository()

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
                        _machineState.value = state
                        _isConnected.value = true
                        _isLoading.value = false
                        _error.value = null

                        if (state.isActive && state.brewTime > 0) {
                            val points = _chartData.value.toMutableList()
                            points.add(ChartPoint(
                                time = state.brewTime,
                                pressure = state.pressure,
                                flowRate = state.flow
                            ))
                            if (points.size > 300) points.removeAt(0)
                            _chartData.value = points
                        } else if (!state.isActive && _chartData.value.isNotEmpty()) {
                            val settled = _chartData.value.size > 2
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
    }

    fun startBrew() {
        viewModelScope.launch {
            repository.startBrew()
        }
    }

    fun stopBrew() {
        viewModelScope.launch {
            repository.stopBrew()
        }
    }

    fun refresh() {
        _isLoading.value = true
        startPolling()
    }

    
    fun primePump() {
        viewModelScope.launch {
            repository.primePump()
        }
    }

    fun flush() {
        viewModelScope.launch {
            repository.flush()
        }
    }

    fun toggleSteam(on: Boolean) {
        viewModelScope.launch {
            repository.toggleSteam(on)
        }
    }

    fun setSetpoint(temperature: Float) {
        viewModelScope.launch {
            repository.setSetpoint(temperature)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
