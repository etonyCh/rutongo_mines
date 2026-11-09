@file:Suppress("MagicNumber", "LongMethod")

package rw.rutongo.mmems.web.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rw.rutongo.mmems.web.WebIntent
import rw.rutongo.mmems.web.WebState
import rw.rutongo.mmems.web.data.AuditEventRow
import rw.rutongo.mmems.web.data.WebUser
import rw.rutongo.mmems.web.ui.components.EmptyState
import rw.rutongo.mmems.web.ui.components.LoadingBlock
import rw.rutongo.mmems.web.ui.components.PageHeader
import rw.rutongo.mmems.web.ui.components.SectionTitle
import rw.rutongo.mmems.web.ui.components.StatusBadge

private val assignableRoles = listOf("MECHANIC", "SUPERVISOR", "MANAGER", "GENERAL_MANAGER", "ADMINISTRATOR")

@Composable
fun PeopleScreen(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    var section by remember { mutableStateOf("Users") }
    var showCreate by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "People",
            subtitle = "User administration and immutable audit trail",
            busy = state.usersBusy || state.auditBusy,
            onRefresh = { onIntent(WebIntent.Refresh) },
            trailing = {
                if (section == "Users") Button(onClick = { showCreate = true }) { Text("New user") }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == "Users", onClick = { section = "Users" }, label = { Text("Users") })
            FilterChip(selected = section == "Audit", onClick = { section = "Audit" }, label = { Text("Audit trail") })
        }
        if (section == "Users") {
            UserList(state, onIntent)
        } else {
            AuditList(state)
        }
    }
    if (showCreate) {
        CreateUserDialog(state, onDismiss = { showCreate = false }) {
            onIntent(it)
            showCreate = false
        }
    }
}

@Composable
private fun UserList(
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    val users = state.users.orEmpty()
    when {
        users.isEmpty() && state.usersBusy -> LoadingBlock("Loading users…")
        users.isEmpty() -> EmptyState("No users are registered.")
        else ->
            users.sortedBy { it.fullName }.forEach { user ->
                UserCard(user, state, onIntent)
                Spacer(Modifier.height(8.dp))
            }
    }
}

@Composable
private fun UserCard(
    user: WebUser,
    state: WebState,
    onIntent: (WebIntent) -> Unit,
) {
    val siteNames = user.siteIds.mapNotNull { id -> state.sites.firstOrNull { it.id == id }?.name }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(user.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StatusBadge(if (user.active) "ACTIVE" else "INACTIVE")
                    StatusBadge(user.role)
                }
                Text("${user.username} · ${user.employeeId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (siteNames.isEmpty()) "No site restriction" else siteNames.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = { onIntent(WebIntent.SetUserActive(user.id, !user.active)) }) {
                Text(if (user.active) "Deactivate" else "Activate")
            }
        }
    }
}

@Composable
private fun AuditList(state: WebState) {
    val events = state.auditEvents.orEmpty()
    when {
        events.isEmpty() && state.auditBusy -> LoadingBlock("Loading audit events…")
        events.isEmpty() -> EmptyState("No audit events are available.")
        else -> {
            SectionTitle("Latest audit events", "${events.size} events, newest first")
            Spacer(Modifier.height(10.dp))
            events.forEach { event -> AuditRow(event) }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEventRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.action ?: "—", fontWeight = FontWeight.SemiBold)
                Text(event.occurredAt?.take(19)?.replace('T', ' ') ?: "—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${event.entityType ?: "—"} · ${event.entityId ?: "—"} · actor ${event.actorId?.take(8) ?: "system"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.metadata?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("Hash ${event.eventHash?.take(16) ?: "—"}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CreateUserDialog(
    state: WebState,
    onDismiss: () -> Unit,
    onIntent: (WebIntent) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var employeeId by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("MECHANIC") }
    var password by remember { mutableStateOf("") }
    var roleExpanded by remember { mutableStateOf(false) }
    val siteIds = remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create user") },
        text = {
            Column(modifier = Modifier.width(520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fullName, { fullName = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(employeeId, { employeeId = it }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box {
                    OutlinedButton(onClick = { roleExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Role: $role") }
                    DropdownMenu(expanded = roleExpanded, onDismissRequest = { roleExpanded = false }) {
                        assignableRoles.forEach { value ->
                            DropdownMenuItem(text = { Text(value.replace('_', ' ')) }, onClick = {
                                role = value
                                roleExpanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(password, { password = it }, label = { Text("Initial password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Site access", style = MaterialTheme.typography.labelLarge)
                if (state.sites.isEmpty()) {
                    Text("No sites available.", style = MaterialTheme.typography.bodySmall)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.sites.forEach { site ->
                            FilterChip(
                                selected = site.id in siteIds.value,
                                onClick = {
                                    siteIds.value = if (site.id in siteIds.value) siteIds.value - site.id else siteIds.value + site.id
                                },
                                label = { Text(site.code) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onIntent(
                        WebIntent.CreateUser(
                            username = username,
                            fullName = fullName,
                            employeeId = employeeId,
                            role = role,
                            initialPassword = password,
                            siteIds = siteIds.value.toList(),
                        ),
                    )
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
