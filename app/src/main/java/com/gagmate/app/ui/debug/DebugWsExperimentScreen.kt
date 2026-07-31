package com.gagmate.app.ui.debug

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Hidden-WebView protocol experiment screen.
 *
 * A 1×1 invisible WebView loads `assets/ws_experiment.html`, which speaks the
 * *exact same* Gaggiuino WebSocket protocol the official WebUI uses (ported from
 * our ProtoCodec/ProtoCommands). It connects to the machine, captures the real
 * `d_prof_dict` and the response to `g_prof(id)` for a non-active profile, and
 * reports back via the [WsExperimentJsBridge]. The log panel surfaces the result
 * in-app so no adb/logcat capture is needed.
 *
 * The experiment also verifies whether `g_prof(id)` returns the *requested*
 * profile without `selectProfile` (so we could show any curve with zero side
 * effects), or only after selecting it active (matching the WebUI's "tap to
 * select → preview" behaviour).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugWsExperimentScreen(
    viewModel: DebugWsExperimentViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val logs by viewModel.logs.collectAsState()
    val status by viewModel.status.collectAsState()
    val summary by viewModel.summary.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val bridge = remember { WsExperimentJsBridge(viewModel) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("WebSocket 协议实验") },
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
                    status.ifBlank { "填写机器地址并连接后，点「运行实验」。" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    viewModel.setSummary(null)
                    val cfg = viewModel.buildConfigJson()
                    if (cfg != null) {
                        if (pageReady) {
                            // JSON is valid JS object-literal syntax, so inject directly.
                            webView?.evaluateJavascript("runExperiment($cfg)", null)
                        } else {
                            viewModel.appendLog("WebView 尚未就绪，请稍候再点一次「运行实验」。")
                        }
                    }
                }) { Text("运行实验") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "实验会另开一个 WebSocket 连接到机器（与 App 现有连接并存），结束时会恢复活跃 profile。",
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
                Text("实验日志", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize().weight(1f)) {
                    items(logs) { LogLine(it) }
                }
            }
        }

        // Invisible WebView that runs the harness. Sized 1×1 so it never shows,
        // but remains attached (and thus executes JS) for the whole screen lifetime.
        AndroidView(
            modifier = Modifier
                .size(1.dp)
                .align(Alignment.TopStart),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }
                    }
                    addJavascriptInterface(bridge, "GagMateBridge")
                    loadUrl("file:///android_asset/ws_experiment.html")
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
