package rw.rutongo.mmems.modules.status

import kotlinx.serialization.Serializable
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.modules.status.domain.StatusChangeInput
import rw.rutongo.mmems.modules.status.domain.StatusPolicy
import rw.rutongo.mmems.shared.enums.OperationalStatus
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Serializable data class StatusEventView(
    val id: String,
    val equipmentId: String,
    val sequence: Long,
    val previousStatus: String?,
    val newStatus: String,
    val problem: String?,
    val actionTaken: String?,
    val partsRequired: String?,
    val remarks: String?,
    val siteId: String,
    val tunnelId: String?,
    val shiftId: String?,
    val userId: String,
    val userEid: String,
    val occurredAt: String,
    val recordedAt: String,
    val source: String,
    val overrideReason: String?,
)

@Serializable data class StatusResult(
    val event: StatusEventView,
    val equipmentVersion: Long,
    val projected: Boolean,
)

class StatusService(
    private val dataSource: DataSource,
) {
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "ThrowsCount", "MagicNumber")
    fun record(
        actor: Principal,
        equipmentId: UUID,
        expectedVersion: Long,
        idempotencyKey: String,
        newStatus: OperationalStatus,
        problem: String?,
        actionTaken: String?,
        partsRequired: String?,
        remarks: String?,
        tunnelId: UUID?,
        shiftId: UUID?,
        occurredAt: Instant,
        overrideReason: String? = null,
    ): StatusResult {
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 200) { "A valid Idempotency-Key is required" }
        val errors = StatusPolicy.validate(StatusChangeInput(newStatus, problem, actionTaken))
        require(errors.isEmpty()) { errors.joinToString(" ") { it.message } }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                replay(c, actor, idempotencyKey)?.let {
                    c.rollback()
                    return it
                }
                val equipment =
                    c.prepareStatement("select e.site_id,e.tunnel_id,e.current_status,e.version,e.current_status_event_id,ev.source current_source,u.employee_id from equipment e left join equipment_status_event ev on ev.id=e.current_status_event_id join app_user u on u.id=? where e.id=? for update of e").use { ps ->
                        ps.setObject(1, actor.id)
                        ps.setObject(2, equipmentId)
                        ps.executeQuery().use { rs ->
                            if (!rs.next()) throw AuthFailure("EQUIPMENT_NOT_FOUND")
                            EquipmentLock(
                                rs.getObject(1, UUID::class.java),
                                rs.getObject(2, UUID::class.java),
                                rs.getString(3),
                                rs.getLong(4),
                                rs.getObject(5, UUID::class.java),
                                rs.getString(6),
                                rs.getString(7),
                            )
                        }
                    }
                replay(c, actor, idempotencyKey)?.let {
                    c.rollback()
                    return it
                }
                val overridingSystemRule = equipment.source == "SYSTEM_RULE" && newStatus.name != equipment.status
                if (overridingSystemRule) {
                    if (!RolePermissions.allows(actor.role, "STATUS_OVERRIDE")) throw AuthFailure("STATUS_OVERRIDE_REQUIRED")
                    require(!overrideReason.isNullOrBlank()) { "An override reason is required to contradict an automatic status rule." }
                } else if (!RolePermissions.allows(actor.role, "STATUS_UPDATE")) {
                    throw AuthFailure("FORBIDDEN")
                }
                if (!hasSiteAccess(c, actor, equipment.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                if (equipment.version != expectedVersion) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT")
                val effectiveTunnel = tunnelId ?: equipment.tunnelId
                if (effectiveTunnel != null && !exists(c, "select exists(select 1 from tunnel where id=? and site_id=? and state='ACTIVE')", effectiveTunnel, equipment.siteId)) throw AuthFailure("TUNNEL_NOT_IN_SITE")
                if (shiftId != null && !exists(c, "select exists(select 1 from shift where id=?)", shiftId)) throw AuthFailure("SHIFT_NOT_FOUND")
                val seq =
                    c.prepareStatement("select coalesce(max(seq),0)+1 from equipment_status_event where equipment_id=?").use { ps ->
                        ps.setObject(1, equipmentId)
                        ps.executeQuery().use {
                            it.next()
                            it.getLong(1)
                        }
                    }
                val id = UUID.randomUUID()
                c.prepareStatement("""insert into equipment_status_event(id,equipment_id,seq,previous_status,new_status,problem,action_taken,parts_required,remarks,site_id,tunnel_id,shift_id,user_id,user_eid,occurred_at,recorded_at,source,override_reason,idempotency_key) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp(),'MANUAL',?,?)""").use { ps ->
                    ps.setObject(1, id)
                    ps.setObject(2, equipmentId)
                    ps.setLong(3, seq)
                    ps.setString(4, equipment.status)
                    ps.setString(5, newStatus.name)
                    ps.setString(6, problem?.trim())
                    ps.setString(7, actionTaken?.trim())
                    ps.setString(8, partsRequired?.trim())
                    ps.setString(9, remarks?.trim())
                    ps.setObject(10, equipment.siteId)
                    ps.setObject(11, effectiveTunnel)
                    ps.setObject(12, shiftId)
                    ps.setObject(13, actor.id)
                    ps.setString(14, equipment.employeeId)
                    ps.setTimestamp(15, Timestamp.from(occurredAt))
                    ps.setString(16, overrideReason?.trim())
                    ps.setString(17, idempotencyKey)
                    ps.executeUpdate()
                }
                val latest =
                    c.prepareStatement("select id,new_status,occurred_at from equipment_status_event where equipment_id=? order by occurred_at desc,seq desc limit 1").use { ps ->
                        ps.setObject(1, equipmentId)
                        ps.executeQuery().use { rs ->
                            rs.next()
                            Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant())
                        }
                    }
                val projected = latest.first == id
                val nextVersion = if (projected) equipment.version + 1 else equipment.version
                if (projected) {
                    c.prepareStatement("update equipment set current_status=?,current_status_event_id=?,current_problem=?,current_shift_id=?,last_status_update_at=?,last_status_reported_by=?,version=version+1,updated_at=now() where id=? and version=?").use { ps ->
                        ps.setString(1, latest.second)
                        ps.setObject(2, latest.first)
                        ps.setString(3, problem?.trim())
                        ps.setObject(4, shiftId)
                        ps.setTimestamp(5, Timestamp.from(latest.third))
                        ps.setObject(6, actor.id)
                        ps.setObject(7, equipmentId)
                        ps.setLong(8, expectedVersion)
                        if (ps.executeUpdate() != 1) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT")
                    }
                }
                c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,'EQUIPMENT_STATUS_CHANGED','equipment',?,now(),?::jsonb,?)").use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setString(2, equipmentId.toString())
                    ps.setString(3, "{\"status\":\"${newStatus.name}\",\"eventId\":\"$id\",\"override\":$overridingSystemRule}")
                    ps.setBytes(
                        4,
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest("$id:$newStatus".toByteArray()),
                    )
                    ps.executeUpdate()
                }
                c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?, 'EQUIPMENT_STATUS_CHANGED', ?::jsonb, now())").use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, "{\"eventId\":\"$id\",\"equipmentId\":\"$equipmentId\",\"siteId\":\"${equipment.siteId}\",\"status\":\"${newStatus.name}\"}")
                    ps.executeUpdate()
                }
                val view = loadEvent(c, id)
                c.commit()
                return StatusResult(view, nextVersion, projected)
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun history(
        actor: Principal,
        equipmentId: UUID,
        limit: Int = 100,
    ): List<StatusEventView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            if (!hasSiteAccess(c, actor, siteForEquipment(c, equipmentId))) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("""select ev.* from equipment_status_event ev where ev.equipment_id=? and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=ev.site_id) or (? and not exists(select 1 from user_site_access a where a.user_id=?))) order by ev.occurred_at desc,ev.seq desc limit ?""").use { ps ->
                ps.setObject(1, equipmentId)
                ps.setObject(2, actor.id)
                ps.setBoolean(3, actor.role in broadRoles)
                ps.setObject(4, actor.id)
                ps.setInt(5, limit.coerceIn(1, 200))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toView()) } }
            }
        }

    fun activeProblems(
        actor: Principal,
        siteId: UUID? = null,
        limit: Int = 100,
    ): List<StatusEventView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            val sql = """select ev.* from equipment e join equipment_status_event ev on ev.id=e.current_status_event_id where e.lifecycle_state='ACTIVE' and e.current_status in ('DOWN','UNDER_REPAIR') and (?::uuid is null or e.site_id=?) and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=e.site_id) or (? and not exists(select 1 from user_site_access a where a.user_id=?))) order by ev.occurred_at desc limit ?"""
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, siteId)
                ps.setObject(3, actor.id)
                ps.setBoolean(4, actor.role in broadRoles)
                ps.setObject(5, actor.id)
                ps.setInt(6, limit.coerceIn(1, 200))
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toView()) } }
            }
        }

    fun eventsAfter(
        actor: Principal,
        after: Instant?,
        afterId: UUID? = null,
    ): List<StatusEventView> =
        dataSource.connection.use { c ->
            if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
            c.prepareStatement("select ev.* from equipment_status_event ev where (?::timestamptz is null or ev.recorded_at>? or (ev.recorded_at=? and ev.id>?)) and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=ev.site_id) or (? and not exists(select 1 from user_site_access a where a.user_id=?))) order by ev.recorded_at,ev.id limit 100").use { ps ->
                ps.setTimestamp(1, after?.let(Timestamp::from))
                ps.setTimestamp(2, after?.let(Timestamp::from))
                ps.setTimestamp(3, after?.let(Timestamp::from))
                ps.setObject(4, afterId ?: UUID(0, 0))
                ps.setObject(5, actor.id)
                ps.setBoolean(6, actor.role in broadRoles)
                ps.setObject(7, actor.id)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toView()) } }
            }
        }

    private fun replay(
        c: Connection,
        actor: Principal,
        key: String,
    ): StatusResult? =
        findByKey(c, key)?.also {
            if (!RolePermissions.allows(actor.role, "STATUS_UPDATE") || it.event.userId != actor.id.toString() || !hasSiteAccess(c, actor, UUID.fromString(it.event.siteId))) throw AuthFailure("IDEMPOTENCY_KEY_REPLAY_FORBIDDEN")
        }

    private fun findByKey(
        c: Connection,
        key: String,
    ): StatusResult? =
        c.prepareStatement("select * from equipment_status_event where idempotency_key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val ev = rs.toView()
                    val version =
                        c.prepareStatement("select version from equipment where id=?").use { p ->
                            p.setObject(1, UUID.fromString(ev.equipmentId))
                            p.executeQuery().use {
                                it.next()
                                it.getLong(1)
                            }
                        }
                    StatusResult(ev, version, false)
                }
            }
        }

    private fun loadEvent(
        c: Connection,
        id: UUID,
    ) = c.prepareStatement("select * from equipment_status_event where id=?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use {
            it.next()
            it.toView()
        }
    }

    private fun siteForEquipment(
        c: Connection,
        id: UUID,
    ) = c.prepareStatement("select site_id from equipment where id=?").use { ps ->
        ps.setObject(1, id)
        ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else throw AuthFailure("EQUIPMENT_NOT_FOUND") }
    }

    private fun hasSiteAccess(
        c: Connection,
        a: Principal,
        s: UUID,
    ) = exists(c, "select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))", a.id, s, a.role in broadRoles, a.id)

    private fun exists(
        c: Connection,
        sql: String,
        vararg args: Any?,
    ): Boolean =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun java.sql.ResultSet.toView() = StatusEventView(getObject("id", UUID::class.java).toString(), getObject("equipment_id", UUID::class.java).toString(), getLong("seq"), getString("previous_status"), getString("new_status"), getString("problem"), getString("action_taken"), getString("parts_required"), getString("remarks"), getObject("site_id", UUID::class.java).toString(), getObject("tunnel_id", UUID::class.java)?.toString(), getObject("shift_id", UUID::class.java)?.toString(), getObject("user_id", UUID::class.java).toString(), getString("user_eid"), getTimestamp("occurred_at").toInstant().toString(), getTimestamp("recorded_at").toInstant().toString(), getString("source"), getString("override_reason"))

    private data class EquipmentLock(
        val siteId: UUID,
        val tunnelId: UUID?,
        val status: String?,
        val version: Long,
        val statusEventId: UUID?,
        val source: String?,
        val employeeId: String,
    )

    companion object {
        private val broadRoles = setOf(rw.rutongo.mmems.shared.enums.Role.MANAGER, rw.rutongo.mmems.shared.enums.Role.GENERAL_MANAGER, rw.rutongo.mmems.shared.enums.Role.ADMINISTRATOR)
    }
}
