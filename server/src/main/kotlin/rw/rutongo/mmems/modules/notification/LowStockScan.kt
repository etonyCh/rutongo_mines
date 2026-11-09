@file:Suppress("MagicNumber")

package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

class LowStockScan(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    fun run(): Int =
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val created = scan(c)
                c.commit()
                created
            } catch (error: SQLException) {
                c.rollback()
                throw error
            }
        }

    private fun scan(c: Connection): Int {
        val date = now().atZone(ZoneOffset.UTC).toLocalDate()
        return lowStockParts(c).count { part ->
            val dedupeKey = "lowstock:${part.id}:$date"
            if (exists(c, dedupeKey)) {
                false
            } else {
                insert(
                    c,
                    mapOf(
                        "partId" to part.id.toString(),
                        "partNumber" to part.partNumber,
                        "onHand" to part.onHand.toString(),
                        "minStock" to part.minStock.toString(),
                        "dedupeKey" to dedupeKey,
                    ),
                )
                true
            }
        }
    }

    private fun lowStockParts(c: Connection): List<LowStockPart> =
        c
            .prepareStatement(
                """
                select p.id,p.part_number,p.min_stock,coalesce(sum(s.on_hand),0) on_hand
                from part p
                left join part_stock s on s.part_id=p.id
                where p.active
                group by p.id,p.part_number,p.min_stock
                having coalesce(sum(s.on_hand),0) < p.min_stock
                order by p.part_number
                """.trimIndent(),
            ).use { ps ->
                ps.executeQuery().use { it.toLowStockParts() }
            }

    private fun ResultSet.toLowStockParts(): List<LowStockPart> =
        buildList {
            while (next()) {
                add(
                    LowStockPart(
                        id = getObject("id", UUID::class.java),
                        partNumber = getString("part_number"),
                        minStock = getInt("min_stock"),
                        onHand = getInt("on_hand"),
                    ),
                )
            }
        }

    private fun exists(
        c: Connection,
        dedupeKey: String,
    ): Boolean =
        c.prepareStatement("select exists(select 1 from outbox_message where topic='LOW_STOCK' and payload->>'dedupeKey'=?)").use { ps ->
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
        c.prepareStatement("insert into outbox_message(id,topic,payload,occurred_at) values (?,'LOW_STOCK',?::jsonb,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setString(2, Json.encodeToString(payload))
            ps.setTimestamp(3, java.sql.Timestamp.from(now()))
            ps.executeUpdate()
        }
    }

    private data class LowStockPart(
        val id: UUID,
        val partNumber: String,
        val minStock: Int,
        val onHand: Int,
    )
}
