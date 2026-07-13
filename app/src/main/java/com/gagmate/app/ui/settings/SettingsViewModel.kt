package com.gagmate.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.repository.MachineRepository
import com.gagmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 * Manages ggboard connection configuration and app settings.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val machineRepository = MachineRepository()

    private val _host = MutableStateFlow("192.168.4.1")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow("80")
    val port: StateFlow<String> = _port.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _savedMessage = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> = _savedMessage.asStateFlow()

    enum class ConnectionStatus {
        Unknown, Testing, Connected, Failed
    }

    fun loadSettings() {
        viewModelScope.launch {
            _host.value = settingsRepository.host.first()
            _port.value = settingsRepository.port.first()
        }
    }

    fun updateHost(newHost: String) {
        _host.value = newHost
    }

    fun updatePort(newPort: String) {
        _port.value = newPort
    }

    fun saveAndApply() {
        viewModelScope.launch {
            settingsRepository.saveSettings(_host.value, _port.value)
            machineRepository.updateConnection(_host.value, _port.value.toIntOrNull() ?: 80)
            _savedMessage.value = "Connection settings saved"
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.Testing

            // Apply current settings first
            val hostValue = _host.value
            val portValue = _port.value.toIntOrNull() ?: 80
            machineRepository.updateConnection(hostValue, portValue)

            // Try to fetch state
            machineRepository.getMachineState().fold(
                onSuccess = {
                    _connectionStatus.value = ConnectionStatus.Connected
                },
                onFailure = {
                    _connectionStatus.value = ConnectionStatus.Failed
                }
            )
        }
    }

    fun clearMessage() {
        _savedMessage.value = null
    }
}
