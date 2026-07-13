package com.gagmate.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.api.ShotRecord
import com.gagmate.app.data.repository.MachineRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShotHistoryViewModel : ViewModel() {

    private val repository = MachineRepository()
    private val gson = Gson()

    private val _shots = MutableStateFlow<List<ShotRecord>>(emptyList())
    val shots: StateFlow<List<ShotRecord>> = _shots.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadShots() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getShotHistory().fold(
                onSuccess = { list ->
                    _shots.value = list
                    _error.value = null
                },
                onFailure = { e ->
                    _error.value = "Failed to load shots: ${e.message}"
                }
            )
            _isLoading.value = false
        }
    }

    fun exportShotAsJson(shot: ShotRecord): String {
        return gson.toJson(shot)
    }

    fun deleteShot(shotId: String) {
        viewModelScope.launch {
            repository.deleteShotHistory(shotId).fold(
                onSuccess = { loadShots() },
                onFailure = { e ->
                    _error.value = "Delete failed: ${e.message}"
                }
            )
        }
    }
}
