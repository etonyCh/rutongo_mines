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
import rw.rutongo.mmems.shared.api.BreakdownTransitionDto
import rw.rutongo.mmems.shared.api.BreakdownViewDto
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.InspectionDetailDto
import rw.rutongo.mmems.shared.api.MaintenanceCreateDto
import rw.rutongo.mmems.shared.api.MaintenanceTechnicianDto
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.PhotoDto
import rw.rutongo.mmems.shared.api.ServiceScheduleViewDto
import rw.rutongo.mmems.shared.api.ServiceTypeViewDto
import rw.rutongo.mmems.shared.api.SessionResponse
import rw.rutongo.mmems.shared.api.ShiftDto
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.api.StatusEventDto
import rw.rutongo.mmems.shared.api.WorkingHoursResponseDto
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.WorkingHoursGroupBy
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

    @Test
    fun `offline maintenance is queued and cached from the server response`() =
        runTest {
            val input =
                MaintenanceCreateDto(
                    equipmentId = equipmentId,
                    serviceTypeId = "service-1",
                    workPerformed = "Replaced hydraulic filter",
                    technicianId = "technician-1",
                )
            val clientUuid = store.enqueueMaintenance(equipmentId, input)
            assertEquals(1, store.pendingOutbox().size)

            val api =
                FakeApi(
                    maintenanceCreateResult = {
                        ApiResult.Ok(
                            MaintenanceViewDto(
                                id = "maintenance-1",
                                equipmentId = equipmentId,
                                equipmentCode = "EQ-01",
                                equipmentName = "LHD 01",
                                serviceTypeId = "service-1",
                                serviceTypeCode = "SERVICE-250",
                                serviceTypeName = "250-hour service",
                                siteId = siteId,
                                siteName = "Kigali site",
                                performedAt = "2026-01-01T00:00:00Z",
                                workPerformed = "Replaced hydraulic filter",
                                technicianId = "technician-1",
                                technicianName = "Technician",
                                totalCost = null,
                                currency = "RWF",
                                verificationStatus = MaintenanceVerificationStatus.PENDING_VERIFICATION,
                                version = 0,
                            ),
                        )
                    },
                )
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertEquals(1, report.sentOperations)
            assertEquals("DONE", store.outbox(clientUuid)?.state)
            assertEquals("maintenance-1", store.maintenanceNow().single().id)
            assertNull(store.maintenanceNow().single().totalCost)
        }

    @Test
    fun `reference sync does not fail when breakdown listing is forbidden`() =
        runTest {
            val api = FakeApi(breakdownsResult = ApiResult.Invalid("FORBIDDEN", "Breakdowns are not available to this role."))

            val result = SyncEngine(store, api).syncReferences()

            assertEquals(ApiResult.Ok(Unit), result)
        }

    @Test
    fun `offline breakdown report is replaced by the server view after sync`() =
        runTest {
            val localId = store.enqueueBreakdownReport(store.equipment(equipmentId)!!, "MECHANICAL", "Hydraulic leak")
            assertEquals("CREATE", store.breakdown(localId)?.pending_state)
            assertEquals(1, store.pendingOutbox().size)

            val api = FakeApi(breakdownCreateResult = { ApiResult.Ok(breakdownView("server-breakdown-1")) })
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertEquals(1, report.sentOperations)
            assertNull(store.breakdown(localId))
            assertEquals("server-breakdown-1", store.breakdown("server-breakdown-1")?.id)
            assertEquals("DONE", store.outbox(localId)?.state)
        }

    @Test
    fun `queued repair transition follows the new breakdown report and is repointed to the server id`() =
        runTest {
            val localId = store.enqueueBreakdownReport(store.equipment(equipmentId)!!, "MECHANICAL", "Hydraulic leak")
            store.enqueueBreakdownTransition(
                store.breakdown(localId)!!,
                BreakdownTransitionDto(state = BreakdownState.DIAGNOSING, actionTaken = "Checked hoses"),
            )
            store.enqueueBreakdownTransition(
                store.breakdown(localId)!!,
                BreakdownTransitionDto(state = BreakdownState.UNDER_REPAIR, actionTaken = "Replacing hose"),
            )
            val transitionReferences = mutableListOf<String?>()
            val transitionVersions = mutableListOf<Long?>()
            var transitionCalls = 0

            val api =
                FakeApi(
                    breakdownCreateResult = { ApiResult.Ok(breakdownView("server-breakdown-2")) },
                    breakdownTransitionResult = { operation, breakdownId ->
                        transitionReferences += breakdownId
                        transitionVersions += operation.if_match
                        transitionCalls++
                        val state = if (transitionCalls == 1) BreakdownState.DIAGNOSING else BreakdownState.UNDER_REPAIR
                        ApiResult.Ok(breakdownView("server-breakdown-2", state = state, version = (transitionCalls + 1).toLong()))
                    },
                )
            val report = SyncEngine(store, api).syncAll(refreshReferences = false)

            assertEquals(3, report.sentOperations)
            assertEquals(listOf("server-breakdown-2", "server-breakdown-2"), transitionReferences)
            assertEquals(listOf<Long?>(1L, 2L), transitionVersions)
            assertEquals("UNDER_REPAIR", store.breakdown("server-breakdown-2")?.state)
            assertEquals(3L, store.breakdown("server-breakdown-2")?.version)
            assertNull(store.breakdown("server-breakdown-2")?.pending_state)
        }

    private fun breakdownView(
        id: String,
        state: BreakdownState = BreakdownState.REPORTED,
        version: Long = 1,
    ) = BreakdownViewDto(
        id = id,
        equipmentId = equipmentId,
        equipmentCode = "EQ-01",
        category = "MECHANICAL",
        problem = "Hydraulic leak",
        siteId = siteId,
        reportedBy = "mechanic-1",
        reportedAt = "2026-01-01T00:00:00Z",
        breakdownStart = "2026-01-01T00:00:00Z",
        state = state.name,
        version = version,
    )

    private class FakeApi(
        private val statusResult: (suspend (Outbox) -> ApiResult<StatusEventDto>)? = null,
        private val inspectionResult: (suspend (Outbox) -> ApiResult<InspectionDetailDto>)? = null,
        private val photoResult: (suspend (Pending_photo) -> ApiResult<PhotoDto>)? = null,
        private val breakdownCreateResult: (suspend (Outbox) -> ApiResult<BreakdownViewDto>)? = null,
        private val breakdownTransitionResult: (suspend (Outbox, String) -> ApiResult<BreakdownViewDto>)? = null,
        private val breakdownsResult: ApiResult<List<BreakdownViewDto>> = ApiResult.Ok(emptyList()),
        private val maintenanceCreateResult: (suspend (Outbox) -> ApiResult<MaintenanceViewDto>)? = null,
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

        override suspend fun breakdowns(): ApiResult<List<BreakdownViewDto>> = breakdownsResult

        override suspend fun submitBreakdown(operation: Outbox): ApiResult<BreakdownViewDto> = breakdownCreateResult?.invoke(operation) ?: error("not used")

        override suspend fun submitBreakdownTransition(
            operation: Outbox,
            breakdownId: String,
        ): ApiResult<BreakdownViewDto> = breakdownTransitionResult?.invoke(operation, breakdownId) ?: error("not used")

        override suspend fun uploadPhoto(
            operation: Pending_photo,
            clientUuid: String,
        ): ApiResult<PhotoDto> {
            val result = photoResult?.invoke(operation) ?: error("not used")
            return if (result is ApiResult.Ok) ApiResult.Ok(result.value, result.rawBody) else result
        }

        override suspend fun serviceTypes(): ApiResult<List<ServiceTypeViewDto>> = ApiResult.Ok(emptyList())

        override suspend fun maintenanceTechnicians(): ApiResult<List<MaintenanceTechnicianDto>> = ApiResult.Ok(emptyList())

        override suspend fun maintenance(): ApiResult<List<MaintenanceViewDto>> = ApiResult.Ok(emptyList())

        override suspend fun serviceSchedule(): ApiResult<List<ServiceScheduleViewDto>> = ApiResult.Ok(emptyList())

        override suspend fun workingHours(): ApiResult<WorkingHoursResponseDto> = ApiResult.Ok(WorkingHoursResponseDto(WorkingHoursGroupBy.EQUIPMENT, emptyList(), emptyList(), 0.0))

        override suspend fun submitMaintenance(operation: Outbox): ApiResult<MaintenanceViewDto> = maintenanceCreateResult?.invoke(operation) ?: error("not used")

        override suspend fun transitionMaintenance(
            maintenanceId: String,
            action: String,
            version: Long,
            comment: String?,
        ): ApiResult<MaintenanceViewDto> = error("not used")
    }
}
