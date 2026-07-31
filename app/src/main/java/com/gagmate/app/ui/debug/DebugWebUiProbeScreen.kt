package com.gagmate.app.ui.debug

import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

/**
 * Hidden-WebView WebUI-probe screen.
 *
 * A full-size but invisible (alpha 0) WebView navigates to the *real* WebUI SPA
 * served by the machine. Every `/api/` request the SPA makes is logged via
 * [WebViewClient.shouldInterceptRequest] (captures startup calls too, with no
 * injection-order race). Once the SPA is loaded, [runProbe] from
 * `assets/ws_webui_probe.js` is injected and run: it enumerates every button
 * (labels only — never clicks), regexes the SPA's JS bundle for `/api/...`
 * endpoints, and replays any read-only endpoint our app does not yet cover so we
 * can capture the data shape. Settings endpoints are listed but never simulated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugWebUiProbeScreen(
    viewModel: DebugWebUiProbeViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val logs by viewModel.logs.collectAsState()
    val status by viewModel.status.collectAsState()
    val summary by viewModel.summary.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingCfg by remember { mutableStateOf<String?>(null) }
    val bridge = remember { WebUiProbeJsBridge(viewModel) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("WebUI 探测") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { inner ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp)
            ) {
                Text(
                    status.ifBlank { "填写机器地址并连接后，点「运行探测」。会加载真实 WebUI 并枚举按钮 + 重放未覆盖的可读接口。" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val cfg = viewModel.buildConfigJson()
                    if (cfg != null) {
                        viewModel.setSummary(null)
                        viewModel.appendLog("加载真实 WebUI: " + JSONObject(cfg).optString("base", "") + " ...")
                        pendingCfg = cfg
                        val base = JSONObject(cfg).optString("base", "")
                        webView?.loadUrl(base)
                    }
                }) { Text("运行探测") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "只读探测：枚举所有按钮（不点击）、重放本 App 未覆盖的可读 GET 接口；设置类接口已排除、绝不改动。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                summary?.let {
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("结论", style = MaterialTheme.typography.titleSmall)
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("探测日志", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                    items(logs) { LogLine(it) }
                }
            }
        }

        // Invisible (alpha 0) full-size WebView so the SPA gets a real viewport
        // yet stays hidden behind the Scaffold. It loads the machine's own WebUI.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.TopStart)
                .alpha(0.001f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: ""
                            if (url.contains("/api/")) {
                                viewModel.appendLog("WEBUI REQ " + (request?.method ?: "GET") + " " + url)
                            }
                            return null // let the WebView handle it normally
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            val cfg = pendingCfg
                            if (cfg != null) {
                                pendingCfg = null
                                try {
                                    val js = view?.context?.assets
                                        ?.open("ws_webui_probe.js")
                                        ?.bufferedReader()
                                        ?.readText() ?: ""
                                    view?.evaluateJavascript(js + "\n;runProbe($cfg);", null)
                                } catch (e: Exception) {
                                    viewModel.appendLog("注入探针失败: " + e.message)
                                }
                            }
                        }
                    }
                    addJavascriptInterface(bridge, "GagMateBridge")
                }.also { webView = it }
            }
        )
    }
}

@Composable
private fun LogLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
}
