package rw.rutongo.mmems.android.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rw.rutongo.mmems.android.db.MmemsDatabase
import rw.rutongo.mmems.android.db.Outbox
import rw.rutongo.mmems.android.db.Pending_photo
import rw.rutongo.mmems.android.sync.SyncEngine
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.InspectionDetailDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.PhotoDto
import rw.rutongo.mmems.shared.api.SessionResponse
import rw.rutongo.mmems.shared.api.ShiftDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.api.StatusEventDto
import rw.rutongo.mmems.shared.inspection.InspectionDraft
import rw.rutongo.mmems.shared.inspection.WizardStep
import java.nio.file.Files
import java.util.UUID

class LocalStoreSyncTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: LocalStore
    private val siteId = "11111111-1111-1111-1111-111111111111"
    private val equipmentId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MmemsDatabase.Schema.create(driver)
        store = LocalStore(driver)
        store.replaceReferences(
            sites = listOf(SiteDto(siteId, "SITE-A", "Kigali site", "ACTIVE", "Africa/Kigali", 1)),
            equipment =
                listOf(
                    EquipmentDto(
                        id = equipmentId,
                        assetId = "AST-1",
                        equipmentCode = "EQ-01",
                        name = "LHD 01",
                        type = "LHD",
                        siteId = siteId,
                        siteName = "Kigali site",
                        lifecycleState = "ACTIVE",
                        operationalStatus = "OPERATIONAL",
                        engineHours = 100.0,
                        version = 3,
                    ),
                ),
            checklist = emptyList(),
            shifts = emptyList(),
            expected = emptyList(),
            syncedAt = "2026-01-01T00:00:00Z",
        )
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `queued status survives offline and updates local equipment from server version`() =
        runTest {
            val clientUuid = UUID.randomUUID().toString()
            val payload = """{"status":"DOWN","occurredAt":"2026-01-01T00:00:00Z","problem":"Hydraulic leak"}"""
            store.enqueueOutbox(clientUuid, "STATUS", equipmentId, payload, ifMatch = 3)
            assertEquals(1, store.pendingOutbox().size)

            val api = FakeApi(statusResult = { ApiResult.Ok(StatusEventDto("event-1", equipmentId, "DOWN", "2026-01-01T00:00:00Z", "2026-01-01T00:01:00Z", 4)) })
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertEquals(1, report.sentOperations)
            assertEquals("DONE", store.outbox(clientUuid)?.state)
            val equipment = store.equipment(equipmentId)!!
            assertEquals("DOWN", equipment.operational_status)
            assertEquals(4, equipment.version)
        }

    @Test
    fun `stale offline status becomes a conflict and never overwrites current equipment state`() =
        runTest {
            val clientUuid = UUID.randomUUID().toString()
            val payload = """{"status":"UNDER_REPAIR","occurredAt":"2026-01-01T00:00:00Z"}"""
            store.enqueueOutbox(clientUuid, "STATUS", equipmentId, payload, ifMatch = 3)
            val api = FakeApi(statusResult = { ApiResult.Conflict("Version conflict", "version=9,status=DOWN") })
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertEquals(1, report.conflicts)
            assertEquals("CONFLICT", store.outbox(clientUuid)?.state)
            assertEquals("version=9,status=DOWN", store.outbox(clientUuid)?.conflict_state)
            assertEquals("OPERATIONAL", store.equipment(equipmentId)!!.operational_status)
            assertEquals(3, store.equipment(equipmentId)!!.version)
        }

    @Test
    fun `photo upload is retried independently and remains queued`() =
        runTest {
            val photoId = UUID.randomUUID().toString()
            val clientUuid = UUID.randomUUID().toString()
            val file = Files.createTempFile("mmems-photo", ".jpg").toFile()
            file.writeText("jpeg-bytes")
            store.enqueuePhoto(photoId, clientUuid, "SAFE-01", siteId, "INSPECTION_ANSWER", "item-1", file.absolutePath, "image/jpeg", file.length())

            val api = FakeApi(photoResult = { ApiResult.Retryable("No connection") })
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertTrue(report.retryNeeded)
            assertEquals(0, report.uploadedPhotos)
            val photo = store.pendingPhoto(photoId)!!
            assertEquals("RETRY", photo.state)
            assertEquals(1, photo.attempts)
            assertEquals(1, store.pendingPhotosForInspection(clientUuid).size)
            file.delete()
        }

    @Test
    fun `successful inspection removes the local draft and records the server reference`() =
        runTest {
            val clientUuid = UUID.randomUUID().toString()
            val draft =
                InspectionDraft(
                    clientUuid = clientUuid,
                    equipmentId = equipmentId,
                    equipmentName = "LHD 01",
                    equipmentCode = "EQ-01",
                    siteId = siteId,
                    siteName = "Kigali site",
                    shiftId = "shift-1",
                    shiftCode = "DAY",
                    reportDate = "2026-01-01",
                    capturedAt = "2026-01-01T00:00:00Z",
                    startedAt = "2026-01-01T00:00:00Z",
                    step = WizardStep.SIGN,
                )
            store.saveDraft(draft)
            store.enqueueOutbox(clientUuid, "INSPECTION", equipmentId, "{}")
            val api = FakeApi(inspectionResult = { ApiResult.Ok(InspectionDetailDto("inspection-1", clientUuid, "COMPLETED", "COMPLETED", false, "2026-01-01T00:05:00Z"), "inspection-1") })

            SyncEngine(store, api).syncAll(refreshReferences = false)

            assertNull(store.draft(clientUuid))
            assertEquals("DONE", store.outbox(clientUuid)?.state)
            assertEquals("inspection-1", store.outbox(clientUuid)?.server_reference)
        }

    private class FakeApi(
        private val statusResult: (suspend (Outbox) -> ApiResult<StatusEventDto>)? = null,
        private val inspectionResult: (suspend (Outbox) -> ApiResult<InspectionDetailDto>)? = null,
        private val photoResult: (suspend (Pending_photo) -> ApiResult<PhotoDto>)? = null,
    ) : MmemsApi {
        override suspend fun login(
            username: String,
            password: String,
            deviceLabel: String,
        ): ApiResult<SessionResponse> = error("not used")

        override suspend fun me(): ApiResult<MeView> = error("not used")

        override suspend fun sites(): ApiResult<List<SiteDto>> = ApiResult.Ok(emptyList())

        override suspend fun equipment(): ApiResult<List<EquipmentDto>> = ApiResult.Ok(emptyList())

        override suspend fun shifts(): ApiResult<List<ShiftDto>> = ApiResult.Ok(emptyList())

        override suspend fun checklist(): ApiResult<List<ChecklistItemDto>> = ApiResult.Ok(emptyList())

        override suspend fun expected(): ApiResult<List<ExpectedInspectionDto>> = ApiResult.Ok(emptyList())

        override suspend fun submitInspection(operation: Outbox): ApiResult<InspectionDetailDto> = inspectionResult?.invoke(operation) ?: error("not used")

        override suspend fun submitStatus(operation: Outbox): ApiResult<StatusEventDto> = statusResult?.invoke(operation) ?: error("not used")

        override suspend fun uploadPhoto(
            operation: Pending_photo,
            clientUuid: String,
        ): ApiResult<PhotoDto> {
            val result = photoResult?.invoke(operation) ?: error("not used")
            return if (result is ApiResult.Ok) ApiResult.Ok(result.value, result.rawBody) else result
        }
    }
}
