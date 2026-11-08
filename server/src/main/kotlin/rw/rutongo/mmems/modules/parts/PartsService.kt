@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "MagicNumber",
    "TooGenericExceptionCaught",
)

package rw.rutongo.mmems.modules.parts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.api.PartCreateDto
import rw.rutongo.mmems.shared.api.PartRequestCreateDto
import rw.rutongo.mmems.shared.api.PartRequestDecisionDto
import rw.rutongo.mmems.shared.api.PartRequestLineCreateDto
import rw.rutongo.mmems.shared.api.PartRequestLineQuantityDto
import rw.rutongo.mmems.shared.api.PartRequestLineViewDto
import rw.rutongo.mmems.shared.api.PartRequestQuantityActionDto
import rw.rutongo.mmems.shared.api.PartRequestViewDto
import rw.rutongo.mmems.shared.api.PartStockViewDto
import rw.rutongo.mmems.shared.api.PartUpdateDto
import rw.rutongo.mmems.shared.api.PartViewDto
import rw.rutongo.mmems.shared.api.StockMovementCreateDto
import rw.rutongo.mmems.shared.api.StockMovementViewDto
import rw.rutongo.mmems.shared.enums.PartRequestState
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.StockMovementType
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)

private data class RequestRow(
    val id: UUID,
    val state: PartRequestState,
    val siteId: UUID,
    val requestedBy: UUID,
    val version: Long,
)

private data class RequestLineInput(
    val partId: UUID,
    val quantity: Int,
    val location: String,
    val notes: String?,
)

private data class LineRow(
    val id: UUID,
    val partId: UUID,
    val partNumber: String,
    val description: String,
    val unit: String,
    val location: String,
    val requestedQty: Int,
    val issuedQty: Int,
    val usedQty: Int,
    val returnedQty: Int,
    val unitCost: Long?,
    val notes: String?,
)

