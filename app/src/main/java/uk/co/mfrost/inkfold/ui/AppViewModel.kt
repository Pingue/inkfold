package uk.co.mfrost.inkfold.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.co.mfrost.inkfold.model.Notebook
import uk.co.mfrost.inkfold.model.snapshot
import uk.co.mfrost.inkfold.storage.DocumentRepository
import uk.co.mfrost.inkfold.storage.FolderContents
import uk.co.mfrost.inkfold.storage.SectionSummary
import uk.co.mfrost.inkfold.sync.DriveSync
import uk.co.mfrost.inkfold.sync.SyncCoordinator
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Drives the whole app: the notebook list, the open notebook, and Drive sync. */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val repo = DocumentRepository(app)
    val driveSync = DriveSync(app, repo)

    /** Path (slash-separated, "" for the root) of the section currently browsed in the list screen. */
    var currentPath by mutableStateOf("")
        private set
    var folderContents by mutableStateOf(FolderContents(emptyList(), emptyList()))
        private set
    var current by mutableStateOf<Notebook?>(null, neverEqualPolicy())
        private set

    var signedInEmail by mutableStateOf<String?>(null)
        private set
    var syncing by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)

    /**
     * The freshly-pulled remote version of the currently open document, when a
     * sync downloaded an update for it. The editor surfaces a prompt; the local
     * in-memory copy stays authoritative until the user chooses to reload.
     */
    var pendingRemoteDoc by mutableStateOf<Notebook?>(null)
        private set

    /** Bumped to force the editor to re-bind a reloaded notebook of the same id. */
    var reloadKey by mutableStateOf(0)
        private set

    init {
        refresh()
        refreshAccount()
        startAutoSync()
        // Covers the case where the app is closed between in-app syncs; see
        // SyncWorker's doc comment for how it avoids racing this loop.
        if (driveSync.isSignedIn()) SyncCoordinator.schedulePeriodicSync(app)
    }

    /** Periodically syncs in the background while signed in (silent unless something changes). */
    private fun startAutoSync() {
        viewModelScope.launch {
            while (isActive) {
                delay(AUTO_SYNC_MS)
                if (driveSync.isSignedIn()) sync(silent = true)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        folderContents = repo.listFolder(currentPath)
    }

    fun refreshAccount() {
        signedInEmail = driveSync.currentAccount()?.name
    }

    /** Navigates into a subsection of the current folder. */
    fun openSection(path: String) = viewModelScope.launch {
        currentPath = path
        folderContents = repo.listFolder(currentPath)
    }

    /** Navigates to the parent of the current folder; a no-op at the root. */
    fun navigateUp() = viewModelScope.launch {
        currentPath = currentPath.substringBeforeLast('/', "")
        folderContents = repo.listFolder(currentPath)
    }

    fun create(title: String) = viewModelScope.launch {
        val nb = repo.create(title.ifBlank { "Untitled" }, currentPath)
        folderContents = repo.listFolder(currentPath)
        current = nb
    }

    fun createSection(title: String) = viewModelScope.launch {
        repo.createSection(title.ifBlank { "Untitled" }, currentPath)
        folderContents = repo.listFolder(currentPath)
    }

    /** Files notebook [id] directly under [toSectionPath] ("" for the top level). */
    fun moveToSection(id: String, toSectionPath: String) = viewModelScope.launch {
        repo.moveNotebook(id, toSectionPath)
        folderContents = repo.listFolder(currentPath)
    }

    suspend fun allSections(): List<SectionSummary> = repo.allSections()

    fun open(id: String) = viewModelScope.launch {
        pendingRemoteDoc = null
        current = repo.load(id)
    }

    fun close() = viewModelScope.launch {
        saveJob?.cancel()
        pendingRemoteDoc = null
        current?.let { nb -> SyncCoordinator.exclusive { repo.save(nb.snapshot()) } }
        current = null
        folderContents = repo.listFolder(currentPath)
        if (driveSync.isSignedIn()) sync(silent = true)
    }

    private var saveJob: Job? = null

    /**
     * Debounced autosave: coalesces rapid edits into one write, and serialises a
     * deep [snapshot] (taken on the main thread) so the background write never
     * races the live strokes being drawn. Takes the same lock as [sync] so this
     * write can never interleave with a foreground or background sync pass
     * touching the same document's files.
     */
    fun saveCurrent() {
        val nb = current ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            SyncCoordinator.exclusive { repo.save(nb.snapshot()) }
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        repo.delete(id)
        folderContents = repo.listFolder(currentPath)
    }

    fun rename(title: String) {
        current?.let { it.title = title.ifBlank { "Untitled" } }
        current = current // neverEqualPolicy forces the UI to pick up the new title
        saveCurrent()
    }

    fun signOut() {
        SyncCoordinator.cancelPeriodicSync(getApplication())
        driveSync.signOut()
        refreshAccount()
    }

    /** Recent sync history (start/finish/error per pass), for the "Sync log" view. */
    fun syncLogText(): String = driveSync.logText()

    fun clearSyncLog() = driveSync.clearLog()

    /** Called once [DriveSync.authorize] (and any consent screen it triggered) succeeds. */
    fun onDriveAuthorized() {
        refreshAccount()
        statusMessage = "Signed in as $signedInEmail"
        SyncCoordinator.schedulePeriodicSync(getApplication())
        sync()
    }

    /** Called when account selection or authorization fails or is cancelled. */
    fun onDriveAuthorizationFailed(error: Throwable? = null, cancelled: Boolean = false) {
        refreshAccount()
        if (!cancelled) statusMessage = driveAuthorizationErrorMessage(error)
    }

    private fun driveAuthorizationErrorMessage(error: Throwable?): String = when {
        // CommonStatusCodes.DEVELOPER_ERROR
        error is ApiException && error.statusCode == 10 ->
            "Drive sign-in failed (error 10): this APK's signing certificate " +
                "is not registered in a Google Cloud OAuth client. See the README."
        error is ApiException && error.statusCode == 12501 -> "Sign-in cancelled."
        error is ApiException && error.statusCode == 7 -> "Sign-in failed: network error."
        error is ApiException -> "Drive sign-in failed (error ${error.statusCode})."
        else -> "Drive sign-in failed: ${error?.message ?: "unknown error"}"
    }

    fun sync(silent: Boolean = false) {
        if (syncing) return
        viewModelScope.launch {
            syncing = true
            if (!silent) statusMessage = "Syncing…"
            // Shared with SyncWorker so a background pass never races this one.
            val result = SyncCoordinator.exclusive { driveSync.sync() }
            syncing = false
            val changed = (result.uploaded + result.downloaded + result.deleted) > 0
            when {
                result.error != null -> if (!silent) statusMessage = "Sync failed: ${result.error}"
                !silent || changed -> statusMessage =
                    "Synced ↑${result.uploaded} ↓${result.downloaded}" +
                        if (result.deleted > 0) " ✕${result.deleted}" else ""
                // Silent pass with no changes: stay quiet.
            }
            // Refresh the list; the open document keeps its in-memory state and is
            // not reloaded here, so active edits are never clobbered mid-session.
            folderContents = repo.listFolder(currentPath)

            // If the open document was deleted on another device, close it here
            // too and drop any queued autosave, so it can't get silently
            // recreated on disk by the next edit's debounced save.
            val open = current
            if (open != null && open.id in result.deletedIds) {
                saveJob?.cancel()
                pendingRemoteDoc = null
                current = null
                statusMessage = "'${open.title}' was deleted on another device"
            } else if (open != null && open.id in result.downloadedIds && pendingRemoteDoc?.id != open.id) {
                // Updated (not deleted) from Drive: stash the fresh copy and let
                // the editor offer to reload it, rather than silently losing the
                // remote change to the local cache.
                pendingRemoteDoc = repo.load(open.id)
            }
        }
    }

    /** Apply the pending remote version to the editor, discarding local edits. */
    fun acceptRemoteReload() {
        val remote = pendingRemoteDoc ?: return
        saveJob?.cancel() // don't let a queued save of the old copy overwrite it
        pendingRemoteDoc = null
        current = remote
        reloadKey++
    }

    /** Keep editing the local copy; it will be saved over the pulled files. */
    fun dismissRemoteReload() {
        pendingRemoteDoc = null
        saveCurrent()
    }

    companion object {
        private const val AUTO_SYNC_MS = 5 * 60 * 1000L
        private const val SAVE_DEBOUNCE_MS = 700L
    }
}
