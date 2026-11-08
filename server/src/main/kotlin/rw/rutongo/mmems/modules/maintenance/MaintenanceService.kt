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

package rw.rutongo.mmems.modules.maintenance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.modules.maintenance.domain.MaintenancePolicy
import rw.rutongo.mmems.modules.maintenance.domain.serviceDue
import rw.rutongo.mmems.shared.api.MaintenanceViewDto
import rw.rutongo.mmems.shared.api.ServiceScheduleViewDto
import rw.rutongo.mmems.shared.enums.MaintenanceAction
import rw.rutongo.mmems.shared.enums.MaintenanceVerificationStatus
import rw.rutongo.mmems.shared.enums.Role
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

typealias MaintenanceView = MaintenanceViewDto

data class MaintenanceCreate(
    val idempotencyKey: String,
    val equipmentId: UUID,
    val serviceTypeId: UUID,
    val tunnelId: UUID?,
    val performedAt: Instant?,
    val engineHours: Double?,
    val workPerformed: String,
    val cause: String?,
    val technicianId: UUID,
    val supervisorId: UUID?,
    val breakdownId: UUID?,
    val replacedComponents: List<String>,
    val downtimeMinutes: Int?,
    val labourCost: Long,
    val partsCost: Long,
    val currency: String,
    val notes: String?,
)

data class MaintenanceActionInput(
    val comment: String?,
)