class PartsService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun listParts(
        actor: Principal,
        query: String? = null,
        lowStock: Boolean = false,
        includeInactive: Boolean = false,
        limit: Int = 100,
    ): List<PartViewDto> {
        requirePartView(actor)
        return dataSource.connection.use { c ->
            val parts =
                c
                    .prepareStatement(
                        """
                        select p.*,coalesce(s.on_hand,0) on_hand
                        from part p
                        left join (select part_id,sum(on_hand) on_hand from part_stock group by part_id) s on s.part_id=p.id
                        where (?::text is null or p.part_number ilike ? or p.description ilike ? or coalesce(p.category,'') ilike ?)
                          and (? or p.active)
                          and (not ? or coalesce(s.on_hand,0) < p.min_stock)
                        order by p.part_number limit ?
                        """.trimIndent(),
                    ).use { ps ->
                        val term = query?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
                        ps.setString(1, term)
                        ps.setString(2, term)
                        ps.setString(3, term)
                        ps.setString(4, term)
                        ps.setBoolean(5, includeInactive)
                        ps.setBoolean(6, lowStock)
                        ps.setInt(7, limit.coerceIn(1, 500))
                        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toPart(actor)) } }
                    }
            attachLocations(c, parts)
        }
    }

    fun getPart(
        actor: Principal,
        partId: UUID,
    ): PartViewDto {
        requirePartView(actor)
        return dataSource.connection.use { c ->
            val parts = loadParts(c, actor, listOf(partId))
            parts.singleOrNull() ?: throw AuthFailure("PART_NOT_FOUND")
        }
    }

    fun createPart(
        actor: Principal,
        input: PartCreateDto,
    ): PartViewDto {
        requirePermission(actor, "PART_MANAGE")
        val partNumber = input.partNumber.trim()
        val description = input.description.trim()
        val unit = input.unit.trim().uppercase()
        require(partNumber.isNotEmpty() && partNumber.length <= 100) { "Part number is required." }
        require(description.isNotEmpty()) { "Description is required." }
        require(unit.isNotEmpty() && unit.length <= 20) { "Unit is required." }
        require(input.minStock >= 0) { "Minimum stock cannot be negative." }
        val unitCost = input.unitCost
        require(unitCost == null || unitCost >= 0) { "Unit cost cannot be negative." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val id = UUID.randomUUID()
                try {
                    c
                        .prepareStatement(
                            "insert into part(id,part_number,description,category,unit,min_stock,unit_cost) values (?,?,?,?,?,?,?)",
                        ).use { ps ->
                            ps.setObject(1, id)
                            ps.setString(2, partNumber)
                            ps.setString(3, description)
                            ps.setString(4, input.category?.trim()?.takeIf(String::isNotEmpty))
                            ps.setString(5, unit)
                            ps.setInt(6, input.minStock)
                            ps.setObject(7, unitCost)
                            ps.executeUpdate()
                        }
                } catch (e: SQLException) {
                    if (e.sqlState == "23505") throw AuthFailure("PART_NUMBER_EXISTS")
                    throw e
                }
                audit(c, actor, id, "PART_CREATED", mapOf("partNumber" to partNumber), "part")
                val view = loadParts(c, actor, listOf(id)).single()
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun updatePart(
        actor: Principal,
        partId: UUID,
        expectedVersion: Long,
        input: PartUpdateDto,
    ): PartViewDto {
        requirePermission(actor, "PART_MANAGE")
        val description = input.description.trim()
        val unit = input.unit.trim().uppercase()
        require(description.isNotEmpty()) { "Description is required." }
        require(unit.isNotEmpty() && unit.length <= 20) { "Unit is required." }
        require(input.minStock >= 0) { "Minimum stock cannot be negative." }
        val unitCost = input.unitCost
        require(unitCost == null || unitCost >= 0) { "Unit cost cannot be negative." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val locked = lockPart(c, partId)
                if (locked != expectedVersion) throw AuthFailure("PART_VERSION_CONFLICT")
                c
                    .prepareStatement(
                        """
                        update part set description=?,category=?,unit=?,min_stock=?,unit_cost=?,active=?,
                          updated_at=now(),version=version+1
                        where id=?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, description)
                        ps.setString(2, input.category?.trim()?.takeIf(String::isNotEmpty))
                        ps.setString(3, unit)
                        ps.setInt(4, input.minStock)
                        ps.setObject(5, unitCost)
                        ps.setBoolean(6, input.active)
                        ps.setObject(7, partId)
                        ps.executeUpdate()
                    }
                audit(c, actor, partId, "PART_UPDATED", mapOf("version" to (expectedVersion + 1).toString()), "part")
                val view = loadParts(c, actor, listOf(partId)).single()
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun recordStockMovement(
        actor: Principal,
        input: StockMovementCreateDto,
    ): StockMovementViewDto {
        requirePermission(actor, "PART_MANAGE")
        require(input.type == StockMovementType.RECEIPT || input.type == StockMovementType.ADJUSTMENT) {
            "Issues and returns must go through a part request."
        }
        require(input.quantity != 0) { "Stock movement quantity cannot be zero." }
        if (input.type == StockMovementType.RECEIPT) require(input.quantity > 0) { "Receipt quantity must be positive." }
        val partId = uuid(input.partId, "Part")
        val location = input.location.trim().ifEmpty { "MAIN" }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val partNumber = partNumber(c, partId)
                val id = UUID.randomUUID()
                insertMovement(c, id, partId, location, input.quantity, input.type, actor, null, null, input.reason)
                audit(
                    c,
                    actor,
                    id,
                    "STOCK_MOVEMENT_RECORDED",
                    mapOf("partId" to partId.toString(), "quantity" to input.quantity.toString(), "type" to input.type.name),
                    "stock_movement",
                )
                emitLowStock(c, partId, partNumber)
                val view = loadStockMovement(c, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun listStockMovements(
        actor: Principal,
        partId: UUID? = null,
        requestId: UUID? = null,
        limit: Int = 100,
    ): List<StockMovementViewDto> {
        if (!canViewStock(actor)) throw AuthFailure("FORBIDDEN")
        return dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select sm.*,p.part_number
                    from stock_movement sm join part p on p.id=sm.part_id
                    where (?::uuid is null or sm.part_id=?) and (?::uuid is null or sm.request_id=?)
                    order by sm.occurred_at desc,sm.id desc limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, partId)
                    ps.setObject(2, partId)
                    ps.setObject(3, requestId)
                    ps.setObject(4, requestId)
                    ps.setInt(5, limit.coerceIn(1, 500))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toStockMovement()) } }
                }
        }
    }

    fun createRequest(
        actor: Principal,
        idempotencyKey: String,
        input: PartRequestCreateDto,
    ): PartRequestViewDto {
        requirePermission(actor, "PART_REQUEST")
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200) { "A valid Idempotency-Key is required." }
        require(input.lines.isNotEmpty() && input.lines.size <= 100) { "A part request must contain between 1 and 100 lines." }
        val siteId = uuid(input.siteId, "Site")
        val normalized = normalizeLines(input.lines)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                replayRequest(c, actor, idempotencyKey)?.let {
                    c.rollback()
                    return it
                }
                if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                val equipmentId = resolveEquipment(c, siteId, input.equipmentId, input.breakdownId, input.maintenanceId)
                val id = UUID.randomUUID()
                c
                    .prepareStatement(
                        """
                        insert into part_request(id,idempotency_key,site_id,equipment_id,breakdown_id,maintenance_id,requested_by,notes)
                        values (?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, id)
                        ps.setString(2, idempotencyKey.trim())
                        ps.setObject(3, siteId)
                        ps.setObject(4, equipmentId)
                        ps.setObject(5, input.breakdownId?.let { uuid(it, "Breakdown") })
                        ps.setObject(6, input.maintenanceId?.let { uuid(it, "Maintenance") })
                        ps.setObject(7, actor.id)
                        ps.setString(8, input.notes?.trim()?.takeIf(String::isNotEmpty))
                        ps.executeUpdate()
                    }
                normalized.forEach { line ->
                    val unitCost = loadRequestPart(c, line.partId)
                    c
                        .prepareStatement(
                            "insert into part_request_line(request_id,part_id,location,requested_qty,unit_cost,notes) values (?,?,?,?,?,?)",
                        ).use { ps ->
                            ps.setObject(1, id)
                            ps.setObject(2, line.partId)
                            ps.setString(3, line.location)
                            ps.setInt(4, line.quantity)
                            ps.setObject(5, unitCost)
                            ps.setString(6, line.notes)
                            ps.executeUpdate()
                        }
                }
                audit(c, actor, id, "PART_REQUESTED", mapOf("siteId" to siteId.toString(), "lines" to normalized.size.toString()))
                outbox(c, "PART_REQUESTED", id, siteId, PartRequestState.REQUESTED.name)
                val view = loadRequest(c, id, actor)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun listRequests(
        actor: Principal,
        siteId: UUID? = null,
        state: PartRequestState? = null,
        limit: Int = 100,
    ): List<PartRequestViewDto> {
        requireRequestView(actor)
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            val viewAll = canViewAllRequests(actor)
            val ids =
                c
                    .prepareStatement(
                        """
                        select id from part_request r
                        where (?::uuid is null or r.site_id=?) and (?::text is null or r.state=?)
                          and ${siteAccessSql("r.site_id")}
                          and (? or r.requested_by=?)
                        order by r.requested_at desc limit ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, siteId)
                        ps.setObject(2, siteId)
                        ps.setString(3, state?.name)
                        ps.setString(4, state?.name)
                        bindSiteAccess(ps, actor, 5)
                        ps.setBoolean(8, viewAll)
                        ps.setObject(9, actor.id)
                        ps.setInt(10, limit.coerceIn(1, 200))
                        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
                    }
            loadRequests(c, ids, actor)
        }
    }

    fun approve(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        input: PartRequestDecisionDto,
    ): PartRequestViewDto = decide(actor, requestId, expectedVersion, PartRequestState.APPROVED, input.comment)

    fun reject(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        input: PartRequestDecisionDto,
    ): PartRequestViewDto {
        val comment = input.comment?.trim()
        require(!comment.isNullOrEmpty()) { "A rejection comment is required." }
        return decide(actor, requestId, expectedVersion, PartRequestState.REJECTED, comment)
    }

    fun issue(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        input: PartRequestQuantityActionDto,
    ): PartRequestViewDto {
        requirePermission(actor, "PART_ISSUE")
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val request = lockRequest(c, requestId)
                requireVersion(request, expectedVersion)
                requireRequestSite(c, actor, request.siteId)
                if (request.state != PartRequestState.APPROVED) throw AuthFailure("INVALID_PART_REQUEST_TRANSITION")
                val lines = loadLines(c, requestId)
                val quantities = quantityMap(input.lines, lines)
                val defaultToAll = input.lines.isEmpty()
                var issued = 0
                lines.forEach { line ->
                    val quantity = quantities[line.id] ?: if (defaultToAll) line.requestedQty else 0
                    if (quantity <= 0) return@forEach
                    require(quantity <= line.requestedQty) { "Issued quantity cannot exceed the requested quantity." }
                    c.prepareStatement("update part_request_line set issued_qty=? where id=?").use { ps ->
                        ps.setInt(1, quantity)
                        ps.setObject(2, line.id)
                        ps.executeUpdate()
                    }
                    try {
                        insertMovement(c, UUID.randomUUID(), line.partId, line.location, -quantity, StockMovementType.ISSUE, actor, requestId, line.id, null)
                    } catch (e: SQLException) {
                        if (e.sqlState == "23514") throw AuthFailure("INSUFFICIENT_STOCK")
                        throw e
                    }
                    issued += quantity
                }
                require(issued != 0) { "At least one line must be issued." }
                c.prepareStatement("update part_request set state='ISSUED',issued_by=?,issued_at=now(),updated_at=now(),version=version+1 where id=?").use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setObject(2, requestId)
                    ps.executeUpdate()
                }
                audit(c, actor, requestId, "PART_REQUEST_ISSUED", mapOf("state" to "ISSUED", "quantity" to issued.toString()))
                outbox(c, "PART_REQUEST_STATE_CHANGED", requestId, request.siteId, PartRequestState.ISSUED.name)
                lines.filter { (quantities[it.id] ?: 0) > 0 }.forEach { emitLowStock(c, it.partId, it.partNumber) }
                val view = loadRequest(c, requestId, actor)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun use(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        input: PartRequestQuantityActionDto,
    ): PartRequestViewDto {
        requireRequestView(actor)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val request = lockRequest(c, requestId)
                requireVersion(request, expectedVersion)
                requireRequestSite(c, actor, request.siteId)
                requireRequestActor(actor, request)
                if (request.state != PartRequestState.ISSUED) throw AuthFailure("INVALID_PART_REQUEST_TRANSITION")
                val lines = loadLines(c, requestId)
                val quantities = quantityMap(input.lines, lines)
                val defaultToAll = input.lines.isEmpty()
                lines.forEach { line ->
                    val remaining = line.issuedQty - line.returnedQty
                    val quantity = quantities[line.id] ?: if (defaultToAll) remaining else 0
                    if (quantity <= 0) return@forEach
                    require(quantity <= remaining) { "Used quantity cannot exceed the quantity still issued." }
                    c.prepareStatement("update part_request_line set used_qty=used_qty+? where id=?").use { ps ->
                        ps.setInt(1, quantity)
                        ps.setObject(2, line.id)
                        ps.executeUpdate()
                    }
                }
                c.prepareStatement("update part_request set state='USED',used_by=?,used_at=now(),updated_at=now(),version=version+1 where id=?").use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setObject(2, requestId)
                    ps.executeUpdate()
                }
                audit(c, actor, requestId, "PART_REQUEST_USED", mapOf("state" to "USED"))
                outbox(c, "PART_REQUEST_STATE_CHANGED", requestId, request.siteId, PartRequestState.USED.name)
                val view = loadRequest(c, requestId, actor)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun returnParts(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        input: PartRequestQuantityActionDto,
    ): PartRequestViewDto {
        requireRequestView(actor)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val request = lockRequest(c, requestId)
                requireVersion(request, expectedVersion)
                requireRequestSite(c, actor, request.siteId)
                requireRequestActor(actor, request)
                if (request.state != PartRequestState.ISSUED && request.state != PartRequestState.USED) {
                    throw AuthFailure("INVALID_PART_REQUEST_TRANSITION")
                }
                val lines = loadLines(c, requestId)
                val quantities = quantityMap(input.lines, lines)
                val defaultToAll = input.lines.isEmpty()
                var returned = 0
                lines.forEach { line ->
                    val remaining = line.issuedQty - line.usedQty - line.returnedQty
                    val quantity = quantities[line.id] ?: if (defaultToAll) remaining else 0
                    if (quantity <= 0) return@forEach
                    require(quantity <= remaining) { "Returned quantity cannot exceed the quantity still issued." }
                    c.prepareStatement("update part_request_line set returned_qty=returned_qty+? where id=?").use { ps ->
                        ps.setInt(1, quantity)
                        ps.setObject(2, line.id)
                        ps.executeUpdate()
                    }
                    insertMovement(c, UUID.randomUUID(), line.partId, line.location, quantity, StockMovementType.RETURN, actor, requestId, line.id, null)
                    returned += quantity
                }
                require(returned != 0) { "At least one line must be returned." }
                val remainingLines = loadLines(c, requestId).count { it.issuedQty - it.usedQty - it.returnedQty > 0 }
                val targetState = if (remainingLines == 0) PartRequestState.RETURNED else request.state
                c
                    .prepareStatement(
                        "update part_request set state=?,returned_by=?,returned_at=now(),updated_at=now(),version=version+1 where id=?",
                    ).use { ps ->
                        ps.setString(1, targetState.name)
                        ps.setObject(2, actor.id)
                        ps.setObject(3, requestId)
                        ps.executeUpdate()
                    }
                audit(c, actor, requestId, "PART_REQUEST_RETURNED", mapOf("state" to targetState.name, "quantity" to returned.toString()))
                outbox(c, "PART_REQUEST_STATE_CHANGED", requestId, request.siteId, targetState.name)
                val view = loadRequest(c, requestId, actor)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun decide(
        actor: Principal,
        requestId: UUID,
        expectedVersion: Long,
        target: PartRequestState,
        comment: String?,
    ): PartRequestViewDto {
        requirePermission(actor, "PART_APPROVE")
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val request = lockRequest(c, requestId)
                requireVersion(request, expectedVersion)
                requireRequestSite(c, actor, request.siteId)
                if (request.state != PartRequestState.REQUESTED) throw AuthFailure("INVALID_PART_REQUEST_TRANSITION")
                c
                    .prepareStatement(
                        "update part_request set state=?,approved_by=?,approved_at=now(),decision_comment=?,updated_at=now(),version=version+1 where id=?",
                    ).use { ps ->
                        ps.setString(1, target.name)
                        ps.setObject(2, actor.id)
                        ps.setString(3, comment?.trim()?.takeIf(String::isNotEmpty))
                        ps.setObject(4, requestId)
                        ps.executeUpdate()
                    }
                audit(c, actor, requestId, "PART_REQUEST_${target.name}", mapOf("state" to target.name))
                outbox(c, "PART_REQUEST_STATE_CHANGED", requestId, request.siteId, target.name)
                val view = loadRequest(c, requestId, actor)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun normalizeLines(lines: List<PartRequestLineCreateDto>): List<RequestLineInput> {
        val keys = mutableSetOf<String>()
        return lines.map { line ->
            require(line.quantity > 0) { "Requested quantity must be positive." }
            val location = line.location.trim().ifEmpty { "MAIN" }
            val partId = uuid(line.partId, "Part")
            val key = "$partId:$location"
            require(keys.add(key)) { "The same part and location cannot appear twice in a request." }
            RequestLineInput(
                partId = partId,
                quantity = line.quantity,
                location = location,
                notes = line.notes?.trim()?.takeIf(String::isNotEmpty),
            )
        }
    }

    private fun quantityMap(
        input: List<PartRequestLineQuantityDto>,
        lines: List<LineRow>,
    ): Map<UUID, Int> {
        if (input.isEmpty()) return emptyMap()
        val known = lines.associateBy { it.id }
        val quantities = mutableMapOf<UUID, Int>()
        input.forEach { item ->
            val lineId = uuid(item.lineId, "Request line")
            require(known.containsKey(lineId)) { "The request line does not belong to this request." }
            require(item.quantity > 0) { "Quantity must be positive." }
            require(quantities.put(lineId, item.quantity) == null) { "A request line cannot appear twice." }
        }
        return quantities
    }

    private fun resolveEquipment(
        c: Connection,
        siteId: UUID,
        equipmentValue: String?,
        breakdownValue: String?,
        maintenanceValue: String?,
    ): UUID? {
        var equipmentId = equipmentValue?.let { uuid(it, "Equipment") }
        val breakdownId = breakdownValue?.let { uuid(it, "Breakdown") }
        if (breakdownId != null) {
            c.prepareStatement("select equipment_id from breakdown where id=?").use { ps ->
                ps.setObject(1, breakdownId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw AuthFailure("BREAKDOWN_NOT_FOUND")
                    val linked = rs.getObject(1, UUID::class.java)
                    if (equipmentId != null && equipmentId != linked) throw AuthFailure("BREAKDOWN_EQUIPMENT_MISMATCH")
                    equipmentId = linked
                }
            }
        }
        val maintenanceId = maintenanceValue?.let { uuid(it, "Maintenance") }
        if (maintenanceId != null) {
            c.prepareStatement("select equipment_id from maintenance_record where id=?").use { ps ->
                ps.setObject(1, maintenanceId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw AuthFailure("MAINTENANCE_NOT_FOUND")
                    val linked = rs.getObject(1, UUID::class.java)
                    if (equipmentId != null && equipmentId != linked) throw AuthFailure("MAINTENANCE_EQUIPMENT_MISMATCH")
                    equipmentId = linked
                }
            }
        }
        if (equipmentId != null) {
            c.prepareStatement("select site_id,lifecycle_state from equipment where id=?").use { ps ->
                ps.setObject(1, equipmentId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw AuthFailure("EQUIPMENT_NOT_FOUND")
                    if (rs.getObject(1, UUID::class.java) != siteId) throw AuthFailure("EQUIPMENT_SITE_MISMATCH")
                    if (rs.getString(2) != "ACTIVE") throw AuthFailure("EQUIPMENT_INACTIVE")
                }
            }
        }
        return equipmentId
    }

    private fun replayRequest(
        c: Connection,
        actor: Principal,
        key: String,
    ): PartRequestViewDto? =
        c.prepareStatement("select id,requested_by from part_request where idempotency_key=?").use { ps ->
            ps.setString(1, key.trim())
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val id = rs.getObject(1, UUID::class.java)
                if (rs.getObject(2, UUID::class.java) != actor.id) throw AuthFailure("IDEMPOTENCY_KEY_REPLAY_FORBIDDEN")
                loadRequest(c, id, actor)
            }
        }

    private fun lockPart(
        c: Connection,
        partId: UUID,
    ): Long =
        c.prepareStatement("select version from part where id=? for update").use { ps ->
            ps.setObject(1, partId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("PART_NOT_FOUND")
                it.getLong(1)
            }
        }

    private fun lockRequest(
        c: Connection,
        requestId: UUID,
    ): RequestRow =
        c
            .prepareStatement("select id,state,site_id,requested_by,version from part_request where id=? for update")
            .use { ps ->
                ps.setObject(1, requestId)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("PART_REQUEST_NOT_FOUND")
                    RequestRow(
                        id = it.getObject("id", UUID::class.java),
                        state = PartRequestState.valueOf(it.getString("state")),
                        siteId = it.getObject("site_id", UUID::class.java),
                        requestedBy = it.getObject("requested_by", UUID::class.java),
                        version = it.getLong("version"),
                    )
                }
            }

    private fun requireVersion(
        request: RequestRow,
        expectedVersion: Long,
    ) {
        if (request.version != expectedVersion) throw AuthFailure("PART_REQUEST_VERSION_CONFLICT")
    }

    private fun requireRequestActor(
        actor: Principal,
        request: RequestRow,
    ) {
        if (request.requestedBy != actor.id && !RolePermissions.allows(actor.role, "PART_ISSUE") && !RolePermissions.allows(actor.role, "PART_APPROVE")) {
            throw AuthFailure("FORBIDDEN")
        }
    }

    private fun requireRequestSite(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ) {
        if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
    }

    private fun loadRequestPart(
        c: Connection,
        partId: UUID,
    ): Long? =
        c.prepareStatement("select active,unit_cost from part where id=? for update").use { ps ->
            ps.setObject(1, partId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("PART_NOT_FOUND")
                if (!it.getBoolean("active")) throw AuthFailure("PART_INACTIVE")
                it.getObject("unit_cost") as Long?
            }
        }

    private fun insertMovement(
        c: Connection,
        id: UUID,
        partId: UUID,
        location: String,
        quantity: Int,
        type: StockMovementType,
        actor: Principal,
        requestId: UUID?,
        requestLineId: UUID?,
        reason: String?,
    ) {
        c
            .prepareStatement(
                """
                insert into stock_movement(id,part_id,location,qty,type,request_id,request_line_id,user_id,occurred_at,reason)
                values (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, partId)
                ps.setString(3, location)
                ps.setInt(4, quantity)
                ps.setString(5, type.name)
                ps.setObject(6, requestId)
                ps.setObject(7, requestLineId)
                ps.setObject(8, actor.id)
                ps.setTimestamp(9, Timestamp.from(now()))
                ps.setString(10, reason?.trim()?.takeIf(String::isNotEmpty))
                ps.executeUpdate()
            }
    }

    private fun loadStockMovement(
        c: Connection,
        id: UUID,
    ): StockMovementViewDto =
        c
            .prepareStatement(
                """
                select sm.*,p.part_number from stock_movement sm join part p on p.id=sm.part_id where sm.id=?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("STOCK_MOVEMENT_NOT_FOUND")
                    it.toStockMovement()
                }
            }

    private fun partNumber(
        c: Connection,
        partId: UUID,
    ): String =
        c.prepareStatement("select part_number from part where id=?").use { ps ->
            ps.setObject(1, partId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("PART_NOT_FOUND")
                it.getString(1)
            }
        }

    private fun loadParts(
        c: Connection,
        actor: Principal,
        ids: List<UUID>,
    ): List<PartViewDto> {
        if (ids.isEmpty()) return emptyList()
        return c.prepareStatement("select p.*,coalesce(s.on_hand,0) on_hand from part p left join (select part_id,sum(on_hand) on_hand from part_stock group by part_id) s on s.part_id=p.id where p.id = any(?) order by p.part_number").use { ps ->
            val array = c.createArrayOf("uuid", ids.toTypedArray())
            ps.setArray(1, array)
            try {
                ps.executeQuery().use { rs -> attachLocations(c, buildList { while (rs.next()) add(rs.toPart(actor)) }) }
            } finally {
                array.free()
            }
        }
    }

    private fun attachLocations(
        c: Connection,
        parts: List<PartViewDto>,
    ): List<PartViewDto> {
        if (parts.isEmpty()) return parts
        val ids = parts.map { UUID.fromString(it.id) }
        val locations = mutableMapOf<UUID, MutableList<PartStockViewDto>>()
        c.prepareStatement("select part_id,location,on_hand from part_stock where part_id = any(?) order by location").use { ps ->
            val array = c.createArrayOf("uuid", ids.toTypedArray())
            ps.setArray(1, array)
            try {
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val partId = rs.getObject(1, UUID::class.java)
                        locations.getOrPut(partId) { mutableListOf() }.add(PartStockViewDto(rs.getString(2), rs.getInt(3)))
                    }
                }
            } finally {
                array.free()
            }
        }
        return parts.map { it.copy(locations = locations[UUID.fromString(it.id)] ?: emptyList()) }
    }

    private fun loadRequest(
        c: Connection,
        requestId: UUID,
        actor: Principal,
    ): PartRequestViewDto = loadRequests(c, listOf(requestId), actor).singleOrNull() ?: throw AuthFailure("PART_REQUEST_NOT_FOUND")

    private fun loadRequests(
        c: Connection,
        ids: List<UUID>,
        actor: Principal,
    ): List<PartRequestViewDto> {
        if (ids.isEmpty()) return emptyList()
        val lines = loadLinesForRequests(c, ids)
        return c
            .prepareStatement(
                """
                select r.*,s.name site_name,e.equipment_code,u.full_name requested_by_name
                from part_request r join site s on s.id=r.site_id
                left join equipment e on e.id=r.equipment_id join app_user u on u.id=r.requested_by
                where r.id = any(?)
                order by r.requested_at desc
                """.trimIndent(),
            ).use { ps ->
                val array = c.createArrayOf("uuid", ids.toTypedArray())
                ps.setArray(1, array)
                try {
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val id = rs.getObject("id", UUID::class.java)
                                add(rs.toRequest(actor, lines[id] ?: emptyList()))
                            }
                        }
                    }
                } finally {
                    array.free()
                }
            }
    }

    private fun loadLines(
        c: Connection,
        requestId: UUID,
    ): List<LineRow> = loadLinesForRequests(c, listOf(requestId))[requestId] ?: emptyList()

    private fun loadLinesForRequests(
        c: Connection,
        ids: List<UUID>,
    ): Map<UUID, List<LineRow>> {
        if (ids.isEmpty()) return emptyMap()
        return c
            .prepareStatement(
                """
                select l.*,p.part_number,p.description,p.unit
                from part_request_line l join part p on p.id=l.part_id
                where l.request_id = any(?) order by p.part_number,l.location
                """.trimIndent(),
            ).use { ps ->
                val array = c.createArrayOf("uuid", ids.toTypedArray())
                ps.setArray(1, array)
                try {
                    ps.executeQuery().use { rs ->
                        val result = mutableMapOf<UUID, MutableList<LineRow>>()
                        while (rs.next()) {
                            val requestId = rs.getObject("request_id", UUID::class.java)
                            result.getOrPut(requestId) { mutableListOf() }.add(rs.toLine())
                        }
                        result
                    }
                } finally {
                    array.free()
                }
            }
    }

    private fun ResultSet.toPart(actor: Principal): PartViewDto {
        val id = getObject("id", UUID::class.java)
        val minStock = getInt("min_stock")
        val onHand = getInt("on_hand")
        return PartViewDto(
            id = id.toString(),
            partNumber = getString("part_number"),
            description = getString("description"),
            category = getString("category"),
            unit = getString("unit"),
            minStock = minStock,
            unitCost = if (canViewCost(actor)) getObject("unit_cost") as Long? else null,
            active = getBoolean("active"),
            onHand = onHand,
            lowStock = onHand < minStock,
            version = getLong("version"),
        )
    }

    private fun ResultSet.toStockMovement() =
        StockMovementViewDto(
            id = getObject("id", UUID::class.java).toString(),
            partId = getObject("part_id", UUID::class.java).toString(),
            partNumber = getString("part_number"),
            location = getString("location"),
            quantity = getInt("qty"),
            type = StockMovementType.valueOf(getString("type")),
            requestId = getObject("request_id", UUID::class.java)?.toString(),
            userId = getObject("user_id", UUID::class.java).toString(),
            occurredAt = getTimestamp("occurred_at").toInstant().toString(),
            reason = getString("reason"),
        )

    private fun ResultSet.toLine() =
        LineRow(
            id = getObject("id", UUID::class.java),
            partId = getObject("part_id", UUID::class.java),
            partNumber = getString("part_number"),
            description = getString("description"),
            unit = getString("unit"),
            location = getString("location"),
            requestedQty = getInt("requested_qty"),
            issuedQty = getInt("issued_qty"),
            usedQty = getInt("used_qty"),
            returnedQty = getInt("returned_qty"),
            unitCost = getObject("unit_cost") as Long?,
            notes = getString("notes"),
        )

    private fun ResultSet.toRequest(
        actor: Principal,
        lines: List<LineRow>,
    ): PartRequestViewDto {
        val showCost = canViewCost(actor)
        val lineViews =
            lines.map {
                val cost = it.unitCost
                PartRequestLineViewDto(
                    id = it.id.toString(),
                    partId = it.partId.toString(),
                    partNumber = it.partNumber,
                    description = it.description,
                    unit = it.unit,
                    location = it.location,
                    requestedQty = it.requestedQty,
                    issuedQty = it.issuedQty,
                    usedQty = it.usedQty,
                    returnedQty = it.returnedQty,
                    unitCost = if (showCost) cost else null,
                    lineCost = if (showCost) cost?.times(it.requestedQty.toLong()) else null,
                    notes = it.notes,
                )
            }
        return PartRequestViewDto(
            id = getObject("id", UUID::class.java).toString(),
            state = PartRequestState.valueOf(getString("state")),
            siteId = getObject("site_id", UUID::class.java).toString(),
            siteName = getString("site_name"),
            equipmentId = getObject("equipment_id", UUID::class.java)?.toString(),
            equipmentCode = getString("equipment_code"),
            breakdownId = getObject("breakdown_id", UUID::class.java)?.toString(),
            maintenanceId = getObject("maintenance_id", UUID::class.java)?.toString(),
            requestedBy = getString("requested_by_name"),
            requestedAt = getTimestamp("requested_at").toInstant().toString(),
            approvedBy = getObject("approved_by", UUID::class.java)?.toString(),
            approvedAt = getTimestamp("approved_at")?.toInstant()?.toString(),
            decisionComment = getString("decision_comment"),
            issuedBy = getObject("issued_by", UUID::class.java)?.toString(),
            issuedAt = getTimestamp("issued_at")?.toInstant()?.toString(),
            usedBy = getObject("used_by", UUID::class.java)?.toString(),
            usedAt = getTimestamp("used_at")?.toInstant()?.toString(),
            returnedBy = getObject("returned_by", UUID::class.java)?.toString(),
            returnedAt = getTimestamp("returned_at")?.toInstant()?.toString(),
            notes = getString("notes"),
            totalCost = if (showCost) lineViews.sumOf { it.lineCost ?: 0L } else null,
            version = getLong("version"),
            lines = lineViews,
        )
    }

    private fun emitLowStock(
        c: Connection,
        partId: UUID,
        partNumber: String,
    ) {
        val stock =
            c.prepareStatement("select p.min_stock,coalesce(sum(s.on_hand),0) from part p left join part_stock s on s.part_id=p.id where p.id=? group by p.id,p.min_stock").use { ps ->
                ps.setObject(1, partId)
                ps.executeQuery().use {
                    if (!it.next()) return
                    it.getInt(1) to it.getInt(2)
                }
            }
        if (stock.second >= stock.first) return
        val dedupeKey = "lowstock:$partId:${now().atZone(ZoneOffset.UTC).toLocalDate()}"
        val exists =
            c.prepareStatement("select exists(select 1 from outbox_message where topic='LOW_STOCK' and payload->>'dedupeKey'=?)").use { ps ->
                ps.setString(1, dedupeKey)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (exists) return
        outbox(
            c,
            "LOW_STOCK",
            mapOf(
                "partId" to partId.toString(),
                "partNumber" to partNumber,
                "onHand" to stock.second.toString(),
                "minStock" to stock.first.toString(),
                "dedupeKey" to dedupeKey,
            ),
        )
    }

    private fun audit(
        c: Connection,
        actor: Principal,
        entityId: UUID,
        action: String,
        metadata: Map<String, String>,
        entityType: String = "part_request",
    ) {
        val json = Json.encodeToString(metadata)
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,?,?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, action)
            ps.setString(3, entityType)
            ps.setString(4, entityId.toString())
            ps.setString(5, json)
            ps.setBytes(6, MessageDigest.getInstance("SHA-256").digest("$entityId:$action:$json".toByteArray()))
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        topic: String,
        payload: Map<String, String>,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,?,?::jsonb,now())").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, topic)
            ps.setString(3, Json.encodeToString(payload))
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        topic: String,
        requestId: UUID,
        siteId: UUID,
        state: String,
    ) {
        outbox(
            c,
            topic,
            mapOf("requestId" to requestId.toString(), "siteId" to siteId.toString(), "state" to state),
        )
    }

    private fun requirePermission(
        actor: Principal,
        permission: String,
    ) {
        if (!RolePermissions.allows(actor.role, permission)) throw AuthFailure("FORBIDDEN")
    }

    private fun requirePartView(actor: Principal) {
        if (!canViewParts(actor)) throw AuthFailure("FORBIDDEN")
    }

    private fun requireRequestView(actor: Principal) {
        if (!canViewParts(actor)) throw AuthFailure("FORBIDDEN")
    }

    private fun canViewParts(actor: Principal) = listOf("PART_REQUEST", "PART_APPROVE", "PART_ISSUE", "PART_MANAGE").any { RolePermissions.allows(actor.role, it) }

    private fun canViewStock(actor: Principal) = listOf("PART_APPROVE", "PART_ISSUE", "PART_MANAGE").any { RolePermissions.allows(actor.role, it) }

    private fun canViewAllRequests(actor: Principal) = listOf("PART_APPROVE", "PART_ISSUE", "PART_MANAGE").any { RolePermissions.allows(actor.role, it) }

    private fun canViewCost(actor: Principal) = RolePermissions.allows(actor.role, "COST_VIEW")

    private fun hasSiteAccess(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ): Boolean =
        c.prepareStatement("select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))").use { ps ->
            ps.setObject(1, actor.id)
            ps.setObject(2, siteId)
            ps.setBoolean(3, actor.role in broadRoles)
            ps.setObject(4, actor.id)
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun siteAccessSql(column: String) = "(exists(select 1 from user_site_access a where a.user_id=? and a.site_id=$column) or (? and not exists(select 1 from user_site_access a where a.user_id=?)))"

    private fun bindSiteAccess(
        ps: java.sql.PreparedStatement,
        actor: Principal,
        first: Int,
    ) {
        ps.setObject(first, actor.id)
        ps.setBoolean(first + 1, actor.role in broadRoles)
        ps.setObject(first + 2, actor.id)
    }

    private fun uuid(
        value: String,
        label: String,
    ): UUID = runCatching { UUID.fromString(value) }.getOrElse { throw IllegalArgumentException("$label ID is invalid.") }
}
