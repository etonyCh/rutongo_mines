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

package rw.rutongo.mmems.modules.personnel

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.api.AssignmentCreateDto
import rw.rutongo.mmems.shared.api.AssignmentViewDto
import rw.rutongo.mmems.shared.api.OperatorCreateDto
import rw.rutongo.mmems.shared.api.OperatorUpdateDto
import rw.rutongo.mmems.shared.api.OperatorViewDto
import rw.rutongo.mmems.shared.api.PersonnelTransferCreateDto
import rw.rutongo.mmems.shared.api.PersonnelTransferViewDto
import rw.rutongo.mmems.shared.api.TechnicianCreateDto
import rw.rutongo.mmems.shared.api.TechnicianUpdateDto
import rw.rutongo.mmems.shared.api.TechnicianViewDto
import rw.rutongo.mmems.shared.enums.PersonnelType
import rw.rutongo.mmems.shared.enums.Role
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)

private data class PersonnelLocation(
    val siteId: UUID?,
    val tunnelId: UUID?,
    val shiftId: UUID?,
)

private data class PersonRow(
    val versions: Long,
    val location: PersonnelLocation,
)

class PersonnelService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun listTechnicians(
        actor: Principal,
        siteId: UUID? = null,
        activeOnly: Boolean = false,
        query: String? = null,
        limit: Int = 100,
    ): List<TechnicianViewDto> {
        requirePermission(actor, "PERSONNEL_VIEW")
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c
                .prepareStatement(
                    """
                    select t.*,s.name site_name,tu.name tunnel_name,sh.code shift_code
                    from technician t left join site s on s.id=t.site_id
                    left join tunnel tu on tu.id=t.tunnel_id left join shift sh on sh.id=t.shift_id
                    where (?::uuid is null or t.site_id=?) and (? or t.active)
                      and (?::text is null or t.name ilike ? or t.eid ilike ?)
                      and ((? and t.site_id is null) or ${siteAccessSql("t.site_id")})
                    order by t.name limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    ps.setBoolean(3, !activeOnly)
                    val term = query?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
                    ps.setString(4, term)
                    ps.setString(5, term)
                    ps.setString(6, term)
                    ps.setBoolean(7, actor.role in broadRoles)
                    bindSiteAccess(ps, actor, 8)
                    ps.setInt(11, limit.coerceIn(1, 500))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTechnician()) } }
                }
        }
    }

    fun createTechnician(
        actor: Principal,
        input: TechnicianCreateDto,
    ): TechnicianViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val eid = input.eid.trim()
        val name = input.name.trim()
        require(eid.isNotEmpty() && eid.length <= 100) { "Employee ID is required." }
        require(name.isNotEmpty()) { "Name is required." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val location = validateLocation(c, input.siteId?.let { uuid(it, "Site") }, input.tunnelId?.let { uuid(it, "Tunnel") }, input.shiftId?.let { uuid(it, "Shift") })
                val id = UUID.randomUUID()
                try {
                    c
                        .prepareStatement(
                            "insert into technician(id,eid,name,function,phone,site_id,tunnel_id,shift_id,active) values (?,?,?,?,?,?,?,?,?)",
                        ).use { ps ->
                            ps.setObject(1, id)
                            ps.setString(2, eid)
                            ps.setString(3, name)
                            ps.setString(4, input.function?.trim()?.takeIf(String::isNotEmpty))
                            ps.setString(5, input.phone?.trim()?.takeIf(String::isNotEmpty))
                            ps.setObject(6, location.siteId)
                            ps.setObject(7, location.tunnelId)
                            ps.setObject(8, location.shiftId)
                            ps.setBoolean(9, input.active)
                            ps.executeUpdate()
                        }
                } catch (e: SQLException) {
                    if (e.sqlState == "23505") throw AuthFailure("TECHNICIAN_EXISTS")
                    throw e
                }
                audit(c, actor, id, "TECHNICIAN_CREATED", mapOf("eid" to eid), "technician")
                val view = loadTechnician(c, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun updateTechnician(
        actor: Principal,
        technicianId: UUID,
        expectedVersion: Long,
        input: TechnicianUpdateDto,
    ): TechnicianViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val name = input.name.trim()
        require(name.isNotEmpty()) { "Name is required." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                lockPerson(c, "technician", technicianId, expectedVersion, "TECHNICIAN_VERSION_CONFLICT")
                val location = validateLocation(c, input.siteId?.let { uuid(it, "Site") }, input.tunnelId?.let { uuid(it, "Tunnel") }, input.shiftId?.let { uuid(it, "Shift") })
                c
                    .prepareStatement(
                        """
                        update technician set name=?,function=?,phone=?,site_id=?,tunnel_id=?,shift_id=?,active=?,
                          updated_at=now(),version=version+1 where id=?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, name)
                        ps.setString(2, input.function?.trim()?.takeIf(String::isNotEmpty))
                        ps.setString(3, input.phone?.trim()?.takeIf(String::isNotEmpty))
                        ps.setObject(4, location.siteId)
                        ps.setObject(5, location.tunnelId)
                        ps.setObject(6, location.shiftId)
                        ps.setBoolean(7, input.active)
                        ps.setObject(8, technicianId)
                        ps.executeUpdate()
                    }
                audit(c, actor, technicianId, "TECHNICIAN_UPDATED", mapOf("version" to (expectedVersion + 1).toString()), "technician")
                val view = loadTechnician(c, technicianId)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun listOperators(
        actor: Principal,
        siteId: UUID? = null,
        activeOnly: Boolean = false,
        query: String? = null,
        limit: Int = 100,
    ): List<OperatorViewDto> {
        requirePermission(actor, "PERSONNEL_VIEW")
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c
                .prepareStatement(
                    """
                    select o.*,s.name site_name,tu.name tunnel_name,sh.code shift_code
                    from operator o left join site s on s.id=o.site_id
                    left join tunnel tu on tu.id=o.tunnel_id left join shift sh on sh.id=o.shift_id
                    where (?::uuid is null or o.site_id=?) and (? or o.active)
                      and (?::text is null or o.name ilike ? or o.employee_id ilike ?)
                      and ((? and o.site_id is null) or ${siteAccessSql("o.site_id")})
                    order by o.name limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    ps.setBoolean(3, !activeOnly)
                    val term = query?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
                    ps.setString(4, term)
                    ps.setString(5, term)
                    ps.setString(6, term)
                    ps.setBoolean(7, actor.role in broadRoles)
                    bindSiteAccess(ps, actor, 8)
                    ps.setInt(11, limit.coerceIn(1, 500))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toOperator()) } }
                }
        }
    }

    fun createOperator(
        actor: Principal,
        input: OperatorCreateDto,
    ): OperatorViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val employeeId = input.employeeId.trim()
        val name = input.name.trim()
        require(employeeId.isNotEmpty() && employeeId.length <= 100) { "Employee ID is required." }
        require(name.isNotEmpty()) { "Name is required." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val location = validateLocation(c, input.siteId?.let { uuid(it, "Site") }, input.tunnelId?.let { uuid(it, "Tunnel") }, input.shiftId?.let { uuid(it, "Shift") })
                val id = UUID.randomUUID()
                try {
                    c
                        .prepareStatement(
                            "insert into operator(id,employee_id,name,site_id,tunnel_id,shift_id,active) values (?,?,?,?,?,?,?)",
                        ).use { ps ->
                            ps.setObject(1, id)
                            ps.setString(2, employeeId)
                            ps.setString(3, name)
                            ps.setObject(4, location.siteId)
                            ps.setObject(5, location.tunnelId)
                            ps.setObject(6, location.shiftId)
                            ps.setBoolean(7, input.active)
                            ps.executeUpdate()
                        }
                } catch (e: SQLException) {
                    if (e.sqlState == "23505") throw AuthFailure("OPERATOR_EXISTS")
                    throw e
                }
                audit(c, actor, id, "OPERATOR_CREATED", mapOf("employeeId" to employeeId), "operator")
                val view = loadOperator(c, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun updateOperator(
        actor: Principal,
        operatorId: UUID,
        expectedVersion: Long,
        input: OperatorUpdateDto,
    ): OperatorViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val name = input.name.trim()
        require(name.isNotEmpty()) { "Name is required." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                lockPerson(c, "operator", operatorId, expectedVersion, "OPERATOR_VERSION_CONFLICT")
                val location = validateLocation(c, input.siteId?.let { uuid(it, "Site") }, input.tunnelId?.let { uuid(it, "Tunnel") }, input.shiftId?.let { uuid(it, "Shift") })
                c
                    .prepareStatement(
                        "update operator set name=?,site_id=?,tunnel_id=?,shift_id=?,active=?,updated_at=now(),version=version+1 where id=?",
                    ).use { ps ->
                        ps.setString(1, name)
                        ps.setObject(2, location.siteId)
                        ps.setObject(3, location.tunnelId)
                        ps.setObject(4, location.shiftId)
                        ps.setBoolean(5, input.active)
                        ps.setObject(6, operatorId)
                        ps.executeUpdate()
                    }
                audit(c, actor, operatorId, "OPERATOR_UPDATED", mapOf("version" to (expectedVersion + 1).toString()), "operator")
                val view = loadOperator(c, operatorId)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun createAssignment(
        actor: Principal,
        input: AssignmentCreateDto,
    ): AssignmentViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val personId = uuid(input.personId, "Personnel")
        val equipmentId = uuid(input.equipmentId, "Equipment")
        val assignedFrom = input.assignedFrom?.let { parseInstant(it, "Assignment timestamp") } ?: now()
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val siteId = equipmentSite(c, equipmentId)
                if (!hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                requireActivePerson(c, input.personType, personId)
                val id = UUID.randomUUID()
                when (input.personType) {
                    PersonnelType.OPERATOR -> {
                        closeOpenOperatorAssignments(c, equipmentId, assignedFrom)
                        c
                            .prepareStatement(
                                "insert into operator_equipment(id,operator_id,equipment_id,assigned_from,assigned_by,reason) values (?,?,?,?,?,?)",
                            ).use { ps ->
                                ps.setObject(1, id)
                                ps.setObject(2, personId)
                                ps.setObject(3, equipmentId)
                                ps.setTimestamp(4, Timestamp.from(assignedFrom))
                                ps.setObject(5, actor.id)
                                ps.setString(6, input.reason?.trim()?.takeIf(String::isNotEmpty))
                                ps.executeUpdate()
                            }
                        c.prepareStatement("update equipment set assigned_operator_id=?,updated_at=now(),version=version+1 where id=?").use { ps ->
                            ps.setObject(1, personId)
                            ps.setObject(2, equipmentId)
                            ps.executeUpdate()
                        }
                    }
                    PersonnelType.TECHNICIAN -> {
                        closeOpenTechnicianAssignments(c, equipmentId, assignedFrom)
                        c
                            .prepareStatement(
                                "insert into technician_equipment(id,technician_id,equipment_id,assigned_from,assigned_by,reason) values (?,?,?,?,?,?)",
                            ).use { ps ->
                                ps.setObject(1, id)
                                ps.setObject(2, personId)
                                ps.setObject(3, equipmentId)
                                ps.setTimestamp(4, Timestamp.from(assignedFrom))
                                ps.setObject(5, actor.id)
                                ps.setString(6, input.reason?.trim()?.takeIf(String::isNotEmpty))
                                ps.executeUpdate()
                            }
                        c.prepareStatement("update equipment set assigned_technician_id=?,updated_at=now(),version=version+1 where id=?").use { ps ->
                            ps.setObject(1, personId)
                            ps.setObject(2, equipmentId)
                            ps.executeUpdate()
                        }
                    }
                }
                audit(
                    c,
                    actor,
                    id,
                    "PERSONNEL_ASSIGNED",
                    mapOf("personType" to input.personType.name, "personId" to personId.toString(), "equipmentId" to equipmentId.toString()),
                    "personnel_assignment",
                )
                outbox(
                    c,
                    "PERSONNEL_ASSIGNED",
                    mapOf("personType" to input.personType.name, "personId" to personId.toString(), "equipmentId" to equipmentId.toString()),
                )
                val view = loadAssignment(c, input.personType, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun listAssignments(
        actor: Principal,
        personType: PersonnelType? = null,
        personId: UUID? = null,
        equipmentId: UUID? = null,
        openOnly: Boolean = false,
        limit: Int = 100,
    ): List<AssignmentViewDto> {
        requirePermission(actor, "PERSONNEL_VIEW")
        return dataSource.connection.use { c ->
            when (personType) {
                PersonnelType.OPERATOR -> loadAssignments(c, actor, PersonnelType.OPERATOR, personId, equipmentId, openOnly, limit)
                PersonnelType.TECHNICIAN -> loadAssignments(c, actor, PersonnelType.TECHNICIAN, personId, equipmentId, openOnly, limit)
                null -> {
                    val all =
                        loadAssignments(c, actor, PersonnelType.OPERATOR, personId, equipmentId, openOnly, limit) +
                            loadAssignments(c, actor, PersonnelType.TECHNICIAN, personId, equipmentId, openOnly, limit)
                    all.sortedByDescending { it.assignedFrom }.take(limit.coerceIn(1, 500))
                }
            }
        }
    }

    fun createTransfer(
        actor: Principal,
        input: PersonnelTransferCreateDto,
    ): PersonnelTransferViewDto {
        requirePermission(actor, "PERSONNEL_MANAGE")
        val personId = uuid(input.personId, "Personnel")
        val reason = input.reason.trim()
        require(reason.isNotEmpty() && reason.length <= 500) { "A transfer reason is required." }
        val targetSiteId = uuid(input.toSiteId, "Site")
        val targetTunnelId = input.toTunnelId?.let { uuid(it, "Tunnel") }
        val targetShiftId = input.toShiftId?.let { uuid(it, "Shift") }
        val effectiveAt = input.effectiveAt?.let { parseInstant(it, "Transfer timestamp") } ?: now()
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val target = validateLocation(c, targetSiteId, targetTunnelId, targetShiftId)
                val current = lockPerson(c, personTable(input.personType), personId, null, "PERSON_NOT_FOUND")
                val id = UUID.randomUUID()
                c
                    .prepareStatement(
                        """
                        insert into personnel_transfer(id,person_type,person_id,from_site_id,from_tunnel_id,from_shift_id,
                          to_site_id,to_tunnel_id,to_shift_id,transferred_at,transferred_by,reason)
                        values (?,?,?,?,?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, id)
                        ps.setString(2, input.personType.name)
                        ps.setObject(3, personId)
                        ps.setObject(4, current.location.siteId)
                        ps.setObject(5, current.location.tunnelId)
                        ps.setObject(6, current.location.shiftId)
                        ps.setObject(7, targetSiteId)
                        ps.setObject(8, target.tunnelId)
                        ps.setObject(9, target.shiftId)
                        ps.setTimestamp(10, Timestamp.from(effectiveAt))
                        ps.setObject(11, actor.id)
                        ps.setString(12, reason)
                        ps.executeUpdate()
                    }
                c
                    .prepareStatement(
                        "update ${personTable(input.personType)} set site_id=?,tunnel_id=?,shift_id=?,updated_at=now(),version=version+1 where id=?",
                    ).use { ps ->
                        ps.setObject(1, targetSiteId)
                        ps.setObject(2, target.tunnelId)
                        ps.setObject(3, target.shiftId)
                        ps.setObject(4, personId)
                        ps.executeUpdate()
                    }
                if (current.location.siteId != targetSiteId) {
                    closeAssignmentsForTransfer(c, input.personType, personId, effectiveAt)
                }
                audit(
                    c,
                    actor,
                    id,
                    "PERSONNEL_TRANSFERRED",
                    mapOf("personType" to input.personType.name, "personId" to personId.toString(), "toSiteId" to targetSiteId.toString()),
                    "personnel_transfer",
                )
                outbox(
                    c,
                    "PERSONNEL_TRANSFERRED",
                    mapOf("personType" to input.personType.name, "personId" to personId.toString(), "siteId" to targetSiteId.toString()),
                )
                val view = loadTransfer(c, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun listTransfers(
        actor: Principal,
        personType: PersonnelType? = null,
        personId: UUID? = null,
        limit: Int = 100,
    ): List<PersonnelTransferViewDto> {
        requirePermission(actor, "PERSONNEL_VIEW")
        return dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select pt.* from personnel_transfer pt
                    where (?::text is null or pt.person_type=?) and (?::uuid is null or pt.person_id=?)
                      and ${siteAccessSql("pt.to_site_id")}
                    order by pt.transferred_at desc limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, personType?.name)
                    ps.setString(2, personType?.name)
                    ps.setObject(3, personId)
                    ps.setObject(4, personId)
                    bindSiteAccess(ps, actor, 5)
                    ps.setInt(8, limit.coerceIn(1, 500))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toTransfer()) } }
                }
        }
    }

    private fun validateLocation(
        c: Connection,
        siteId: UUID?,
        tunnelId: UUID?,
        shiftId: UUID?,
    ): PersonnelLocation {
        var resolvedSite = siteId
        if (resolvedSite != null) {
            c.prepareStatement("select exists(select 1 from site where id=?)").use { ps ->
                ps.setObject(1, resolvedSite)
                ps.executeQuery().use {
                    it.next()
                    if (!it.getBoolean(1)) throw AuthFailure("SITE_NOT_FOUND")
                }
            }
        }
        if (tunnelId != null) {
            c.prepareStatement("select site_id from tunnel where id=?").use { ps ->
                ps.setObject(1, tunnelId)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("TUNNEL_NOT_FOUND")
                    val tunnelSite = it.getObject(1, UUID::class.java)
                    if (resolvedSite == null) {
                        resolvedSite = tunnelSite
                    } else if (resolvedSite != tunnelSite) {
                        throw AuthFailure("TUNNEL_NOT_IN_SITE")
                    }
                }
            }
        }
        if (shiftId != null) {
            c.prepareStatement("select exists(select 1 from shift where id=?)").use { ps ->
                ps.setObject(1, shiftId)
                ps.executeQuery().use {
                    it.next()
                    if (!it.getBoolean(1)) throw AuthFailure("SHIFT_NOT_FOUND")
                }
            }
        }
        return PersonnelLocation(resolvedSite, tunnelId, shiftId)
    }

    private fun lockPerson(
        c: Connection,
        table: String,
        personId: UUID,
        expectedVersion: Long?,
        conflictCode: String,
    ): PersonRow =
        c.prepareStatement("select version,site_id,tunnel_id,shift_id from $table where id=? for update").use { ps ->
            ps.setObject(1, personId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure(if (conflictCode == "PERSON_NOT_FOUND") "PERSON_NOT_FOUND" else conflictCode.replace("_VERSION_CONFLICT", "_NOT_FOUND"))
                val version = it.getLong("version")
                if (expectedVersion != null && version != expectedVersion) throw AuthFailure(conflictCode)
                PersonRow(
                    versions = version,
                    location =
                        PersonnelLocation(
                            it.getObject("site_id", UUID::class.java),
                            it.getObject("tunnel_id", UUID::class.java),
                            it.getObject("shift_id", UUID::class.java),
                        ),
                )
            }
        }

    private fun requireActivePerson(
        c: Connection,
        type: PersonnelType,
        personId: UUID,
    ) {
        c.prepareStatement("select active from ${personTable(type)} where id=?").use { ps ->
            ps.setObject(1, personId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("PERSON_NOT_FOUND")
                if (!it.getBoolean(1)) throw AuthFailure("PERSON_NOT_ACTIVE")
            }
        }
    }

    private fun equipmentSite(
        c: Connection,
        equipmentId: UUID,
    ): UUID =
        c.prepareStatement("select site_id,lifecycle_state from equipment where id=?").use { ps ->
            ps.setObject(1, equipmentId)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("EQUIPMENT_NOT_FOUND")
                if (it.getString("lifecycle_state") != "ACTIVE") throw AuthFailure("EQUIPMENT_INACTIVE")
                it.getObject("site_id", UUID::class.java)
            }
        }

    private fun closeOpenOperatorAssignments(
        c: Connection,
        equipmentId: UUID,
        assignedFrom: Instant,
    ) {
        c.prepareStatement("update operator_equipment set assigned_to=greatest(?,assigned_from) where equipment_id=? and assigned_to is null").use { ps ->
            ps.setTimestamp(1, Timestamp.from(assignedFrom))
            ps.setObject(2, equipmentId)
            ps.executeUpdate()
        }
    }

    private fun closeOpenTechnicianAssignments(
        c: Connection,
        equipmentId: UUID,
        assignedFrom: Instant,
    ) {
        c.prepareStatement("update technician_equipment set assigned_to=greatest(?,assigned_from) where equipment_id=? and assigned_to is null").use { ps ->
            ps.setTimestamp(1, Timestamp.from(assignedFrom))
            ps.setObject(2, equipmentId)
            ps.executeUpdate()
        }
    }

    private fun closeAssignmentsForTransfer(
        c: Connection,
        type: PersonnelType,
        personId: UUID,
        effectiveAt: Instant,
    ) {
        when (type) {
            PersonnelType.OPERATOR -> {
                c.prepareStatement("update operator_equipment set assigned_to=greatest(?,assigned_from) where operator_id=? and assigned_to is null").use { ps ->
                    ps.setTimestamp(1, Timestamp.from(effectiveAt))
                    ps.setObject(2, personId)
                    ps.executeUpdate()
                }
                c.prepareStatement("update equipment set assigned_operator_id=null,updated_at=now(),version=version+1 where assigned_operator_id=?").use { ps ->
                    ps.setObject(1, personId)
                    ps.executeUpdate()
                }
            }
            PersonnelType.TECHNICIAN -> {
                c.prepareStatement("update technician_equipment set assigned_to=greatest(?,assigned_from) where technician_id=? and assigned_to is null").use { ps ->
                    ps.setTimestamp(1, Timestamp.from(effectiveAt))
                    ps.setObject(2, personId)
                    ps.executeUpdate()
                }
                c.prepareStatement("update equipment set assigned_technician_id=null,updated_at=now(),version=version+1 where assigned_technician_id=?").use { ps ->
                    ps.setObject(1, personId)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun loadAssignments(
        c: Connection,
        actor: Principal,
        type: PersonnelType,
        personId: UUID?,
        equipmentId: UUID?,
        openOnly: Boolean,
        limit: Int,
    ): List<AssignmentViewDto> {
        val table = if (type == PersonnelType.OPERATOR) "operator_equipment" else "technician_equipment"
        val personColumn = if (type == PersonnelType.OPERATOR) "operator_id" else "technician_id"
        return c
            .prepareStatement(
                """
                select a.*,e.site_id from $table a join equipment e on e.id=a.equipment_id
                where (?::uuid is null or a.$personColumn=?) and (?::uuid is null or a.equipment_id=?)
                  and (not ? or a.assigned_to is null)
                  and ${siteAccessSql("e.site_id")}
                order by a.assigned_from desc limit ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, personId)
                ps.setObject(2, personId)
                ps.setObject(3, equipmentId)
                ps.setObject(4, equipmentId)
                ps.setBoolean(5, openOnly)
                bindSiteAccess(ps, actor, 6)
                ps.setInt(9, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toAssignment(type)) } }
            }
    }

    private fun loadAssignment(
        c: Connection,
        type: PersonnelType,
        id: UUID,
    ): AssignmentViewDto {
        val table = if (type == PersonnelType.OPERATOR) "operator_equipment" else "technician_equipment"
        return c.prepareStatement("select * from $table where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("ASSIGNMENT_NOT_FOUND")
                it.toAssignment(type)
            }
        }
    }

    private fun loadTechnician(
        c: Connection,
        id: UUID,
    ): TechnicianViewDto =
        c
            .prepareStatement(
                """
                select t.*,s.name site_name,tu.name tunnel_name,sh.code shift_code
                from technician t left join site s on s.id=t.site_id
                left join tunnel tu on tu.id=t.tunnel_id left join shift sh on sh.id=t.shift_id where t.id=?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("TECHNICIAN_NOT_FOUND")
                    it.toTechnician()
                }
            }

    private fun loadOperator(
        c: Connection,
        id: UUID,
    ): OperatorViewDto =
        c
            .prepareStatement(
                """
                select o.*,s.name site_name,tu.name tunnel_name,sh.code shift_code
                from operator o left join site s on s.id=o.site_id
                left join tunnel tu on tu.id=o.tunnel_id left join shift sh on sh.id=o.shift_id where o.id=?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    if (!it.next()) throw AuthFailure("OPERATOR_NOT_FOUND")
                    it.toOperator()
                }
            }

    private fun loadTransfer(
        c: Connection,
        id: UUID,
    ): PersonnelTransferViewDto =
        c.prepareStatement("select * from personnel_transfer where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("TRANSFER_NOT_FOUND")
                it.toTransfer()
            }
        }

    private fun ResultSet.toTechnician() =
        TechnicianViewDto(
            id = getObject("id", UUID::class.java).toString(),
            eid = getString("eid"),
            name = getString("name"),
            function = getString("function"),
            phone = getString("phone"),
            siteId = getObject("site_id", UUID::class.java)?.toString(),
            siteName = getString("site_name"),
            tunnelId = getObject("tunnel_id", UUID::class.java)?.toString(),
            tunnelName = getString("tunnel_name"),
            shiftId = getObject("shift_id", UUID::class.java)?.toString(),
            shiftCode = getString("shift_code"),
            active = getBoolean("active"),
            version = getLong("version"),
        )

    private fun ResultSet.toOperator() =
        OperatorViewDto(
            id = getObject("id", UUID::class.java).toString(),
            employeeId = getString("employee_id"),
            name = getString("name"),
            siteId = getObject("site_id", UUID::class.java)?.toString(),
            siteName = getString("site_name"),
            tunnelId = getObject("tunnel_id", UUID::class.java)?.toString(),
            tunnelName = getString("tunnel_name"),
            shiftId = getObject("shift_id", UUID::class.java)?.toString(),
            shiftCode = getString("shift_code"),
            active = getBoolean("active"),
            version = getLong("version"),
        )

    private fun ResultSet.toAssignment(type: PersonnelType) =
        AssignmentViewDto(
            id = getObject("id", UUID::class.java).toString(),
            personType = type,
            personId = getObject(if (type == PersonnelType.OPERATOR) "operator_id" else "technician_id", UUID::class.java).toString(),
            equipmentId = getObject("equipment_id", UUID::class.java).toString(),
            assignedFrom = getTimestamp("assigned_from").toInstant().toString(),
            assignedTo = getTimestamp("assigned_to")?.toInstant()?.toString(),
            assignedBy = getObject("assigned_by", UUID::class.java).toString(),
            reason = getString("reason"),
        )

    private fun ResultSet.toTransfer() =
        PersonnelTransferViewDto(
            id = getObject("id", UUID::class.java).toString(),
            personType = PersonnelType.valueOf(getString("person_type")),
            personId = getObject("person_id", UUID::class.java).toString(),
            fromSiteId = getObject("from_site_id", UUID::class.java)?.toString(),
            fromTunnelId = getObject("from_tunnel_id", UUID::class.java)?.toString(),
            fromShiftId = getObject("from_shift_id", UUID::class.java)?.toString(),
            toSiteId = getObject("to_site_id", UUID::class.java).toString(),
            toTunnelId = getObject("to_tunnel_id", UUID::class.java)?.toString(),
            toShiftId = getObject("to_shift_id", UUID::class.java)?.toString(),
            transferredAt = getTimestamp("transferred_at").toInstant().toString(),
            transferredBy = getObject("transferred_by", UUID::class.java).toString(),
            reason = getString("reason"),
        )

    private fun personTable(type: PersonnelType) = if (type == PersonnelType.OPERATOR) "operator" else "technician"

    private fun audit(
        c: Connection,
        actor: Principal,
        entityId: UUID,
        action: String,
        metadata: Map<String, String>,
        entityType: String,
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

    private fun requirePermission(
        actor: Principal,
        permission: String,
    ) {
        if (!RolePermissions.allows(actor.role, permission)) throw AuthFailure("FORBIDDEN")
    }

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

    private fun parseInstant(
        value: String,
        label: String,
    ): Instant = runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("$label is invalid.") }
}
