package com.gagmate.app.ui.history

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.ShotRecord
import com.gagmate.app.R
import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.repository.MachineRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShotHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepo = AppContainer.localRepo
    private val machineRepo = MachineRepository()
    private val gson = Gson()

    private val _shots = MutableStateFlow<List<ShotEntity>>(emptyList())
    val shots: StateFlow<List<ShotEntity>> = _shots.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }

    init {
        viewModelScope.launch {
            localRepo.shotsFlow.collectLatest { list ->
                _shots.value = list
            }
        }
    }

    fun loadShots() {
        // Show local data immediately
        _isLoading.value = true
        viewModelScope.launch {
            val local = localRepo.getAllShots()
            _shots.value = local.sortedByDescending { it.timestamp }
            _isLoading.value = false
            _error.value = null
        }
        // Sync from machine in background (don't block UI)
        viewModelScope.launch {
            try {
                AppContainer.syncManager.fullSync()
            } catch (e: Exception) {
                // silent – local data is already displayed
            }
        }
    }

    fun exportShotAsJson(shot: ShotEntity): String {
        return gson.toJson(shot.toShotRecord())
    }

    fun deleteShot(shotId: String) {
        viewModelScope.launch {
            // Remove from local DB; no DELETE endpoint in Gaggiuino v3 REST API
            localRepo.deleteShot(shotId)
        }
    }
}
