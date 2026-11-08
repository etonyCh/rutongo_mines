package rw.rutongo.mmems.modules.notification

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay
import javax.sql.DataSource

class NotificationScheduler(
    private val dataSource: DataSource,
    private val dispatcher: NotificationDispatcher,
    private val serviceDueScan: ServiceDueScan = ServiceDueScan(dataSource),
) {
    private var scheduler: Scheduler? = null

    fun start() {
        if (scheduler != null) return
        val dispatchTask =
            Tasks
                .recurring("notification-dispatch", FixedDelay.ofSeconds(DISPATCH_INTERVAL_SECONDS))
                .execute { _, _ -> dispatcher.dispatchOnce() }
        val serviceDueTask =
            Tasks
                .recurring("service-due-scan", FixedDelay.ofSeconds(SERVICE_DUE_INTERVAL_SECONDS))
                .execute { _, _ -> serviceDueScan.run() }
        scheduler =
            Scheduler
                .create(dataSource)
                .startTasks(dispatchTask, serviceDueTask)
                .threads(1)
                .build()
                .also { it.start() }
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
    }

    companion object {
        private const val DISPATCH_INTERVAL_SECONDS = 30
        private const val SERVICE_DUE_INTERVAL_SECONDS = 3600
    }
}
