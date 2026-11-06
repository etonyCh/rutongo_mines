package rw.rutongo.mmems.modules.inspection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.modules.inspection.domain.AttestationInput
import rw.rutongo.mmems.modules.inspection.domain.DefectInput
import rw.rutongo.mmems.modules.inspection.domain.InspectionAnswerInput
import rw.rutongo.mmems.modules.inspection.domain.InspectionSubmission
import rw.rutongo.mmems.modules.status.StatusService
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.OperationalStatus
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.Severity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InspectionSubmitIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: InspectionService
    private lateinit var typeId: UUID
    private lateinit var shiftId: UUID
    private val fixedNow = Instant.parse("2026-11-05T10:00:00Z")

    @BeforeAll
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                },
            )
        Flyway
            .configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        service = InspectionService(dataSource) { fixedNow }
        typeId =
            dataSource.connection.use { c ->
                c.prepareStatement("insert into equipment_type(name) values (?) returning id").use { ps ->
                    ps.setString(1, "Inspection type ${UUID.randomUUID()}")
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
        shiftId =
            dataSource.connection.use { c ->
                c.prepareStatement("select id from shift where code='DAY'").use { ps ->
                    ps.executeQuery().use {
                        it.next()
                        it.getObject(1, UUID::class.java)
                    }
                }
            }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `submission snapshots answers creates defects and is idempotent`() {
        val site = createSite("SUBMIT")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T11:00:00Z"))
        val items = checklistItems()
        val failing = items.first { it.second == "ENGINE_OIL" }
        val submission = submission(equipment, expectation, items, failing.first, Answer.FAIL)

        val created = service.submit(actor, submission)
        val replayed = service.submit(actor, submission)

        assertEquals(created.inspection.id, replayed.inspection.id)
        assertEquals("COMPLETED_WITH_DEFECTS", created.inspection.result)
        assertFalse(created.inspection.submittedLate)
        assertEquals(items.size, created.answers.size)
        assertEquals(1, created.defects.size)
        assertEquals("Oil leak from the engine", created.defects.single().description)
        assertEquals("Engine Oil", created.answers.first { it.itemId == failing.first }.label)
        assertEquals(1, count("select count(*) from inspection where client_uuid=?", UUID.fromString(submission.clientUuid)))
        assertEquals(items.size, count("select count(*) from inspection_answer where inspection_id=?", UUID.fromString(created.inspection.id)))
        assertEquals(2, count("select count(*) from engine_hour_reading where equipment_id=?", equipment))
        assertEquals("COMPLETED_WITH_DEFECTS", expectationState(expectation))
        assertEquals(108.0, equipmentHours(equipment))
        assertEquals(UUID.fromString(created.inspection.id), lastInspection(equipment))
        assertEquals("VALID", workingHoursStatus(created.inspection.id))
        assertEquals(null, equipmentStatus(equipment))
        assertTrue(service.control(actor, LocalDate.parse("2026-11-05")).any { it.id == expectation.toString() })
    }

    @Test
    fun `invalid mandatory fields do not create an inspection or complete the expectation`() {
        val site = createSite("INVALID")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T11:00:00Z"))
        val items = checklistItems()
        val submission =
            submission(equipment, expectation, items, items.first().first, Answer.PASS).copy(
                answers = listOf(InspectionAnswerInput(items.first().first, Answer.PASS)),
                engineHoursStart = 50.0,
            )

        assertFailsWith<InspectionValidationException> { service.submit(actor, submission) }

        assertEquals(0, count("select count(*) from inspection where client_uuid=?", UUID.fromString(submission.clientUuid)))
        assertEquals("NOT_STARTED", expectationState(expectation))
        assertEquals(0, count("select count(*) from defect where equipment_id=?", equipment))
    }

    @Test
    fun `late submission is accepted after missing and keeps the missing timestamp`() {
        val site = createSite("LATE")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T06:00:00Z"), Instant.parse("2026-11-05T07:00:00Z"))
        dataSource.connection.use { c ->
            c.prepareStatement("update inspection_expectation set state='MISSING',missing_marked_at=? where id=?").use { ps ->
                ps.setTimestamp(1, java.sql.Timestamp.from(Instant.parse("2026-11-05T07:05:00Z")))
                ps.setObject(2, expectation)
                ps.executeUpdate()
            }
        }
        val items = checklistItems()

        val created = service.submit(actor, submission(equipment, expectation, items, items.first().first, Answer.PASS))

        assertTrue(created.inspection.submittedLate)
        assertEquals("COMPLETED", created.inspection.result)
        assertEquals("COMPLETED", expectationState(expectation))
        assertNotNull(expectationMissingAt(expectation))
    }

    @Test
    fun `a completed expectation rejects a second inspection`() {
        val site = createSite("DUPLICATE")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T11:00:00Z"))
        val items = checklistItems()
        val failing = items.first { it.second == "ENGINE_OIL" }
        val first = submission(equipment, expectation, items, failing.first, Answer.FAIL)

        service.submit(actor, first)

        assertFailsWith<AuthFailure> {
            service.submit(actor, first.copy(clientUuid = UUID.randomUUID().toString()))
        }
        assertEquals(1, count("select count(*) from inspection where equipment_id=?", equipment))
    }

    @Test
    fun `critical safety failure produces a critical inspection result`() {
        val site = createSite("CRITICAL")
        val actor = createActor(Role.MECHANIC, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T11:00:00Z"))
        val items = checklistItems()
        val failing = items.first { it.second == "BRAKING_SYSTEM" }
        val submission =
            submission(equipment, expectation, items, failing.first, Answer.FAIL).copy(
                answers =
                    submission(equipment, expectation, items, failing.first, Answer.FAIL).answers.map {
                        if (it.itemId == failing.first) {
                            it.copy(defect = DefectInput("Brake failure", Severity.CRITICAL), photoIds = listOf(UUID.randomUUID().toString()))
                        } else {
                            it
                        }
                    },
            )

        val created = service.submit(actor, submission)

        assertEquals("CRITICAL_FAILURE", created.inspection.result)
        assertEquals("CRITICAL", created.defects.single().severity)
        assertEquals("CRITICAL_FAILURE", expectationState(expectation))
        assertEquals("DOWN", equipmentStatus(equipment))
        assertEquals("SYSTEM_RULE", latestStatusSource(equipment))
        assertEquals(1, statusEventCount(equipment))
        assertEquals(1, photoReferenceCount(created.inspection.id, failing.first))
        val failingAnswer = created.answers.first { it.itemId == failing.first }
        assertEquals(1, failingAnswer.photoIds.size)
    }

    @Test
    fun `manager must provide an override reason to reverse an automatic status rule`() {
        val site = createSite("OVERRIDE")
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        val equipment = createEquipment(site)
        val expectation = createExpectation(site, equipment, LocalDate.parse("2026-11-05"), Instant.parse("2026-11-05T08:00:00Z"), Instant.parse("2026-11-05T11:00:00Z"))
        val items = checklistItems()
        val failing = items.first { it.second == "BRAKING_SYSTEM" }
        val critical =
            submission(equipment, expectation, items, failing.first, Answer.FAIL).copy(
                answers =
                    submission(equipment, expectation, items, failing.first, Answer.FAIL).answers.map {
                        if (it.itemId == failing.first) {
                            it.copy(defect = DefectInput("Brake failure", Severity.CRITICAL), photoIds = listOf(UUID.randomUUID().toString()))
                        } else {
                            it
                        }
                    },
            )
        service.submit(mechanic, critical)
        val statuses = StatusService(dataSource)
        val version = equipmentVersion(equipment)

        assertFailsWith<IllegalArgumentException> {
            statuses.record(manager, equipment, version, "override-without-reason", OperationalStatus.OPERATIONAL, null, null, null, null, null, null, fixedNow.plusSeconds(60))
        }
        assertEquals("DOWN", equipmentStatus(equipment))

        val overridden = statuses.record(manager, equipment, version, "override-with-reason", OperationalStatus.OPERATIONAL, null, null, null, null, null, null, fixedNow.plusSeconds(60), "Brake system repaired and verified")
        assertEquals("OPERATIONAL", equipmentStatus(equipment))
        assertEquals("MANUAL", overridden.event.source)
        assertEquals("Brake system repaired and verified", overridden.event.overrideReason)
    }

    private fun submission(
        equipment: UUID,
        expectation: UUID,
        items: List<Pair<String, String>>,
        failingItem: String,
        failingAnswer: Answer,
    ) = InspectionSubmission(
        clientUuid = UUID.randomUUID().toString(),
        expectationId = expectation.toString(),
        equipmentId = equipment.toString(),
        shiftId = shiftId.toString(),
        reportDate = LocalDate.parse("2026-11-05"),
        engineHoursStart = 100.0,
        engineHoursEnd = 108.0,
        capturedAt = Instant.parse("2026-11-05T09:55:00Z"),
        startedAt = Instant.parse("2026-11-05T09:50:00Z"),
        attestation = AttestationInput("Mechanic", "EID-MECHANIC", "PIN", "fingerprint"),
        answers =
            items.map { (id, _) ->
                if (id == failingItem) {
                    InspectionAnswerInput(id, failingAnswer, defect = if (failingAnswer == Answer.FAIL) DefectInput("Oil leak from the engine", Severity.MAJOR) else null)
                } else {
                    InspectionAnswerInput(id, Answer.PASS)
                }
            },
    )

    private fun checklistItems(): List<Pair<String, String>> =
        dataSource.connection.use { c ->
            c.prepareStatement("select id,code from checklist_item where active order by sort_order").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getObject(1, UUID::class.java).toString() to rs.getString(2))
                    }
                }
            }
        }

    private fun createSite(prefix: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into site(id,code,name,state,timezone) values (?,?,?,'ACTIVE','Africa/Kigali')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "$prefix-${id.toString().take(8)}".uppercase())
                ps.setString(3, "$prefix Site")
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createActor(
        role: Role,
        sites: List<UUID>,
    ): Principal {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash) values (?,?,?,?,?,'not-used')").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "inspection-user-$id")
                ps.setString(3, "Inspection User")
                ps.setString(4, "EID-$id")
                ps.setString(5, role.name)
                ps.executeUpdate()
            }
            c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                sites.forEach {
                    ps.setObject(1, id)
                    ps.setObject(2, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        return Principal(id, "inspection-user-$id", role, false)
    }

    private fun createEquipment(site: UUID): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id) values (?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "ASSET-$id")
                ps.setString(3, "EQ-${id.toString().take(8)}")
                ps.setString(4, "Inspection Equipment")
                ps.setObject(5, typeId)
                ps.setObject(6, site)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun createExpectation(
        site: UUID,
        equipment: UUID,
        reportDate: LocalDate,
        windowStart: Instant,
        windowEnd: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.prepareStatement("insert into inspection_expectation(id,equipment_id,site_id,shift_id,report_date,window_start,window_end) values (?,?,?,?,?,?,?)").use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, equipment)
                ps.setObject(3, site)
                ps.setObject(4, shiftId)
                ps.setObject(5, reportDate)
                ps.setTimestamp(6, java.sql.Timestamp.from(windowStart))
                ps.setTimestamp(7, java.sql.Timestamp.from(windowEnd))
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun count(
        sql: String,
        id: UUID,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun expectationState(id: UUID): String =
        dataSource.connection.use { c ->
            c.prepareStatement("select state from inspection_expectation where id=?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun expectationMissingAt(id: UUID): Instant? =
        dataSource.connection.use { c ->
            c.prepareStatement("select missing_marked_at from inspection_expectation where id=?").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getTimestamp(1)?.toInstant()
                }
            }
        }

    private fun equipmentHours(equipment: UUID): Double? =
        dataSource.connection.use { c ->
            c.prepareStatement("select engine_hours_current from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getBigDecimal(1)?.toDouble()
                }
            }
        }

    private fun lastInspection(equipment: UUID): UUID? =
        dataSource.connection.use { c ->
            c.prepareStatement("select last_inspection_id from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getObject(1, UUID::class.java)
                }
            }
        }

    private fun equipmentStatus(equipment: UUID): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("select current_status from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun equipmentVersion(equipment: UUID): Long =
        dataSource.connection.use { c ->
            c.prepareStatement("select version from equipment where id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun latestStatusSource(equipment: UUID): String? =
        dataSource.connection.use { c ->
            c.prepareStatement("select source from equipment_status_event where equipment_id=? order by occurred_at desc,seq desc limit 1").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun statusEventCount(equipment: UUID): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from equipment_status_event where equipment_id=?").use { ps ->
                ps.setObject(1, equipment)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun photoReferenceCount(
        inspectionId: String,
        itemId: String,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select count(*) from inspection_answer_photo where inspection_id=? and checklist_item_id=?").use { ps ->
                ps.setObject(1, UUID.fromString(inspectionId))
                ps.setObject(2, UUID.fromString(itemId))
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun workingHoursStatus(inspectionId: String): String =
        dataSource.connection.use { c ->
            c.prepareStatement("select status from working_hours_entry where inspection_id=?").use { ps ->
                ps.setObject(1, UUID.fromString(inspectionId))
                ps.executeQuery().use {
                    it.next()
                    it.getString(1)
                }
            }
        }
}
