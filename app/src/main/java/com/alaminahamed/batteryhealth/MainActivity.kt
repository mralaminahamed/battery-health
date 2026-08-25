package com.alaminahamed.batteryhealth

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.ui.nav.BatteryHealthApp
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import com.alaminahamed.batteryhealth.ui.theme.resolveDesignLanguageId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before setContent: it swaps the starting theme for the app theme and
        // holds the first frame until the content is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val choice by settings.designLanguageChoice.collectAsStateWithLifecycle(
                initialValue = DesignLanguageChoice.Auto,
            )
            BatteryHealthTheme(
                languageId = resolveDesignLanguageId(choice, Build.MANUFACTURER.orEmpty()),
            ) {
                BatteryHealthApp()
            }
        }
    }
}
