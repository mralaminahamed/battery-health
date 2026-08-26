package com.alaminahamed.batteryhealth.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alaminahamed.batteryhealth.ui.components.CollapsingTitleScaffold
import com.alaminahamed.batteryhealth.ui.health.HealthScreen
import com.alaminahamed.batteryhealth.ui.history.HistoryScreen
import com.alaminahamed.batteryhealth.ui.live.LiveScreen
import com.alaminahamed.batteryhealth.ui.settings.SettingsScreen
import com.alaminahamed.batteryhealth.ui.settings.SettingsViewModel
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

/**
 * [Settings] is a real [NavHost] destination -- it needs a route and a title the same as
 * every other screen -- but is deliberately excluded from the bottom [NavigationBar]
 * loop below rather than becoming its fourth item. Three content tabs with text-only
 * labels (no icon set is depended on anywhere else in this app either) already use most
 * of a phone-width bar's room; Settings is also qualitatively different from the other
 * three -- app configuration, not a data view -- so it gets its own entry point instead:
 * a gear glyph in the top bar, a plain-text glyph so this doesn't need a Material Icons
 * dependency this project otherwise has no reason to add.
 */
enum class Destination(val route: String, val label: String) {
    Health("health", "Health"),
    Live("live", "Live"),
    History("history", "History"),
    Settings("settings", "Settings"),
}

private val BOTTOM_NAV_DESTINATIONS = Destination.entries.filterNot { it == Destination.Settings }

object BatteryHealthAppTags {
    const val SETTINGS_ACTION = "settings-top-bar-action"
}

@Composable
fun BatteryHealthApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.Health.route
    val current = Destination.entries.firstOrNull { it.route == currentRoute } ?: Destination.Health

    CollapsingTitleScaffold(
        title = current.label,
        actions = {
            if (current != Destination.Settings) {
                val colors = LocalOneUiColors.current
                Text(
                    text = "⚙",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable {
                            navController.navigate(Destination.Settings.route) {
                                launchSingleTop = true
                            }
                        }
                        .testTag(BatteryHealthAppTags.SETTINGS_ACTION),
                )
            }
        },
        bottomBar = {
            val colors = LocalOneUiColors.current
            NavigationBar(containerColor = colors.card) {
                BOTTOM_NAV_DESTINATIONS.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == current,
                        onClick = {
                            if (destination != current) {
                                navController.navigate(destination.route) {
                                    popUpTo(Destination.Health.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Text(
                                text = destination.label,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.accent,
                            unselectedIconColor = colors.textSecondary,
                            indicatorColor = colors.card,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Health.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Health.route) { HealthScreen() }
            composable(Destination.Live.route) { LiveScreen() }
            composable(Destination.History.route) { HistoryScreen() }
            composable(Destination.Settings.route) {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(viewModel = viewModel, onDesignLanguageChange = viewModel::setDesignLanguage)
            }
        }
    }
}
