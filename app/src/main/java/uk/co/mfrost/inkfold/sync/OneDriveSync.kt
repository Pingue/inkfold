package uk.co.mfrost.inkfold.sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import uk.co.mfrost.inkfold.BuildConfig
import uk.co.mfrost.inkfold.storage.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two-way sync between the local document store and this app's dedicated
 * "special" folder in the user's Microsoft OneDrive (the "approot" folder,
 * created automatically by the Graph API the first time it's addressed).
 * Each document is a *sub-folder* named after its document id, containing the
 * `index.inkfold` manifest and one SVG/Markdown file per page; the manifest's
 * own `updatedAt` field (rather than any custom folder metadata, which OneDrive
 * personal accounts don't support) is read remotely to compare modification
 * times. Conflicts resolve last-write-wins per document, same as [DriveSync];
 * the app is fully usable offline.
 *
 * Auth is a standard OAuth 2.0 authorization code flow with PKCE against the
 * Microsoft identity platform: no client secret is needed (or stored), since
 * this is a public client. [beginSignIn] builds the browser URL; the redirect
 * is caught by `MainActivity`'s `uk.co.mfrost.inkfold://auth` intent filter and
 * handed to [completeSignIn]. The `Files.ReadWrite.AppFolder` scope limits
 * access to this one app-specific folder, mirroring Drive's `drive.file` scope.
 * See the README for the Azure app registration required to enable sync.
 */
class OneDriveSync(context: Context, private val repo: DocumentRepository) : CloudSync {

    private val http = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(HTTP_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val log = SyncLog(context)
    private val stateFile = File(context.filesDir, "sync-state-onedrive.json")

    override fun logText(): String = log.read()

    override fun clearLog() = log.clear()

    override fun isSignedIn(): Boolean = prefs.contains(KEY_REFRESH_TOKEN)

    /** Email (or user principal name) of the signed-in account, once known. */
    fun currentAccountLabel(): String? = prefs.getString(KEY_ACCOUNT_LABEL, null)

    override fun signOut() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            remove(KEY_ACCOUNT_LABEL)
        }
    }

    /**
     * Builds the browser URL to start sign-in, stashing the PKCE verifier and
     * CSRF state needed to validate and complete it in [completeSignIn].
     */
    fun beginSignIn(): Uri {
        val verifier = randomUrlSafeString(64)
        val state = randomUrlSafeString(16)
        prefs.edit {
            putString(KEY_PENDING_VERIFIER, verifier)
            putString(KEY_PENDING_STATE, state)
        }
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /** Completes sign-in from the `uk.co.mfrost.inkfold://auth` redirect URI. */
    suspend fun completeSignIn(redirect: Uri): Boolean = withContext(Dispatchers.IO) {
        val expectedState = prefs.getString(KEY_PENDING_STATE, null)
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null)
        prefs.edit {
            remove(KEY_PENDING_STATE)
            remove(KEY_PENDING_VERIFIER)
        }
        val code = redirect.getQueryParameter("code")
        val state = redirect.getQueryParameter("state")
        if (code == null || verifier == null || state == null || state != expectedState) {
            log.add("OneDrive sign-in failed: ${redirect.getQueryParameter("error_description") ?: "invalid redirect"}")
            return@withContext false
        }
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()
            storeTokens(JSONObject(exec(Request.Builder().url(TOKEN_URL).post(body).build(), "OneDrive sign-in failed")))
            val label = fetchAccountLabel()
            prefs.edit { putString(KEY_ACCOUNT_LABEL, label) }
            true
        }.getOrElse {
            log.add("OneDrive sign-in failed: ${it.message}")
            false
        }
    }

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        if (!isSignedIn()) return@withContext SyncResult(error = "Not signed in")

        log.add("Sync started")
        val result = withTimeoutOrNull(SYNC_TIMEOUT_MS) { runSync() }
            ?: SyncResult(error = "Sync timed out after ${SYNC_TIMEOUT_MS / 1000}s").also {
                log.add("Sync timed out after ${SYNC_TIMEOUT_MS / 1000}s")
            }
        if (result.error == null) {
            log.add("Sync finished: ↑${result.uploaded} ↓${result.downloaded} ✕${result.deleted}")
        }
        result
    }

    private suspend fun runSync(): SyncResult {
        return try {
            val token = withTimeoutOrNull(AUTH_TIMEOUT_MS) { getValidToken() }
                ?: throw IOException("OneDrive authentication timed out after ${AUTH_TIMEOUT_MS / 1000}s")
            log.add("Got auth token")
            ensureAppFolder(token)
            val remote = listRemoteDocs(token).associateBy { it.docId }
            val locals = repo.list().associateBy { it.id }
            log.add("Found ${remote.size} remote, ${locals.size} local document(s)")

            // See DriveSync.runSync: same tombstone-baseline approach, kept in a
            // separate file so the two providers' sync passes never clobber it.
            val baseline = readBaseline()

            var uploaded = 0
            var downloaded = 0
            var deleted = 0
            val downloadedIds = mutableListOf<String>()
            val deletedIds = mutableListOf<String>()
            val survivors = HashMap<String, Long>()

            for (id in locals.keys + remote.keys) {
                currentCoroutineContext().ensureActive()
                val local = locals[id]
                val rem = remote[id]
                val base = baseline[id]
                when {
                    local != null && rem != null -> {
                        when {
                            local.updatedAt > rem.updatedAt -> {
                                log.add("Uploading '${local.title}' ($id)")
                                pushFiles(id, token)
                                uploaded++
                            }
                            rem.updatedAt > local.updatedAt -> {
                                log.add("Downloading '${local.title}' ($id)")
                                pullFiles(id, token)
                                downloaded++
                                downloadedIds.add(id)
                            }
                        }
                        survivors[id] = maxOf(local.updatedAt, rem.updatedAt)
                    }

                    local != null && rem == null -> {
                        if (base != null && local.updatedAt <= base) {
                            currentCoroutineContext().ensureActive()
                            log.add("Deleting local copy of '${local.title}' ($id)")
                            repo.delete(id)
                            deleted++
                            deletedIds.add(id)
                        } else {
                            log.add("Uploading new document '${local.title}' ($id)")
                            createRemoteFolder(id, token)
                            pushFiles(id, token)
                            uploaded++
                            survivors[id] = local.updatedAt
                        }
                    }

                    local == null && rem != null -> {
                        if (base != null && rem.updatedAt <= base) {
                            log.add("Deleting remote copy of document $id")
                            deleteRemoteFolder(id, token)
                            deleted++
                        } else {
                            log.add("Downloading new document $id")
                            pullFiles(id, token)
                            downloaded++
                            downloadedIds.add(id)
                            survivors[id] = rem.updatedAt
                        }
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            writeBaseline(survivors)
            SyncResult(
                uploaded = uploaded,
                downloaded = downloaded,
                deleted = deleted,
                downloadedIds = downloadedIds,
                deletedIds = deletedIds,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.add("Sync failed: ${e.javaClass.simpleName}: $message")
            SyncResult(error = message)
        }
    }

    // ---- OneDrive model helpers --------------------------------------------

    private data class RemoteDoc(val docId: String, val updatedAt: Long)

    /** Addressing the special folder creates it on first access; done explicitly so [listRemoteDocs] can rely on it existing. */
    private suspend fun ensureAppFolder(token: String) {
        get("$GRAPH/me/drive/special/approot", token)
    }

    private suspend fun listRemoteDocs(token: String): List<RemoteDoc> {
        val body = get("$GRAPH/me/drive/special/approot/children?\$select=name,folder", token)
        val arr = JSONObject(body).optJSONArray("value") ?: return emptyList()
        val out = mutableListOf<RemoteDoc>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (!item.has("folder")) continue // skip any stray non-folder item
            val docId = item.getString("name")
            currentCoroutineContext().ensureActive()
            val manifest = runCatching { getManifest(docId, token) }.getOrNull() ?: continue
            out.add(RemoteDoc(docId, manifest.optLong("updatedAt")))
        }
        return out
    }

    private suspend fun getManifest(docId: String, token: String): JSONObject =
        JSONObject(get("$GRAPH/me/drive/special/approot:/${seg(docId)}/${DocumentRepository.MANIFEST}:/content", token))

    private suspend fun createRemoteFolder(docId: String, token: String) {
        val meta = JSONObject().put("name", docId).put("folder", JSONObject())
        post("$GRAPH/me/drive/special/approot/children", token, meta.toString().toRequestBody(JSON_MEDIA))
    }

    private suspend fun deleteRemoteFolder(docId: String, token: String) {
        delete("$GRAPH/me/drive/special/approot:/${seg(docId)}", token)
    }

    private suspend fun listFilesInFolder(docId: String, token: String): Set<String> {
        val body = get("$GRAPH/me/drive/special/approot:/${seg(docId)}:/children?\$select=name", token)
        val arr = JSONObject(body).optJSONArray("value") ?: return emptySet()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toSet()
    }

    private suspend fun pushFiles(docId: String, token: String) {
        val remoteNames = listFilesInFolder(docId, token)
        val localFiles = repo.filesFor(docId)
        val localNames = localFiles.map { it.name }.toSet()

        for (file in localFiles) {
            currentCoroutineContext().ensureActive()
            uploadContent(docId, file.name, file.readText(), mimeFor(file.name), token)
        }
        // Remove remote files for pages deleted locally.
        for (name in remoteNames) {
            currentCoroutineContext().ensureActive()
            if (name !in localNames) deleteItem(docId, name, token)
        }
    }

    private suspend fun pullFiles(docId: String, token: String) {
        val remoteNames = listFilesInFolder(docId, token)
        for (name in remoteNames) {
            currentCoroutineContext().ensureActive()
            val content = get("$GRAPH/me/drive/special/approot:/${seg(docId)}/${seg(name)}:/content", token)
            currentCoroutineContext().ensureActive()
            repo.writeFile(docId, name, content)
        }
        // Remove local files no longer present remotely.
        for (file in repo.filesFor(docId)) {
            currentCoroutineContext().ensureActive()
            if (file.name !in remoteNames) file.delete()
        }
    }

    private suspend fun uploadContent(docId: String, name: String, content: String, mime: String, token: String) {
        val url = "$GRAPH/me/drive/special/approot:/${seg(docId)}/${seg(name)}:/content"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .put(content.toRequestBody(mime.toMediaType()))
            .build()
        exec(request, "OneDrive upload failed")
    }

    private suspend fun deleteItem(docId: String, name: String, token: String) {
        delete("$GRAPH/me/drive/special/approot:/${seg(docId)}/${seg(name)}", token)
    }

    private fun seg(pathSegment: String) = Uri.encode(pathSegment)

    // ---- Auth ---------------------------------------------------------------

    private suspend fun fetchAccountLabel(): String {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return "OneDrive"
        val obj = JSONObject(get("$GRAPH/me?\$select=mail,userPrincipalName", token))
        return obj.optString("mail").ifBlank { obj.optString("userPrincipalName") }.ifBlank { "OneDrive" }
    }

    /** Returns a valid access token, refreshing it first if it's expired or about to. */
    private suspend fun getValidToken(): String {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val cached = prefs.getString(KEY_ACCESS_TOKEN, null)
        if (cached != null && System.currentTimeMillis() < expiresAt - TOKEN_REFRESH_SKEW_MS) return cached

        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: throw IOException("Not signed in")
        val body = FormBody.Builder()
            .add("client_id", BuildConfig.ONEDRIVE_CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("redirect_uri", REDIRECT_URI)
            .add("scope", SCOPES)
            .build()
        storeTokens(JSONObject(exec(Request.Builder().url(TOKEN_URL).post(body).build(), "OneDrive token refresh failed")))
        return prefs.getString(KEY_ACCESS_TOKEN, null) ?: throw IOException("OneDrive token refresh failed")
    }

    private fun storeTokens(json: JSONObject) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000)
            // Microsoft's refresh tokens rotate: a fresh one normally comes back
            // with every token response, but keep the old one if it's ever absent.
            if (json.has("refresh_token")) putString(KEY_REFRESH_TOKEN, json.getString("refresh_token"))
        }
    }

    private fun randomUrlSafeString(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // ---- Low-level HTTP ----------------------------------------------------

    private suspend fun get(url: String, token: String): String =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())

    private suspend fun post(url: String, token: String, body: okhttp3.RequestBody): String =
        exec(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build())

    private suspend fun delete(url: String, token: String) {
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build())
            .awaitCompletion()
    }

    private suspend fun Call.awaitCompletion(): Unit = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }

    private suspend fun exec(request: Request, failurePrefix: String? = "OneDrive request failed"): String =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (failurePrefix != null && !it.isSuccessful) {
                                throw IOException("$failurePrefix: ${it.code}")
                            }
                            body
                        }
                    }
                    result.fold(
                        onSuccess = { if (continuation.isActive) continuation.resume(it) },
                        onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                    )
                }
            })
        }

    // ---- Sync baseline (tombstone tracking) -------------------------------

    private fun readBaseline(): Map<String, Long> {
        if (!stateFile.exists()) return emptyMap()
        return runCatching {
            val obj = JSONObject(stateFile.readText())
            buildMap { for (key in obj.keys()) put(key, obj.optLong(key)) }
        }.getOrDefault(emptyMap())
    }

    private fun writeBaseline(state: Map<String, Long>) {
        val obj = JSONObject()
        for ((id, updatedAt) in state) obj.put(id, updatedAt)
        runCatching { stateFile.writeText(obj.toString()) }
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".inkfold") || name.endsWith(".json") -> "application/json"
        name.endsWith(".md") -> "text/markdown"
        else -> "text/plain"
    }

    companion object {
        private const val AUTHORIZE_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        private const val TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val GRAPH = "https://graph.microsoft.com/v1.0"

        /** Must match the `uk.co.mfrost.inkfold://auth` intent filter on `MainActivity`. */
        const val REDIRECT_URI = "uk.co.mfrost.inkfold://auth"
        private const val SCOPES = "offline_access Files.ReadWrite.AppFolder User.Read"

        private const val PREFS_NAME = "onedrive_sync"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ACCOUNT_LABEL = "account_label"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_PENDING_STATE = "pending_state"

        private val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
        private const val AUTH_TIMEOUT_MS = 30_000L
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000L
        private const val HTTP_READ_TIMEOUT_MS = 30_000L
        private const val HTTP_WRITE_TIMEOUT_MS = 30_000L
        private const val HTTP_CALL_TIMEOUT_MS = 30_000L

        /** Upper bound on a single pass so a stuck network/auth call fails loudly instead of hanging forever. */
        private const val SYNC_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
