package uk.co.mfrost.inkfold.sync

/** Outcome of a sync pass, surfaced to the UI as a short status line. */
data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    /** Document ids whose local copy was updated from the cloud this pass. */
    val downloadedIds: List<String> = emptyList(),
    /** Document ids whose local copy was removed this pass (deleted on another device). */
    val deletedIds: List<String> = emptyList(),
    val error: String? = null,
)
