package rw.rutongo.mmems.android.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.android.db.Breakdown
import rw.rutongo.mmems.android.db.Checklist_item
import rw.rutongo.mmems.android.db.Equipment
import rw.rutongo.mmems.android.db.Expected_inspection
import rw.rutongo.mmems.android.db.MmemsDatabase
import rw.rutongo.mmems.android.db.Outbox
import rw.rutongo.mmems.android.db.Pending_photo
import rw.rutongo.mmems.android.db.Shift
import rw.rutongo.mmems.android.db.Site
import rw.rutongo.mmems.shared.api.BreakdownCreateDto
import rw.rutongo.mmems.shared.api.BreakdownTransitionDto
import rw.rutongo.mmems.shared.api.BreakdownViewDto
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.MaintenanceTechnicianDto
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.ServiceScheduleViewDto
import rw.rutongo.mmems.shared.api.ServiceTypeViewDto
import rw.rutongo.mmems.shared.api.ShiftDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.api.WorkingHoursResponseDto
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.inspection.InspectionDraft
import java.time.Instant
import java.util.UUID

class LocalStore(
    driver: SqlDriver,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    val database = MmemsDatabase(driver)
    private val queries = database.mmemsQueries

    fun replaceReferences(
        sites: List<SiteDto>,
        equipment: List<EquipmentDto>,
        checklist: List<ChecklistItemDto>,
        shifts: List<ShiftDto>,
        expected: List<ExpectedInspectionDto>,
        syncedAt: String,
    ) {
        val now = Instant.now().toString()
        database.transaction {
            queries.clearSites()
            queries.clearEquipment()
            queries.clearChecklist()
            queries.clearShifts()
            queries.clearExpected()
            sites.forEach {
                queries.insertSite(it.id, it.code, it.name, it.state, it.timezone, it.version)
            }
            equipment.forEach {
                queries.insertEquipment(
                    it.id,
                    it.assetId,
                    it.equipmentCode,
                    it.name,
                    it.type,
                    it.siteId,
                    it.siteName,
                    it.tunnelId,
                    it.tunnelName,
                    it.lifecycleState,
                    it.operationalStatus,
                    it.engineHours,
                    it.version,
                    1,
                    now,
                )
            }
            checklist.forEach {
                queries.insertChecklistItem(
                    it.id,
                    it.category,
                    it.code,
                    it.label,
                    it.sortOrder.toLong(),
                    if (it.mandatory) 1 else 0,
                    if (it.safetyCritical) 1 else 0,
                    if (it.photoRequiredOnFail) 1 else 0,
                    if (it.naReasonRequired) 1 else 0,
                    if (it.active) 1 else 0,
                )
            }
            shifts.forEach { queries.insertShift(it.id, it.code, it.name, it.version) }
            expected.forEach {
                queries.insertExpectedInspection(
                    it.id,
                    it.equipmentId,
                    it.equipmentName,
                    it.equipmentCode,
                    it.siteId,
                    it.siteName,
                    it.shiftId,
                    it.shiftCode,
                    it.reportDate,
                    it.windowStart,
                    it.windowEnd,
                    it.state,
                    it.missingMarkedAt,
                )
            }
            queries.upsertSyncState("references", syncedAt, now)
        }
    }

    fun sites(): Flow<List<Site>> = queries.selectSites().asFlow().mapToList(Dispatchers.IO)

    fun equipment(): Flow<List<Equipment>> = queries.selectAllEquipment().asFlow().mapToList(Dispatchers.IO)

    fun equipment(id: String): Equipment? = queries.selectEquipmentById(id).executeAsOneOrNull()

    fun checklist(): Flow<List<Checklist_item>> = queries.selectChecklist().asFlow().mapToList(Dispatchers.IO)

    fun checklistNow(): List<Checklist_item> = queries.selectChecklist().executeAsList()

    fun shifts(): List<Shift> = queries.selectShifts().executeAsList()

    fun expected(): Flow<List<Expected_inspection>> = queries.selectExpected().asFlow().mapToList(Dispatchers.IO)

    fun expected(id: String): Expected_inspection? = queries.selectExpectedById(id).executeAsOneOrNull()

    fun saveDraft(draft: InspectionDraft) {
        val now = Instant.now().toString()
        queries.upsertDraft(
            id = draft.clientUuid,
            client_uuid = draft.clientUuid,
            equipment_id = draft.equipmentId,
            expectation_id = draft.expectationId,
            shift_id = draft.shiftId,
            report_date = draft.reportDate,
            payload = json.encodeToString(InspectionDraft.serializer(), draft),
            step = draft.step.name,
            updated_at = now,
        )
    }

    fun draft(clientUuid: String): InspectionDraft? = draftRow(clientUuid)?.let { decodeDraft(it.payload) }

    fun draftRow(clientUuid: String) = queries.selectDraftByClientUuid(clientUuid).executeAsOneOrNull()

    fun openDrafts(): List<InspectionDraft> = queries.selectOpenDrafts().executeAsList().map { decodeDraft(it.payload) }

    fun openDraftsFlow(): Flow<List<InspectionDraft>> =
        queries
            .selectOpenDraftsFlow()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { decodeDraft(it.payload) } }

    fun outboxForKind(kind: String): Flow<List<Outbox>> = queries.selectOutboxForKind(kind).asFlow().mapToList(Dispatchers.IO)

    private fun decodeDraft(payload: String): InspectionDraft =
        runCatching { json.decodeFromString(InspectionDraft.serializer(), payload) }.getOrElse {
            throw IllegalStateException("The local inspection draft is corrupt.")
        }

    fun enqueueOutbox(
        clientUuid: String,
        kind: String,
        equipmentId: String,
        payload: String,
        ifMatch: Long? = null,
        serverReference: String? = null,
        now: String = Instant.now().toString(),
    ) {
        queries.insertOutbox(
            id = clientUuid,
            client_uuid = clientUuid,
            kind = kind,
            equipment_id = equipmentId,
            payload = payload,
            if_match = ifMatch,
            state = "PENDING",
            attempts = 0,
            last_error = null,
            conflict_state = null,
            server_reference = serverReference,
            created_at = now,
            updated_at = now,
        )
    }

    fun pendingOutbox(): List<Outbox> = queries.selectPendingOutbox().executeAsList()

    fun allOutbox(): Flow<List<Outbox>> = queries.selectOutbox().asFlow().mapToList(Dispatchers.IO)

    fun outbox(clientUuid: String): Outbox? = queries.selectOutboxByClientUuid(clientUuid).executeAsOneOrNull()

    fun markOutboxAttempt(
        id: String,
        error: String?,
    ) {
        val current = queries.selectOutboxByClientUuid(id).executeAsOneOrNull() ?: return
        queries.updateOutboxState(
            state = if (error == null) "SENT" else "RETRY",
            attempts = current.attempts + 1,
            last_error = error,
            updated_at = Instant.now().toString(),
            client_uuid = id,
        )
    }

    fun markOutboxDone(
        clientUuid: String,
        serverReference: String,
    ) {
        queries.updateOutboxDone(
            state = "DONE",
            server_reference = serverReference,
            updated_at = Instant.now().toString(),
            client_uuid = clientUuid,
        )
        queries.deleteDraftByClientUuid(clientUuid)
    }

    fun markOutboxRejected(
        clientUuid: String,
        error: String,
    ) {
        queries.updateOutboxTerminal("REJECTED", error, Instant.now().toString(), clientUuid)
    }

    fun markOutboxConflict(
        clientUuid: String,
        state: String,
    ) {
        queries.updateOutboxConflict("CONFLICT", state, Instant.now().toString(), clientUuid)
    }

    fun markOutboxPending(clientUuid: String) {
        queries.updateOutboxPending("PENDING", Instant.now().toString(), clientUuid)
    }

    fun enqueuePhoto(
        photoId: String,
        inspectionClientUuid: String,
        answerCode: String?,
        siteId: String,
        targetType: String,
        targetId: String,
        filePath: String,
        mime: String,
        sizeBytes: Long,
    ) {
        val now = Instant.now().toString()
        queries.insertPendingPhoto(
            id = photoId,
            inspection_client_uuid = inspectionClientUuid,
            answer_code = answerCode,
            site_id = siteId,
            target_type = targetType,
            target_id = targetId,
            file_path = filePath,
            mime = mime,
            size_bytes = sizeBytes,
            state = "PENDING",
            attempts = 0,
            last_error = null,
            remote_id = null,
            created_at = now,
            updated_at = now,
        )
    }

    fun pendingPhotos(): List<Pending_photo> = queries.selectPendingPhotos().executeAsList()

    fun pendingPhotosForInspection(clientUuid: String): List<Pending_photo> = queries.selectPendingPhotosForInspection(clientUuid).executeAsList()

    fun pendingPhoto(id: String): Pending_photo? = queries.selectPendingPhotoById(id).executeAsOneOrNull()

    fun markPhotoRetry(
        id: String,
        error: String,
    ) {
        val current = queries.selectPendingPhotoById(id).executeAsOneOrNull() ?: return
        queries.updatePendingPhotoState(
            state = "RETRY",
            attempts = current.attempts + 1,
            last_error = error,
            remote_id = null,
            updated_at = Instant.now().toString(),
            id = id,
        )
    }

    fun markPhotoDone(
        id: String,
        remoteId: String,
    ) {
        val current = queries.selectPendingPhotoById(id).executeAsOneOrNull() ?: return
        queries.updatePendingPhotoState(
            state = "DONE",
            attempts = current.attempts,
            last_error = null,
            remote_id = remoteId,
            updated_at = Instant.now().toString(),
            id = id,
        )
    }

    fun markPhotoRejected(
        id: String,
        error: String,
    ) {
        val current = queries.selectPendingPhotoById(id).executeAsOneOrNull() ?: return
        queries.updatePendingPhotoState(
            state = "REJECTED",
            attempts = current.attempts,
            last_error = error,
            remote_id = null,
            updated_at = Instant.now().toString(),
            id = id,
        )
    }

    fun updateEquipmentAfterStatus(
        id: String,
        status: String,
        version: Long,
    ) {
        queries.updateEquipmentStatus(status, version, Instant.now().toString(), id)
    }

    fun outboxCount(): Long = queries.countOutbox().executeAsOne()

    fun pendingPhotoCount(): Long = queries.countPendingPhotos().executeAsOne()

    fun lastReferenceSync(): String? = queries.selectSyncState("references").executeAsOneOrNull()

    fun saveProfile(profile: MeView) {
        queries.upsertSyncState("profile", json.encodeToString(MeView.serializer(), profile), Instant.now().toString())
    }

    fun profile(): MeView? =
        queries.selectSyncState("profile").executeAsOneOrNull()?.let {
            runCatching { json.decodeFromString(MeView.serializer(), it) }.getOrNull()
        }

    fun discardOutbox(clientUuid: String) {
        queries.deleteOutboxByClientUuid(clientUuid)
    }

    fun purgeLocalData() {
        database.transaction {
            queries.clearOutbox()
            queries.clearDrafts()
            queries.clearPendingPhotos()
            queries.clearSyncState()
            queries.clearBreakdowns()
            queries.clearExpected()
            queries.clearChecklist()
            queries.clearShifts()
            queries.clearEquipment()
            queries.clearSites()
        }
    }

    fun serviceTypesNow(): List<ServiceTypeViewDto> = cachedList(SERVICE_TYPES)

    fun serviceTypes(): Flow<List<ServiceTypeViewDto>> = cachedListFlow(SERVICE_TYPES)

    fun cacheServiceTypes(items: List<ServiceTypeViewDto>) = saveCache(SERVICE_TYPES, items)

    fun maintenanceNow(): List<MaintenanceViewDto> = cachedList(MAINTENANCE)

    fun maintenance(): Flow<List<MaintenanceViewDto>> = cachedListFlow(MAINTENANCE)

    fun cacheMaintenance(items: List<MaintenanceViewDto>) = saveCache(MAINTENANCE, items)

    fun upsertMaintenance(view: MaintenanceViewDto) {
        cacheMaintenance(maintenanceNow().filterNot { it.id == view.id } + view)
    }

    fun techniciansNow(): List<MaintenanceTechnicianDto> = cachedList(TECHNICIANS)

    fun technicians(): Flow<List<MaintenanceTechnicianDto>> = cachedListFlow(TECHNICIANS)

    fun cacheTechnicians(items: List<MaintenanceTechnicianDto>) = saveCache(TECHNICIANS, items)

    fun serviceSchedule(): Flow<List<ServiceScheduleViewDto>> = cachedListFlow(SERVICE_SCHEDULE)

    fun cacheServiceSchedule(items: List<ServiceScheduleViewDto>) = saveCache(SERVICE_SCHEDULE, items)

    fun workingHours(): Flow<WorkingHoursResponseDto?> =
        queries
            .selectSyncStateValue(WORKING_HOURS)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it?.let { value -> runCatching { json.decodeFromString(WorkingHoursResponseDto.serializer(), value) }.getOrNull() } }

    fun cacheWorkingHours(value: WorkingHoursResponseDto) = saveCache(WORKING_HOURS, value)

    fun enqueueMaintenance(
        equipmentId: String,
        input: MaintenanceCreateDto,
    ): String {
        val clientUuid = UUID.randomUUID().toString()
        enqueueOutbox(
            clientUuid = clientUuid,
            kind = "MAINTENANCE_CREATE",
            equipmentId = equipmentId,
            payload = json.encodeToString(MaintenanceCreateDto.serializer(), input),
        )
        return clientUuid
    }

    private inline fun <reified T> saveCache(
        key: String,
        value: T,
    ) {
        queries.upsertSyncState(key, json.encodeToString(value), Instant.now().toString())
    }

    private inline fun <reified T> cachedList(key: String): List<T> =
        queries.selectSyncStateValue(key).executeAsOneOrNull()?.let { value ->
            runCatching { json.decodeFromString<List<T>>(value) }.getOrNull()
        } ?: emptyList()

    private inline fun <reified T> cachedListFlow(key: String): Flow<List<T>> =
        queries
            .selectSyncStateValue(key)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { value ->
                value?.let { runCatching { json.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()
            }

    fun equipmentFlow(id: String): Flow<Equipment?> = queries.selectEquipmentById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun equipmentBySite(siteId: String): Flow<List<Equipment>> =
        queries
            .selectEquipmentBySite(siteId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { it }

    fun breakdowns(): Flow<List<Breakdown>> = queries.selectBreakdowns().asFlow().mapToList(Dispatchers.IO)

    fun breakdown(id: String): Breakdown? = queries.selectBreakdownById(id).executeAsOneOrNull()

    fun breakdownFlow(id: String): Flow<Breakdown?> = queries.selectBreakdownById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun breakdownsForEquipment(equipmentId: String): List<Breakdown> = queries.selectBreakdownsForEquipment(equipmentId).executeAsList()

    fun activeBreakdown(equipmentId: String): Breakdown? = queries.selectActiveBreakdownForEquipment(equipmentId).executeAsOneOrNull()

    fun cacheBreakdowns(items: List<BreakdownViewDto>) {
        database.transaction {
            items.forEach { storeBreakdown(it) }
        }
    }

    fun enqueueBreakdownReport(
        equipment: Equipment,
        category: String,
        problem: String,
        actionTaken: String? = null,
        partsRequired: String? = null,
        remarks: String? = null,
        breakdownStart: String = Instant.now().toString(),
    ): String {
        val clientUuid = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val payload =
            BreakdownCreateDto(
                equipmentId = equipment.id,
                category = category.trim(),
                problem = problem.trim(),
                breakdownStart = breakdownStart,
                reportedAt = now,
                actionTaken = actionTaken?.trim()?.ifBlank { null },
                partsRequired = partsRequired?.trim()?.ifBlank { null },
                remarks = remarks?.trim()?.ifBlank { null },
            )
        queries.upsertBreakdown(
            id = clientUuid,
            equipment_id = equipment.id,
            equipment_code = equipment.equipment_code,
            category = payload.category,
            problem = payload.problem,
            site_id = equipment.site_id,
            state = BreakdownState.REPORTED.name,
            breakdown_start = breakdownStart,
            repair_start = null,
            repair_end = null,
            reported_at = now,
            action_taken = payload.actionTaken,
            parts_required = payload.partsRequired,
            remarks = payload.remarks,
            version = 0,
            pending_state = "CREATE",
            defect_count = 0,
            photo_count = 0,
            cached_at = now,
        )
        enqueueOutbox(
            clientUuid = clientUuid,
            kind = "BREAKDOWN_CREATE",
            equipmentId = equipment.id,
            payload = json.encodeToString(BreakdownCreateDto.serializer(), payload),
        )
        return clientUuid
    }

    fun enqueueBreakdownTransition(
        breakdown: Breakdown,
        transition: BreakdownTransitionDto,
    ): String {
        val clientUuid = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val repairStart =
            transition.repairStart
                ?: if (transition.state == BreakdownState.UNDER_REPAIR) now else breakdown.repair_start
        val repairEnd =
            transition.repairEnd
                ?: if (transition.state == BreakdownState.RESOLVED && breakdown.repair_end == null) now else breakdown.repair_end
        queries.updateBreakdownTransition(
            state = transition.state.name,
            repair_start = repairStart,
            repair_end = repairEnd,
            action_taken = transition.actionTaken?.trim()?.ifBlank { null },
            parts_required = transition.partsRequired?.trim()?.ifBlank { null },
            remarks = transition.remarks?.trim()?.ifBlank { null },
            version = breakdown.version + 1,
            pending_state = "TRANSITION",
            cached_at = now,
            id = breakdown.id,
        )
        enqueueOutbox(
            clientUuid = clientUuid,
            kind = "BREAKDOWN_TRANSITION",
            equipmentId = breakdown.equipment_id,
            payload = json.encodeToString(BreakdownTransitionDto.serializer(), transition),
            ifMatch = breakdown.version,
            serverReference = breakdown.id,
        )
        return clientUuid
    }

    fun completeBreakdownCreate(
        clientUuid: String,
        view: BreakdownViewDto,
    ) {
        database.transaction {
            queries.repointBreakdownOutbox(serverReference = view.id, versionOffset = view.version, localReference = clientUuid)
            queries.deleteBreakdown(clientUuid)
            storeBreakdown(view)
        }
    }

    fun clearBreakdownPending(id: String) {
        queries.clearBreakdownPending(cached_at = Instant.now().toString(), id = id)
    }

    fun deleteBreakdown(id: String) {
        queries.deleteBreakdown(id)
    }

    private fun storeBreakdown(view: BreakdownViewDto) {
        queries.upsertBreakdown(
            id = view.id,
            equipment_id = view.equipmentId,
            equipment_code = view.equipmentCode,
            category = view.category,
            problem = view.problem,
            site_id = view.siteId,
            state = view.state,
            breakdown_start = view.breakdownStart,
            repair_start = view.repairStart,
            repair_end = view.repairEnd,
            reported_at = view.reportedAt,
            action_taken = view.actionTaken,
            parts_required = view.partsRequired,
            remarks = view.remarks,
            version = view.version,
            pending_state = null,
            defect_count = view.defectIds.size.toLong(),
            photo_count = view.photoIds.size.toLong(),
            cached_at = Instant.now().toString(),
        )
    }

    companion object {
        private const val SERVICE_TYPES = "service_types"
        private const val MAINTENANCE = "maintenance"
        private const val TECHNICIANS = "maintenance_technicians"
        private const val SERVICE_SCHEDULE = "service_schedule"
        private const val WORKING_HOURS = "working_hours"

        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
