package com.gagmate.app.ui.history

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.api.ShotRecord
import com.gagmate.app.R
import com.gagmate.app.data.repository.MachineRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ShotHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MachineRepository()
    private val gson = Gson()

    private val _shots = MutableStateFlow<List<ShotRecord>>(emptyList())
    val shots: StateFlow<List<ShotRecord>> = _shots.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }
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
                    _error.value = appString(R.string.history_load_failed, e.message ?: "")
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
                    _error.value = appString(R.string.history_delete_failed, e.message ?: "")
                }
            )
        }
    }
}
