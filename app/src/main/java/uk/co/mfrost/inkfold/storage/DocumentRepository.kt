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
 * Each document is a *folder* under the app's private files directory:
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
 * still its id, so [folderFor] finds it by searching the section tree rather
 * than assuming it's directly under the root. Sections are a local-only
 * browsing aid, not synced to Drive: [DriveSync] only ever deals with
 * notebooks (via [list]/[filesFor]/[writeFile], all keyed by id), so a
 * notebook downloaded fresh from Drive always lands at the top level - the
 * section tree itself isn't part of what's backed up or shared between
 * devices.
 */
class DocumentRepository(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val dir: File = File(context.filesDir, "documents").apply { mkdirs() }

    private fun folderAt(sectionPath: String): File = if (sectionPath.isEmpty()) dir else File(dir, sectionPath)

    /** Searches the section tree for notebook [id]'s folder; falls back to the root for one not yet on disk. */
    fun folderFor(id: String): File = locate(dir, id) ?: File(dir, id)

    private fun locate(folder: File, id: String): File? {
        val candidate = File(folder, id)
        if (candidate.isDirectory && File(candidate, MANIFEST).exists()) return candidate
        for (child in folder.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            if (File(child, SECTION_META).exists()) locate(child, id)?.let { return it }
        }
        return null
    }

    private fun manifestFile(id: String): File = File(folderFor(id), MANIFEST)

    /** All files that make up a document on disk (manifest + page files). */
    fun filesFor(id: String): List<File> =
        folderFor(id).listFiles()?.filter { it.isFile } ?: emptyList()

    /** Every notebook anywhere in the section tree, flattened - what [DriveSync] reconciles against. */
    suspend fun list(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DocumentSummary>()
        fun walk(folder: File) {
            for (child in folder.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                val mf = File(child, MANIFEST)
                if (mf.exists()) {
                    runCatching {
                        val m = json.decodeFromString(DocumentManifest.serializer(), mf.readText())
                        out.add(DocumentSummary(m.id, m.title, m.pages.size, m.updatedAt))
                    }
                } else if (File(child, SECTION_META).exists()) {
                    walk(child)
                }
            }
        }
        walk(dir)
        out.sortedByDescending { it.updatedAt }
    }

    /** The sections and notebooks directly inside [sectionPath] ("" for the root), for the browser UI. */
    suspend fun listFolder(sectionPath: String): FolderContents = withContext(Dispatchers.IO) {
        val sections = mutableListOf<SectionSummary>()
        val notebooks = mutableListOf<DocumentSummary>()
        for (child in folderAt(sectionPath).listFiles { f -> f.isDirectory } ?: emptyArray()) {
            val sectionMeta = File(child, SECTION_META)
            val manifest = File(child, MANIFEST)
            when {
                sectionMeta.exists() -> {
                    val meta = runCatching {
                        json.decodeFromString(SectionMeta.serializer(), sectionMeta.readText())
                    }.getOrNull()
                    val childPath = if (sectionPath.isEmpty()) child.name else "$sectionPath/${child.name}"
                    sections.add(SectionSummary(childPath, meta?.title ?: child.name))
                }
                manifest.exists() -> runCatching {
                    val m = json.decodeFromString(DocumentManifest.serializer(), manifest.readText())
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
        fun walk(folder: File, path: String) {
            for (child in folder.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                val sectionMeta = File(child, SECTION_META)
                if (sectionMeta.exists()) {
                    val meta = runCatching {
                        json.decodeFromString(SectionMeta.serializer(), sectionMeta.readText())
                    }.getOrNull()
                    val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                    out.add(SectionSummary(childPath, meta?.title ?: child.name))
                    walk(child, childPath)
                }
            }
        }
        walk(dir, "")
        out.sortedBy { it.path.lowercase() }
    }

    suspend fun createSection(title: String, parentPath: String = ""): SectionSummary = withContext(Dispatchers.IO) {
        val parent = folderAt(parentPath).apply { mkdirs() }
        val name = uniqueFolderName(parent, sanitizeFolderName(title))
        val folder = File(parent, name).apply { mkdirs() }
        File(folder, SECTION_META).writeText(json.encodeToString(SectionMeta.serializer(), SectionMeta(title)))
        SectionSummary(if (parentPath.isEmpty()) name else "$parentPath/$name", title)
    }

    /** Moves notebook [id] so it's filed directly under [toSectionPath] ("" for the top level). */
    suspend fun moveNotebook(id: String, toSectionPath: String) = withContext(Dispatchers.IO) {
        val current = folderFor(id)
        val target = File(folderAt(toSectionPath).apply { mkdirs() }, id)
        if (current.exists() && current.absolutePath != target.absolutePath) current.renameTo(target)
        Unit
    }

    private fun sanitizeFolderName(title: String): String =
        title.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "Untitled" }

    private fun uniqueFolderName(parent: File, base: String): String {
        if (!File(parent, base).exists()) return base
        var n = 2
        while (File(parent, "$base ($n)").exists()) n++
        return "$base ($n)"
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
        saveToFolder(notebook, folderFor(notebook.id))
    }

    private fun saveToFolder(notebook: Notebook, folder: File) {
        folder.mkdirs()
        notebook.updatedAt = System.currentTimeMillis()

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
    }

    /** Creates a notebook directly inside [sectionPath] ("" for the top level). */
    suspend fun create(title: String, sectionPath: String = ""): Notebook = withContext(Dispatchers.IO) {
        val nb = Notebook(title = title.ifBlank { "Untitled" })
        saveToFolder(nb, File(folderAt(sectionPath), nb.id))
        nb
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
        private const val SECTION_META = "section.inkfold"
    }
}
