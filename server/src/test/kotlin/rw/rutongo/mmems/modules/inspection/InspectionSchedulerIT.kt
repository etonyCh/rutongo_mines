package rw.rutongo.mmems.modules.inspection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

@Testcontainers(disabledWithoutDocker = true)
class InspectionSchedulerIT {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    }

    @Test
    fun `scheduler persists its recurring inspection jobs`() {
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 4
                },
            )
        try {
            Flyway
                .configure()
                .dataSource(dataSource)
                .load()
                .migrate()
            val scheduler = InspectionScheduler(dataSource, GenerateExpectationsJob(dataSource), MarkMissingJob(dataSource))
            scheduler.start()
            try {
                var taskNames = emptyList<String>()
                repeat(100) {
                    taskNames = scheduledTaskNames(dataSource)
                    if (taskNames.size == 2) return@repeat
                    Thread.sleep(100)
                }
                assertEquals(listOf("inspection-generate-expectations", "inspection-mark-missing"), taskNames.sorted())
            } finally {
                scheduler.stop()
            }
        } finally {
            dataSource.close()
        }
    }

    private fun scheduledTaskNames(dataSource: HikariDataSource): List<String> =
        dataSource.connection.use { c ->
            c.prepareStatement("select task_name from scheduled_tasks").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1))
                    }
                }
            }
        }
}
