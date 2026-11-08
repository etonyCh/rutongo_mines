@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList")

package rw.rutongo.mmems.modules.parts

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
import rw.rutongo.mmems.shared.api.PartCreateDto
import rw.rutongo.mmems.shared.api.PartRequestCreateDto
import rw.rutongo.mmems.shared.api.PartRequestDecisionDto
import rw.rutongo.mmems.shared.api.PartRequestLineCreateDto
import rw.rutongo.mmems.shared.api.PartRequestQuantityActionDto
import rw.rutongo.mmems.shared.api.PartUpdateDto
import rw.rutongo.mmems.shared.api.StockMovementCreateDto
import rw.rutongo.mmems.shared.enums.PartRequestState
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.StockMovementType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartsWorkflowIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    private lateinit var dataSource: HikariDataSource
    private lateinit var service: PartsService
    private val fixedNow = Instant.parse("2026-11-08T10:00:00Z")

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
        service = PartsService(dataSource) { fixedNow }
    }

    @AfterAll
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `creating and approving a request never changes stock`() {
        val site = createSite("PARTS")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val manager = createActor(Role.MANAGER, listOf(site))
        val part = createStockedPart(admin, "P-${UUID.randomUUID().toString().take(8)}", 20, 5, 2500)
        val movementCount = count("select count(*) from stock_movement where part_id=?", UUID.fromString(part.id))

        val input =
            PartRequestCreateDto(
                siteId = site.toString(),
                notes = "Repair kit",
                lines = listOf(PartRequestLineCreateDto(part.id, 8)),
            )
        val created = service.createRequest(mechanic, "parts-request-1", input)
        val replayed = service.createRequest(mechanic, "parts-request-1", input)

        assertEquals(created.id, replayed.id)
        assertEquals(PartRequestState.REQUESTED, created.state)
        assertEquals(20, service.getPart(mechanic, UUID.fromString(part.id)).onHand)
        assertEquals(movementCount, count("select count(*) from stock_movement where part_id=?", UUID.fromString(part.id)))
        assertNull(created.totalCost)
        assertNull(created.lines.single().unitCost)

        val approved = service.approve(supervisor, UUID.fromString(created.id), created.version, PartRequestDecisionDto("Approved"))
        assertEquals(PartRequestState.APPROVED, approved.state)
        assertEquals(20, stock(part.id, "MAIN"))
        assertEquals(movementCount, count("select count(*) from stock_movement where part_id=?", UUID.fromString(part.id)))
        assertEquals(20000, service.listRequests(manager, site).single().totalCost)
    }

    @Test
    fun `issue use and return move stock only through immutable stock movements`() {
        val site = createSite("ISSUE")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val part = createStockedPart(admin, "P-${UUID.randomUUID().toString().take(8)}", 20, 5, 1200)

        val created = request(mechanic, site, part.id, 8, "issue-one")
        val approved = service.approve(supervisor, UUID.fromString(created.id), created.version, PartRequestDecisionDto())
        val issued = service.issue(supervisor, UUID.fromString(created.id), approved.version, PartRequestQuantityActionDto())

        assertEquals(PartRequestState.ISSUED, issued.state)
        assertEquals(12, stock(part.id, "MAIN"))
        assertEquals(8, issued.lines.single().issuedQty)
        val issueMovement = service.listStockMovements(supervisor, UUID.fromString(part.id)).first { it.type == StockMovementType.ISSUE }
        assertEquals(-8, issueMovement.quantity)
        assertEquals(created.id, issueMovement.requestId)

        val used = service.use(mechanic, UUID.fromString(created.id), issued.version, PartRequestQuantityActionDto())
        assertEquals(PartRequestState.USED, used.state)
        assertEquals(8, used.lines.single().usedQty)
        assertEquals(12, stock(part.id, "MAIN"))

        val second = request(mechanic, site, part.id, 5, "issue-two")
        val approvedSecond = service.approve(supervisor, UUID.fromString(second.id), second.version, PartRequestDecisionDto())
        val issuedSecond = service.issue(supervisor, UUID.fromString(second.id), approvedSecond.version, PartRequestQuantityActionDto())
        assertEquals(7, stock(part.id, "MAIN"))
        val returned = service.returnParts(mechanic, UUID.fromString(second.id), issuedSecond.version, PartRequestQuantityActionDto())
        assertEquals(PartRequestState.RETURNED, returned.state)
        assertEquals(12, stock(part.id, "MAIN"))
        val returnMovement = service.listStockMovements(supervisor, UUID.fromString(part.id)).first { it.type == StockMovementType.RETURN }
        assertEquals(5, returnMovement.quantity)
    }

    @Test
    fun `insufficient stock rolls the issue back and low stock is notified once per day`() {
        val site = createSite("LOW")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val part = createStockedPart(admin, "P-${UUID.randomUUID().toString().take(8)}", 6, 10, 700)

        val tooLarge = request(mechanic, site, part.id, 9, "too-large")
        val approvedTooLarge = service.approve(supervisor, UUID.fromString(tooLarge.id), tooLarge.version, PartRequestDecisionDto())
        val failure =
            assertFailsWith<AuthFailure> {
                service.issue(supervisor, UUID.fromString(tooLarge.id), approvedTooLarge.version, PartRequestQuantityActionDto())
            }
        assertEquals("INSUFFICIENT_STOCK", failure.code)
        assertEquals(6, stock(part.id, "MAIN"))
        assertEquals(PartRequestState.APPROVED, service.listRequests(supervisor, site).single { it.id == tooLarge.id }.state)
        assertEquals(1, count("select count(*) from stock_movement where part_id=?", UUID.fromString(part.id)))
        assertEquals(1, count("select count(*) from outbox_message where topic='LOW_STOCK' and payload->>'partId'=?", part.id))

        val valid = request(mechanic, site, part.id, 2, "valid")
        val approved = service.approve(supervisor, UUID.fromString(valid.id), valid.version, PartRequestDecisionDto())
        service.issue(supervisor, UUID.fromString(valid.id), approved.version, PartRequestQuantityActionDto())
        assertEquals(4, stock(part.id, "MAIN"))
        assertEquals(1, count("select count(*) from outbox_message where topic='LOW_STOCK' and payload->>'partId'=?", part.id))
        assertTrue(service.listParts(supervisor, lowStock = true).any { it.id == part.id })
    }

    @Test
    fun `rejection requires a comment and catalog edits are versioned`() {
        val site = createSite("DECISION")
        val admin = createActor(Role.ADMINISTRATOR, listOf(site))
        val mechanic = createActor(Role.MECHANIC, listOf(site))
        val supervisor = createActor(Role.SUPERVISOR, listOf(site))
        val outsider = createActor(Role.SUPERVISOR, listOf(createSite("OTHER")))
        val part = createStockedPart(admin, "P-${UUID.randomUUID().toString().take(8)}", 4, 1, 100)
        val created = request(mechanic, site, part.id, 1, "reject")

        assertFailsWith<IllegalArgumentException> {
            service.reject(supervisor, UUID.fromString(created.id), created.version, PartRequestDecisionDto("  "))
        }
        val scope =
            assertFailsWith<AuthFailure> {
                service.approve(outsider, UUID.fromString(created.id), created.version, PartRequestDecisionDto())
            }
        assertEquals("SITE_NOT_AUTHORIZED", scope.code)
        val rejected = service.reject(supervisor, UUID.fromString(created.id), created.version, PartRequestDecisionDto("Not needed"))
        assertEquals(PartRequestState.REJECTED, rejected.state)
        val invalid =
            assertFailsWith<AuthFailure> {
                service.approve(supervisor, UUID.fromString(created.id), rejected.version, PartRequestDecisionDto())
            }
        assertEquals("INVALID_PART_REQUEST_TRANSITION", invalid.code)

        val stale =
            assertFailsWith<AuthFailure> {
                service.updatePart(admin, UUID.fromString(part.id), part.version + 1, PartUpdateDto("Updated", unit = "BOX", minStock = 2))
            }
        assertEquals("PART_VERSION_CONFLICT", stale.code)
        val updated = service.updatePart(admin, UUID.fromString(part.id), part.version, PartUpdateDto("Updated", unit = "BOX", minStock = 2, unitCost = 150))
        assertEquals(1, updated.version)
        assertEquals("BOX", updated.unit)
        assertEquals(2, updated.minStock)
    }

    private fun createStockedPart(
        admin: Principal,
        partNumber: String,
        quantity: Int,
        minStock: Int,
        unitCost: Long,
    ) = service.createPart(admin, PartCreateDto(partNumber, "Filter for repair", unit = "PIECE", minStock = minStock, unitCost = unitCost)).also {
        if (quantity > 0) {
            service.recordStockMovement(
                admin,
                StockMovementCreateDto(it.id, "MAIN", quantity, StockMovementType.RECEIPT, "Initial stock"),
            )
        }
    }

    private fun request(
        mechanic: Principal,
        site: UUID,
        partId: String,
        quantity: Int,
        key: String,
    ) = service.createRequest(
        mechanic,
        "$key-${UUID.randomUUID()}",
        PartRequestCreateDto(site.toString(), lines = listOf(PartRequestLineCreateDto(partId, quantity))),
    )

    private fun stock(
        partId: String,
        location: String,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("select on_hand from part_stock where part_id=? and location=?").use { ps ->
                ps.setObject(1, UUID.fromString(partId))
                ps.setString(2, location)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
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
                ps.setString(2, "parts-user-$id")
                ps.setString(3, "Parts User")
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
        return Principal(id, "parts-user-$id", role, false)
    }

    private fun count(
        sql: String,
        parameter: Any,
    ): Int =
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, parameter)
                ps.executeQuery().use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
}
