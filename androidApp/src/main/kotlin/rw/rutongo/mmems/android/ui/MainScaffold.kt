package rw.rutongo.mmems.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.ui.screens.BreakdownDetailScreen
import rw.rutongo.mmems.android.ui.screens.BreakdownReportScreen
import rw.rutongo.mmems.android.ui.screens.BreakdownsScreen
import rw.rutongo.mmems.android.ui.screens.DashboardScreen
import rw.rutongo.mmems.android.ui.screens.EquipmentScreen
import rw.rutongo.mmems.android.ui.screens.HomeScreen
import rw.rutongo.mmems.android.ui.screens.InspectionsScreen
import rw.rutongo.mmems.android.ui.screens.MaintenanceScreen
import rw.rutongo.mmems.android.ui.screens.MoreScreen
import rw.rutongo.mmems.android.ui.screens.PlaceholderScreen
import rw.rutongo.mmems.android.ui.screens.StatusUpdateScreen
import rw.rutongo.mmems.android.ui.screens.TasksScreen
import rw.rutongo.mmems.android.ui.wizard.InspectionWizardScreen
import rw.rutongo.mmems.android.ui.wizard.WizardViewModel
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.enums.Role

data class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private fun tabsFor(role: Role): List<MainTab> =
    when (role) {
        Role.MECHANIC ->
            listOf(
                MainTab("home", "Home", Icons.Default.Home),
                MainTab("inspections", "Inspections", Icons.Default.Assignment),
                MainTab("equipment", "Equipment", Icons.Default.Engineering),
                MainTab("tasks", "My tasks", Icons.Default.Build),
                MainTab("maintenance", "Maintenance", Icons.Default.Build),
                MainTab("more", "More", Icons.Default.Menu),
            )
        Role.SUPERVISOR ->
            listOf(
                MainTab("dashboard", "Dashboard", Icons.Default.Dashboard),
                MainTab("inspections", "Inspections", Icons.Default.Assignment),
                MainTab("problems", "Problems", Icons.Default.Warning),
                MainTab("report", "Site report", Icons.Default.Report),
                MainTab("maintenance", "Maintenance", Icons.Default.Build),
                MainTab("more", "More", Icons.Default.Menu),
            )
        Role.MANAGER, Role.GENERAL_MANAGER ->
            listOf(
                MainTab("dashboard", "Dashboard", Icons.Default.Dashboard),
                MainTab("sites", "Sites", Icons.Default.Storefront),
                MainTab("equipment", "Equipment", Icons.Default.Engineering),
                MainTab("maintenance", "Maintenance", Icons.Default.Build),
                MainTab("reports", "Reports", Icons.Default.Report),
                MainTab("more", "More", Icons.Default.Menu),
            )
        Role.ADMINISTRATOR ->
            listOf(
                MainTab("dashboard", "Dashboard", Icons.Default.Dashboard),
                MainTab("operations", "Operations", Icons.Default.Folder),
                MainTab("people", "People", Icons.Default.People),
                MainTab("configuration", "Configuration", Icons.Default.Settings),
                MainTab("more", "More", Icons.Default.Menu),
            )
    }

private fun startRoute(role: Role): String = if (role == Role.MECHANIC) "home" else "dashboard"

@Composable
fun MainScaffold(
    container: AppContainer,
    me: MeView,
    onLogout: () -> Unit,
) {
    val nav = rememberNavController()
    val tabs = tabsFor(me.role)
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val hideChrome = current?.startsWith("inspection/") == true || current?.startsWith("draft/") == true || current?.startsWith("status/") == true || current?.startsWith("breakdown/") == true
    Scaffold(
        bottomBar = {
            if (!hideChrome) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = startRoute(me.role), modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    container,
                    me,
                    onOpenInspections = { nav.navigate("inspections") },
                    onOpenEquipment = { nav.navigate("equipment") },
                    onOpenBreakdowns = { nav.navigate("breakdowns") },
                )
            }
            composable("dashboard") { DashboardScreen(container, me) }
            composable("inspections") { InspectionsScreen(container, me, onOpen = { nav.navigate("inspection/$it") }, onResume = { nav.navigate("draft/$it") }) }
            composable("equipment") { EquipmentScreen(container, onOpen = { nav.navigate("status/$it") }) }
            composable("tasks") { TasksScreen(container) }
            composable("problems") {
                BreakdownsScreen(container, me, onReport = { nav.navigate("breakdown/report") }, onOpen = { nav.navigate("breakdown/$it") })
            }
            composable("breakdowns") {
                BreakdownsScreen(container, me, onReport = { nav.navigate("breakdown/report") }, onOpen = { nav.navigate("breakdown/$it") })
            }
            composable("report") { PlaceholderScreen("Daily site report", "The daily report is generated by the server. Open it when connected; inspection data waits safely on the device.") }
            composable("maintenance") { MaintenanceScreen(container, me) }
            composable("sites") { PlaceholderScreen("Sites", "Site performance and roll-ups follow in Sprint 9 and Sprint 10.") }
            composable("reports") { PlaceholderScreen("Reports", "Daily report snapshots and PDF downloads follow in Sprint 9.") }
            composable("operations") { PlaceholderScreen("Operations", "Operations administration is managed from the web console.") }
            composable("people") { PlaceholderScreen("People", "People administration is managed from the web console.") }
            composable("configuration") { PlaceholderScreen("Configuration", "Checklists and references are downloaded automatically for offline use.") }
            composable("more") {
                MoreScreen(
                    container,
                    me,
                    onLogout = onLogout,
                    onReviewConflict = { nav.navigate("status/$it") },
                    onReviewBreakdown = { nav.navigate("breakdown/$it") },
                )
            }
            composable("status/{equipmentId}") { entry ->
                StatusUpdateScreen(
                    container,
                    entry.arguments?.getString("equipmentId").orEmpty(),
                    onDone = { nav.popBackStack() },
                    onReportBreakdown = { nav.navigate("breakdown/report?equipmentId=${entry.arguments?.getString("equipmentId").orEmpty()}") },
                )
            }
            composable(
                "breakdown/report?equipmentId={equipmentId}",
                arguments =
                    listOf(
                        navArgument("equipmentId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { entry ->
                BreakdownReportScreen(
                    container,
                    initialEquipmentId = entry.arguments?.getString("equipmentId"),
                    onDone = { nav.popBackStack() },
                    onOpenBreakdown = { nav.navigate("breakdown/$it") },
                )
            }
            composable("breakdown/{breakdownId}") { entry ->
                BreakdownDetailScreen(
                    container,
                    entry.arguments?.getString("breakdownId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("inspection/{expectedId}") { entry ->
                WizardEntry(container, me, expectedId = entry.arguments?.getString("expectedId"), draftId = null, onBack = { nav.popBackStack() })
            }
            composable("draft/{clientUuid}") { entry ->
                WizardEntry(container, me, expectedId = null, draftId = entry.arguments?.getString("clientUuid"), onBack = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun WizardEntry(
    container: AppContainer,
    me: MeView,
    expectedId: String?,
    draftId: String?,
    onBack: () -> Unit,
) {
    val viewModel = remember(expectedId, draftId) { WizardViewModel(container, expectedId, draftId, me) }
    InspectionWizardScreen(viewModel, onBack = onBack)
}
