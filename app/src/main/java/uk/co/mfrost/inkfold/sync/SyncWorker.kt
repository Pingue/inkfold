package uk.co.mfrost.inkfold.sync

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.co.mfrost.inkfold.storage.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a Drive sync pass while the app is closed, via WorkManager's periodic
 * scheduler (see [SyncCoordinator.schedulePeriodicSync]).
 *
 * This complements, rather than replaces, [uk.co.mfrost.inkfold.ui.AppViewModel]'s own
 * 5-minute in-app sync loop: that loop only runs while the process is alive,
 * so without this worker, changes made on another device wouldn't show up
 * until the app was reopened *and* had run another sync.
 *
 * Two things this worker deliberately does NOT do, both because there's no UI
 * to do them from:
 *  - Interactively re-authenticate. If the stored Google session can't mint a
 *    token silently, the pass is treated as a transient failure; the next
 *    foreground sync will surface the real sign-in prompt.
 *  - Reload a document that's open in the editor. Instead it skips entirely
 *    while the app is in the foreground (see [isAppInForeground]), leaving
 *    that case to [uk.co.mfrost.inkfold.ui.AppViewModel.sync], which already prompts
 *    the user before discarding in-memory edits.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (isAppInForeground()) {
            // The in-app loop already covers this case; avoid a racy double
            // sync and let AppViewModel handle any open-document reload prompt.
            return Result.success()
        }

        val repo = DocumentRepository(applicationContext)
        val driveSync = DriveSync(applicationContext, repo)
        if (!driveSync.isSignedIn()) return Result.success()

        val result = SyncCoordinator.exclusive { driveSync.sync() }
        return when {
            result.error == null -> Result.success()
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure() // give up quietly; the next periodic run will try again anyway
        }
    }

    private suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        const val WORK_NAME = "drive-sync"
        const val INTERVAL_MINUTES = 15L // Android's floor for periodic work
        private const val MAX_ATTEMPTS = 3
    }
}
