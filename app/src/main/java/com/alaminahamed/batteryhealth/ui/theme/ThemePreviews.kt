package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.alaminahamed.batteryhealth.domain.HealthBand
import com.alaminahamed.batteryhealth.ui.components.BigMetric
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.ProgressTrack
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.ui.components.Value

/**
 * Every shared primitive in one column, so a change to a token is visible in four previews
 * at once. This exists because the app now carries two design languages and the second one
 * only stays maintained if it is easy to look at — see the spec's Risks section.
 */
@Composable
private fun PrimitiveSampler() {
    val language = LocalDesignLanguage.current
    Column(Modifier.fillMaxWidth().background(language.colors.canvas)) {
        SectionHeader("Battery health")
        OneUiCard {
            BigMetric(
                value = "87",
                unit = "%",
                color = language.colors.forBand(HealthBand.Good),
            )
            ProgressTrack(fraction = 0.87f, color = language.colors.accent)
            KeyValueRow(label = "Cycles") { Value("619") }
            KeyValueRow(label = "Design capacity") { Value("5000 mAh") }
            KeyValueRow(label = "Full charge", showDivider = false) { Value("4350 mAh") }
        }
    }
}

@Preview(name = "One UI · light", showBackground = true)
@Composable
private fun PreviewOneUiLight() {
    BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) { PrimitiveSampler() }
}

@Preview(name = "One UI · dark", showBackground = true)
@Composable
private fun PreviewOneUiDark() {
    BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = true) { PrimitiveSampler() }
}

@Preview(name = "Expressive · light", showBackground = true)
@Composable
private fun PreviewExpressiveLight() {
    BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) { PrimitiveSampler() }
}

@Preview(name = "Expressive · dark", showBackground = true)
@Composable
private fun PreviewExpressiveDark() {
    BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = true) { PrimitiveSampler() }
}

@Preview(name = "Expressive · light · large font", showBackground = true, fontScale = 1.8f)
@Composable
private fun PreviewExpressiveLargeFont() {
    BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) { PrimitiveSampler() }
}
