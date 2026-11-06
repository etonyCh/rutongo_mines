package rw.rutongo.mmems.modules.inspection

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import javax.sql.DataSource

class InspectionScheduler(
    private val dataSource: DataSource,
    private val expectations: GenerateExpectationsJob,
    private val missing: MarkMissingJob,
) {
    private var scheduler: Scheduler? = null

    fun start() {
        if (scheduler != null) return
        val generate =
            Tasks
                .recurring("inspection-generate-expectations", FixedDelay.ofMinutes(GENERATE_INTERVAL_MINUTES))
                .execute { _, _ -> expectations.run() }
        val markMissing =
            Tasks
                .recurring("inspection-mark-missing", FixedDelay.ofMinutes(MISSING_INTERVAL_MINUTES))
                .execute { _, _ -> missing.run() }
        scheduler =
            Scheduler
                .create(dataSource)
                .startTasks(generate, markMissing)
                .threads(2)
                .build()
                .also { it.start() }
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
    }

    companion object {
        private const val GENERATE_INTERVAL_MINUTES = 15
        private const val MISSING_INTERVAL_MINUTES = 5
    }
}
