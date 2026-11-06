@file:Suppress("TooManyFunctions", "LongMethod", "MagicNumber", "LongParameterList", "NestedBlockDepth", "TooGenericExceptionCaught")

package rw.rutongo.mmems.modules.inspection

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

data class GeneratedExpectations(
    val inserted: Int,
)

data class MarkedMissing(
    val expectationIds: List<UUID>,
)

class GenerateExpectationsJob(
    private val dataSource: DataSource,
) {
    fun run(at: Instant = Instant.now()): GeneratedExpectations =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                var inserted = 0
                for ((siteId, timezone, schedule) in schedules(c)) {
                    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Africa/Kigali"))
                    for (reportDate in listOf(at.atZone(zone).toLocalDate().minusDays(1), at.atZone(zone).toLocalDate())) {
                        val start = LocalDateTime.of(reportDate, schedule.startLocal).atZone(zone).toInstant()
                        val endDay = if (schedule.crossesMidnight) reportDate.plusDays(1) else reportDate
                        val end = LocalDateTime.of(endDay, schedule.endLocal).atZone(zone).toInstant()
                        if (start.isAfter(at.plusSeconds(900)) || !end.isAfter(at) || start.isBefore(at.minusSeconds(86_400))) continue
                        val windowEnd = start.plusSeconds(schedule.inspectionWindowMinutes * 60L)
                        inserted += insertExpectations(c, siteId, schedule.shiftId, reportDate, start, windowEnd)
                    }
                }
                c.commit()
                GeneratedExpectations(inserted)
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private fun insertExpectations(
        c: Connection,
        siteId: UUID,
        shiftId: UUID,
        reportDate: LocalDate,
        windowStart: Instant,
        windowEnd: Instant,
    ): Int =
        c
            .prepareStatement(
                """
                insert into inspection_expectation(id,equipment_id,site_id,shift_id,report_date,window_start,window_end)
                select gen_random_uuid(),id,site_id,?,?,?,? from equipment
                where site_id=? and lifecycle_state='ACTIVE' and requires_daily_inspection
                on conflict (equipment_id,shift_id,report_date) do nothing
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, shiftId)
                ps.setObject(2, reportDate)
                ps.setTimestamp(3, Timestamp.from(windowStart))
                ps.setTimestamp(4, Timestamp.from(windowEnd))
                ps.setObject(5, siteId)
                ps.executeUpdate()
            }

    private fun schedules(c: Connection): List<SiteSchedule> {
        val all =
            c
                .prepareStatement(
                    """
                    select ss.*,s.timezone,sh.code shift_code from shift_schedule ss
                    join site s on s.id=ss.site_id
                    join shift sh on sh.id=ss.shift_id
                    where s.state='ACTIVE'
                      and ss.effective_from<=current_date and (ss.effective_to is null or ss.effective_to>=current_date)
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ScheduleRow(
                                        rs.getObject("site_id", UUID::class.java),
                                        rs.getObject("shift_id", UUID::class.java),
                                        rs.getString("shift_code"),
                                        rs.getTime("start_local").toLocalTime(),
                                        rs.getTime("end_local").toLocalTime(),
                                        rs.getInt("inspection_window_minutes"),
                                        rs.getString("timezone"),
                                    ),
                                )
                            }
                        }
                    }
                }
        val global =
            c
                .prepareStatement(
                    """
                    select ss.*,s.timezone,sh.code shift_code from shift_schedule ss
                    cross join site s
                    join shift sh on sh.id=ss.shift_id
                    where ss.site_id is null and s.state='ACTIVE'
                      and (ss.effective_from<=current_date and (ss.effective_to is null or ss.effective_to>=current_date))
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    ScheduleRow(
                                        null,
                                        rs.getObject("shift_id", UUID::class.java),
                                        rs.getString("shift_code"),
                                        rs.getTime("start_local").toLocalTime(),
                                        rs.getTime("end_local").toLocalTime(),
                                        rs.getInt("inspection_window_minutes"),
                                        rs.getString("timezone"),
                                    ),
                                )
                            }
                        }
                    }
                }
        val siteRows = all.associateBy { it.siteId!! to it.code }
        val result = mutableListOf<SiteSchedule>()
        for (row in all) {
            result += SiteSchedule(row.siteId!!, row.timezone, Schedule(row.shiftId, row.code, row.startLocal, row.endLocal, row.inspectionWindowMinutes))
        }
        for (row in global) {
            val sites =
                c.prepareStatement("select id,timezone from site where state='ACTIVE'").use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getObject(1, UUID::class.java) to rs.getString(2))
                        }
                    }
                }
            for ((siteId, timezone) in sites) {
                if ((siteId to row.code) !in siteRows) {
                    result += SiteSchedule(siteId, timezone, Schedule(row.shiftId, row.code, row.startLocal, row.endLocal, row.inspectionWindowMinutes))
                }
            }
        }
        return result
    }

    private data class ScheduleRow(
        val siteId: UUID?,
        val shiftId: UUID,
        val code: String,
        val startLocal: LocalTime,
        val endLocal: LocalTime,
        val inspectionWindowMinutes: Int,
        val timezone: String,
    )

    private data class SiteSchedule(
        val siteId: UUID,
        val timezone: String,
        val schedule: Schedule,
    )

    private data class Schedule(
        val shiftId: UUID,
        val code: String,
        val startLocal: LocalTime,
        val endLocal: LocalTime,
        val inspectionWindowMinutes: Int,
    ) {
        val crossesMidnight: Boolean get() = endLocal <= startLocal
    }
}

class MarkMissingJob(
    private val dataSource: DataSource,
) {
    fun run(at: Instant = Instant.now()): MarkedMissing =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val due = dueExpectations(c, at)
                val marked = mutableListOf<UUID>()
                for (row in due) {
                    c.prepareStatement("update inspection_expectation set state='MISSING',missing_marked_at=? where id=? and state in ('NOT_STARTED','IN_PROGRESS')").use { ps ->
                        ps.setTimestamp(1, Timestamp.from(at))
                        ps.setObject(2, row.id)
                        if (ps.executeUpdate() == 1) marked += row.id
                    }
                    insertOutbox(c, row, at)
                }
                c.commit()
                MarkedMissing(marked)
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }

    private fun dueExpectations(
        c: Connection,
        at: Instant,
    ): List<DueExpectation> =
        c
            .prepareStatement(
                """
                select x.id,x.equipment_id,x.site_id,x.shift_id,x.report_date
                from inspection_expectation x
                left join lateral (
                  select ss.hard_backstop_hours from shift_schedule ss
                  where ss.shift_id=x.shift_id and (ss.site_id=x.site_id or ss.site_id is null)
                    and ss.effective_from<=x.report_date and (ss.effective_to is null or ss.effective_to>=x.report_date)
                  order by ss.site_id nulls last limit 1
                ) hb on true
                where x.state in ('NOT_STARTED','IN_PROGRESS')
                  and (x.window_end < ? or x.window_start + make_interval(hours => coalesce(hb.hard_backstop_hours,24)) < ?)
                for update of x
                """.trimIndent(),
            ).use { ps ->
                ps.setTimestamp(1, Timestamp.from(at))
                ps.setTimestamp(2, Timestamp.from(at))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(DueExpectation(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getObject(5, LocalDate::class.java)))
                        }
                    }
                }
            }

    private fun insertOutbox(
        c: Connection,
        row: DueExpectation,
        at: Instant,
    ) {
        val dedupe = "missing:${row.id}"
        val exists =
            c.prepareStatement("select exists(select 1 from outbox_message where topic='INSPECTION_MISSING' and payload->>'dedupeKey'=?)").use { ps ->
                ps.setString(1, dedupe)
                ps.executeQuery().use {
                    it.next()
                    it.getBoolean(1)
                }
            }
        if (exists) return
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,'INSPECTION_MISSING',?::jsonb,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, "{\"expectationId\":\"${row.id}\",\"equipmentId\":\"${row.equipmentId}\",\"siteId\":\"${row.siteId}\",\"shiftId\":\"${row.shiftId}\",\"reportDate\":\"${row.reportDate}\",\"dedupeKey\":\"$dedupe\"}")
            ps.setTimestamp(3, Timestamp.from(at))
            ps.executeUpdate()
        }
    }

    private data class DueExpectation(
        val id: UUID,
        val equipmentId: UUID,
        val siteId: UUID,
        val shiftId: UUID,
        val reportDate: LocalDate,
    )
}
