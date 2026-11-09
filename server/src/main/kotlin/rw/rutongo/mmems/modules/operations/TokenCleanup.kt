@file:Suppress("MagicNumber")

package rw.rutongo.mmems.modules.operations

import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

class TokenCleanup(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
    private val retention: Duration = Duration.ofDays(30),
) {
    fun run(): Int {
        val cutoff = now().minus(retention)
        dataSource.connection.use { c ->
            val tokens =
                c
                    .prepareStatement(
                        "delete from refresh_token where expires_at<? or (revoked_at is not null and revoked_at<?) or (used_at is not null and used_at<?)",
                    ).use { ps ->
                        ps.setTimestamp(1, java.sql.Timestamp.from(cutoff))
                        ps.setTimestamp(2, java.sql.Timestamp.from(cutoff))
                        ps.setTimestamp(3, java.sql.Timestamp.from(cutoff))
                        ps.executeUpdate()
                    }
            val buckets =
                c.prepareStatement("delete from login_rate_bucket where window_started_at<?").use { ps ->
                    ps.setTimestamp(1, java.sql.Timestamp.from(cutoff))
                    ps.executeUpdate()
                }
            return tokens + buckets
        }
    }
}
