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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.android.ui.components.DangerButton
import rw.rutongo.mmems.android.ui.components.PrimaryButton
import rw.rutongo.mmems.android.ui.components.ScreenHeader
import rw.rutongo.mmems.android.ui.components.SecondaryButton
import rw.rutongo.mmems.android.ui.components.SectionCard
import rw.rutongo.mmems.android.ui.theme.SpecColors
import rw.rutongo.mmems.android.ui.theme.SpecSpacing
import rw.rutongo.mmems.android.ui.theme.SpecTypography
import rw.rutongo.mmems.shared.api.MeView

@Composable
fun MoreScreen(
    container: AppContainer,
    me: MeView,
    onLogout: () -> Unit,
    onReviewConflict: (String) -> Unit,
    onReviewBreakdown: (String) -> Unit,
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
            .padding(horizontal = SpecSpacing.PhoneMargin),
        verticalArrangement = Arrangement.spacedBy(SpecSpacing.SectionGap),
    ) {
        ScreenHeader(eyebrow = me.role.name.replace('_', ' '), title = "More")
        SectionCard("Account", subtitle = "${me.fullName} · ${me.employeeId}") {
            Text(me.role.name.replace('_', ' '), style = SpecTypography.Body)
            Text("${me.siteIds.size} site assignment(s)", style = SpecTypography.BodySmall)
        }
        SectionCard("Synchronisation", subtitle = "WORK MANAGER · 15 MINUTE BACKGROUND SYNC") {
            Text("$pendingOps pending operation(s)", style = SpecTypography.Body)
            Text("$pendingPhotos pending photo(s)", style = SpecTypography.Body)
            Text("Last reference sync: ${container.store.lastReferenceSync() ?: "never"}", style = SpecTypography.BodySmall)
            PrimaryButton("SYNC NOW", { SyncWorker.enqueueNow(container.appContext) })
        }
        if (failedOps.isNotEmpty()) {
            SectionCard("Synchronisation issues", subtitle = "Nothing is discarded automatically. Review each conflict against the server state.") {
                failedOps.forEach { operation ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${operation.kind} · ${container.store.equipment(operation.equipment_id)?.name ?: operation.equipment_id}", style = SpecTypography.Body)
                        Text(
                            operation.last_error ?: operation.conflict_state ?: "The server rejected this operation.",
                            style = SpecTypography.BodySmall,
                            color = SpecColors.ErrorText,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (operation.state == "CONFLICT" && operation.kind == "STATUS") {
                                PrimaryButton(
                                    "RE-READ EQUIPMENT",
                                    {
                                        container.store.discardOutbox(operation.client_uuid)
                                        onReviewConflict(operation.equipment_id)
                                    },
                                    Modifier.weight(1f),
                                )
                            }
                            if (operation.state == "CONFLICT" && operation.kind == "BREAKDOWN_TRANSITION" && operation.server_reference != null) {
                                PrimaryButton(
                                    "RE-READ BREAKDOWN",
                                    {
                                        container.store.discardOutbox(operation.client_uuid)
                                        onReviewBreakdown(operation.server_reference!!)
                                    },
                                    Modifier.weight(1f),
                                )
                            }
                            DangerButton("DISCARD", { container.store.discardOutbox(operation.client_uuid) }, Modifier.weight(1f))
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
            SecondaryButton("SAVE SERVER URL", { container.settings.apiBaseUrl = baseUrl })
        }
        SectionCard("Device data", subtitle = "The local database is encrypted with a random device key.") {
            Text("Inspections, reference data and pending photos stay on this device until synchronised.", style = SpecTypography.Body)
            Text("Pending photos are kept in the app cache and retried by WorkManager.", style = SpecTypography.BodySmall)
        }
        DangerButton(if (pendingOps > 0) "SIGN OUT (UNSYNCED WORK)" else "SIGN OUT", { confirmLogout = true })
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
