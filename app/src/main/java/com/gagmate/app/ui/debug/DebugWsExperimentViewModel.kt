package com.gagmate.app.ui.debug

import androidx.lifecycle.ViewModel
import com.gagmate.app.data.repository.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Drives the invisible-WebView protocol experiment.
 *
 * The experiment harness (`assets/ws_experiment.html`) connects to the machine's
 * WebSocket and probes how profile details are actually fetched — specifically
 * whether `g_prof(id)` returns the *requested* profile or only the *active* one.
 * This is the ground-truth check for how the official Gaggiuino WebUI obtains
 * profile curves, replacing guesswork with captured logs.
 */
class DebugWsExperimentViewModel : ViewModel() {

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
     * Build the experiment config JSON from the live machine session.
     * Returns null (and sets [status]) when the machine/profiles are not ready.
     */
    fun buildConfigJson(): String? {
        val session = AppContainer.machineSession
        val wsUrl = session.wsUrl
        if (wsUrl.isBlank() || wsUrl == "ws:///ws") {
            _status.value = "机器地址未知：请先在「设置」里连接并保存机器地址。"
            return null
        }
        val profiles = session.currentProfiles.value
        val activeId = session.selectedProfileId.value
        val activeName = session.selectedProfileName.value.ifBlank {
            profiles.firstOrNull { it.isSelected }?.name ?: ""
        }
        val target = profiles.firstOrNull { !it.isSelected }
        if (target == null) {
            _status.value = "没有「非活跃」profile 可供测试（当前只有活跃 profile）。请创建一个未选中的 profile 后再试。"
            return null
        }
        if (activeName.isBlank()) {
            _status.value = "未识别到活跃 profile 名称，无法比对。请确认机器已连接且 d_prof_dict 已到达。"
            return null
        }
        val obj = JSONObject().apply {
            put("wsUrl", wsUrl)
            put("targetId", target.id)
            put("targetName", target.name)
            put("activeId", activeId)
            put("activeName", activeName)
        }
        _status.value = "配置就绪：目标='${target.name}'(id=${target.id})，活跃='$activeName'(id=$activeId)"
        return obj.toString()
    }
}
