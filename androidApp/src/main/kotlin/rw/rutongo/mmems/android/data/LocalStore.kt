package rw.rutongo.mmems.android.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.android.db.Checklist_item
import rw.rutongo.mmems.android.db.Equipment
import rw.rutongo.mmems.android.db.Expected_inspection
import rw.rutongo.mmems.android.db.MmemsDatabase
import rw.rutongo.mmems.android.db.Outbox
import rw.rutongo.mmems.android.db.Pending_photo
import rw.rutongo.mmems.android.db.Shift
import rw.rutongo.mmems.android.db.Site
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.ShiftDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.inspection.InspectionDraft
import java.time.Instant

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
            server_reference = null,
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
            queries.clearExpected()
            queries.clearChecklist()
            queries.clearShifts()
            queries.clearEquipment()
            queries.clearSites()
        }
    }

    fun equipmentFlow(id: String): Flow<Equipment?> = queries.selectEquipmentById(id).asFlow().mapToOneOrNull(Dispatchers.IO)

    fun equipmentBySite(siteId: String): Flow<List<Equipment>> =
        queries
            .selectEquipmentBySite(siteId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { it }

    companion object {
        val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
