package com.example.posecamera.sync

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.posecamera.power.PowerMonitor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Verdict { SUCCESS, RETRY }

/**
 * Pure outcome -> WorkManager mapping. PAUSED_AUTH is SUCCESS on purpose: retrying with a dead token only burns
 * battery; SyncRuntime has already set the needs-login flag and the next login re-enqueues sync.
 * DEFERRED_POWER (thermal) retries later with WorkManager's exponential backoff.
 */
fun verdictOf(outcome: SyncOutcome): Verdict = when (outcome) {
    SyncOutcome.OK, SyncOutcome.PAUSED_AUTH -> Verdict.SUCCESS
    SyncOutcome.RETRY, SyncOutcome.DEFERRED_POWER -> Verdict.RETRY
}

/**
 * Coalescing: a run that has not started yet (ENQUEUED, incl. retry backoff, or BLOCKED) will read everything
 * saved before now, so KEEP it. Otherwise (nothing, or only a RUNNING one that may have missed the new data)
 * APPEND_OR_REPLACE queues exactly one run after it. Never more than one running + one pending.
 */
fun syncPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy =
    if (states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }) ExistingWorkPolicy.KEEP
    else ExistingWorkPolicy.APPEND_OR_REPLACE

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // PowerMonitor reads the current battery/saver/thermal snapshot on construction; no receiver needed here.
        val power = PowerMonitor(applicationContext).state.value
        val outcome = SyncRuntime.get(applicationContext).run(power, inputData.getBoolean(KEY_USER, false))
        return when (verdictOf(outcome)) {
            Verdict.SUCCESS -> Result.success()
            Verdict.RETRY -> Result.retry()
        }
    }

    companion object {
        const val KEY_USER = "user_initiated"
    }
}

object SyncScheduler {
    private const val NOW = "sahirep-sync-now"
    private const val PERIODIC = "sahirep-sync-periodic"
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Serial, off the main thread: the WorkInfo lookup blocks, and serial order keeps check-then-enqueue race free. */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * After a set/log is saved, on app start, and for the "Sync now" button. At most one not-yet-started run is
     * queued ([syncPolicy]); a request that arrives while one is only RUNNING queues one trailing run, so data
     * saved mid-sync is still pushed. Expedited only on API 31+: older releases would need a foreground-service
     * notification (getForegroundInfo).
     */
    fun syncNow(context: Context, userInitiated: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        io.execute {
            val states = wm.getWorkInfosForUniqueWork(NOW).get().map { it.state }
            wm.enqueueUniqueWork(NOW, syncPolicy(states), request(userInitiated))
        }
    }

    private fun request(userInitiated: Boolean): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_USER, userInitiated).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }
            .build()
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
