@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList", "MagicNumber")

package rw.rutongo.mmems.modules.workinghours

import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.modules.workinghours.domain.HoursInterval
import rw.rutongo.mmems.modules.workinghours.domain.recordedHours
import rw.rutongo.mmems.shared.api.WorkingHoursEntryDto
import rw.rutongo.mmems.shared.api.WorkingHoursGroupDto
import rw.rutongo.mmems.shared.api.WorkingHoursResponseDto
import rw.rutongo.mmems.shared.enums.Role
import rw.rutongo.mmems.shared.enums.WorkingHoursGroupBy
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.round

class WorkingHoursService(
    private val dataSource: DataSource,
) {
    fun list(
        actor: Principal,
        siteId: UUID? = null,
        equipmentId: UUID? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        shiftId: UUID? = null,
        groupBy: WorkingHoursGroupBy = WorkingHoursGroupBy.EQUIPMENT,
    ): WorkingHoursResponseDto {
        if (!RolePermissions.allows(actor.role, "EQUIPMENT_VIEW")) throw AuthFailure("FORBIDDEN")
        val entries =
            dataSource.connection.use { c ->
                if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                c
                    .prepareStatement(
                        """
                        select w.inspection_id,w.equipment_id,e.equipment_code,e.name equipment_name,
                          w.site_id,s.name site_name,w.shift_id,sh.code shift_code,w.report_date,
                          w.hours_start,w.hours_end,w.recorded_hours,w.status
                        from working_hours_entry w
                        join equipment e on e.id=w.equipment_id
                        join site s on s.id=w.site_id
                        join shift sh on sh.id=w.shift_id
                        where (?::uuid is null or w.site_id=?) and (?::uuid is null or w.equipment_id=?)
                          and (?::date is null or w.report_date>=?) and (?::date is null or w.report_date<=?)
                          and (?::uuid is null or w.shift_id=?)
                          and ${siteAccessSql("w.site_id")}
                        order by w.report_date,w.equipment_id,w.shift_id
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, siteId)
                        ps.setObject(2, siteId)
                        ps.setObject(3, equipmentId)
                        ps.setObject(4, equipmentId)
                        ps.setObject(5, from)
                        ps.setObject(6, from)
                        ps.setObject(7, to)
                        ps.setObject(8, to)
                        ps.setObject(9, shiftId)
                        ps.setObject(10, shiftId)
                        bindSiteAccess(ps, actor, 11)
                        ps.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        WorkingHoursEntryDto(
                                            inspectionId = rs.getObject("inspection_id", UUID::class.java).toString(),
                                            equipmentId = rs.getObject("equipment_id", UUID::class.java).toString(),
                                            equipmentCode = rs.getString("equipment_code"),
                                            equipmentName = rs.getString("equipment_name"),
                                            siteId = rs.getObject("site_id", UUID::class.java).toString(),
                                            siteName = rs.getString("site_name"),
                                            shiftId = rs.getObject("shift_id", UUID::class.java).toString(),
                                            shiftCode = rs.getString("shift_code"),
                                            reportDate = rs.getObject("report_date", LocalDate::class.java).toString(),
                                            hoursStart = rs.getBigDecimal("hours_start")?.toDouble(),
                                            hoursEnd = rs.getBigDecimal("hours_end")?.toDouble(),
                                            recordedHours = rs.getBigDecimal("recorded_hours")?.toDouble(),
                                            status = rs.getString("status"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            }
        return WorkingHoursResponseDto(
            groupBy = groupBy,
            entries = entries,
            groups = group(entries, groupBy),
            totalRecordedHours = summarize(entries),
        )
    }

    private fun group(
        entries: List<WorkingHoursEntryDto>,
        groupBy: WorkingHoursGroupBy,
    ): List<WorkingHoursGroupDto> =
        entries
            .groupBy { keyOf(it, groupBy) }
            .toSortedMap()
            .map { (key, grouped) ->
                val first = grouped.first()
                WorkingHoursGroupDto(
                    key = key,
                    siteId = first.siteId.takeIf { groupBy == WorkingHoursGroupBy.SITE },
                    siteName = first.siteName.takeIf { groupBy == WorkingHoursGroupBy.SITE },
                    equipmentId = first.equipmentId.takeIf { groupBy == WorkingHoursGroupBy.EQUIPMENT },
                    equipmentCode = first.equipmentCode.takeIf { groupBy == WorkingHoursGroupBy.EQUIPMENT },
                    equipmentName = first.equipmentName.takeIf { groupBy == WorkingHoursGroupBy.EQUIPMENT },
                    shiftId = first.shiftId.takeIf { groupBy == WorkingHoursGroupBy.SHIFT },
                    shiftCode = first.shiftCode.takeIf { groupBy == WorkingHoursGroupBy.SHIFT },
                    reportDate = first.reportDate.takeIf { groupBy == WorkingHoursGroupBy.DAY },
                    recordedHours = summarize(grouped),
                    entryCount = grouped.size,
                    overlapEntries = grouped.count { it.status == "OVERLAP" },
                    missingReadings = grouped.count { it.status == "MISSING_READING" },
                )
            }

    private fun summarize(entries: List<WorkingHoursEntryDto>): Double {
        val total =
            entries
                .groupBy { it.equipmentId }
                .values
                .sumOf { equipmentEntries ->
                    equipmentEntries
                        .mapNotNull { entry ->
                            val start = entry.hoursStart ?: return@mapNotNull null
                            val end = entry.hoursEnd ?: return@mapNotNull null
                            if (end < start) null else HoursInterval(start, end)
                        }.recordedHours()
                }
        return round(total * 10.0) / 10.0
    }

    private fun keyOf(
        entry: WorkingHoursEntryDto,
        groupBy: WorkingHoursGroupBy,
    ): String =
        when (groupBy) {
            WorkingHoursGroupBy.SITE -> entry.siteId
            WorkingHoursGroupBy.EQUIPMENT -> entry.equipmentId
            WorkingHoursGroupBy.SHIFT -> entry.shiftId
            WorkingHoursGroupBy.DAY -> entry.reportDate
        }

    private fun hasSiteAccess(
        c: java.sql.Connection,
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

    companion object {
        private val broadRoles = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
    }
}
