@file:Suppress("MagicNumber", "LongMethod")

package rw.rutongo.mmems.web.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.web.WebTab
import rw.rutongo.mmems.shared.web.tabsFor
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.StatusBadge
import rw.rutongo.mmems.web.ui.screens.ConfigurationScreen
import rw.rutongo.mmems.web.ui.screens.DashboardScreen
import rw.rutongo.mmems.web.ui.screens.EquipmentScreen
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
        val wide = maxWidth >= 900.dp
        Scaffold(
            topBar = { TopBar(state, me.fullName, me.role.name, onIntent) },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab,
                                onClick = { onIntent(WebIntent.SelectTab(tab)) },
                                icon = { Text(tab.label.take(1), fontWeight = FontWeight.Bold) },
                                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                        Spacer(Modifier.height(8.dp))
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = current == tab,
                                onClick = { onIntent(WebIntent.SelectTab(tab)) },
                                icon = {
                                    Box(
                                        modifier =
                                            Modifier
                                                .clip(CircleShape)
                                                .background(if (current == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            tab.label.take(1),
                                            color = if (current == tab) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 20.dp)) {
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
    Surface(color = MaterialTheme.colorScheme.primary, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("RM", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("RUTONGO MMEMS", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "Mechanical Equipment Management",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.weight(1f))
            state.siteName?.let {
                Text(it, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusBadge(role)
            Text(fullName, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onIntent(WebIntent.Logout) }) {
                Text("Sign out", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
