package com.mralaminahamed.batteryhealth.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mralaminahamed.batteryhealth.ui.components.CollapsingTitleScaffold
import com.mralaminahamed.batteryhealth.ui.health.HealthScreen
import com.mralaminahamed.batteryhealth.ui.live.LiveScreen
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors

enum class Destination(val route: String, val label: String) {
    Health("health", "Health"),
    Live("live", "Live"),
}

@Composable
fun BatteryHealthApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Destination.Health.route
    val current = Destination.entries.firstOrNull { it.route == currentRoute } ?: Destination.Health

    CollapsingTitleScaffold(
        title = current.label,
        bottomBar = {
            val colors = LocalOneUiColors.current
            NavigationBar(containerColor = colors.card) {
                Destination.entries.forEach { destination ->
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
        }
    }
}
