package com.gagmate.app.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.LocaleHelper
import androidx.core.content.FileProvider
import java.io.File
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.repository.MachineRepository
import com.gagmate.app.data.repository.SettingsRepository
import com.gagmate.app.data.api.NetworkLogger
import com.gagmate.app.data.api.ApiDebugLogger
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

    private val _host = MutableStateFlow("192.168.0.186")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow("80")
    val port: StateFlow<String> = _port.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _savedMessage = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> = _savedMessage.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncResult = MutableStateFlow<SyncResultInfo?>(null)
    val syncResult: StateFlow<SyncResultInfo?> = _syncResult.asStateFlow()

    init {
        viewModelScope.launch {
            _host.value = settingsRepository.host.first()
            _port.value = settingsRepository.port.first()
        }
    }

    enum class ConnectionStatus {
        Unknown, Testing, Connected, Failed
    }

    data class SyncResultInfo(
        val profilesAdded: Int = 0,
        val profilesUpdated: Int = 0,
        val profilesConflicted: Int = 0,
        val profilesUploaded: Int = 0,
        val errors: List<String> = emptyList()
    ) {
        val isSuccess: Boolean get() = errors.isEmpty()
        val summary: String get() {
            val parts = mutableListOf<String>()
            if (profilesAdded > 0) parts.add("+$profilesAdded downloaded")
            if (profilesUpdated > 0) parts.add("$profilesUpdated updated")
            if (profilesConflicted > 0) parts.add("$profilesConflicted conflict(s)")
            if (profilesUploaded > 0) parts.add("$profilesUploaded uploaded")
            if (errors.isNotEmpty()) parts.add("${errors.size} error(s)")
            return parts.joinToString(", ").ifEmpty { "Nothing to sync" }
        }
    }

    val availableLanguages = listOf(
        LocaleInfo("", "System Default"),
        LocaleInfo("en", "English"),
        LocaleInfo("zh", "中文")
    )

    data class LocaleInfo(val code: String, val displayName: String)

    private val _currentLang = MutableStateFlow("")
    val currentLang: StateFlow<String> = _currentLang.asStateFlow()

    fun setLanguage(context: Context, langCode: String) {
        _currentLang.value = langCode
        LocaleHelper.setLanguage(context, langCode)
        (context as? android.app.Activity)?.recreate()
    }

    fun initLanguage(context: Context) {
        _currentLang.value = LocaleHelper.getLanguageCode(context)
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

    fun shareLog(context: Context) {
        val logDir = NetworkLogger.getLogDir() ?: run {
            NetworkLogger.share(context); return
        }
        val combinedFile = File(logDir, "gagmate_combined.log")
        combinedFile.bufferedWriter().use { writer ->
            writer.write("===== GagMate Network Log =====\n")
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            writer.write("Generated: $ts\n\n")
            writer.write(">>> REST API (gagmate_network.log) <<<\n")
            NetworkLogger.getLogFile()?.takeIf { it.exists() }?.forEachLine { line ->
                writer.write(line)
                writer.write("\n")
            }
            writer.write("\n>>> WebSocket + API Debug (api_debug.log) <<<\n")
            ApiDebugLogger.getLogFile()?.takeIf { it.exists() }?.forEachLine { line ->
                writer.write(line)
                writer.write("\n")
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            combinedFile
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Network Log"))
    }


    fun clearLog() {
        NetworkLogger.clearLog()
        _savedMessage.value = null
    }

    fun hasLog(): Boolean =
        NetworkLogger.hasLog() || ApiDebugLogger.getLogFile()?.exists() == true

    fun logSizeFormatted(): String {
        val bytes = NetworkLogger.logSize()
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes.toFloat() / (1024 * 1024))} MB"
        }
    }

    fun triggerSync() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncResult.value = null
            val result = AppContainer.syncManager.fullSync()
            _syncResult.value = SyncResultInfo(
                profilesAdded = result.profilesAdded,
                profilesUploaded = result.profilesUploaded,
                profilesUpdated = result.profilesUpdated,
                profilesConflicted = result.profilesConflicted,
                errors = result.errors
            )
            _isSyncing.value = false
        }
    }

    fun uploadPendingProfiles() {
        viewModelScope.launch {
            _isSyncing.value = true
            _syncResult.value = null
            val result = AppContainer.syncManager.uploadPendingProfiles()
            _syncResult.value = SyncResultInfo(
                profilesUploaded = result.profilesUploaded,
                errors = result.errors
            )
            _isSyncing.value = false
        }
    }

    fun clearSyncResult() {
        _syncResult.value = null
    }

    fun clearMessage() {
        _savedMessage.value = null
    }
}
