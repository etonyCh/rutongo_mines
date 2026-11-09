package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.shared.api.NotificationRuleDto
import rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SectionTitle
import rw.rutongo.mmems.web.ui.components.StatusBadge

@Composable
fun ConfigurationScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "Configuration",
            subtitle = "Notification routing and equipment service monitoring",
            busy = state.configBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
        )
        ServiceMonitoringCard()
        HorizontalDivider()
        SectionTitle("Notification rules", "Role targeting, site scoping and delivery channels")
        Spacer(Modifier.height(10.dp))
        val rules = state.notificationRules.orEmpty()
        when {
            rules.isEmpty() && state.configBusy -> LoadingBlock("Loading notification rules…")
            rules.isEmpty() -> EmptyState("No notification rules are configured.")
            else ->
                rules.forEach { rule ->
                    RuleCard(rule) {
                        onIntent(
                            WebIntent.UpdateNotificationRule(
                                rule.type.name,
                                NotificationRuleUpdateDto(
                                    roles = rule.roles,
                                    siteScoped = rule.siteScoped,
                                    channel = rule.channel,
                                    enabled = !rule.enabled,
                                ),
                            ),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
        }
    }
}

@Composable
private fun ServiceMonitoringCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Service-due monitoring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "The server runs an hourly scan against equipment service intervals and writes due-soon and overdue alerts to the notification outbox. Delivery is deduplicated per equipment, service type and status.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Outbox dispatcher: every minute · Service scan: hourly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RuleCard(
    rule: NotificationRuleDto,
    onToggle: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.type.name.replace('_', ' '), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${rule.channel.name.replace('_', ' ')} · ${if (rule.siteScoped) "site scoped" else "global"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rule.roles.forEach { role -> StatusBadge(role.name) }
                }
            }
            StatusBadge(if (rule.enabled) "ENABLED" else "DISABLED")
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        }
    }
}
