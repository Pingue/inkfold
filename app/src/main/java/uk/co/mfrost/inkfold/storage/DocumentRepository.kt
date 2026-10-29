package uk.co.mfrost.inkfold.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import uk.co.mfrost.inkfold.drawing.SvgPage
import uk.co.mfrost.inkfold.model.DocumentManifest
import uk.co.mfrost.inkfold.model.Notebook
import uk.co.mfrost.inkfold.model.Page
import uk.co.mfrost.inkfold.model.PageRef
import uk.co.mfrost.inkfold.model.PageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Lightweight summary used by the document list screen. */
data class DocumentSummary(
    val id: String,
    val title: String,
    val pageCount: Int,
    val updatedAt: Long,
)

/**
 * A folder that groups notebooks (and other sections) for browsing. Purely a
 * local organisational layer: it is never synced to Drive, so a notebook's
 * section membership doesn't travel between devices (see [DocumentRepository]
 * doc comment).
 */
data class SectionSummary(
    /** Slash-separated path from the documents root, e.g. "Work/Travel". */
    val path: String,
    val title: String,
)

/** The sections and notebooks directly inside one folder, for the browser UI. */
data class FolderContents(
    val sections: List<SectionSummary>,
    val notebooks: List<DocumentSummary>,
)

@Serializable
private data class SectionMeta(
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Owns the on-device document store and doubles as the offline cache for Drive.
 *
 * Each document is a *folder*:
 *
 * ```
 * documents/<id>/
 *   index.inkfold      # JSON manifest: ordered list of pages
 *   <pageId>.svg        # one self-describing SVG per page
 * ```
 *
 * Notebooks can additionally be filed into *sections* - plain subfolders
 * (anywhere in the tree, nestable) marked with a small `section.inkfold`
 * metadata file holding just a display title. A notebook's folder name is
 * still its id, so [existingFolderFor] finds it by searching the section tree
 * rather than assuming it's directly under the root. Sections are a local-only
 * browsing aid, not synced to Drive: [DriveSync] only ever deals with
 * notebooks (via [list]/[filesFor]/[writeFile], all keyed by id), so a
 * notebook downloaded fresh from Drive always lands at the top level - the
 * section tree itself isn't part of what's backed up or shared between
 * devices.
 *
 * The store normally lives in the app's private storage, which Android erases
 * on uninstall. [Prefs.storageTreeUri] can instead point [root] at a folder
 * the user picked via the system folder picker (see [moveStorageTo]): since
 * that folder lives outside the app's private storage, its contents survive
 * an uninstall/reinstall, and can be re-attached by picking the same folder
 * again after reinstalling. Both cases are read through [DocumentFile] so the
 * rest of this class doesn't need to know which one is active.
 */
class DocumentRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val prefs = Prefs(context)

    private fun defaultRoot(): DocumentFile =
        DocumentFile.fromFile(File(context.filesDir, "documents").apply { mkdirs() })

    /** The active storage root: a user-chosen folder if one is set and still accessible, else the app's private store. */
    private val root: DocumentFile
        get() {
            val uri = prefs.storageTreeUri?.let(Uri::parse) ?: return defaultRoot()
            val hasPermission = context.contentResolver.persistedUriPermissions
                .any { it.uri == uri && it.isWritePermission }
            val tree = if (hasPermission) DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() } else null
            if (tree != null) return tree
            // The permission was revoked, or the folder was moved/deleted outside the
            // app - fall back to the private store rather than silently losing access
            // to every document.
            prefs.storageTreeUri = null
            return defaultRoot()
        }

    /** Human-friendly description of the active storage root, for the storage-location UI. */
    fun describeRoot(): String {
        val uri = prefs.storageTreeUri ?: return "App storage (removed if the app is uninstalled)"
        val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrNull()
        val (volume, path) = docId?.split(":", limit = 2)?.let { it.getOrNull(0) to it.getOrNull(1) } ?: (null to null)
        val volumeLabel = if (volume == "primary") "Internal storage" else volume ?: "Chosen folder"
        return if (path.isNullOrEmpty()) volumeLabel else "$volumeLabel/$path"
    }

    fun hasCustomStorageLocation(): Boolean = prefs.storageTreeUri != null

    /**
     * Switches the storage root to [newTreeUri] (or back to the app-private store
     * if null), copying every existing section/notebook into it first so nothing
     * already on disk goes missing. The previous location is left untouched (not
     * deleted), so switching is always safe to retry or reverse.
     */
    suspend fun moveStorageTo(newTreeUri: Uri?) = withContext(Dispatchers.IO) {
        val oldRoot = root
        val oldTreeUri = prefs.storageTreeUri
        if (newTreeUri != null) {
            context.contentResolver.takePersistableUriPermission(
                newTreeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val newRoot = newTreeUri?.let { DocumentFile.fromTreeUri(context, it) } ?: defaultRoot()
        if (oldRoot.uri != newRoot.uri) {
            for (child in oldRoot.listFiles()) copyTree(child, newRoot, child.name ?: continue)
        }
        prefs.storageTreeUri = newTreeUri?.toString()
        if (oldTreeUri != null && oldTreeUri != newTreeUri?.toString()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(oldTreeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    /** Recursively copies [src] (a file or a whole subtree) into [destParent], as [name]. */
    private fun copyTree(src: DocumentFile, destParent: DocumentFile, name: String) {
        if (src.isDirectory) {
            val dest = destParent.findFile(name) ?: destParent.createDirectory(name) ?: return
            for (child in src.listFiles()) copyTree(child, dest, child.name ?: continue)
        } else {
            val dest = destParent.createFile(OCTET_STREAM, name) ?: return
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                context.contentResolver.openOutputStream(dest.uri, "wt")?.use { output -> input.copyTo(output) }
            }
        }
    }

    private fun readText(file: DocumentFile): String =
        context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun writeText(file: DocumentFile, text: String) {
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Writes [text] to a child of this folder named [name], creating it if it doesn't exist yet. */
    private fun DocumentFile.writeChild(name: String, text: String) {
        val file = findFile(name) ?: createFile(OCTET_STREAM, name) ?: error("Couldn't create $name")
        writeText(file, text)
    }

    private fun folderAt(sectionPath: String): DocumentFile =
        if (sectionPath.isEmpty()) root
        else sectionPath.split('/').fold(root) { parent, name -> parent.findFile(name) ?: parent }

    /** Searches the section tree for notebook [id]'s folder; null if it doesn't exist on disk. */
    private fun existingFolderFor(id: String): DocumentFile? = locate(root, id)

    private fun locate(folder: DocumentFile, id: String): DocumentFile? {
        val candidate = folder.findFile(id)
        if (candidate != null && candidate.isDirectory && candidate.findFile(MANIFEST) != null) return candidate
        for (child in folder.listFiles()) {
            if (child.isDirectory && child.findFile(SECTION_META) != null) {
                locate(child, id)?.let { return it }
            }
        }
        return null
    }

    /** Like [existingFolderFor], creating a top-level folder named [id] if none exists yet. */
    private fun folderFor(id: String): DocumentFile =
        existingFolderFor(id) ?: root.createDirectory(id) ?: error("Couldn't create notebook folder")

    /** All files that make up a document on disk (manifest + page files). */
    fun filesFor(id: String): List<DocumentFile> =
        existingFolderFor(id)?.listFiles()?.filter { it.isFile } ?: emptyList()

    /** Reads a file's full text content; used by [DriveSync] when pushing local files. */
    fun readFileText(file: DocumentFile): String = readText(file)

    /** Every notebook anywhere in the section tree, flattened - what [DriveSync] reconciles against. */
    suspend fun list(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DocumentSummary>()
        fun walk(folder: DocumentFile) {
            for (child in folder.listFiles()) {
                if (!child.isDirectory) continue
                val mf = child.findFile(MANIFEST)
                if (mf != null) {
                    runCatching {
                        val m = json.decodeFromString(DocumentManifest.serializer(), readText(mf))
                        out.add(DocumentSummary(m.id, m.title, m.pages.size, m.updatedAt))
                    }
                } else if (child.findFile(SECTION_META) != null) {
                    walk(child)
                }
            }
        }
        walk(root)
        out.sortedByDescending { it.updatedAt }
    }

    /** The sections and notebooks directly inside [sectionPath] ("" for the root), for the browser UI. */
    suspend fun listFolder(sectionPath: String): FolderContents = withContext(Dispatchers.IO) {
        val sections = mutableListOf<SectionSummary>()
        val notebooks = mutableListOf<DocumentSummary>()
        for (child in folderAt(sectionPath).listFiles()) {
            if (!child.isDirectory) continue
            val sectionMeta = child.findFile(SECTION_META)
            val manifest = child.findFile(MANIFEST)
            val childName = child.name ?: continue
            when {
                sectionMeta != null -> {
                    val meta = runCatching {
                        json.decodeFromString(SectionMeta.serializer(), readText(sectionMeta))
                    }.getOrNull()
                    val childPath = if (sectionPath.isEmpty()) childName else "$sectionPath/$childName"
                    sections.add(SectionSummary(childPath, meta?.title ?: childName))
                }
                manifest != null -> runCatching {
                    val m = json.decodeFromString(DocumentManifest.serializer(), readText(manifest))
                    notebooks.add(DocumentSummary(m.id, m.title, m.pages.size, m.updatedAt))
                }
            }
        }
        FolderContents(
            sections = sections.sortedBy { it.title.lowercase() },
            notebooks = notebooks.sortedByDescending { it.updatedAt },
        )
    }

    /** Every section anywhere in the tree, for a "move to section" picker. */
    suspend fun allSections(): List<SectionSummary> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SectionSummary>()
        fun walk(folder: DocumentFile, path: String) {
            for (child in folder.listFiles()) {
                if (!child.isDirectory) continue
                val sectionMeta = child.findFile(SECTION_META) ?: continue
                val childName = child.name ?: continue
                val meta = runCatching {
                    json.decodeFromString(SectionMeta.serializer(), readText(sectionMeta))
                }.getOrNull()
                val childPath = if (path.isEmpty()) childName else "$path/$childName"
                out.add(SectionSummary(childPath, meta?.title ?: childName))
                walk(child, childPath)
            }
        }
        walk(root, "")
        out.sortedBy { it.path.lowercase() }
    }

    suspend fun createSection(title: String, parentPath: String = ""): SectionSummary = withContext(Dispatchers.IO) {
        val parent = folderAt(parentPath)
        val name = uniqueFolderName(parent, sanitizeFolderName(title))
        val folder = parent.createDirectory(name) ?: error("Couldn't create section")
        folder.writeChild(SECTION_META, json.encodeToString(SectionMeta.serializer(), SectionMeta(title)))
        SectionSummary(if (parentPath.isEmpty()) name else "$parentPath/$name", title)
    }

    /** Moves notebook [id] so it's filed directly under [toSectionPath] ("" for the top level). */
    suspend fun moveNotebook(id: String, toSectionPath: String) = withContext(Dispatchers.IO) {
        val current = existingFolderFor(id) ?: return@withContext
        val target = folderAt(toSectionPath)
        if (current.parentFile?.uri != target.uri) {
            copyTree(current, target, id)
            current.delete()
        }
        Unit
    }

    private fun sanitizeFolderName(title: String): String =
        title.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "Untitled" }

    private fun uniqueFolderName(parent: DocumentFile, base: String): String {
        if (parent.findFile(base) == null) return base
        var n = 2
        while (parent.findFile("$base ($n)") != null) n++
        return "$base ($n)"
    }

    suspend fun load(id: String): Notebook? = withContext(Dispatchers.IO) {
        val folder = existingFolderFor(id) ?: return@withContext null
        val mf = folder.findFile(MANIFEST) ?: return@withContext null
        val manifest = runCatching {
            json.decodeFromString(DocumentManifest.serializer(), readText(mf))
        }.getOrNull() ?: return@withContext null

        val pages = manifest.pages.map { ref ->
            val pf = folder.findFile(ref.file)
            when (ref.type) {
                PageType.MARKDOWN -> Page(
                    id = ref.id,
                    orientation = ref.orientation,
                    type = PageType.MARKDOWN,
                    markdown = pf?.let { readText(it) } ?: "",
                )
                PageType.SVG -> Page(
                    id = ref.id,
                    orientation = ref.orientation,
                    strokes = pf?.let { SvgPage.parseStrokes(readText(it)) } ?: mutableListOf(),
                    type = PageType.SVG,
                )
            }
        }.toMutableList()

        Notebook(
            id = manifest.id,
            title = manifest.title,
            pages = if (pages.isEmpty()) mutableListOf(Page()) else pages,
            updatedAt = manifest.updatedAt,
        )
    }

    suspend fun save(notebook: Notebook) = withContext(Dispatchers.IO) {
        saveToFolder(notebook, folderFor(notebook.id))
    }

    private fun saveToFolder(notebook: Notebook, folder: DocumentFile) {
        notebook.updatedAt = System.currentTimeMillis()

        val refs = notebook.pages.map { page ->
            when (page.type) {
                PageType.MARKDOWN -> {
                    val file = "${page.id}.md"
                    folder.writeChild(file, page.markdown)
                    PageRef(id = page.id, type = PageType.MARKDOWN, file = file, orientation = page.orientation)
                }
                PageType.SVG -> {
                    val file = "${page.id}.svg"
                    folder.writeChild(file, SvgPage.toSvg(page))
                    PageRef(id = page.id, type = PageType.SVG, file = file, orientation = page.orientation)
                }
            }
        }.toMutableList()

        val manifest = DocumentManifest(
            id = notebook.id,
            title = notebook.title,
            updatedAt = notebook.updatedAt,
            pages = refs,
        )
        folder.writeChild(MANIFEST, json.encodeToString(DocumentManifest.serializer(), manifest))

        // Drop files for pages that no longer exist.
        val keep = refs.map { it.file }.toMutableSet().apply { add(MANIFEST) }
        for (f in folder.listFiles()) if (f.isFile && f.name.let { it == null || it !in keep }) f.delete()
    }

    /** Creates a notebook directly inside [sectionPath] ("" for the top level). */
    suspend fun create(title: String, sectionPath: String = ""): Notebook = withContext(Dispatchers.IO) {
        val nb = Notebook(title = title.ifBlank { "Untitled" })
        val folder = folderAt(sectionPath).createDirectory(nb.id) ?: error("Couldn't create notebook folder")
        saveToFolder(nb, folder)
        nb
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        existingFolderFor(id)?.delete()
        Unit
    }

    // ---- Helpers for the Drive sync layer ---------------------------------

    fun manifestText(id: String): String? =
        existingFolderFor(id)?.findFile(MANIFEST)?.let { readText(it) }

    fun readManifest(id: String): DocumentManifest? =
        manifestText(id)?.let {
            runCatching { json.decodeFromString(DocumentManifest.serializer(), it) }.getOrNull()
        }

    fun writeFile(id: String, name: String, content: String) {
        folderFor(id).writeChild(name, content)
    }

    companion object {
        const val MANIFEST = "index.inkfold"
        private const val SECTION_META = "section.inkfold"

        /** A MIME type with no registered file extension, so [DocumentFile.createFile] never mangles our names. */
        private const val OCTET_STREAM = "application/octet-stream"
    }
}
