package uk.co.mfrost.inkfold.storage

import android.content.Context
import uk.co.mfrost.inkfold.drawing.SvgPage
import uk.co.mfrost.inkfold.model.DocumentManifest
import uk.co.mfrost.inkfold.model.Notebook
import uk.co.mfrost.inkfold.model.Page
import uk.co.mfrost.inkfold.model.PageRef
import uk.co.mfrost.inkfold.model.PageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Owns the on-device document store and doubles as the offline cache for Drive.
 *
 * Each document is a *folder* under the app's private files directory:
 *
 * ```
 * documents/<id>/
 *   index.inkfold      # JSON manifest: ordered list of pages
 *   <pageId>.svg        # one self-describing SVG per page
 * ```
 */
class DocumentRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val dir: File = File(context.filesDir, "documents").apply { mkdirs() }

    /**
     * Forensic copies of files that failed to decode on load, kept outside
     * `dir` so they're never picked up by [filesFor] (and so never pushed to
     * Drive or deleted by [save]'s per-document file cleanup).
     */
    private val corruptDir: File = File(context.filesDir, "corrupted-backups").apply { mkdirs() }

    fun folderFor(id: String): File = File(dir, id)
    private fun manifestFile(id: String): File = File(folderFor(id), MANIFEST)

    private fun backupCorrupt(id: String, file: File) {
        runCatching {
            file.copyTo(File(corruptDir, "$id-${file.name}.corrupt-${System.currentTimeMillis()}"), overwrite = true)
        }
    }

    /** Writes [content] via a temp file + atomic rename, so a crash mid-write never leaves a truncated file. */
    private fun File.writeTextAtomically(content: String) {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Rebuilds a minimal manifest from whatever page files are still present
     * in [folder], for when `index.inkfold` itself is missing or corrupt.
     * Page order isn't recoverable (file ids are UUIDs), but this keeps the
     * document and its drawings from silently vanishing.
     */
    private fun recoverManifest(folder: File): DocumentManifest? {
        val files = folder.listFiles { f -> f.isFile && (f.extension == "svg" || f.extension == "md") }
            ?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val pages = files.map { f ->
            PageRef(
                id = f.nameWithoutExtension,
                type = if (f.extension == "md") PageType.MARKDOWN else PageType.SVG,
                file = f.name,
            )
        }.toMutableList()
        return DocumentManifest(
            id = folder.name,
            title = "Recovered notebook",
            updatedAt = files.maxOf { it.lastModified() },
            pages = pages,
        )
    }

    /** All files that make up a document on disk (manifest + page files). */
    fun filesFor(id: String): List<File> =
        folderFor(id).listFiles()?.filter { it.isFile } ?: emptyList()

    suspend fun list(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { folder ->
                val mf = File(folder, MANIFEST)
                if (!mf.exists()) return@mapNotNull null
                val decoded = runCatching {
                    json.decodeFromString(DocumentManifest.serializer(), mf.readText())
                }.getOrNull()
                val m = decoded ?: recoverManifest(folder)?.also { backupCorrupt(folder.name, mf) }
                m?.let { DocumentSummary(it.id, it.title, it.pages.size, it.updatedAt) }
            }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * [onCorruptPage] is called once per page (or the manifest) that failed to
     * decode, with a message fit to show the user — so a corrupt file surfaces
     * as a visible warning instead of silently becoming "empty" and then being
     * overwritten for good by the next save.
     */
    suspend fun load(id: String, onCorruptPage: (String) -> Unit = {}): Notebook? = withContext(Dispatchers.IO) {
        val folder = folderFor(id)
        val mf = manifestFile(id)
        if (!mf.exists()) return@withContext null
        val decoded = runCatching {
            json.decodeFromString(DocumentManifest.serializer(), mf.readText())
        }.getOrNull()
        val manifest = if (decoded != null) {
            decoded
        } else {
            val recovered = recoverManifest(folder) ?: return@withContext null
            backupCorrupt(id, mf)
            onCorruptPage("This notebook's index was unreadable; recovered ${recovered.pages.size} page(s) from its files")
            recovered
        }

        val pages = manifest.pages.map { ref ->
            val pf = File(folder, ref.file)
            val exists = pf.exists()
            when (ref.type) {
                PageType.MARKDOWN -> Page(
                    id = ref.id,
                    orientation = ref.orientation,
                    type = PageType.MARKDOWN,
                    markdown = if (exists) pf.readText() else "",
                )
                PageType.SVG -> {
                    val parsed = if (exists) SvgPage.parseStrokes(pf.readText()) else mutableListOf()
                    if (parsed == null) {
                        backupCorrupt(id, pf)
                        onCorruptPage("A page in '${manifest.title}' was unreadable and has been backed up")
                    }
                    Page(
                        id = ref.id,
                        orientation = ref.orientation,
                        strokes = parsed ?: mutableListOf(),
                        type = PageType.SVG,
                    )
                }
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
        notebook.updatedAt = System.currentTimeMillis()
        val folder = folderFor(notebook.id).apply { mkdirs() }

        val refs = notebook.pages.map { page ->
            when (page.type) {
                PageType.MARKDOWN -> {
                    val file = "${page.id}.md"
                    File(folder, file).writeTextAtomically(page.markdown)
                    PageRef(id = page.id, type = PageType.MARKDOWN, file = file, orientation = page.orientation)
                }
                PageType.SVG -> {
                    val file = "${page.id}.svg"
                    File(folder, file).writeTextAtomically(SvgPage.toSvg(page))
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
        File(folder, MANIFEST).writeTextAtomically(json.encodeToString(DocumentManifest.serializer(), manifest))

        // Drop files for pages that no longer exist.
        val keep = refs.map { it.file }.toMutableSet().apply { add(MANIFEST) }
        folder.listFiles()?.forEach { if (it.isFile && it.name !in keep) it.delete() }
        Unit
    }

    suspend fun create(title: String): Notebook {
        val nb = Notebook(title = title.ifBlank { "Untitled" })
        save(nb)
        return nb
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        folderFor(id).deleteRecursively()
        Unit
    }

    // ---- Helpers for the Drive sync layer ---------------------------------

    fun manifestText(id: String): String? =
        manifestFile(id).takeIf { it.exists() }?.readText()

    fun readManifest(id: String): DocumentManifest? =
        manifestText(id)?.let {
            runCatching { json.decodeFromString(DocumentManifest.serializer(), it) }.getOrNull()
        }

    fun writeFile(id: String, name: String, content: String) {
        val folder = folderFor(id).apply { mkdirs() }
        File(folder, name).writeTextAtomically(content)
    }

    companion object {
        const val MANIFEST = "index.inkfold"
    }
}
