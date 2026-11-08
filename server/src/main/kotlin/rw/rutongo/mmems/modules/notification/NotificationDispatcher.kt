@file:Suppress("TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught", "ReturnCount", "LoopWithTooManyJumpStatements", "MagicNumber")

package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.shared.enums.NotificationChannel
import rw.rutongo.mmems.shared.enums.NotificationType
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class DispatchResult(
    val processed: Int,
    val notifications: Int,
    val pushed: Int,
)

class NotificationDispatcher(
    private val dataSource: DataSource,
    private val notifier: PushNotifier,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { encodeDefaults = true }
    private val service = NotificationService(dataSource, now)

    fun dispatchOnce(limit: Int = 50): DispatchResult {
        var processed = 0
        var createdCount = 0
        val pushes = mutableListOf<PushMessage>()
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val messages = pending(c, limit)
                for (message in messages) {
                    val savepoint = c.setSavepoint()
                    try {
                        val plan = plan(c, message.topic, message.payload)
                        if (plan == null) {
                            publish(c, message.id, message.attempts)
                            processed++
                            continue
                        }
                        val rule = service.loadRule(c, plan.type)
                        if (rule == null || !rule.enabled) {
                            publish(c, message.id, message.attempts)
                            processed++
                            continue
                        }
                        val recipients = recipients(c, rule, plan)
                        val inserted = mutableListOf<Pair<UUID, NotificationPlan>>()
                        for (userId in recipients) {
                            val notificationId = insert(c, userId, plan)
                            if (notificationId != null) {
                                inserted += userId to plan
                                createdCount++
                            }
                        }
                        val messagePushes =
                            if (rule.channel != NotificationChannel.IN_APP) {
                                pushMessages(c, inserted)
                            } else {
                                emptyList()
                            }
                        publish(c, message.id, message.attempts)
                        pushes += messagePushes
                        processed++
                    } catch (error: Exception) {
                        c.rollback(savepoint)
                        fail(c, message.id, message.attempts, error)
                    }
                }
                c.commit()
            } catch (error: Exception) {
                c.rollback()
                throw error
            }
        }
        pushes.forEach { push ->
            notifier.send(push.tokens, push.title, push.body, push.data)
        }
        return DispatchResult(processed, createdCount, pushes.size)
    }

    private fun pending(
        c: Connection,
        limit: Int,
    ): List<OutboxMessage> =
        c
            .prepareStatement(
                """
                select id,topic,payload::text payload,attempts
                from outbox_message
                where published_at is null
                order by occurred_at,id
                for update skip locked
                limit ?
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, limit.coerceIn(1, 200))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OutboxMessage(
                                    rs.getObject("id", UUID::class.java),
                                    rs.getString("topic"),
                                    rs.getString("payload"),
                                    rs.getInt("attempts"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun recipients(
        c: Connection,
        rule: rw.rutongo.mmems.shared.api.NotificationRuleDto,
        plan: NotificationPlan,
    ): Set<UUID> {
        val recipients = mutableSetOf<UUID>()
        if (plan.roleBased && rule.roles.isNotEmpty()) {
            c
                .prepareStatement(
                    """
                    select u.id from app_user u
                    where u.active and u.role = any(?::text[])
                      and (
                        not ?::boolean
                        or exists(select 1 from user_site_access a where a.user_id=u.id and a.site_id=?)
                        or (
                          u.role in ('MANAGER','GENERAL_MANAGER','ADMINISTRATOR')
                          and not exists(select 1 from user_site_access a where a.user_id=u.id)
                        )
                      )
                    """.trimIndent(),
                ).use { ps ->
                    ps.setArray(1, c.createArrayOf("text", rule.roles.map { it.name }.toTypedArray()))
                    ps.setBoolean(2, rule.siteScoped)
                    ps.setObject(3, plan.siteId)
                    ps.executeQuery().use { rs -> while (rs.next()) recipients += rs.getObject(1, UUID::class.java) }
                }
        }
        recipients += plan.extraUserIds
        return recipients
    }

    private fun insert(
        c: Connection,
        userId: UUID,
        plan: NotificationPlan,
    ): UUID? =
        c
            .prepareStatement(
                """
                insert into notification(user_id,type,dedupe_key,payload,created_at)
                values (?,?,?,?::jsonb,?)
                on conflict(user_id,dedupe_key) do nothing
                returning id
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, plan.type.name)
                ps.setString(3, plan.dedupeKey)
                ps.setString(4, json.encodeToString(plan.payload))
                ps.setTimestamp(5, java.sql.Timestamp.from(now()))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
            }

    private fun pushMessages(
        c: Connection,
        inserted: List<Pair<UUID, NotificationPlan>>,
    ): List<PushMessage> {
        if (inserted.isEmpty()) return emptyList()
        val userIds = inserted.map { it.first }.distinct()
        val tokens = mutableMapOf<UUID, MutableList<String>>()
        c.prepareStatement("select user_id,token from push_device where user_id = any(?)").use { ps ->
            ps.setArray(1, c.createArrayOf("uuid", userIds.toTypedArray()))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    tokens.getOrPut(rs.getObject("user_id", UUID::class.java)) { mutableListOf() } += rs.getString("token")
                }
            }
        }
        return inserted.mapNotNull { (userId, plan) ->
            val userTokens = tokens[userId] ?: return@mapNotNull null
            PushMessage(userTokens, plan.title, plan.body, plan.payload + ("type" to plan.type.name))
        }
    }

    private fun publish(
        c: Connection,
        id: UUID,
        attempts: Int,
    ) {
        c.prepareStatement("update outbox_message set published_at=now(),attempts=? where id=?").use { ps ->
            ps.setInt(1, attempts + 1)
            ps.setObject(2, id)
            ps.executeUpdate()
        }
    }

    private fun fail(
        c: Connection,
        id: UUID,
        attempts: Int,
        error: Exception,
    ) {
        val next = attempts + 1
        c.prepareStatement("update outbox_message set attempts=?,published_at=case when ?>=5 then now() else published_at end where id=?").use { ps ->
            ps.setInt(1, next)
            ps.setInt(2, next)
            ps.setObject(3, id)
            ps.executeUpdate()
        }
        org.slf4j.LoggerFactory
            .getLogger(NotificationDispatcher::class.java)
            .warn("Notification outbox message {} failed on attempt {}", id, next, error)
    }

    private fun plan(
        c: Connection,
        topic: String,
        payload: Map<String, String>,
    ): NotificationPlan? =
        when (topic) {
            "INSPECTION_MISSING" -> inspectionMissing(payload)
            "INSPECTION_SUBMITTED" -> criticalDefect(c, payload)
            "EQUIPMENT_STATUS_CHANGED" -> equipmentStatus(payload)
            "EQUIPMENT_STATUS_DOWN_RECOMMENDED" -> equipmentDownRecommended(payload)
            "BREAKDOWN_REPORTED" -> breakdownReported(payload)
            "BREAKDOWN_STATE_CHANGED" -> repairAttention(c, payload)
            "PART_REQUESTED" -> partRequested(payload)
            "LOW_STOCK" -> lowStock(payload)
            "MAINTENANCE_RECORDED" -> maintenanceRecorded(payload)
            "REPORT_SUBMITTED" -> reportSubmitted(payload)
            "REPORT_APPROVED" -> reportApproved(c, payload)
            "REPORT_RETURNED" -> reportReturned(c, payload)
            "SERVICE_DUE" -> serviceDue(payload)
            else -> null
        }

    private fun inspectionMissing(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val expectationId = payload["expectationId"] ?: return null
        val dedupe = payload["dedupeKey"] ?: "missing:$expectationId"
        return NotificationPlan(
            NotificationType.INSPECTION_MISSING,
            siteId,
            dedupe,
            "Inspection missing",
            "A required inspection has passed its deadline.",
            payload,
        )
    }

    private fun criticalDefect(
        c: Connection,
        payload: Map<String, String>,
    ): NotificationPlan? {
        if (payload["result"] != "CRITICAL_FAILURE") return null
        val siteId = uuid(payload["siteId"]) ?: return null
        val inspectionId = uuid(payload["inspectionId"]) ?: return null
        val defectId =
            c.prepareStatement("select id from defect where inspection_id=? order by reported_at limit 1").use { ps ->
                ps.setObject(1, inspectionId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
            }
        return NotificationPlan(
            NotificationType.CRITICAL_DEFECT,
            siteId,
            "critical:${defectId ?: "inspection:$inspectionId"}",
            "Critical defect reported",
            "A critical inspection defect requires supervisor attention.",
            payload + ("defectId" to (defectId?.toString() ?: "")),
        )
    }

    private fun equipmentStatus(payload: Map<String, String>): NotificationPlan? {
        if (payload["status"] != "DOWN") return null
        val siteId = uuid(payload["siteId"]) ?: return null
        val eventId = payload["eventId"] ?: return null
        return NotificationPlan(
            NotificationType.EQUIPMENT_DOWN,
            siteId,
            "down:$eventId",
            "Equipment down",
            "Equipment ${payload["equipmentId"] ?: ""} is down.",
            payload,
        )
    }

    private fun equipmentDownRecommended(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val inspectionId = payload["inspectionId"] ?: return null
        return NotificationPlan(
            NotificationType.EQUIPMENT_DOWN,
            siteId,
            payload["dedupeKey"] ?: "down:recommendation:$inspectionId",
            "Equipment down recommended",
            "An inspection recommends taking equipment out of service.",
            payload,
        )
    }

    private fun breakdownReported(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val breakdownId = payload["breakdownId"] ?: return null
        return NotificationPlan(
            NotificationType.EQUIPMENT_DOWN,
            siteId,
            "down:$breakdownId",
            "New breakdown reported",
            "A new equipment breakdown was reported.",
            payload,
        )
    }

    private fun repairAttention(
        c: Connection,
        payload: Map<String, String>,
    ): NotificationPlan? {
        val state = payload["state"] ?: return null
        if (state !in setOf("DIAGNOSING", "AWAITING_PARTS", "UNDER_REPAIR")) return null
        val siteId = uuid(payload["siteId"]) ?: return null
        val breakdownId = payload["breakdownId"] ?: return null
        val technicianUserId =
            c.prepareStatement("select u.id from breakdown b join app_user u on u.technician_id=b.assigned_technician_id where b.id=?").use { ps ->
                ps.setObject(1, UUID.fromString(breakdownId))
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("id", UUID::class.java) else null }
            }
        return NotificationPlan(
            NotificationType.REPAIR_ATTENTION,
            siteId,
            "repair:$breakdownId:$state",
            "Repair needs attention",
            "A breakdown moved to $state and requires follow-up.",
            payload,
            extraUserIds = setOfNotNull(technicianUserId),
        )
    }

    private fun partRequested(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val requestId = payload["requestId"] ?: return null
        return NotificationPlan(
            NotificationType.PART_REQUEST,
            siteId,
            "partreq:$requestId",
            "Part request submitted",
            "A part request is awaiting approval.",
            payload,
        )
    }

    private fun lowStock(payload: Map<String, String>): NotificationPlan {
        val partId = payload["partId"] ?: "unknown"
        return NotificationPlan(
            NotificationType.LOW_STOCK,
            null,
            payload["dedupeKey"] ?: "lowstock:$partId:${now().toString().take(10)}",
            "Low stock",
            "Part ${payload["partNumber"] ?: partId} is below its minimum stock level.",
            payload,
            roleBased = true,
        )
    }

    private fun maintenanceRecorded(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val maintenanceId = payload["maintenanceId"] ?: return null
        return NotificationPlan(
            NotificationType.MAINTENANCE_VERIFY,
            siteId,
            "verify:$maintenanceId",
            "Maintenance awaiting verification",
            "A maintenance record is awaiting supervisor verification.",
            payload,
        )
    }

    private fun reportSubmitted(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val reportId = payload["reportId"] ?: return null
        val version = payload["versionNo"] ?: "1"
        return NotificationPlan(
            NotificationType.REPORT_APPROVAL,
            siteId,
            "report:$reportId:$version:SUBMITTED",
            "Report awaiting approval",
            "A daily report was submitted for approval.",
            payload,
        )
    }

    private fun reportApproved(
        c: Connection,
        payload: Map<String, String>,
    ): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val reportId = uuid(payload["reportId"]) ?: return null
        val version = payload["versionNo"] ?: "1"
        val author =
            c.prepareStatement("select prepared_by from daily_report where id=?").use { ps ->
                ps.setObject(1, reportId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("prepared_by", UUID::class.java) else null }
            } ?: return null
        return NotificationPlan(
            NotificationType.REPORT_APPROVAL,
            siteId,
            "report:$reportId:$version:APPROVED",
            "Report approved",
            "A daily report was approved.",
            payload,
            extraUserIds = setOf(author),
            roleBased = false,
        )
    }

    private fun reportReturned(
        c: Connection,
        payload: Map<String, String>,
    ): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val reportId = uuid(payload["reportId"]) ?: return null
        val version = payload["versionNo"] ?: "1"
        val author =
            c.prepareStatement("select prepared_by from daily_report where id=?").use { ps ->
                ps.setObject(1, reportId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject("prepared_by", UUID::class.java) else null }
            } ?: return null
        return NotificationPlan(
            NotificationType.REPORT_APPROVAL,
            siteId,
            "report:$reportId:$version:RETURNED",
            "Report returned for correction",
            "A daily report was returned for correction.",
            payload,
            extraUserIds = setOf(author),
            roleBased = false,
        )
    }

    private fun serviceDue(payload: Map<String, String>): NotificationPlan? {
        val siteId = uuid(payload["siteId"]) ?: return null
        val equipmentId = payload["equipmentId"] ?: return null
        val serviceTypeCode = payload["serviceTypeCode"] ?: payload["serviceTypeId"] ?: "SERVICE"
        val status = payload["status"] ?: "DUE_SOON"
        return NotificationPlan(
            NotificationType.SERVICE_DUE,
            siteId,
            payload["dedupeKey"] ?: "service:$equipmentId:$serviceTypeCode:$status",
            "Service $status",
            "Equipment $equipmentId has a service $status.",
            payload,
        )
    }

    private fun uuid(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private data class OutboxMessage(
        val id: UUID,
        val topic: String,
        val payloadJson: String,
        val attempts: Int,
    ) {
        val payload: Map<String, String> = NotificationServicePayload.parse(payloadJson)
    }

    private data class NotificationPlan(
        val type: NotificationType,
        val siteId: UUID?,
        val dedupeKey: String,
        val title: String,
        val body: String,
        val payload: Map<String, String>,
        val extraUserIds: Set<UUID> = emptySet(),
        val roleBased: Boolean = true,
    )

    private data class PushMessage(
        val tokens: Collection<String>,
        val title: String,
        val body: String,
        val data: Map<String, String>,
    )
}

private object NotificationServicePayload {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Map<String, String> =
        runCatching {
            json
                .parseToJsonElement(raw)
                .let { element ->
                    (element as? kotlinx.serialization.json.JsonObject)
                        ?.entries
                        ?.associate { (key, value) ->
                            key to ((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: value.toString())
                        }
                } ?: emptyMap()
        }.getOrDefault(emptyMap())
}
