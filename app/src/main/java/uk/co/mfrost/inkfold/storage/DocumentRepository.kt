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

    fun folderFor(id: String): File = File(dir, id)
    private fun manifestFile(id: String): File = File(folderFor(id), MANIFEST)

    /** All files that make up a document on disk (manifest + page files). */
    fun filesFor(id: String): List<File> =
        folderFor(id).listFiles()?.filter { it.isFile } ?: emptyList()

    suspend fun list(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { folder ->
                val mf = File(folder, MANIFEST)
                if (!mf.exists()) return@mapNotNull null
                runCatching {
                    val m = json.decodeFromString(DocumentManifest.serializer(), mf.readText())
                    DocumentSummary(m.id, m.title, m.pages.size, m.updatedAt)
                }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): Notebook? = withContext(Dispatchers.IO) {
        val mf = manifestFile(id)
        if (!mf.exists()) return@withContext null
        val manifest = runCatching {
            json.decodeFromString(DocumentManifest.serializer(), mf.readText())
        }.getOrNull() ?: return@withContext null

        val folder = folderFor(id)
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
                PageType.SVG -> Page(
                    id = ref.id,
                    orientation = ref.orientation,
                    strokes = if (exists) SvgPage.parseStrokes(pf.readText()) else mutableListOf(),
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
        notebook.updatedAt = System.currentTimeMillis()
        val folder = folderFor(notebook.id).apply { mkdirs() }

        val refs = notebook.pages.map { page ->
            when (page.type) {
                PageType.MARKDOWN -> {
                    val file = "${page.id}.md"
                    File(folder, file).writeText(page.markdown)
                    PageRef(id = page.id, type = PageType.MARKDOWN, file = file, orientation = page.orientation)
                }
                PageType.SVG -> {
                    val file = "${page.id}.svg"
                    File(folder, file).writeText(SvgPage.toSvg(page))
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
        File(folder, MANIFEST).writeText(json.encodeToString(DocumentManifest.serializer(), manifest))

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
        File(folder, name).writeText(content)
    }

    companion object {
        const val MANIFEST = "index.inkfold"
    }
}
