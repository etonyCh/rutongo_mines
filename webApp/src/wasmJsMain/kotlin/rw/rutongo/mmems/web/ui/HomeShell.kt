@file:Suppress("MagicNumber", "LongMethod")

package rw.rutongo.mmems.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.web.WebTab
import rw.rutongo.mmems.shared.web.labelFor
import rw.rutongo.mmems.shared.web.tabsFor
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.StatusBadge
import rw.rutongo.mmems.web.ui.screens.ActiveProblemsScreen
import rw.rutongo.mmems.web.ui.screens.ConfigurationScreen
import rw.rutongo.mmems.web.ui.screens.DashboardScreen
import rw.rutongo.mmems.web.ui.screens.EquipmentScreen
import rw.rutongo.mmems.web.ui.screens.InspectionsScreen
import rw.rutongo.mmems.web.ui.screens.MaintenanceScreen
import rw.rutongo.mmems.web.ui.screens.MoreScreen
import rw.rutongo.mmems.web.ui.screens.OperationsScreen
import rw.rutongo.mmems.web.ui.screens.PeopleScreen
import rw.rutongo.mmems.web.ui.screens.ReportsScreen
import rw.rutongo.mmems.web.ui.screens.SitesScreen

@Composable
fun HomeShell(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    val me = state.me ?: return
    val tabs = tabsFor(me.role)
    val current = state.tab?.takeIf { it in tabs } ?: tabs.first()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1100.dp
        Scaffold(
            topBar = { TopBar(state, me.fullName, me.role.name, onIntent) },
            bottomBar = {
                if (!wide) {
                    NavigationBar(containerColor = WebSpecColors.Background) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab,
                                onClick = { onIntent(WebIntent.SelectTab(tab)) },
                                icon = { Text(tab.labelFor(me.role).take(1), style = WebSpecTypography.Label) },
                                label = { Text(tab.labelFor(me.role), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = WebSpecColors.Action,
                                        selectedTextColor = WebSpecColors.Action,
                                        indicatorColor = WebSpecColors.SelectedSurface,
                                        unselectedIconColor = WebSpecColors.DisabledText,
                                        unselectedTextColor = WebSpecColors.SecondaryText,
                                    ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(
                        containerColor = WebSpecColors.Surface,
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    ) {
                        Spacer(Modifier.height(28.dp))
                        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Text("Rutongo Mines", style = WebSpecTypography.SectionTitle)
                            Text("MECHANICAL DEPARTMENT", style = WebSpecTypography.Label)
                        }
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = WebSpecColors.Divider)
                        Spacer(Modifier.height(12.dp))
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = current == tab,
                                onClick = { onIntent(WebIntent.SelectTab(tab)) },
                                icon = {},
                                label = {
                                    Text(
                                        tab.labelFor(me.role),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                },
                                colors =
                                    NavigationRailItemDefaults.colors(
                                        selectedIconColor = WebSpecColors.Action,
                                        selectedTextColor = WebSpecColors.Action,
                                        indicatorColor = WebSpecColors.SelectedSurface,
                                        unselectedIconColor = WebSpecColors.DisabledText,
                                        unselectedTextColor = WebSpecColors.SecondaryText,
                                    ),
                            )
                        }
                    }
                    HorizontalDivider(color = WebSpecColors.Divider, modifier = Modifier.width(1.dp).fillMaxHeight())
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = if (wide) 48.dp else 24.dp, vertical = 24.dp),
                ) {
                    Screen(current, state, onIntent)
                }
            }
        }
    }
}

@Composable
private fun Screen(
    tab: WebTab,
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    when (tab) {
        WebTab.DASHBOARD -> DashboardScreen(state, onIntent)
        WebTab.SITES -> SitesScreen(state, onIntent)
        WebTab.EQUIPMENT -> EquipmentScreen(state, onIntent)
        WebTab.INSPECTIONS -> InspectionsScreen(state, onIntent)
        WebTab.ACTIVE_PROBLEMS -> ActiveProblemsScreen(state, onIntent)
        WebTab.MAINTENANCE -> MaintenanceScreen(state, onIntent)
        WebTab.REPORTS -> ReportsScreen(state, onIntent)
        WebTab.OPERATIONS -> OperationsScreen(state, onIntent)
        WebTab.PEOPLE -> PeopleScreen(state, onIntent)
        WebTab.CONFIGURATION -> ConfigurationScreen(state, onIntent)
        WebTab.MORE -> MoreScreen(state, onIntent)
    }
}

@Composable
private fun TopBar(
    state: WebState,
    fullName: String,
    role: String,
    onIntent: (WebIntent) -> Unit,
) {
    Surface(color = WebSpecColors.Background, tonalElevation = 0.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("RUTONGO MMEMS", style = WebSpecTypography.SectionTitle)
                state.siteName?.let {
                    Text(
                        it,
                        style = WebSpecTypography.BodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusBadge(role)
                Text(fullName, style = WebSpecTypography.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onIntent(WebIntent.Logout) }) {
                    Text("Sign out", color = WebSpecColors.Action)
                }
            }
            HorizontalDivider(color = WebSpecColors.Divider)
        }
    }
}
