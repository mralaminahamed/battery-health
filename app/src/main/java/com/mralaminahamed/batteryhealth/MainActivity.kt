package com.mralaminahamed.batteryhealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mralaminahamed.batteryhealth.ui.live.LiveScreen
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors
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
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = LocalOneUiColors.current.canvas,
                ) { innerPadding ->
                    LiveScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
