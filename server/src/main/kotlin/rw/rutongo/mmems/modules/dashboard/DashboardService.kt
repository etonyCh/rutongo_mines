@file:Suppress("TooManyFunctions", "LongMethod", "LargeClass", "LongParameterList", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "MagicNumber")

package rw.rutongo.mmems.modules.dashboard

import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.api.DashboardBreakdownMetricsDto
import rw.rutongo.mmems.shared.api.DashboardCostMetricsDto
import rw.rutongo.mmems.shared.api.DashboardDto
import rw.rutongo.mmems.shared.api.DashboardEquipmentMetricsDto
import rw.rutongo.mmems.shared.api.DashboardInspectionMetricsDto
import rw.rutongo.mmems.shared.api.DashboardMaintenanceMetricsDto
import rw.rutongo.mmems.shared.api.DashboardPartsMetricsDto
import rw.rutongo.mmems.shared.api.DashboardReportMetricsDto
import rw.rutongo.mmems.shared.api.DashboardSiteMetricsDto
import rw.rutongo.mmems.shared.enums.DashboardKind
import rw.rutongo.mmems.shared.enums.Role
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

class DashboardService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun get(
        actor: Principal,
        kind: DashboardKind,
        siteId: UUID?,
        from: Instant,
        to: Instant,
    ): DashboardDto {
        requireAllowed(actor, kind)
        require(!to.isBefore(from)) { "The dashboard end must not be before its start." }
        return dataSource.connection.use { c ->
            val wasReadOnly = c.isReadOnly
            val wasAutoCommit = c.autoCommit
            c.autoCommit = false
            c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            c.isReadOnly = true
            try {
                val sites = sites(c, actor, siteId)
                if (siteId != null && sites.isEmpty()) throw AuthFailure("SITE_NOT_AUTHORIZED")
                val perSite =
                    sites.map { site ->
                        val metrics = siteMetrics(c, site, from, to)
                        DashboardSiteMetricsDto(
                            siteId = site.id.toString(),
                            siteCode = site.code,
                            siteName = site.name,
                            equipment = metrics.equipment,
                            inspections = metrics.inspections,
                            breakdowns = metrics.breakdowns,
                            maintenance = metrics.maintenance,
                            parts = metrics.parts,
                            reports = metrics.reports,
                            costs = if (RolePermissions.allows(actor.role, "COST_VIEW")) metrics.costs else null,
                        )
                    }
                c.rollback()
                aggregate(kind, actor, siteId, sites, perSite, from, to)
            } finally {
                c.isReadOnly = wasReadOnly
                c.autoCommit = wasAutoCommit
            }
        }
    }

    private fun aggregate(
        kind: DashboardKind,
        actor: Principal,
        siteId: UUID?,
        sites: List<SiteRow>,
        perSite: List<DashboardSiteMetricsDto>,
        from: Instant,
        to: Instant,
    ): DashboardDto {
        val selected = sites.singleOrNull { it.id == siteId }
        val equipment = perSite.fold(DashboardEquipmentMetricsDto()) { acc, site -> acc.plus(site.equipment) }
        val inspections = perSite.fold(DashboardInspectionMetricsDto()) { acc, site -> acc.plus(site.inspections) }
        val breakdowns = perSite.fold(DashboardBreakdownMetricsDto()) { acc, site -> acc.plus(site.breakdowns) }
        val maintenance = perSite.fold(DashboardMaintenanceMetricsDto()) { acc, site -> acc.plus(site.maintenance) }
        val parts =
            perSite
                .fold(DashboardPartsMetricsDto(lowStockParts = perSite.firstOrNull()?.parts?.lowStockParts ?: 0)) { acc, site ->
                    acc.copy(openRequests = acc.openRequests + site.parts.openRequests, awaitingApproval = acc.awaitingApproval + site.parts.awaitingApproval)
                }
        val reports = perSite.fold(DashboardReportMetricsDto()) { acc, site -> acc.plus(site.reports) }
        val costs =
            if (RolePermissions.allows(actor.role, "COST_VIEW")) {
                perSite
                    .mapNotNull { it.costs }
                    .fold(DashboardCostMetricsDto()) { acc, cost ->
                        acc.copy(
                            labourCost = acc.labourCost + cost.labourCost,
                            partsCost = acc.partsCost + cost.partsCost,
                            totalCost = acc.totalCost + cost.totalCost,
                            currency = cost.currency,
                        )
                    }
            } else {
                null
            }
        return DashboardDto(
            kind = kind,
            role = actor.role,
            siteId = siteId?.toString(),
            siteCode = selected?.code,
            siteName = selected?.name,
            from = from.toString(),
            to = to.toString(),
            generatedAt = now().toString(),
            equipment = equipment,
            inspections = inspections,
            breakdowns = breakdowns,
            maintenance = maintenance,
            parts = parts,
            reports = reports,
            costs = costs,
            sites = perSite,
        )
    }

    private fun siteMetrics(
        c: Connection,
        site: SiteRow,
        from: Instant,
        to: Instant,
    ): SiteMetrics {
        val zone = ZoneId.of(site.timezone)
        val fromDate = from.atZone(zone).toLocalDate()
        val toDate = to.atZone(zone).toLocalDate()
        return SiteMetrics(
            equipment = equipment(c, site.id),
            inspections = inspections(c, site.id, fromDate, toDate),
            breakdowns = breakdowns(c, site.id, from, to),
            maintenance = maintenance(c, site.id, from, to),
            parts = parts(c, site.id),
            reports = reports(c, site.id, fromDate, toDate),
            costs = costs(c, site.id, from, to),
        )
    }

    private fun equipment(
        c: Connection,
        siteId: UUID,
    ): DashboardEquipmentMetricsDto {
        var total = 0
        var operational = 0
        var withDefects = 0
        var underRepair = 0
        var down = 0
        var notRecorded = 0
        c
            .prepareStatement(
                """
                select e.current_status,
                  exists(select 1 from defect d where d.equipment_id=e.id and d.state in ('OPEN','IN_REPAIR')) has_defect
                from equipment e
                where e.site_id=? and e.lifecycle_state='ACTIVE'
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        total++
                        when (rs.getString("current_status")) {
                            "DOWN" -> down++
                            "UNDER_REPAIR" -> underRepair++
                            "OPERATIONAL" -> if (rs.getBoolean("has_defect")) withDefects++ else operational++
                            else -> notRecorded++
                        }
                    }
                }
            }
        return DashboardEquipmentMetricsDto(total, operational, withDefects, underRepair, down, notRecorded)
    }

    private fun inspections(
        c: Connection,
        siteId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): DashboardInspectionMetricsDto {
        var expected = 0
        var completed = 0
        var withDefects = 0
        var critical = 0
        var missing = 0
        c.prepareStatement("select state,count(*) from inspection_expectation where site_id=? and report_date between ? and ? group by state").use { ps ->
            ps.setObject(1, siteId)
            ps.setObject(2, from)
            ps.setObject(3, to)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val count = rs.getInt(2)
                    expected += count
                    when (rs.getString(1)) {
                        "COMPLETED" -> completed += count
                        "COMPLETED_WITH_DEFECTS" -> {
                            completed += count
                            withDefects += count
                        }
                        "CRITICAL_FAILURE" -> {
                            completed += count
                            critical += count
                        }
                        "MISSING" -> missing += count
                    }
                }
            }
        }
        return DashboardInspectionMetricsDto(
            expected,
            completed,
            withDefects,
            critical,
            missing,
            if (expected == 0) 0.0 else completed * 100.0 / expected,
        )
    }

    private fun breakdowns(
        c: Connection,
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): DashboardBreakdownMetricsDto {
        val rows =
            c
                .prepareStatement(
                    """
                    select id,equipment_id,reported_at,breakdown_start,repair_start,repair_end
                    from breakdown
                    where site_id=? and breakdown_start<? and (repair_end is null or repair_end>?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, siteId)
                    ps.setTimestamp(2, java.sql.Timestamp.from(to))
                    ps.setTimestamp(3, java.sql.Timestamp.from(from))
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    BreakdownRow(
                                        rs.getObject("equipment_id", UUID::class.java),
                                        rs.getTimestamp("reported_at").toInstant(),
                                        rs.getTimestamp("breakdown_start").toInstant(),
                                        rs.getTimestamp("repair_start")?.toInstant(),
                                        rs.getTimestamp("repair_end")?.toInstant(),
                                    ),
                                )
                            }
                        }
                    }
                }
        val reported = rows.count { it.reportedAt in from..to }
        val completedRows = rows.filter { it.repairEnd != null && it.repairEnd in from..to }
        val open = rows.count { it.breakdownStart <= to && (it.repairEnd == null || it.repairEnd > to) }
        val totalRepairMinutes =
            completedRows.sumOf { row ->
                val start = row.repairStart ?: row.breakdownStart
                val end = row.repairEnd ?: return@sumOf 0L
                maxOf(0, Duration.between(start, end).toMinutes())
            }
        return DashboardBreakdownMetricsDto(
            reported = reported,
            completed = completedRows.size,
            open = open,
            downtimeMinutes = downtime(rows, from, to),
            averageRepairMinutes = if (completedRows.isEmpty()) null else totalRepairMinutes.toDouble() / completedRows.size,
        )
    }

    private fun downtime(
        rows: List<BreakdownRow>,
        from: Instant,
        to: Instant,
    ): Int =
        rows
            .mapNotNull { row ->
                val start = if (row.breakdownStart.isAfter(from)) row.breakdownStart else from
                val end = row.repairEnd?.let { if (it.isBefore(to)) it else to } ?: to
                if (start.isBefore(end)) row.equipmentId to (start to end) else null
            }.groupBy({ it.first }, { it.second })
            .values
            .sumOf { intervals ->
                var total = 0L
                var currentStart: Instant? = null
                var currentEnd: Instant? = null
                intervals.sortedBy { it.first }.forEach { (start, end) ->
                    val activeStart = currentStart
                    val activeEnd = currentEnd
                    if (activeStart == null || activeEnd == null) {
                        currentStart = start
                        currentEnd = end
                    } else if (start.isAfter(activeEnd)) {
                        total += Duration.between(activeStart, activeEnd).toMinutes()
                        currentStart = start
                        currentEnd = end
                    } else if (end.isAfter(activeEnd)) {
                        currentEnd = end
                    }
                }
                val start = currentStart
                val end = currentEnd
                if (start != null && end != null) total += Duration.between(start, end).toMinutes()
                total
            }.toInt()

    private fun maintenance(
        c: Connection,
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): DashboardMaintenanceMetricsDto {
        var recorded = 0
        var pending = 0
        var verified = 0
        var rejected = 0
        var correction = 0
        c
            .prepareStatement(
                "select verification_status,count(*) from maintenance_record where site_id=? and performed_at between ? and ? group by verification_status",
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setTimestamp(2, java.sql.Timestamp.from(from))
                ps.setTimestamp(3, java.sql.Timestamp.from(to))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val count = rs.getInt(2)
                        recorded += count
                        when (rs.getString(1)) {
                            "PENDING_VERIFICATION" -> pending += count
                            "VERIFIED" -> verified += count
                            "REJECTED" -> rejected += count
                            "CORRECTION_REQUESTED" -> correction += count
                        }
                    }
                }
            }
        val due = serviceDue(c, siteId)
        return DashboardMaintenanceMetricsDto(recorded, pending, verified, rejected, correction, due.first, due.second)
    }

    private fun serviceDue(
        c: Connection,
        siteId: UUID,
    ): Pair<Int, Int> {
        var due = 0
        var overdue = 0
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
                    while (rs.next()) {
                        val current = rs.getBigDecimal("engine_hours_current")?.toDouble() ?: continue
                        val last = rs.getBigDecimal("last_hours")?.toDouble() ?: continue
                        val remaining = last + rs.getInt("interval_hours") - current
                        when {
                            remaining <= 0.0 -> overdue++
                            remaining <= rs.getInt("due_soon_threshold_hours") -> due++
                        }
                    }
                }
            }
        return due to overdue
    }

    private fun parts(
        c: Connection,
        siteId: UUID,
    ): DashboardPartsMetricsDto {
        var open = 0
        var awaiting = 0
        c.prepareStatement("select state,count(*) from part_request where site_id=? and state in ('REQUESTED','APPROVED','ISSUED') group by state").use { ps ->
            ps.setObject(1, siteId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val count = rs.getInt(2)
                    open += count
                    if (rs.getString(1) == "REQUESTED") awaiting += count
                }
            }
        }
        val lowStock =
            c
                .prepareStatement(
                    """
                    select count(*) from part p
                    left join (select part_id,sum(on_hand) on_hand from part_stock group by part_id) s on s.part_id=p.id
                    where p.active and coalesce(s.on_hand,0)<p.min_stock
                    """.trimIndent(),
                ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        return DashboardPartsMetricsDto(open, awaiting, lowStock)
    }

    private fun reports(
        c: Connection,
        siteId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): DashboardReportMetricsDto {
        var submitted = 0
        var awaiting = 0
        var approved = 0
        var returned = 0
        c
            .prepareStatement(
                "select rv.state,count(*) from report_version rv join daily_report dr on dr.id=rv.report_id where dr.site_id=? and dr.report_date between ? and ? group by rv.state",
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, from)
                ps.setObject(3, to)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val count = rs.getInt(2)
                        when (rs.getString(1)) {
                            "SUBMITTED" -> {
                                submitted += count
                                awaiting += count
                            }
                            "RESUBMITTED" -> {
                                submitted += count
                                awaiting += count
                            }
                            "APPROVED" -> approved += count
                            "RETURNED_FOR_CORRECTION" -> returned += count
                        }
                    }
                }
            }
        return DashboardReportMetricsDto(submitted, awaiting, approved, returned)
    }

    private fun costs(
        c: Connection,
        siteId: UUID,
        from: Instant,
        to: Instant,
    ): DashboardCostMetricsDto =
        c
            .prepareStatement(
                "select coalesce(sum(labour_cost),0) labour,coalesce(sum(parts_cost),0) parts,coalesce(min(currency),'USD') currency from maintenance_record where site_id=? and performed_at between ? and ?",
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setTimestamp(2, java.sql.Timestamp.from(from))
                ps.setTimestamp(3, java.sql.Timestamp.from(to))
                ps.executeQuery().use { rs ->
                    rs.next()
                    val labour = rs.getLong("labour")
                    val parts = rs.getLong("parts")
                    DashboardCostMetricsDto(labour, parts, labour + parts, rs.getString("currency"))
                }
            }

    private fun sites(
        c: Connection,
        actor: Principal,
        siteId: UUID?,
    ): List<SiteRow> =
        c
            .prepareStatement(
                """
                select s.id,s.code,s.name,s.timezone from site s
                where s.state='ACTIVE' and (?::uuid is null or s.id=?)
                  and (
                    exists(select 1 from user_site_access a where a.user_id=? and a.site_id=s.id)
                    or (? and not exists(select 1 from user_site_access a where a.user_id=?))
                  )
                order by s.code
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, siteId)
                ps.setObject(3, actor.id)
                ps.setBoolean(4, actor.role in BROAD_ROLES)
                ps.setObject(5, actor.id)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(SiteRow(rs.getObject("id", UUID::class.java), rs.getString("code"), rs.getString("name"), rs.getString("timezone")))
                        }
                    }
                }
            }

    private fun requireAllowed(
        actor: Principal,
        kind: DashboardKind,
    ) {
        if (actor.role !in allowed.getValue(kind)) throw AuthFailure("FORBIDDEN")
    }

    private fun DashboardEquipmentMetricsDto.plus(other: DashboardEquipmentMetricsDto) =
        DashboardEquipmentMetricsDto(
            total + other.total,
            operational + other.operational,
            operationalWithDefects + other.operationalWithDefects,
            underRepair + other.underRepair,
            down + other.down,
            notRecorded + other.notRecorded,
        )

    private fun DashboardInspectionMetricsDto.plus(other: DashboardInspectionMetricsDto): DashboardInspectionMetricsDto {
        val nextExpected = expected + other.expected
        val nextCompleted = completed + other.completed
        return DashboardInspectionMetricsDto(
            nextExpected,
            nextCompleted,
            completedWithDefects + other.completedWithDefects,
            criticalFailure + other.criticalFailure,
            missing + other.missing,
            if (nextExpected == 0) 0.0 else nextCompleted * 100.0 / nextExpected,
        )
    }

    private fun DashboardBreakdownMetricsDto.plus(other: DashboardBreakdownMetricsDto): DashboardBreakdownMetricsDto {
        val nextCompleted = completed + other.completed
        val nextAverage =
            when {
                nextCompleted == 0 -> null
                else -> {
                    val mine = (averageRepairMinutes ?: 0.0) * completed
                    val theirs = (other.averageRepairMinutes ?: 0.0) * other.completed
                    (mine + theirs) / nextCompleted
                }
            }
        return DashboardBreakdownMetricsDto(
            reported + other.reported,
            nextCompleted,
            open + other.open,
            downtimeMinutes + other.downtimeMinutes,
            nextAverage,
        )
    }

    private fun DashboardMaintenanceMetricsDto.plus(other: DashboardMaintenanceMetricsDto) =
        DashboardMaintenanceMetricsDto(
            recorded + other.recorded,
            pendingVerification + other.pendingVerification,
            verified + other.verified,
            rejected + other.rejected,
            correctionRequested + other.correctionRequested,
            dueSoon + other.dueSoon,
            overdue + other.overdue,
        )

    private fun DashboardReportMetricsDto.plus(other: DashboardReportMetricsDto) =
        DashboardReportMetricsDto(
            submitted + other.submitted,
            awaitingApproval + other.awaitingApproval,
            approved + other.approved,
            returned + other.returned,
        )

    private data class SiteMetrics(
        val equipment: DashboardEquipmentMetricsDto,
        val inspections: DashboardInspectionMetricsDto,
        val breakdowns: DashboardBreakdownMetricsDto,
        val maintenance: DashboardMaintenanceMetricsDto,
        val parts: DashboardPartsMetricsDto,
        val reports: DashboardReportMetricsDto,
        val costs: DashboardCostMetricsDto,
    )

    private data class SiteRow(
        val id: UUID,
        val code: String,
        val name: String,
        val timezone: String,
    )

    private data class BreakdownRow(
        val equipmentId: UUID,
        val reportedAt: Instant,
        val breakdownStart: Instant,
        val repairStart: Instant?,
        val repairEnd: Instant?,
    )

    companion object {
        private val BROAD_ROLES = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        private val allowed =
            mapOf(
                DashboardKind.MECHANIC to setOf(Role.MECHANIC, Role.SUPERVISOR),
                DashboardKind.SUPERVISOR to setOf(Role.SUPERVISOR, Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR),
                DashboardKind.MANAGER to setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR),
                DashboardKind.EXECUTIVE to setOf(Role.GENERAL_MANAGER, Role.ADMINISTRATOR),
            )
    }
}
