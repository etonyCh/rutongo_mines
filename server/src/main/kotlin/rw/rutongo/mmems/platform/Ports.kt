package rw.rutongo.mmems.platform

import java.time.Instant

fun interface Clock { fun now(): Instant }

data class AuditEntry(
    val actorId: String?, val action: String, val entityType: String,
    val entityId: String, val occurredAt: Instant, val metadata: String = "{}",
)

fun interface Audit { suspend fun append(entry: AuditEntry) }

data class OutboxMessage(val id: String, val topic: String, val payload: String, val occurredAt: Instant)

fun interface Outbox { suspend fun enqueue(message: OutboxMessage) }
