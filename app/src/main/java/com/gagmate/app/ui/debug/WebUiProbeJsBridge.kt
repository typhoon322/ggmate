package com.gagmate.app.ui.debug

import android.webkit.JavascriptInterface

/**
 * Bridges the WebUI-probe harness (`assets/ws_webui_probe.js`, injected into the
 * live WebUI page) back to the app. Mirrors [WsExperimentJsBridge].
 */
class WebUiProbeJsBridge(private val vm: DebugWebUiProbeViewModel) {

    @JavascriptInterface
    fun log(line: String) {
        vm.appendLog(line)
    }

    @JavascriptInterface
    fun done(json: String) {
        vm.setSummary(json)
    }
}
