package rw.rutongo.mmems.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import rw.rutongo.mmems.android.MmemsApplication
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MmemsApplication
        val report = app.container.syncEngine.syncAll(refreshReferences = true)
        return when {
            report.rejected > 0 && !report.retryNeeded && report.conflicts == 0 -> Result.failure(workDataOf("message" to report.message))
            report.retryNeeded -> Result.retry()
            report.referenceSyncFailed -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val ONE_TIME = "mmems-sync-now"
        const val PERIODIC = "mmems-sync-periodic"

        fun enqueueNow(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network()).build())
        }

        fun schedule(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(network())
                        .build(),
                )
        }

        private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
