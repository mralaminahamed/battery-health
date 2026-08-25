package com.alaminahamed.batteryhealth

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.alaminahamed.batteryhealth.ui.nav.BatteryHealthApp
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import com.alaminahamed.batteryhealth.ui.theme.resolveDesignLanguageId
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
            BatteryHealthTheme(
                languageId = resolveDesignLanguageId(DesignLanguageChoice.Auto, Build.MANUFACTURER),
            ) {
                BatteryHealthApp()
            }
        }
    }
}
