package pl.kajakapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private object Routes {
    const val RIVERS = "rivers"
    const val TRIPS = "trips"
    const val SECTION = "section/{id}"
    const val TRIP = "trip/{id}"
    const val ADD_ROUTE = "route/new"
    const val SETTINGS = "settings"
    const val ARG_ID = "id"

    fun section(id: Long) = "section/$id"
    fun trip(id: Long) = "trip/$id"
}

private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun KajakAppRoot() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = route == Routes.RIVERS || route == Routes.TRIPS

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.RIVERS,
                        onClick = { nav.switchTab(Routes.RIVERS) },
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        label = { Text("Rzeki") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.TRIPS,
                        onClick = { nav.switchTab(Routes.TRIPS) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text("Spływy") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.RIVERS,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.RIVERS) {
                RiversScreen(
                    onOpenSection = { nav.navigate(Routes.section(it)) },
                    onAddRoute = { nav.navigate(Routes.ADD_ROUTE) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.TRIPS) {
                TripsScreen(
                    onOpenTrip = { nav.navigate(Routes.trip(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.ADD_ROUTE) {
                AddRouteScreen(
                    onBack = { nav.popBackStack() },
                    onSaved = { sectionId ->
                        // Wracamy do listy rzek (tam startuje wysyłka) i otwieramy nową trasę.
                        nav.popBackStack()
                        nav.navigate(Routes.section(sectionId))
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route = Routes.SECTION,
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                SectionScreen(
                    sectionId = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L,
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                route = Routes.TRIP,
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                TripDetailScreen(
                    tripId = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L,
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
