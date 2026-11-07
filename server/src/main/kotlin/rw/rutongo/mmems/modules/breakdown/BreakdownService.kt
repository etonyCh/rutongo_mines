@file:Suppress(
    "TooManyFunctions",
    "LongParameterList",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "MagicNumber",
    "TooGenericExceptionCaught",
)

package rw.rutongo.mmems.modules.breakdown

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.modules.breakdown.domain.BreakdownPolicy
import rw.rutongo.mmems.shared.enums.BreakdownState
import rw.rutongo.mmems.shared.enums.Role
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable data class BreakdownView(
    val id: String,
    val equipmentId: String,
    val equipmentCode: String?,
    val category: String,
    val problem: String,
    val siteId: String,
    val tunnelId: String?,
    val shiftId: String?,
    val reportedBy: String,
    val reportedAt: String,
    val breakdownStart: String,
    val repairStart: String?,
    val repairEnd: String?,
    val state: String,
    val actionTaken: String?,
    val partsRequired: String?,
    val assignedTechnicianId: String?,
    val remarks: String?,
    val defectIds: List<String>,
    val photoIds: List<String>,
    val version: Long,
)

data class BreakdownCreate(
    val idempotencyKey: String,
    val equipmentId: UUID,
    val category: String,
    val problem: String,
    val tunnelId: UUID?,
    val shiftId: UUID?,
    val reportedAt: Instant?,
    val breakdownStart: Instant?,
    val assignedTechnicianId: UUID?,
    val actionTaken: String?,
    val partsRequired: String?,
    val remarks: String?,
    val defectIds: List<UUID>,
)

data class BreakdownTransition(
    val state: BreakdownState,
    val repairStart: Instant?,
    val repairEnd: Instant?,
    val actionTaken: String?,
    val partsRequired: String?,
    val assignedTechnicianId: UUID?,
    val remarks: String?,
)

