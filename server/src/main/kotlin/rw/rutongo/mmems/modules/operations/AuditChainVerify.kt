package rw.rutongo.mmems.modules.operations

import javax.sql.DataSource

data class AuditChainVerification(
    val checked: Long,
    val valid: Boolean,
    val firstBrokenSequence: Long?,
)

class AuditChainVerify(
    private val dataSource: DataSource,
) {
    fun run(): AuditChainVerification =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    with chain as (
                      select audit_sequence,
                             previous_hash,
                             event_hash,
                             lag(event_hash) over (order by audit_sequence) as prior_event_hash,
                             digest(
                               coalesce(encode(previous_hash, 'hex'), '') || '|' ||
                               coalesce(actor_id::text, '') || '|' ||
                               action || '|' || entity_type || '|' || entity_id || '|' ||
                               occurred_at::text || '|' || metadata::text,
                               'sha256'
                             ) as expected_hash
                      from audit_event
                    )
                    select count(*) total,
                           count(*) filter (where previous_hash is distinct from prior_event_hash or event_hash is distinct from expected_hash) invalid,
                           min(audit_sequence) filter (where previous_hash is distinct from prior_event_hash or event_hash is distinct from expected_hash) first_broken
                    from chain
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        val firstBroken = rs.getObject("first_broken") as? Number
                        AuditChainVerification(
                            checked = rs.getLong("total"),
                            valid = rs.getInt("invalid") == 0,
                            firstBrokenSequence = firstBroken?.toLong(),
                        )
                    }
                }
        }
}
