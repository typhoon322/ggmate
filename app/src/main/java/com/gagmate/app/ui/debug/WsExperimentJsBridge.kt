package com.gagmate.app.ui.debug

import android.webkit.JavascriptInterface

/**
 * Bridges the experiment harness (`assets/ws_experiment.html`) back to the app.
 * The harness calls these methods from its WebSocket event handlers.
 */
class WsExperimentJsBridge(private val vm: DebugWsExperimentViewModel) {

    @JavascriptInterface
    fun log(line: String) {
        vm.appendLog(line)
    }

    @JavascriptInterface
    fun done(json: String) {
        vm.setSummary(json)
    }
}
