package com.gagmate.app.ui.debug

import androidx.lifecycle.ViewModel
import com.gagmate.app.data.repository.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Drives the hidden-WebView WebUI-probe experiment.
 *
 * Unlike [DebugWsExperimentViewModel] (which speaks our own re-implemented WS
 * protocol), this probe loads the *real* WebUI SPA served by the machine inside
 * an invisible WebView, intercepts every `/api/` request the SPA makes, enumerates
 * all of its buttons (without clicking — zero side effects), and replays any
 * read-only endpoint our app does not yet cover so we can capture the data shape.
 * Settings endpoints are listed but never simulated, per the user's constraint.
 */
class DebugWebUiProbeViewModel : ViewModel() {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    fun appendLog(line: String) {
        _logs.value = (_logs.value + line).takeLast(400)
    }

    fun setStatus(s: String) { _status.value = s }

    fun setSummary(json: String?) { _summary.value = json }

    /**
     * Build the probe config JSON. Returns null (and sets [status]) when the
     * machine address is not yet known.
     */
    fun buildConfigJson(): String? {
        val session = AppContainer.machineSession
        val base = session.httpBaseUrl
        if (base.isBlank() || base == "http://") {
            _status.value = "机器地址未知：请先在「设置」里连接并保存机器地址。"
            return null
        }
        val obj = JSONObject().apply { put("base", base) }
        _status.value = "配置就绪：WebUI 基地址=$base（将加载真实 WebUI 并探测）"
        return obj.toString()
    }
}
