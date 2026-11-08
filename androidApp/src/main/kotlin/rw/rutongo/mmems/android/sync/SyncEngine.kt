package rw.rutongo.mmems.android.sync

import rw.rutongo.mmems.android.data.ApiResult
import rw.rutongo.mmems.android.data.LocalStore
import rw.rutongo.mmems.android.data.MmemsApi
import rw.rutongo.mmems.shared.api.BreakdownViewDto
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.api.StatusEventDto
import java.time.Instant

data class SyncReport(
    val sentOperations: Int = 0,
    val uploadedPhotos: Int = 0,
    val rejected: Int = 0,
    val conflicts: Int = 0,
    val referenceSyncFailed: Boolean = false,
    val retryNeeded: Boolean = false,
    val message: String? = null,
)

class SyncEngine(
    private val store: LocalStore,
    private val api: MmemsApi,
) {
    suspend fun syncReferences(): ApiResult<Unit> {
        val sites = api.sites() ?: return ApiResult.Retryable("Sites are unavailable.")
        if (sites !is ApiResult.Ok) return sites.failure()
        val equipment = api.equipment()
        if (equipment !is ApiResult.Ok) return equipment.failure()
        val checklist = api.checklist()
        if (checklist !is ApiResult.Ok) return checklist.failure()
        val shifts = api.shifts()
        if (shifts !is ApiResult.Ok) return shifts.failure()
        val expected = api.expected()
        if (expected !is ApiResult.Ok) return expected.failure()
        store.replaceReferences(
            sites = sites.value,
            equipment = equipment.value,
            checklist = checklist.value,
            shifts = shifts.value,
            expected = expected.value,
            syncedAt = Instant.now().toString(),
        )
        val maintenanceData = syncMaintenance()
        if (maintenanceData !is ApiResult.Ok) return maintenanceData
        return when (val breakdowns = api.breakdowns()) {
            is ApiResult.Ok -> {
                store.cacheBreakdowns(breakdowns.value)
                ApiResult.Ok(Unit)
            }
            is ApiResult.Invalid -> if (breakdowns.code == "FORBIDDEN") ApiResult.Ok(Unit) else breakdowns.failure()
            is ApiResult.Conflict, is ApiResult.Retryable -> breakdowns.failure()
        }
    }

    suspend fun syncMaintenance(): ApiResult<Unit> {
        val serviceTypes = api.serviceTypes()
        if (serviceTypes !is ApiResult.Ok && !serviceTypes.isForbidden()) return serviceTypes.failure()
        if (serviceTypes is ApiResult.Ok) store.cacheServiceTypes(serviceTypes.value)

        val technicians = api.maintenanceTechnicians()
        if (technicians !is ApiResult.Ok && !technicians.isForbidden()) return technicians.failure()
        if (technicians is ApiResult.Ok) store.cacheTechnicians(technicians.value)

        val records = api.maintenance()
        if (records !is ApiResult.Ok && !records.isForbidden()) return records.failure()
        if (records is ApiResult.Ok) store.cacheMaintenance(records.value)

        val schedule = api.serviceSchedule()
        if (schedule !is ApiResult.Ok && !schedule.isForbidden()) return schedule.failure()
        if (schedule is ApiResult.Ok) store.cacheServiceSchedule(schedule.value)

        val hours = api.workingHours()
        if (hours !is ApiResult.Ok && !hours.isForbidden()) return hours.failure()
        if (hours is ApiResult.Ok) store.cacheWorkingHours(hours.value)
        return ApiResult.Ok(Unit)
    }

    suspend fun syncAll(refreshReferences: Boolean = true): SyncReport {
        var sent = 0
        var photos = 0
        var rejected = 0
        var conflicts = 0
        var retry = false
        var message: String? = null

        for (photo in store.pendingPhotos()) {
            when (val result = api.uploadPhoto(photo, photo.inspection_client_uuid)) {
                is ApiResult.Ok -> {
                    store.markPhotoDone(photo.id, result.value.id)
                    photos++
                }
                is ApiResult.Invalid -> {
                    store.markPhotoRejected(photo.id, "${result.code}: ${result.message}")
                    rejected++
                }
                is ApiResult.Conflict -> {
                    store.markPhotoRetry(photo.id, result.message)
                    retry = true
                    message = result.message
                    break
                }
                is ApiResult.Retryable -> {
                    store.markPhotoRetry(photo.id, result.message)
                    retry = true
                    message = result.message
                    break
                }
            }
        }

        for (operation in store.pendingOutbox().sortedBy { if (it.kind == "BREAKDOWN_CREATE") 0 else 1 }) {
            val result: ApiResult<Any> =
                when (operation.kind) {
                    "STATUS" -> api.submitStatus(operation)
                    "BREAKDOWN_CREATE" -> api.submitBreakdown(operation)
                    "BREAKDOWN_TRANSITION" -> {
                        val current = store.outbox(operation.client_uuid) ?: operation
                        val breakdownId = current.server_reference
                        if (breakdownId == null) {
                            ApiResult.Invalid("BREAKDOWN_ID_MISSING", "The queued repair is missing its breakdown reference.")
                        } else {
                            api.submitBreakdownTransition(current, breakdownId)
                        }
                    }
                    "MAINTENANCE_CREATE" -> api.submitMaintenance(operation)
                    else -> api.submitInspection(operation)
                }
            when (result) {
                is ApiResult.Ok -> {
                    when (operation.kind) {
                        "STATUS" -> {
                            val event = result.value as? StatusEventDto
                            store.markOutboxDone(operation.client_uuid, result.rawBody)
                            if (event != null) store.updateEquipmentAfterStatus(operation.equipment_id, event.status, event.version)
                        }
                        "BREAKDOWN_CREATE" -> {
                            val view = result.value as BreakdownViewDto
                            store.completeBreakdownCreate(operation.client_uuid, view)
                            store.markOutboxDone(operation.client_uuid, view.id)
                        }
                        "BREAKDOWN_TRANSITION" -> {
                            val view = result.value as BreakdownViewDto
                            store.cacheBreakdowns(listOf(view))
                            store.markOutboxDone(operation.client_uuid, view.id)
                        }
                        "MAINTENANCE_CREATE" -> {
                            val view = result.value as MaintenanceViewDto
                            store.upsertMaintenance(view)
                            store.markOutboxDone(operation.client_uuid, view.id)
                        }
                        else -> store.markOutboxDone(operation.client_uuid, result.rawBody)
                    }
                    sent++
                }
                is ApiResult.Invalid -> {
                    clearBreakdownPending(operation)
                    if (operation.kind == "BREAKDOWN_CREATE") store.deleteBreakdown(operation.client_uuid)
                    store.markOutboxRejected(operation.client_uuid, "${result.code}: ${result.message}")
                    rejected++
                }
                is ApiResult.Conflict -> {
                    clearBreakdownPending(operation)
                    if (operation.kind == "BREAKDOWN_CREATE") store.deleteBreakdown(operation.client_uuid)
                    store.markOutboxConflict(operation.client_uuid, result.currentState ?: result.message)
                    conflicts++
                }
                is ApiResult.Retryable -> {
                    store.markOutboxAttempt(operation.client_uuid, result.message)
                    retry = true
                    message = result.message
                    break
                }
            }
        }

        var referenceFail = false
        if (refreshReferences && !retry) {
            val result = syncReferences()
            if (result !is ApiResult.Ok) {
                referenceFail = true
                if (message == null) message = result.failureMessage()
            }
        }
        return SyncReport(sent, photos, rejected, conflicts, referenceFail, retry, message)
    }

    private fun clearBreakdownPending(operation: rw.rutongo.mmems.android.db.Outbox) {
        if (operation.kind != "BREAKDOWN_TRANSITION") return
        store.outbox(operation.client_uuid)?.server_reference?.let(store::clearBreakdownPending)
    }

    private fun ApiResult<*>.isForbidden() = this is ApiResult.Invalid && code == "FORBIDDEN"

    private fun ApiResult<*>.failure(): ApiResult<Unit> =
        when (this) {
            is ApiResult.Invalid -> this
            is ApiResult.Conflict -> this
            is ApiResult.Retryable -> this
            is ApiResult.Ok -> ApiResult.Ok(Unit)
        }

    private fun ApiResult<*>.failureMessage(): String =
        when (this) {
            is ApiResult.Invalid -> message
            is ApiResult.Conflict -> message
            is ApiResult.Retryable -> message
            is ApiResult.Ok -> ""
        }
}
