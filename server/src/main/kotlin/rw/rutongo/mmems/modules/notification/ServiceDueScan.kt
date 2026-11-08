@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
)

package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class ServiceDueScan(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun run(): Int {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val created = scan(c)
                c.commit()
                return created
            } catch (error: Exception) {
                c.rollback()
                throw error
            }
        }
    }

    private fun scan(c: Connection): Int {
        var created = 0
        c
            .prepareStatement(
                """
                select e.id equipment_id,e.site_id,si.service_type_id,st.code service_type_code,
                  si.interval_hours,si.due_soon_threshold_hours,e.engine_hours_current,
                  (select m.engine_hours
                   from maintenance_record m join service_type source on source.id=m.service_type_id
                   where m.equipment_id=e.id and m.verification_status='VERIFIED' and m.engine_hours is not null
                     and (m.service_type_id=si.service_type_id or si.service_type_id=any(source.satisfies_type_ids))
                   order by m.performed_at desc,m.created_at desc limit 1) last_hours
                from service_interval si
                join service_type st on st.id=si.service_type_id
                join equipment e on e.lifecycle_state='ACTIVE'
                  and (si.equipment_id=e.id or (si.equipment_id is null and (si.equipment_type_id is null or si.equipment_type_id=e.type_id)))
                where e.engine_hours_current is not null
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val current = rs.getBigDecimal("engine_hours_current")?.toDouble() ?: continue
                        val last = rs.getBigDecimal("last_hours")?.toDouble() ?: continue
                        val remaining = last + rs.getInt("interval_hours") - current
                        val status =
                            when {
                                remaining <= 0.0 -> "OVERDUE"
                                remaining <= rs.getInt("due_soon_threshold_hours") -> "DUE_SOON"
                                else -> null
                            } ?: continue
                        val equipmentId = rs.getObject("equipment_id", UUID::class.java)
                        val serviceTypeCode = rs.getString("service_type_code")
                        val dedupeKey = "service:$equipmentId:$serviceTypeCode:$status"
                        if (exists(c, dedupeKey)) continue
                        insert(
                            c,
                            mapOf(
                                "equipmentId" to equipmentId.toString(),
                                "siteId" to rs.getObject("site_id", UUID::class.java).toString(),
                                "serviceTypeId" to rs.getObject("service_type_id", UUID::class.java).toString(),
                                "serviceTypeCode" to serviceTypeCode,
                                "status" to status,
                                "dedupeKey" to dedupeKey,
                            ),
                        )
                        created++
                    }
                }
            }
        return created
    }

    private fun exists(
        c: Connection,
        dedupeKey: String,
    ): Boolean =
        c.prepareStatement("select exists(select 1 from outbox_message where topic='SERVICE_DUE' and payload->>'dedupeKey'=?)").use { ps ->
            ps.setString(1, dedupeKey)
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun insert(
        c: Connection,
        payload: Map<String, String>,
    ) {
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,'SERVICE_DUE',?::jsonb,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, Json.encodeToString(payload))
            ps.setTimestamp(3, java.sql.Timestamp.from(now()))
            ps.executeUpdate()
        }
    }
}
