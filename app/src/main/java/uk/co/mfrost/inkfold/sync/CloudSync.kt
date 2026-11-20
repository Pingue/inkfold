package uk.co.mfrost.inkfold.sync

/**
 * Common surface both cloud providers expose to [uk.co.mfrost.inkfold.ui.AppViewModel],
 * so it can drive whichever one is signed in without caring which it is. Only one
 * provider is ever the active backend at a time (see `AppViewModel.activeCloud`):
 * each maintains its own local<->remote baseline, so running two against the same
 * local document set concurrently could cause a deletion on one remote to look like
 * a local deletion to the other.
 */
interface CloudSync {
    fun isSignedIn(): Boolean
    fun signOut()
    suspend fun sync(): SyncResult
    fun logText(): String
    fun clearLog()
}
