package rw.rutongo.mmems.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun MoreScreen(
    container: AppContainer,
    me: MeView,
    onLogout: () -> Unit,
    onReviewConflict: (String) -> Unit,
) {
    val outbox by container.store.allOutbox().collectAsStateWithLifecycle(emptyList())
    val pendingOps = outbox.count { it.state in setOf("PENDING", "RETRY", "CONFLICT") }
    val failedOps = outbox.filter { it.state in setOf("CONFLICT", "REJECTED") }
    val pendingPhotos = container.store.pendingPhotoCount()
    var baseUrl by remember { mutableStateOf(container.settings.apiBaseUrl) }
    var confirmLogout by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("Account", subtitle = "${me.fullName} · ${me.employeeId}") {
            Text(me.role.name.replace('_', ' '), fontWeight = FontWeight.Bold)
            Text("${me.siteIds.size} site assignment(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionCard("Synchronisation", subtitle = "WORK MANAGER · 15 MINUTE BACKGROUND SYNC") {
            Text("$pendingOps pending operation(s)")
            Text("$pendingPhotos pending photo(s)")
            Text("Last reference sync: ${container.store.lastReferenceSync() ?: "never"}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { SyncWorker.enqueueNow(container.appContext) }, modifier = Modifier.fillMaxWidth()) { Text("SYNC NOW") }
        }
        if (failedOps.isNotEmpty()) {
            SectionCard("Synchronisation issues", subtitle = "Nothing is discarded automatically. Review each conflict against the server state.") {
                failedOps.forEach { operation ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${operation.kind} · ${container.store.equipment(operation.equipment_id)?.name ?: operation.equipment_id}", fontWeight = FontWeight.Bold)
                        Text(
                            operation.last_error ?: operation.conflict_state ?: "The server rejected this operation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (operation.state == "CONFLICT" && operation.kind == "STATUS") {
                                Button(
                                    onClick = {
                                        container.store.discardOutbox(operation.client_uuid)
                                        onReviewConflict(operation.equipment_id)
                                    },
                                ) { Text("RE-READ EQUIPMENT") }
                            }
                            OutlinedButton(onClick = { container.store.discardOutbox(operation.client_uuid) }) { Text("DISCARD") }
                        }
                    }
                }
            }
        }
        SectionCard("Server", subtitle = "Use the reachable server URL for this phone.") {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { container.settings.apiBaseUrl = baseUrl },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SAVE SERVER URL") }
        }
        SectionCard("Device data", subtitle = "The local database is encrypted with a random device key.") {
            Text("Inspections, reference data and pending photos stay on this device until synchronised.")
            Text("Pending photos are kept in the app cache and retried by WorkManager.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (pendingOps > 0) "SIGN OUT (UNSYNCED WORK)" else "SIGN OUT")
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (pendingOps > 0) {
                        "$pendingOps operation(s) have not reached the server. Keep this device signed in until they sync, or they may be lost."
                    } else {
                        "The local session will be removed. Cached reference data can be downloaded again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("SIGN OUT") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("CANCEL") } },
        )
    }
}
