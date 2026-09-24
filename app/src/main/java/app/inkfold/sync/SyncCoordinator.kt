package app.inkfold.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Single point of coordination between the foreground (in-app) sync loop and
 * the background [SyncWorker], so the two can never run at the same time.
 *
 * Both write the same local document files and the same tombstone baseline
 * ([DriveSync]'s `sync-state.json`); running two passes concurrently could
 * interleave those writes and corrupt the baseline or a document. The mutex
 * is process-wide (in-memory), which is sufficient because WorkManager runs
 * its worker in this same app process, not a separate one.
 */
object SyncCoordinator {
    private val mutex = Mutex()

    /** Runs [block] with exclusive access; a concurrent caller simply waits its turn. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Ensures the periodic background sync is scheduled. Safe to call on every
     * app start and after sign-in: [ExistingPeriodicWorkPolicy.KEEP] means an
     * already-scheduled job is left alone rather than restarted.
     */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            SyncWorker.INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Stops the background sync, e.g. on sign-out. */
    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}
