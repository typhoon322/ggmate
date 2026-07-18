package com.gagmate.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.gagmate.app.data.api.NetworkLogger
import com.gagmate.app.data.api.ApiDebugLogger
import com.gagmate.app.data.repository.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gagmate.app.theme.GagMateTheme
import com.gagmate.app.ui.navigation.AppNavigation

/**
 * Main entry point for the GagMate Android app.
 * Sets up edge-to-edge rendering and initializes the Compose navigation.
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkLogger.init(this)
        ApiDebugLogger.init(this)
        AppContainer.init(this)
        // Background sync when machine URL is already configured
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            kotlinx.coroutines.delay(500)  // let UI settle
            try {
                AppContainer.syncManager.fullSync()
            } catch (_: Exception) { /* silent – user can sync from Settings */ }
        }
        enableEdgeToEdge()
        setContent {
            GagMateTheme {
                AppNavigation()
            }
        }
    }
}
