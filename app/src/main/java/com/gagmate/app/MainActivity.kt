package com.gagmate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gagmate.app.theme.GagMateTheme
import com.gagmate.app.ui.navigation.AppNavigation

/**
 * Main entry point for the GagMate Android app.
 * Sets up edge-to-edge rendering and initializes the Compose navigation.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GagMateTheme {
                AppNavigation()
            }
        }
    }
}
