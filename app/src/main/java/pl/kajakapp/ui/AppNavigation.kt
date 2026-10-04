package pl.kajakapp.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.kajakapp.util.Fmt
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
    const val START = "start"
    const val RIVERS = "rivers"
    const val TRIPS = "trips"
    const val HISTORY = "history"
    const val TRACK = "track/{id}"
    const val SECTION = "section/{id}"
    const val TRIP = "trip/{id}"
    const val ADD_ROUTE = "route/new"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val ARG_ID = "id"

    fun section(id: Long) = "section/$id"
    fun trip(id: Long) = "trip/$id"
    fun track(id: Long) = "track/$id"
}

private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun KajakAppRoot(openTripId: Long = -1L, onOpenTripHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    LaunchedEffect(openTripId) {
        if (openTripId > 0) {
            nav.navigate(Routes.trip(openTripId)) { launchSingleTop = true }
            onOpenTripHandled()
        } else if (openTripId == 0L) {
            onOpenTripHandled()
        }
    }
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBottomBar = route == Routes.START || route == Routes.RIVERS || route == Routes.TRIPS || route == Routes.HISTORY
    val container = rememberContainer()
    val recording by container.recorder.live.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (showBottomBar) Column {
                // Pasek przypominający o trwającym nagrywaniu (widoczny na wszystkich zakładkach).
                if (route != Routes.START) recording?.let { live ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.fillMaxWidth().clickable { nav.switchTab(Routes.START) }
                    ) {
                        Text(
                            "● Nagrywanie trasy\t${Fmt.distance(live.distanceM)}\t${Fmt.speed(live.speedKmh)} – otwórz",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                val tabColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.START,
                        onClick = { nav.switchTab(Routes.START) },
                        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                        colors = tabColors,
                        label = { Text("Start") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.HISTORY,
                        onClick = { nav.switchTab(Routes.HISTORY) },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        colors = tabColors,
                        label = { Text("Historia") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.TRIPS,
                        onClick = { nav.switchTab(Routes.TRIPS) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        colors = tabColors,
                        label = { Text("Spływy") }
                    )
                    NavigationBarItem(
                        selected = route == Routes.RIVERS,
                        onClick = { nav.switchTab(Routes.RIVERS) },
                        icon = { Icon(Icons.Default.Place, contentDescription = null) },
                        colors = tabColors,
                        label = { Text("Rzeki") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.START,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.START) {
                StartScreen(
                    onOpenTrack = { nav.navigate(Routes.track(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
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
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onOpenTrack = { nav.navigate(Routes.track(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(
                route = Routes.TRACK,
                arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                TrackDetailScreen(
                    trackId = entry.arguments?.getLong(Routes.ARG_ID) ?: 0L,
                    onBack = { nav.popBackStack() }
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
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAuth = { nav.navigate(Routes.AUTH) }
                )
            }
            composable(Routes.AUTH) {
                AuthScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
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
