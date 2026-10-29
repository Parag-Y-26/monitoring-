package com.edgepulse.monitor.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Best effort every four hours (§4.2); Doze/OEM limits can delay delivery. No exact alarm. */
class DailyCheckpointWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies { fun checkpoints(): DailyCheckpointCoordinator }

    override suspend fun doWork(): Result = try {
        val exporter = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).checkpoints()
        when (exporter.exportNow()) {
            "EXPORTED", "NO_HISTORY", "FOLDER_REQUIRED", "IDENTITY_REQUIRED" -> Result.success()
            else -> if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }

    companion object {
        const val WORK_NAME = "edgepulse.daily-local-checkpoint"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyCheckpointWorker>(4, TimeUnit.HOURS)
                .setInitialDelay(4, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
