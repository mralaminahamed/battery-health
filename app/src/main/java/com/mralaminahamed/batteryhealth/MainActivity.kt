package com.mralaminahamed.batteryhealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mralaminahamed.batteryhealth.ui.nav.BatteryHealthApp
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before setContent: it swaps the starting theme for the app theme and
        // holds the first frame until the content is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryHealthTheme {
                BatteryHealthApp()
            }
        }
    }
}
