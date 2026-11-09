package rw.rutongo.mmems.modules.operations

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import org.slf4j.LoggerFactory
import rw.rutongo.mmems.modules.notification.LowStockScan
import javax.sql.DataSource

class OperationsScheduler(
    private val dataSource: DataSource,
    private val lowStockScan: LowStockScan = LowStockScan(dataSource),
    private val tokenCleanup: TokenCleanup = TokenCleanup(dataSource),
    private val auditChainVerify: AuditChainVerify = AuditChainVerify(dataSource),
    private val backupVerification: BackupVerification = BackupVerification(),
) {
    private val log = LoggerFactory.getLogger(OperationsScheduler::class.java)
    private var scheduler: Scheduler? = null

    fun start() {
        if (scheduler != null) return
        val lowStockTask =
            Tasks
                .recurring("low-stock-scan", FixedDelay.ofSeconds(LOW_STOCK_INTERVAL_SECONDS))
                .execute { _, _ -> lowStockScan.run() }
        val tokenCleanupTask =
            Tasks
                .recurring("token-cleanup", FixedDelay.ofSeconds(DAILY_INTERVAL_SECONDS))
                .execute { _, _ -> tokenCleanup.run() }
        val auditChainTask =
            Tasks
                .recurring("audit-chain-verify", FixedDelay.ofSeconds(DAILY_INTERVAL_SECONDS))
                .execute { _, _ ->
                    val result = auditChainVerify.run()
                    if (!result.valid) {
                        error("Audit hash chain verification failed at sequence ${result.firstBrokenSequence}")
                    }
                    log.info("Audit hash chain verified ({} events)", result.checked)
                }
        val backupTask =
            Tasks
                .recurring("backup-verification", FixedDelay.ofSeconds(DAILY_INTERVAL_SECONDS))
                .execute { _, _ ->
                    when (val result = backupVerification.run()) {
                        is BackupVerificationResult.Verified -> log.info("Backup verified: {}", result.completedAt)
                        BackupVerificationResult.NotConfigured -> log.warn("Backup verification is not configured")
                        is BackupVerificationResult.Invalid -> error(result.reason)
                    }
                }
        scheduler =
            Scheduler
                .create(dataSource)
                .startTasks(lowStockTask, tokenCleanupTask, auditChainTask, backupTask)
                .threads(1)
                .build()
                .also { it.start() }
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
    }

    private companion object {
        private const val LOW_STOCK_INTERVAL_SECONDS = 3600
        private const val DAILY_INTERVAL_SECONDS = 86_400
    }
}