class BreakdownService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun create(
        actor: Principal,
        input: BreakdownCreate,
    ): BreakdownView {
        if (!RolePermissions.allows(actor.role, "BREAKDOWN_MANAGE")) throw AuthFailure("FORBIDDEN")
        require(input.idempotencyKey.isNotBlank() && input.idempotencyKey.length <= 200) { "A valid Idempotency-Key is required." }
        require(input.category.isNotBlank()) { "A breakdown category is required." }
        require(input.problem.isNotBlank()) { "A breakdown problem is required." }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                replay(c, actor, input.idempotencyKey)?.let {
                    c.rollback()
                    return it
                }
                val equipment = lockEquipment(c, input.equipmentId)
                replay(c, actor, input.idempotencyKey)?.let {
                    c.rollback()
                    return it
                }
                if (!hasSiteAccess(c, actor, equipment.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                if (activeBreakdown(c, equipment.id) != null) throw AuthFailure("BREAKDOWN_ALREADY_ACTIVE")
                val tunnelId = input.tunnelId ?: equipment.tunnelId
                validateTunnel(c, tunnelId, equipment.siteId)
                validateShift(c, input.shiftId)
                validateTechnician(c, input.assignedTechnicianId, equipment.siteId)
                val reportedAt = input.reportedAt ?: now()
                val breakdownStart = input.breakdownStart ?: reportedAt
                BreakdownPolicy.validateTimeline(breakdownStart, null, null)
                val id = UUID.randomUUID()
                insertBreakdown(c, id, actor, equipment, input, tunnelId, reportedAt, breakdownStart)
                linkDefects(c, equipment, id, input.defectIds)
                audit(c, actor, id, "BREAKDOWN_REPORTED", mapOf("state" to BreakdownState.REPORTED.name, "equipmentId" to equipment.id.toString()))
                outbox(c, "BREAKDOWN_REPORTED", id, equipment.siteId, BreakdownState.REPORTED.name)
                val view = loadView(c, id)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun list(
        actor: Principal,
        siteId: UUID? = null,
        equipmentId: UUID? = null,
        state: BreakdownState? = null,
        limit: Int = 100,
    ): List<BreakdownView> {
        if (!RolePermissions.allows(actor.role, "BREAKDOWN_MANAGE")) throw AuthFailure("FORBIDDEN")
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("$VIEW_SQL where (?::uuid is null or b.site_id=?) and (?::uuid is null or b.equipment_id=?) and (?::text is null or b.state=?) and ${siteAccessSql("b.site_id")} order by b.reported_at desc limit ?").use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, siteId)
                ps.setObject(3, equipmentId)
                ps.setObject(4, equipmentId)
                ps.setString(5, state?.name)
                ps.setString(6, state?.name)
                bindSiteAccess(ps, actor, 7)
                ps.setInt(10, limit.coerceIn(1, 200))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toView()) } }
            }
        }
    }

    fun transition(
        actor: Principal,
        breakdownId: UUID,
        expectedVersion: Long,
        input: BreakdownTransition,
    ): BreakdownView {
        if (!RolePermissions.allows(actor.role, "BREAKDOWN_MANAGE")) throw AuthFailure("FORBIDDEN")
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current = lockBreakdown(c, breakdownId)
                if (!hasSiteAccess(c, actor, current.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                if (current.version != expectedVersion) throw AuthFailure("BREAKDOWN_VERSION_CONFLICT")
                val from = BreakdownState.valueOf(current.state)
                if (!BreakdownPolicy.canTransition(from, input.state)) throw AuthFailure("INVALID_BREAKDOWN_TRANSITION")
                val currentRepairStart = current.repairStart
                val repairStart =
                    input.repairStart
                        ?: if (input.state == BreakdownState.UNDER_REPAIR && currentRepairStart == null) now() else currentRepairStart
                val repairEnd =
                    input.repairEnd
                        ?: if (input.state == BreakdownState.RESOLVED && current.repairEnd == null) now() else current.repairEnd
                BreakdownPolicy.validateTimeline(current.breakdownStart, repairStart, repairEnd)
                if (input.state in setOf(BreakdownState.RESOLVED, BreakdownState.CLOSED)) {
                    require(repairEnd != null) { "A repair end is required before resolving a breakdown." }
                }
                validateTechnician(c, input.assignedTechnicianId, current.siteId)
                c
                    .prepareStatement(
                        """
                        update breakdown set state=?,repair_start=?,repair_end=?,action_taken=coalesce(?,action_taken),
                          parts_required=coalesce(?,parts_required),assigned_technician_id=coalesce(?,assigned_technician_id),
                          remarks=coalesce(?,remarks),version=version+1,updated_at=now()
                        where id=? and version=?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, input.state.name)
                        ps.setTimestamp(2, repairStart?.let(Timestamp::from))
                        ps.setTimestamp(3, repairEnd?.let(Timestamp::from))
                        ps.setString(4, input.actionTaken?.trim())
                        ps.setString(5, input.partsRequired?.trim())
                        ps.setObject(6, input.assignedTechnicianId)
                        ps.setString(7, input.remarks?.trim())
                        ps.setObject(8, breakdownId)
                        ps.setLong(9, expectedVersion)
                        if (ps.executeUpdate() != 1) throw AuthFailure("BREAKDOWN_VERSION_CONFLICT")
                    }
                audit(c, actor, breakdownId, "BREAKDOWN_STATE_CHANGED", mapOf("from" to from.name, "to" to input.state.name))
                outbox(c, "BREAKDOWN_STATE_CHANGED", breakdownId, current.siteId, input.state.name)
                val view = loadView(c, breakdownId)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun insertBreakdown(
        c: Connection,
        id: UUID,
        actor: Principal,
        equipment: EquipmentLock,
        input: BreakdownCreate,
        tunnelId: UUID?,
        reportedAt: Instant,
        breakdownStart: Instant,
    ) {
        c
            .prepareStatement(
                """
                insert into breakdown(id,equipment_id,category,problem,site_id,tunnel_id,shift_id,reported_by,reported_at,
                  breakdown_start,state,action_taken,parts_required,assigned_technician_id,remarks,idempotency_key)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, equipment.id)
                ps.setString(3, input.category.trim())
                ps.setString(4, input.problem.trim())
                ps.setObject(5, equipment.siteId)
                ps.setObject(6, tunnelId)
                ps.setObject(7, input.shiftId)
                ps.setObject(8, actor.id)
                ps.setTimestamp(9, Timestamp.from(reportedAt))
                ps.setTimestamp(10, Timestamp.from(breakdownStart))
                ps.setString(11, BreakdownState.REPORTED.name)
                ps.setString(12, input.actionTaken?.trim())
                ps.setString(13, input.partsRequired?.trim())
                ps.setObject(14, input.assignedTechnicianId)
                ps.setString(15, input.remarks?.trim())
                ps.setString(16, input.idempotencyKey)
                ps.executeUpdate()
            }
    }

    private fun linkDefects(
        c: Connection,
        equipment: EquipmentLock,
        breakdownId: UUID,
        defectIds: List<UUID>,
    ) {
        for (defectId in defectIds.distinct()) {
            val defect =
                c.prepareStatement("select equipment_id,breakdown_id,state from defect where id=? for update").use { ps ->
                    ps.setObject(1, defectId)
                    ps.executeQuery().use {
                        if (!it.next()) throw AuthFailure("DEFECT_NOT_FOUND")
                        DefectLock(
                            it.getObject(1, UUID::class.java),
                            it.getObject(2, UUID::class.java),
                            it.getString(3),
                        )
                    }
                }
            if (defect.equipmentId != equipment.id) throw AuthFailure("DEFECT_EQUIPMENT_MISMATCH")
            if (defect.breakdownId != null && defect.breakdownId != breakdownId) throw AuthFailure("DEFECT_ALREADY_LINKED")
            if (defect.state == "CLOSED") throw AuthFailure("DEFECT_CLOSED")
            c.prepareStatement("update defect set breakdown_id=?,version=version+1 where id=?").use { ps ->
                ps.setObject(1, breakdownId)
                ps.setObject(2, defectId)
                ps.executeUpdate()
            }
        }
    }

    private fun replay(
        c: Connection,
        actor: Principal,
        key: String,
    ): BreakdownView? {
        val existing =
            c.prepareStatement("select id,reported_by,site_id from breakdown where idempotency_key=?").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use {
                    if (!it.next()) return null
                    Triple(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java))
                }
            }
        if (existing.second != actor.id || !hasSiteAccess(c, actor, existing.third)) {
            throw AuthFailure("IDEMPOTENCY_KEY_REPLAY_FORBIDDEN")
        }
        return loadView(c, existing.first)
    }

    private fun loadView(
        c: Connection,
        id: UUID,
    ): BreakdownView =
        c.prepareStatement("$VIEW_SQL where b.id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("BREAKDOWN_NOT_FOUND")
                it.toView()
            }
        }

    private fun lockEquipment(
        c: Connection,
        id: UUID,
    ): EquipmentLock =
        c.prepareStatement("select id,site_id,tunnel_id from equipment where id=? and lifecycle_state='ACTIVE' for update").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("EQUIPMENT_NOT_FOUND")
                EquipmentLock(it.getObject(1, UUID::class.java), it.getObject(2, UUID::class.java), it.getObject(3, UUID::class.java))
            }
        }

    private fun lockBreakdown(
        c: Connection,
        id: UUID,
    ): BreakdownLock =
        c.prepareStatement("select id,site_id,state,version,breakdown_start,repair_start,repair_end from breakdown where id=? for update").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("BREAKDOWN_NOT_FOUND")
                BreakdownLock(
                    it.getObject(1, UUID::class.java),
                    it.getObject(2, UUID::class.java),
                    it.getString(3),
                    it.getLong(4),
                    it.getTimestamp(5).toInstant(),
                    it.getTimestamp(6)?.toInstant(),
                    it.getTimestamp(7)?.toInstant(),
                )
            }
        }

    private fun activeBreakdown(
        c: Connection,
        equipmentId: UUID,
    ): UUID? =
        c.prepareStatement("select id from breakdown where equipment_id=? and state not in ('RESOLVED','CLOSED')").use { ps ->
            ps.setObject(1, equipmentId)
            ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
        }

    private fun validateTunnel(
        c: Connection,
        tunnelId: UUID?,
        siteId: UUID,
    ) {
        if (tunnelId == null) return
        val valid =
            c.prepareStatement("select exists(select 1 from tunnel where id=? and site_id=? and state='ACTIVE')").use { ps ->
                ps.setObject(1, tunnelId)
                ps.setObject(2, siteId)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (!valid) throw AuthFailure("TUNNEL_NOT_IN_SITE")
    }

    private fun validateShift(
        c: Connection,
        shiftId: UUID?,
    ) {
        if (shiftId == null) return
        val exists =
            c.prepareStatement("select exists(select 1 from shift where id=?)").use { ps ->
                ps.setObject(1, shiftId)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (!exists) throw AuthFailure("SHIFT_NOT_FOUND")
    }

    private fun validateTechnician(
        c: Connection,
        technicianId: UUID?,
        siteId: UUID,
    ) {
        if (technicianId == null) return
        val valid =
            c.prepareStatement("select exists(select 1 from technician where id=? and active and (site_id is null or site_id=?))").use { ps ->
                ps.setObject(1, technicianId)
                ps.setObject(2, siteId)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (!valid) throw AuthFailure("TECHNICIAN_NOT_FOUND")
    }

    private fun audit(
        c: Connection,
        actor: Principal,
        breakdownId: UUID,
        action: String,
        metadata: Map<String, String>,
    ) {
        val json = metadata.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":\"${it.value}\"" }
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'breakdown',?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, action)
            ps.setString(3, breakdownId.toString())
            ps.setString(4, json)
            ps.setBytes(
                5,
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("$breakdownId:$action:$json".toByteArray()),
            )
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        topic: String,
        breakdownId: UUID,
        siteId: UUID,
        state: String,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,?,?::jsonb,now())").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, topic)
            ps.setString(3, "{\"breakdownId\":\"$breakdownId\",\"siteId\":\"$siteId\",\"state\":\"$state\"}")
            ps.executeUpdate()
        }
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

    private fun ResultSet.toView() =
        BreakdownView(
            getObject("id", UUID::class.java).toString(),
            getObject("equipment_id", UUID::class.java).toString(),
            getString("equipment_code"),
            getString("category"),
            getString("problem"),
            getObject("site_id", UUID::class.java).toString(),
            getObject("tunnel_id", UUID::class.java)?.toString(),
            getObject("shift_id", UUID::class.java)?.toString(),
            getObject("reported_by", UUID::class.java).toString(),
            getTimestamp("reported_at").toInstant().toString(),
            getTimestamp("breakdown_start").toInstant().toString(),
            getTimestamp("repair_start")?.toInstant()?.toString(),
            getTimestamp("repair_end")?.toInstant()?.toString(),
            getString("state"),
            getString("action_taken"),
            getString("parts_required"),
            getObject("assigned_technician_id", UUID::class.java)?.toString(),
            getString("remarks"),
            uuidList("defect_ids"),
            uuidList("photo_ids"),
            getLong("version"),
        )

    private fun ResultSet.uuidList(column: String): List<String> = (getArray(column)?.array as? Array<*>)?.map { it.toString() } ?: emptyList()

    private data class EquipmentLock(
        val id: UUID,
        val siteId: UUID,
        val tunnelId: UUID?,
    )

    private data class BreakdownLock(
        val id: UUID,
        val siteId: UUID,
        val state: String,
        val version: Long,
        val breakdownStart: Instant,
        val repairStart: Instant?,
        val repairEnd: Instant?,
    )

    private data class DefectLock(
        val equipmentId: UUID,
        val breakdownId: UUID?,
        val state: String,
    )

    companion object {
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        private val VIEW_SQL =
            """
            select b.*,e.equipment_code,
              coalesce((select array_agg(d.id order by d.reported_at) from defect d where d.breakdown_id=b.id),array[]::uuid[]) defect_ids,
              coalesce((select array_agg(p.id order by p.uploaded_at) from photo p where p.target_type='BREAKDOWN' and p.target_id=b.id),array[]::uuid[]) photo_ids
            from breakdown b join equipment e on e.id=b.equipment_id
            """.trimIndent()
    }
}
