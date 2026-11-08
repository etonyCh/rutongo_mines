@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "MagicNumber",
    "ReturnCount",
)

package rw.rutongo.mmems.modules.report

import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.api.DailyReportSnapshotDto
import rw.rutongo.mmems.shared.api.ReportBreakdownDto
import rw.rutongo.mmems.shared.api.ReportDefectDto
import rw.rutongo.mmems.shared.api.ReportEquipmentDto
import rw.rutongo.mmems.shared.api.ReportInspectionDto
import rw.rutongo.mmems.shared.api.ReportMaintenanceDto
import rw.rutongo.mmems.shared.api.ReportMissingInspectionDto
import rw.rutongo.mmems.shared.api.ReportPartLineDto
import rw.rutongo.mmems.shared.api.ReportSummaryDto
import rw.rutongo.mmems.shared.enums.EquipmentCondition
import rw.rutongo.mmems.shared.enums.ReportPeriod
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

class ReportSnapshotBuilder(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun build(
        actor: Principal,
        siteId: UUID,
        reportDate: LocalDate,
        period: ReportPeriod,
    ): DailyReportSnapshotDto =
        dataSource.connection.use { connection ->
            val wasReadOnly = connection.isReadOnly
            val wasAutoCommit = connection.autoCommit
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            try {
                val site = loadSite(connection, siteId) ?: throw AuthFailure("SITE_NOT_FOUND")
                val cutoff = now()
                val zone = ZoneId.of(site.timezone)
                val window = periodWindow(connection, site, reportDate, period, zone)
                val activityEnd = if (window.second.isBefore(cutoff)) window.second else cutoff
                val effectiveEnd = if (activityEnd.isBefore(window.first)) window.first else activityEnd
                val shiftCode = if (period == ReportPeriod.FULL_DAY) null else period.name
                val defects = loadDefects(connection, siteId)
                val missing = loadMissingInspections(connection, siteId, reportDate, shiftCode)
                val inspections = loadInspections(connection, siteId, reportDate, shiftCode)
                val breakdowns = loadBreakdowns(connection, siteId, window.first, effectiveEnd, shiftCode, cutoff)
                val maintenance = loadMaintenance(connection, siteId, window.first, effectiveEnd)
                val parts = loadPartLines(connection, siteId, window.first, effectiveEnd)
                val serviceDue = loadServiceDue(connection, siteId)
                val lastInspections = inspections.groupBy { it.equipmentId }.mapValues { it.value.maxBy { row -> row.submittedAt } }
                val openBreakdowns = breakdowns.filter { it.dto.state !in setOf("RESOLVED", "CLOSED") }.groupBy { it.equipmentId }.mapValues { it.value.first().dto }
                val lastMaintenance = maintenance.groupBy { it.equipmentId }.mapValues { it.value.maxBy { row -> row.performedAt } }
                val equipment =
                    loadEquipment(connection, siteId).map { row ->
                        val equipmentDefects = defects[row.id].orEmpty()
                        val equipmentMissing = missing[row.id].orEmpty()
                        val lastInspection = lastInspections[row.id]
                        ReportEquipmentDto(
                            id = row.id.toString(),
                            assetId = row.assetId,
                            equipmentCode = row.equipmentCode,
                            name = row.name,
                            typeName = row.typeName,
                            tunnelName = row.tunnelName,
                            shiftCode = row.shiftCode,
                            operationalStatus = row.operationalStatus,
                            condition = condition(row.operationalStatus, equipmentDefects),
                            defects = equipmentDefects,
                            missingInspections = equipmentMissing,
                            openBreakdown = openBreakdowns[row.id],
                            lastInspectionAt = lastInspection?.submittedAt?.toString(),
                            lastInspectionResult = lastInspection?.dto?.result,
                            lastMaintenanceAt = lastMaintenance[row.id]?.performedAt?.toString(),
                        )
                    }
                val technicians = loadTechnicians(connection, siteId)
                val supervisor = loadSupervisor(connection, actor.id)
                val pendingPartLines = parts.count { it.state in setOf("REQUESTED", "APPROVED", "ISSUED") }
                val summary =
                    ReportSummaryDto(
                        operational = equipment.count { it.condition == EquipmentCondition.OPERATIONAL },
                        underRepair = equipment.count { it.condition == EquipmentCondition.UNDER_REPAIR },
                        down = equipment.count { it.condition == EquipmentCondition.DOWN },
                        operationalWithDefects = equipment.count { it.condition == EquipmentCondition.OPERATIONAL_WITH_DEFECTS },
                        notRecorded = equipment.count { it.condition == EquipmentCondition.NOT_RECORDED },
                        totalEquipment = equipment.size,
                        inspectionsCompleted = inspections.size,
                        inspectionsMissing = missing.values.sumOf { it.size },
                        breakdownsReported = breakdowns.count { it.reportedAt in window.first..effectiveEnd },
                        breakdownsOpen = breakdowns.count { it.dto.state !in setOf("RESOLVED", "CLOSED") },
                        repairsCompleted = breakdowns.count { row -> row.repairEnd?.let { it in window.first..effectiveEnd } == true },
                        repairsInProgress = breakdowns.count { it.dto.state !in setOf("RESOLVED", "CLOSED") },
                        downtimeMinutes = fleetDowntimeMinutes(breakdowns, window.first, effectiveEnd),
                        maintenanceRecorded = maintenance.size,
                        maintenanceDue = serviceDue.first,
                        maintenanceOverdue = serviceDue.second,
                        pendingPartLines = pendingPartLines,
                    )
                val outstanding =
                    buildList {
                        missing.values.flatten().forEach {
                            add("Inspection missing — ${it.equipmentCode} ${it.shiftCode}: expected ${formatWindow(it.windowStart, it.windowEnd, zone)}")
                        }
                        equipment.forEach { item ->
                            item.defects.forEach { add("Defect open — ${item.equipmentCode}: ${it.description} (${it.severity})") }
                            item.openBreakdown?.let { add("Breakdown open — ${item.equipmentCode}: ${it.problem}") }
                        }
                        parts.filter { it.state in setOf("REQUESTED", "APPROVED", "ISSUED") }.forEach {
                            add("Parts pending — ${it.equipmentCode ?: "No equipment"}: ${it.partNumber} x${it.requestedQty - it.issuedQty}")
                        }
                    }
                val snapshot =
                    DailyReportSnapshotDto(
                        siteId = site.id.toString(),
                        siteName = site.name,
                        reportDate = reportDate.toString(),
                        period = period,
                        dataCutoffAt = cutoff.toString(),
                        supervisorName = supervisor?.first,
                        supervisorEid = supervisor?.second,
                        technicians = technicians,
                        summary = summary,
                        equipment = equipment.sortedWith(compareBy<ReportEquipmentDto> { conditionPriority(it.condition) }.thenBy { it.equipmentCode }),
                        inspections = inspections.map { it.dto },
                        breakdowns = breakdowns.map { it.dto },
                        maintenance = maintenance.map { it.dto },
                        partsRequired = parts,
                        outstanding = outstanding.distinct(),
                    )
                connection.rollback()
                snapshot
            } finally {
                connection.isReadOnly = wasReadOnly
                connection.autoCommit = wasAutoCommit
            }
        }

    private fun loadSite(
        c: Connection,
        siteId: UUID,
    ): SiteRow? =
        c.prepareStatement("select id,name,code,timezone from site where id=? and state<>'RETIRED'").use { ps ->
            ps.setObject(1, siteId)
            ps.executeQuery().use { rs ->
                if (rs.next()) SiteRow(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("code"), rs.getString("timezone")) else null
            }
        }

    private fun periodWindow(
        c: Connection,
        site: SiteRow,
        reportDate: LocalDate,
        period: ReportPeriod,
        zone: ZoneId,
    ): Pair<Instant, Instant> {
        val dayStart = reportDate.atStartOfDay(zone).toInstant()
        val dayEnd = reportDate.plusDays(1).atStartOfDay(zone).toInstant()
        if (period == ReportPeriod.FULL_DAY) return dayStart to dayEnd
        val local =
            c
                .prepareStatement(
                    """
                    select ss.start_local,ss.end_local
                    from shift_schedule ss join shift s on s.id=ss.shift_id
                    where s.code=? and (ss.site_id=? or ss.site_id is null)
                      and ss.effective_from<=? and (ss.effective_to is null or ss.effective_to>=?)
                    order by (ss.site_id is not null) desc limit 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, period.name)
                    ps.setObject(2, site.id)
                    ps.setObject(3, reportDate)
                    ps.setObject(4, reportDate)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject("start_local", LocalTime::class.java) to rs.getObject("end_local", LocalTime::class.java) else null
                    }
                }
        if (local == null) return dayStart to dayEnd
        val start = reportDate.atTime(local.first).atZone(zone).toInstant()
        val endDate = if (local.second <= local.first) reportDate.plusDays(1) else reportDate
        return start to endDate.atTime(local.second).atZone(zone).toInstant()
    }

    private fun loadEquipment(
        c: Connection,
        siteId: UUID,
    ): List<EquipmentRow> =
        c
            .prepareStatement(
                """
                select e.id,e.asset_id,e.equipment_code,e.name,et.name type_name,t.name tunnel_name,s.code shift_code,e.current_status
                from equipment e
                join equipment_type et on et.id=e.type_id
                left join tunnel t on t.id=e.tunnel_id
                left join shift s on s.id=e.current_shift_id
                where e.site_id=? and e.lifecycle_state='ACTIVE'
                order by e.equipment_code
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                EquipmentRow(
                                    rs.getObject("id", UUID::class.java),
                                    rs.getString("asset_id"),
                                    rs.getString("equipment_code"),
                                    rs.getString("name"),
                                    rs.getString("type_name"),
                                    rs.getString("tunnel_name"),
                                    rs.getString("shift_code"),
                                    rs.getString("current_status"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadDefects(
        c: Connection,
        siteId: UUID,
    ): Map<UUID, List<ReportDefectDto>> =
        c
            .prepareStatement(
                """
                select d.id,d.equipment_id,d.description,d.severity,d.state,d.reported_at
                from defect d join equipment e on e.id=d.equipment_id
                where e.site_id=? and d.state in ('OPEN','IN_REPAIR')
                order by d.reported_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                rs.getObject("equipment_id", UUID::class.java) to
                                    ReportDefectDto(
                                        rs.getObject("id", UUID::class.java).toString(),
                                        rs.getString("description"),
                                        rs.getString("severity"),
                                        rs.getString("state"),
                                        rs.getTimestamp("reported_at").toInstant().toString(),
                                    ),
                            )
                        }
                    }.groupBy({ it.first }, { it.second })
                }
            }

    private fun loadMissingInspections(
        c: Connection,
        siteId: UUID,
        reportDate: LocalDate,
        shiftCode: String?,
    ): Map<UUID, List<ReportMissingInspectionDto>> =
        c
            .prepareStatement(
                """
                select ie.equipment_id,e.equipment_code,s.code,ie.window_start,ie.window_end
                from inspection_expectation ie
                join equipment e on e.id=ie.equipment_id
                join shift s on s.id=ie.shift_id
                where ie.site_id=? and ie.report_date=? and ie.state='MISSING'
                  and (?::text is null or s.code=?)
                order by e.equipment_code
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, reportDate)
                ps.setString(3, shiftCode)
                ps.setString(4, shiftCode)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                rs.getObject("equipment_id", UUID::class.java) to
                                    ReportMissingInspectionDto(
                                        rs.getObject("equipment_id", UUID::class.java).toString(),
                                        rs.getString("equipment_code"),
                                        rs.getString("code"),
                                        rs.getTimestamp("window_start").toInstant().toString(),
                                        rs.getTimestamp("window_end").toInstant().toString(),
                                    ),
                            )
                        }
                    }.groupBy({ it.first }, { it.second })
                }
            }

    private fun loadInspections(
        c: Connection,
        siteId: UUID,
        reportDate: LocalDate,
        shiftCode: String?,
    ): List<InspectionRow> =
        c
            .prepareStatement(
                """
                select i.id,i.equipment_id,e.equipment_code,s.code shift_code,i.result,u.full_name inspector_name,i.inspector_eid,
                  i.submitted_at,i.engine_hours_start,i.engine_hours_end
                from inspection i
                join equipment e on e.id=i.equipment_id
                join shift s on s.id=i.shift_id
                join app_user u on u.id=i.inspector_id
                where i.site_id=? and i.report_date=? and (?::text is null or s.code=?)
                order by e.equipment_code,i.submitted_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, reportDate)
                ps.setString(3, shiftCode)
                ps.setString(4, shiftCode)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val submittedAt = rs.getTimestamp("submitted_at").toInstant()
                            add(
                                InspectionRow(
                                    rs.getObject("equipment_id", UUID::class.java),
                                    submittedAt,
                                    ReportInspectionDto(
                                        rs.getObject("id", UUID::class.java).toString(),
                                        rs.getObject("equipment_id", UUID::class.java).toString(),
                                        rs.getString("equipment_code"),
                                        rs.getString("shift_code"),
                                        rs.getString("result"),
                                        rs.getString("inspector_name"),
                                        rs.getString("inspector_eid"),
                                        submittedAt.toString(),
                                        rs.getBigDecimal("engine_hours_start")?.toDouble(),
                                        rs.getBigDecimal("engine_hours_end")?.toDouble(),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadBreakdowns(
        c: Connection,
        siteId: UUID,
        start: Instant,
        end: Instant,
        shiftCode: String?,
        cutoff: Instant,
    ): List<BreakdownRow> =
        c
            .prepareStatement(
                """
                select b.id,b.equipment_id,e.equipment_code,b.category,b.problem,b.state,b.reported_at,b.breakdown_start,
                  b.repair_start,b.repair_end,b.action_taken,b.parts_required,t.name technician_name
                from breakdown b
                join equipment e on e.id=b.equipment_id
                left join shift s on s.id=b.shift_id
                left join technician t on t.id=b.assigned_technician_id
                where b.site_id=?
                  and ((b.reported_at>=? and b.reported_at<? and (?::text is null or s.code=?))
                    or (b.repair_end>=? and b.repair_end<?)
                    or (b.reported_at<? and b.state not in ('RESOLVED','CLOSED')))
                order by b.reported_at
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setTimestamp(2, java.sql.Timestamp.from(start))
                ps.setTimestamp(3, java.sql.Timestamp.from(end))
                ps.setString(4, shiftCode)
                ps.setString(5, shiftCode)
                ps.setTimestamp(6, java.sql.Timestamp.from(start))
                ps.setTimestamp(7, java.sql.Timestamp.from(end))
                ps.setTimestamp(8, java.sql.Timestamp.from(cutoff))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val breakdownStart = rs.getTimestamp("breakdown_start").toInstant()
                            val repairEnd = rs.getTimestamp("repair_end")?.toInstant()
                            val downtimeStart = maxOf(breakdownStart, start)
                            val downtimeEnd = minOf(repairEnd ?: cutoff, end)
                            add(
                                BreakdownRow(
                                    rs.getObject("equipment_id", UUID::class.java),
                                    rs.getTimestamp("reported_at").toInstant(),
                                    downtimeStart,
                                    downtimeEnd,
                                    repairEnd,
                                    ReportBreakdownDto(
                                        rs.getObject("id", UUID::class.java).toString(),
                                        rs.getObject("equipment_id", UUID::class.java).toString(),
                                        rs.getString("equipment_code"),
                                        rs.getString("category"),
                                        rs.getString("problem"),
                                        rs.getString("state"),
                                        rs.getTimestamp("reported_at").toInstant().toString(),
                                        breakdownStart.toString(),
                                        rs.getTimestamp("repair_start")?.toInstant()?.toString(),
                                        repairEnd?.toString(),
                                        rs.getString("action_taken"),
                                        rs.getString("parts_required"),
                                        rs.getString("technician_name"),
                                        Duration
                                            .between(downtimeStart, downtimeEnd)
                                            .toMinutes()
                                            .toInt()
                                            .coerceAtLeast(0),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadServiceDue(
        c: Connection,
        siteId: UUID,
    ): Pair<Int, Int> =
        c
            .prepareStatement(
                """
                select si.interval_hours,si.due_soon_threshold_hours,e.engine_hours_current,
                  (select m.engine_hours
                   from maintenance_record m join service_type source on source.id=m.service_type_id
                   where m.equipment_id=e.id and m.verification_status='VERIFIED' and m.engine_hours is not null
                     and (m.service_type_id=si.service_type_id or si.service_type_id=any(source.satisfies_type_ids))
                   order by m.performed_at desc,m.created_at desc limit 1) last_hours
                from service_interval si
                join equipment e on e.lifecycle_state='ACTIVE'
                  and (si.equipment_id=e.id or (si.equipment_id is null and (si.equipment_type_id is null or si.equipment_type_id=e.type_id)))
                where e.site_id=?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.executeQuery().use { rs ->
                    var due = 0
                    var overdue = 0
                    while (rs.next()) {
                        val current = rs.getBigDecimal("engine_hours_current")?.toDouble()
                        val last = rs.getBigDecimal("last_hours")?.toDouble()
                        if (current != null && last != null) {
                            val remaining = last + rs.getInt("interval_hours") - current
                            when {
                                remaining <= 0.0 -> overdue++
                                remaining <= rs.getInt("due_soon_threshold_hours") -> due++
                            }
                        }
                    }
                    due to overdue
                }
            }

    private fun fleetDowntimeMinutes(
        rows: List<BreakdownRow>,
        start: Instant,
        end: Instant,
    ): Int =
        rows
            .groupBy { it.equipmentId }
            .values
            .sumOf { equipmentRows ->
                val intervals =
                    equipmentRows
                        .mapNotNull { row ->
                            val intervalStart = maxOf(row.downtimeStart, start)
                            val intervalEnd = minOf(row.downtimeEnd, end)
                            if (intervalEnd.isAfter(intervalStart)) intervalStart to intervalEnd else null
                        }.sortedBy { it.first }
                var total = 0L
                var currentStart: Instant? = null
                var currentEnd: Instant? = null
                intervals.forEach { (intervalStart, intervalEnd) ->
                    val openStart = currentStart
                    val openEnd = currentEnd
                    if (openStart == null || openEnd == null || intervalStart.isAfter(openEnd)) {
                        if (openStart != null && openEnd != null) total += Duration.between(openStart, openEnd).toMinutes()
                        currentStart = intervalStart
                        currentEnd = intervalEnd
                    } else if (intervalEnd.isAfter(openEnd)) {
                        currentEnd = intervalEnd
                    }
                }
                currentStart?.let { openStart ->
                    currentEnd?.let { openEnd -> total += Duration.between(openStart, openEnd).toMinutes() }
                }
                total
            }.toInt()

    private fun loadMaintenance(
        c: Connection,
        siteId: UUID,
        start: Instant,
        end: Instant,
    ): List<MaintenanceRow> =
        c
            .prepareStatement(
                """
                select m.id,m.equipment_id,e.equipment_code,st.name service_type_name,m.work_performed,m.performed_at,
                  t.name technician_name,m.verification_status,m.engine_hours
                from maintenance_record m
                join equipment e on e.id=m.equipment_id
                join service_type st on st.id=m.service_type_id
                left join technician t on t.id=m.technician_id
                where m.site_id=? and m.performed_at>=? and m.performed_at<?
                order by m.performed_at,e.equipment_code
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setTimestamp(2, java.sql.Timestamp.from(start))
                ps.setTimestamp(3, java.sql.Timestamp.from(end))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val performedAt = rs.getTimestamp("performed_at").toInstant()
                            add(
                                MaintenanceRow(
                                    rs.getObject("equipment_id", UUID::class.java),
                                    performedAt,
                                    ReportMaintenanceDto(
                                        rs.getObject("id", UUID::class.java).toString(),
                                        rs.getObject("equipment_id", UUID::class.java).toString(),
                                        rs.getString("equipment_code"),
                                        rs.getString("service_type_name"),
                                        rs.getString("work_performed"),
                                        performedAt.toString(),
                                        rs.getString("technician_name"),
                                        rs.getString("verification_status"),
                                        rs.getBigDecimal("engine_hours")?.toDouble(),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadPartLines(
        c: Connection,
        siteId: UUID,
        start: Instant,
        end: Instant,
    ): List<ReportPartLineDto> =
        c
            .prepareStatement(
                """
                select pr.id request_id,p.part_number,p.description,p.unit,e.equipment_code,pr.state,pr.requested_at,
                  rl.requested_qty,rl.issued_qty,rl.used_qty,rl.returned_qty
                from part_request_line rl
                join part_request pr on pr.id=rl.request_id
                join part p on p.id=rl.part_id
                left join equipment e on e.id=pr.equipment_id
                where pr.site_id=?
                  and ((pr.requested_at>=? and pr.requested_at<?) or pr.state in ('REQUESTED','APPROVED','ISSUED'))
                order by pr.requested_at,p.part_number
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setTimestamp(2, java.sql.Timestamp.from(start))
                ps.setTimestamp(3, java.sql.Timestamp.from(end))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                ReportPartLineDto(
                                    rs.getObject("request_id", UUID::class.java).toString(),
                                    rs.getString("part_number"),
                                    rs.getString("description"),
                                    rs.getString("unit"),
                                    rs.getString("equipment_code"),
                                    rs.getString("state"),
                                    rs.getInt("requested_qty"),
                                    rs.getInt("issued_qty"),
                                    rs.getInt("used_qty"),
                                    rs.getInt("returned_qty"),
                                    rs.getTimestamp("requested_at").toInstant().toString(),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadTechnicians(
        c: Connection,
        siteId: UUID,
    ): List<String> =
        c.prepareStatement("select name from technician where site_id=? and active order by name").use { ps ->
            ps.setObject(1, siteId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    private fun loadSupervisor(
        c: Connection,
        userId: UUID,
    ): Pair<String, String>? =
        c.prepareStatement("select full_name,employee_id from app_user where id=?").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        }

    private fun condition(
        status: String?,
        defects: List<ReportDefectDto>,
    ): EquipmentCondition =
        when {
            status == "DOWN" -> EquipmentCondition.DOWN
            status == "UNDER_REPAIR" -> EquipmentCondition.UNDER_REPAIR
            status == "OPERATIONAL" && defects.isNotEmpty() -> EquipmentCondition.OPERATIONAL_WITH_DEFECTS
            status == "OPERATIONAL" -> EquipmentCondition.OPERATIONAL
            else -> EquipmentCondition.NOT_RECORDED
        }

    private fun conditionPriority(condition: EquipmentCondition): Int =
        when (condition) {
            EquipmentCondition.DOWN -> 0
            EquipmentCondition.UNDER_REPAIR -> 1
            EquipmentCondition.OPERATIONAL_WITH_DEFECTS -> 2
            EquipmentCondition.OPERATIONAL -> 3
            EquipmentCondition.NOT_RECORDED -> 4
        }

    private fun formatWindow(
        start: String,
        end: String,
        zone: ZoneId,
    ): String {
        val formatter =
            java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm")
        return "${Instant.parse(start).atZone(zone).format(formatter)}–${Instant.parse(end).atZone(zone).format(
            java.time.format.DateTimeFormatter
                .ofPattern("HH:mm"),
        )}"
    }

    private data class SiteRow(
        val id: UUID,
        val name: String,
        val code: String,
        val timezone: String,
    )

    private data class EquipmentRow(
        val id: UUID,
        val assetId: String,
        val equipmentCode: String,
        val name: String,
        val typeName: String,
        val tunnelName: String?,
        val shiftCode: String?,
        val operationalStatus: String?,
    )

    private data class InspectionRow(
        val equipmentId: UUID,
        val submittedAt: Instant,
        val dto: ReportInspectionDto,
    )

    private data class BreakdownRow(
        val equipmentId: UUID,
        val reportedAt: Instant,
        val downtimeStart: Instant,
        val downtimeEnd: Instant,
        val repairEnd: Instant?,
        val dto: ReportBreakdownDto,
    )

    private data class MaintenanceRow(
        val equipmentId: UUID,
        val performedAt: Instant,
        val dto: ReportMaintenanceDto,
    )
}