class MaintenanceService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun create(
        actor: Principal,
        input: MaintenanceCreate,
    ): MaintenanceView {
        if (!RolePermissions.allows(actor.role, "MAINTENANCE_RECORD")) throw AuthFailure("FORBIDDEN")
        require(input.idempotencyKey.isNotBlank() && input.idempotencyKey.length <= 200) { "A valid Idempotency-Key is required." }
        require(input.workPerformed.isNotBlank()) { "Work performed is required." }
        require(input.engineHours == null || input.engineHours >= 0.0) { "Engine hours cannot be negative." }
        require(input.labourCost >= 0 && input.partsCost >= 0) { "Costs cannot be negative." }
        require(input.downtimeMinutes == null || input.downtimeMinutes >= 0) { "Downtime cannot be negative." }
        require(input.replacedComponents.none { it.isBlank() }) { "Replaced components cannot be blank." }
        val currency = input.currency.trim().uppercase()
        require(currency.matches(Regex("[A-Z]{3}"))) { "Currency must be a three-letter ISO code." }
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
                validateServiceType(c, input.serviceTypeId)
                val tunnelId = input.tunnelId ?: equipment.tunnelId
                validateTunnel(c, tunnelId, equipment.siteId)
                validateTechnician(c, input.technicianId, equipment.siteId)
                validateSupervisor(c, input.supervisorId)
                validateBreakdown(c, input.breakdownId, equipment.id)
                val id = UUID.randomUUID()
                val performedAt = input.performedAt ?: now()
                insert(c, id, actor, equipment, input, tunnelId, performedAt, currency)
                if (input.engineHours != null) {
                    projectEngineHours(c, id, equipment.id, input.engineHours, performedAt)
                }
                audit(c, actor, id, "MAINTENANCE_RECORDED", mapOf("equipmentId" to equipment.id.toString(), "serviceTypeId" to input.serviceTypeId.toString()))
                outbox(c, "MAINTENANCE_RECORDED", id, equipment.siteId, "PENDING_VERIFICATION")
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
        status: MaintenanceVerificationStatus? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 100,
    ): List<MaintenanceView> {
        if (!RolePermissions.allows(actor.role, "MAINTENANCE_RECORD")) throw AuthFailure("FORBIDDEN")
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c
                .prepareStatement(
                    """
                    $VIEW_SQL
                    where (?::uuid is null or m.site_id=?) and (?::uuid is null or m.equipment_id=?)
                      and (?::text is null or m.verification_status=?)
                      and (?::timestamptz is null or m.performed_at>=?)
                      and (?::timestamptz is null or m.performed_at<=?)
                      and ${siteAccessSql("m.site_id")}
                    order by m.performed_at desc,m.created_at desc limit ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    ps.setObject(3, equipmentId)
                    ps.setObject(4, equipmentId)
                    ps.setString(5, status?.name)
                    ps.setString(6, status?.name)
                    ps.setTimestamp(7, from?.let(Timestamp::from))
                    ps.setTimestamp(8, from?.let(Timestamp::from))
                    ps.setTimestamp(9, to?.let(Timestamp::from))
                    ps.setTimestamp(10, to?.let(Timestamp::from))
                    bindSiteAccess(ps, actor, 11)
                    ps.setInt(14, limit.coerceIn(1, 200))
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toView()) } }
                }
        }
    }

    fun verify(
        actor: Principal,
        maintenanceId: UUID,
        expectedVersion: Long,
        input: MaintenanceActionInput,
    ): MaintenanceView = transition(actor, maintenanceId, expectedVersion, MaintenanceAction.VERIFY, input)

    fun reject(
        actor: Principal,
        maintenanceId: UUID,
        expectedVersion: Long,
        input: MaintenanceActionInput,
    ): MaintenanceView = transition(actor, maintenanceId, expectedVersion, MaintenanceAction.REJECT, input)

    fun requestCorrection(
        actor: Principal,
        maintenanceId: UUID,
        expectedVersion: Long,
        input: MaintenanceActionInput,
    ): MaintenanceView = transition(actor, maintenanceId, expectedVersion, MaintenanceAction.REQUEST_CORRECTION, input)

    fun serviceSchedule(
        actor: Principal,
        siteId: UUID? = null,
        equipmentId: UUID? = null,
    ): List<ServiceScheduleViewDto> {
        if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
        return dataSource.connection.use { c ->
            if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            if (equipmentId != null) {
                val site = equipmentSite(c, equipmentId) ?: throw AuthFailure("EQUIPMENT_NOT_FOUND")
                if (!hasSiteAccess(c, actor, site)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            }
            c
                .prepareStatement(
                    """
                    select si.id interval_id,si.interval_hours,si.due_soon_threshold_hours,
                      st.id service_type_id,st.code service_type_code,st.name service_type_name,
                      e.id equipment_id,e.equipment_code,e.name equipment_name,e.site_id,e.engine_hours_current
                    from service_interval si
                    join service_type st on st.id=si.service_type_id
                    join equipment e on e.lifecycle_state='ACTIVE'
                      and (si.equipment_id=e.id or (si.equipment_id is null and (si.equipment_type_id is null or si.equipment_type_id=e.type_id)))
                    where (?::uuid is null or e.site_id=?) and (?::uuid is null or e.id=?)
                      and ${siteAccessSql("e.site_id")}
                    order by e.equipment_code,st.name
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setObject(2, siteId)
                    ps.setObject(3, equipmentId)
                    ps.setObject(4, equipmentId)
                    bindSiteAccess(ps, actor, 5)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val current = rs.getBigDecimal("engine_hours_current")?.toDouble()
                                val targetTypeId = rs.getObject("service_type_id", UUID::class.java)
                                val last = lastVerified(c, rs.getObject("equipment_id", UUID::class.java), targetTypeId)
                                val due = serviceDue(current, last?.first, rs.getInt("interval_hours"), rs.getInt("due_soon_threshold_hours").toDouble())
                                add(
                                    ServiceScheduleViewDto(
                                        intervalId = rs.getObject("interval_id", UUID::class.java).toString(),
                                        equipmentId = rs.getObject("equipment_id", UUID::class.java).toString(),
                                        equipmentCode = rs.getString("equipment_code"),
                                        equipmentName = rs.getString("equipment_name"),
                                        siteId = rs.getObject("site_id", UUID::class.java).toString(),
                                        serviceTypeId = targetTypeId.toString(),
                                        serviceTypeCode = rs.getString("service_type_code"),
                                        serviceTypeName = rs.getString("service_type_name"),
                                        intervalHours = rs.getInt("interval_hours"),
                                        dueSoonThresholdHours = rs.getInt("due_soon_threshold_hours"),
                                        current = due.current,
                                        lastVerified = due.lastVerified,
                                        lastVerifiedAt = last?.second?.toString(),
                                        nextDue = due.nextDue,
                                        remaining = due.remaining,
                                        status = due.status,
                                    ),
                                )
                            }
                        }
                    }
                }
        }
    }

    fun serviceStatus(
        actor: Principal,
        equipmentId: UUID,
    ): List<ServiceScheduleViewDto> = serviceSchedule(actor, equipmentId = equipmentId)

    private fun transition(
        actor: Principal,
        maintenanceId: UUID,
        expectedVersion: Long,
        action: MaintenanceAction,
        input: MaintenanceActionInput,
    ): MaintenanceView {
        if (!RolePermissions.allows(actor.role, "MAINTENANCE_VERIFY")) throw AuthFailure("FORBIDDEN")
        val comment = input.comment?.trim()?.takeIf { it.isNotEmpty() }
        require(comment == null || comment.length <= 2000) { "The verification comment is too long." }
        if (action != MaintenanceAction.VERIFY) require(comment != null) { "A comment is required for this verification decision." }
        val target =
            when (action) {
                MaintenanceAction.VERIFY -> MaintenanceVerificationStatus.VERIFIED
                MaintenanceAction.REJECT -> MaintenanceVerificationStatus.REJECTED
                MaintenanceAction.REQUEST_CORRECTION -> MaintenanceVerificationStatus.CORRECTION_REQUESTED
            }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val current = lock(c, maintenanceId)
                if (!hasSiteAccess(c, actor, current.siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                if (current.version != expectedVersion) throw AuthFailure("MAINTENANCE_VERSION_CONFLICT")
                if (!MaintenancePolicy.canTransition(current.status, target)) throw AuthFailure("INVALID_MAINTENANCE_TRANSITION")
                c
                    .prepareStatement(
                        """
                        update maintenance_record set verification_status=?,verified_by=?,verified_at=?,verification_comment=?,
                          updated_at=now(),version=version+1
                        where id=? and version=?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setString(1, target.name)
                        ps.setObject(2, actor.id)
                        ps.setTimestamp(3, Timestamp.from(now()))
                        ps.setString(4, comment)
                        ps.setObject(5, maintenanceId)
                        ps.setLong(6, expectedVersion)
                        if (ps.executeUpdate() != 1) throw AuthFailure("MAINTENANCE_VERSION_CONFLICT")
                    }
                if (target == MaintenanceVerificationStatus.VERIFIED) {
                    c.prepareStatement("update equipment set last_maintenance_id=?,updated_at=now(),version=version+1 where id=?").use { ps ->
                        ps.setObject(1, maintenanceId)
                        ps.setObject(2, current.equipmentId)
                        ps.executeUpdate()
                    }
                }
                audit(c, actor, maintenanceId, "MAINTENANCE_${action.name}", mapOf("from" to current.status.name, "to" to target.name))
                outbox(c, "MAINTENANCE_VERIFICATION_CHANGED", maintenanceId, current.siteId, target.name)
                val view = loadView(c, maintenanceId)
                c.commit()
                return view
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun insert(
        c: Connection,
        id: UUID,
        actor: Principal,
        equipment: EquipmentLock,
        input: MaintenanceCreate,
        tunnelId: UUID?,
        performedAt: Instant,
        currency: String,
    ) {
        c
            .prepareStatement(
                """
                insert into maintenance_record(id,equipment_id,service_type_id,site_id,tunnel_id,performed_at,engine_hours,
                  work_performed,cause,recorded_by,technician_id,supervisor_id,breakdown_id,replaced_components,
                  verification_status,downtime_minutes,labour_cost,parts_cost,currency,notes,idempotency_key)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'PENDING_VERIFICATION',?,?,?,?,?,?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, equipment.id)
                ps.setObject(3, input.serviceTypeId)
                ps.setObject(4, equipment.siteId)
                ps.setObject(5, tunnelId)
                ps.setTimestamp(6, Timestamp.from(performedAt))
                ps.setObject(7, input.engineHours)
                ps.setString(8, input.workPerformed.trim())
                ps.setString(9, input.cause?.trim())
                ps.setObject(10, actor.id)
                ps.setObject(11, input.technicianId)
                ps.setObject(12, input.supervisorId)
                ps.setObject(13, input.breakdownId)
                ps.setString(14, Json.encodeToString(input.replacedComponents.map(String::trim)))
                ps.setObject(15, input.downtimeMinutes)
                ps.setLong(16, input.labourCost)
                ps.setLong(17, input.partsCost)
                ps.setString(18, currency)
                ps.setString(19, input.notes?.trim())
                ps.setString(20, input.idempotencyKey)
                ps.executeUpdate()
            }
    }

    private fun projectEngineHours(
        c: Connection,
        maintenanceId: UUID,
        equipmentId: UUID,
        engineHours: Double,
        performedAt: Instant,
    ) {
        c
            .prepareStatement(
                "insert into engine_hour_reading(id,equipment_id,reading,read_at,source,source_id,quality) values (?,?,?,?,'MAINTENANCE',?,'VALID')",
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, equipmentId)
                ps.setDouble(3, engineHours)
                ps.setTimestamp(4, Timestamp.from(performedAt))
                ps.setObject(5, maintenanceId)
                ps.executeUpdate()
            }
        c
            .prepareStatement(
                """
                update equipment set engine_hours_current=
                  case when engine_hours_current is null or engine_hours_current < ? then ? else engine_hours_current end,
                  updated_at=now(),version=version+1
                where id=?
                """.trimIndent(),
            ).use { ps ->
                ps.setDouble(1, engineHours)
                ps.setDouble(2, engineHours)
                ps.setObject(3, equipmentId)
                ps.executeUpdate()
            }
    }

    private fun replay(
        c: Connection,
        actor: Principal,
        key: String,
    ): MaintenanceView? {
        val existing =
            c.prepareStatement("select id,recorded_by,site_id from maintenance_record where idempotency_key=?").use { ps ->
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
    ): MaintenanceView =
        c.prepareStatement("$VIEW_SQL where m.id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("MAINTENANCE_NOT_FOUND")
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

    private fun lock(
        c: Connection,
        id: UUID,
    ): MaintenanceLock =
        c.prepareStatement("select id,equipment_id,site_id,verification_status,version from maintenance_record where id=? for update").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use {
                if (!it.next()) throw AuthFailure("MAINTENANCE_NOT_FOUND")
                MaintenanceLock(
                    it.getObject(1, UUID::class.java),
                    it.getObject(2, UUID::class.java),
                    it.getObject(3, UUID::class.java),
                    MaintenanceVerificationStatus.valueOf(it.getString(4)),
                    it.getLong(5),
                )
            }
        }

    private fun equipmentSite(
        c: Connection,
        equipmentId: UUID,
    ): UUID? =
        c.prepareStatement("select site_id from equipment where id=? and lifecycle_state='ACTIVE'").use { ps ->
            ps.setObject(1, equipmentId)
            ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
        }

    private fun lastVerified(
        c: Connection,
        equipmentId: UUID,
        serviceTypeId: UUID,
    ): Pair<Double, Instant>? =
        c
            .prepareStatement(
                """
                select m.engine_hours,m.performed_at
                from maintenance_record m
                join service_type source on source.id=m.service_type_id
                where m.equipment_id=? and m.verification_status='VERIFIED' and m.engine_hours is not null
                  and (m.service_type_id=? or ?::uuid=any(source.satisfies_type_ids))
                order by m.performed_at desc,m.created_at desc limit 1
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, equipmentId)
                ps.setObject(2, serviceTypeId)
                ps.setObject(3, serviceTypeId)
                ps.executeQuery().use {
                    if (it.next()) it.getBigDecimal(1).toDouble() to it.getTimestamp(2).toInstant() else null
                }
            }

    private fun validateServiceType(
        c: Connection,
        id: UUID,
    ) {
        val exists =
            c.prepareStatement("select exists(select 1 from service_type where id=?)").use { ps ->
                ps.setObject(1, id)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (!exists) throw AuthFailure("SERVICE_TYPE_NOT_FOUND")
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

    private fun validateTechnician(
        c: Connection,
        technicianId: UUID,
        siteId: UUID,
    ) {
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

    private fun validateSupervisor(
        c: Connection,
        supervisorId: UUID?,
    ) {
        if (supervisorId == null) return
        val valid =
            c.prepareStatement("select exists(select 1 from app_user where id=? and active and role='SUPERVISOR')").use { ps ->
                ps.setObject(1, supervisorId)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (!valid) throw AuthFailure("SUPERVISOR_NOT_FOUND")
    }

    private fun validateBreakdown(
        c: Connection,
        breakdownId: UUID?,
        equipmentId: UUID,
    ) {
        if (breakdownId == null) return
        val linkedEquipment =
            c.prepareStatement("select equipment_id from breakdown where id=?").use { ps ->
                ps.setObject(1, breakdownId)
                ps.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
        if (linkedEquipment == null) throw AuthFailure("BREAKDOWN_NOT_FOUND")
        if (linkedEquipment != equipmentId) throw AuthFailure("BREAKDOWN_EQUIPMENT_MISMATCH")
    }

    private fun audit(
        c: Connection,
        actor: Principal,
        maintenanceId: UUID,
        action: String,
        metadata: Map<String, String>,
    ) {
        val json = Json.encodeToString(metadata)
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'maintenance',?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor.id)
            ps.setString(2, action)
            ps.setString(3, maintenanceId.toString())
            ps.setString(4, json)
            ps.setBytes(5, MessageDigest.getInstance("SHA-256").digest("$maintenanceId:$action:$json".toByteArray()))
            ps.executeUpdate()
        }
    }

    private fun outbox(
        c: Connection,
        topic: String,
        maintenanceId: UUID,
        siteId: UUID,
        state: String,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,?,?::jsonb,now())").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, topic)
            ps.setString(3, Json.encodeToString(mapOf("maintenanceId" to maintenanceId.toString(), "siteId" to siteId.toString(), "state" to state)))
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
        MaintenanceView(
            id = getObject("id", UUID::class.java).toString(),
            equipmentId = getObject("equipment_id", UUID::class.java).toString(),
            equipmentCode = getString("equipment_code"),
            equipmentName = getString("equipment_name"),
            serviceTypeId = getObject("service_type_id", UUID::class.java).toString(),
            serviceTypeCode = getString("service_type_code"),
            serviceTypeName = getString("service_type_name"),
            siteId = getObject("site_id", UUID::class.java).toString(),
            siteName = getString("site_name"),
            tunnelId = getObject("tunnel_id", UUID::class.java)?.toString(),
            performedAt = getTimestamp("performed_at").toInstant().toString(),
            engineHours = getBigDecimal("engine_hours")?.toDouble(),
            workPerformed = getString("work_performed"),
            cause = getString("cause"),
            technicianId = getObject("technician_id", UUID::class.java).toString(),
            technicianName = getString("technician_name"),
            supervisorId = getObject("supervisor_id", UUID::class.java)?.toString(),
            breakdownId = getObject("breakdown_id", UUID::class.java)?.toString(),
            replacedComponents = Json.decodeFromString<List<String>>(getString("replaced_components")),
            downtimeMinutes = (getObject("downtime_minutes") as? Number)?.toInt(),
            labourCost = getLong("labour_cost"),
            partsCost = getLong("parts_cost"),
            totalCost = getLong("total_cost"),
            currency = getString("currency"),
            verificationStatus = MaintenanceVerificationStatus.valueOf(getString("verification_status")),
            verifiedBy = getObject("verified_by", UUID::class.java)?.toString(),
            verifiedAt = getTimestamp("verified_at")?.toInstant()?.toString(),
            verificationComment = getString("verification_comment"),
            notes = getString("notes"),
            version = getLong("version"),
        )

    private data class EquipmentLock(
        val id: UUID,
        val siteId: UUID,
        val tunnelId: UUID?,
    )

    private data class MaintenanceLock(
        val id: UUID,
        val equipmentId: UUID,
        val siteId: UUID,
        val status: MaintenanceVerificationStatus,
        val version: Long,
    )

    companion object {
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        private val VIEW_SQL =
            """
            select m.*,e.equipment_code,e.name equipment_name,st.code service_type_code,st.name service_type_name,
              s.name site_name,t.name technician_name
            from maintenance_record m
            join equipment e on e.id=m.equipment_id
            join service_type st on st.id=m.service_type_id
            join site s on s.id=m.site_id
            join technician t on t.id=m.technician_id
            """.trimIndent()
    }
}
